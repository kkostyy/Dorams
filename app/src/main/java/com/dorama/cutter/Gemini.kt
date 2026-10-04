package com.dorama.cutter

import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

private const val GEMINI = "https://generativelanguage.googleapis.com"

class Cancelled : Exception("остановлено")

/**
 * Клиент Gemini REST. [alive] — не остановлена ли задача,
 * [track] получает каждое соединение, чтобы «Остановить» могло его оборвать.
 */
class Gemini(
    private val key: String,
    private val alive: () -> Boolean,
    private val track: (HttpURLConnection) -> Unit
) {
    class Uploaded(val name: String, val uri: String)

    private fun open(url: String, method: String = "GET", tracked: Boolean = true): HttpURLConnection {
        if (tracked && !alive()) throw Cancelled()
        val c = URL(if (url.startsWith("http")) url else "$GEMINI$url").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.setRequestProperty("x-goog-api-key", key)
        if (tracked) track(c)
        return c
    }

    private fun HttpURLConnection.check(what: String) {
        if (responseCode !in 200..299) {
            val err = try { errorStream?.bufferedReader()?.readText() ?: responseMessage } catch (e: Exception) { "HTTP $responseCode" }
            throw Exception("$what: $err")
        }
    }

    fun upload(f: File, progress: (Int) -> Unit): Uploaded {
        val start = open("/upload/v1beta/files", "POST")
        start.doOutput = true
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        start.setRequestProperty("X-Goog-Upload-Command", "start")
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", f.length().toString())
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", "audio/aac")
        start.setRequestProperty("Content-Type", "application/json")
        start.outputStream.use { it.write("{\"file\":{\"display_name\":\"dorama\"}}".toByteArray()) }
        start.check("загрузка")
        val upUrl = start.getHeaderField("X-Goog-Upload-URL") ?: throw Exception("нет адреса загрузки")
        start.disconnect()

        val c = open(upUrl, "POST")
        c.doOutput = true
        c.readTimeout = 600_000
        c.setFixedLengthStreamingMode(f.length())
        c.setRequestProperty("X-Goog-Upload-Offset", "0")
        c.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        val total = f.length().coerceAtLeast(1)
        c.outputStream.use { o ->
            f.inputStream().use { i ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                var last = -1
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    sent += n
                    val p = (sent * 100 / total).toInt()
                    if (p != last) { last = p; progress(p) }
                    if (!alive()) throw Cancelled()
                }
            }
        }
        c.check("загрузка")
        val file = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("file")
        val name = file.getString("name")
        val uri = file.getString("uri")
        var state = file.optString("state")
        var tries = 0
        while (state != "ACTIVE") {
            if (state == "FAILED") throw Exception("Google не смог обработать звук")
            if (tries++ >= 120) throw Exception("звук не обработан за отведённое время")
            Thread.sleep(5000)
            val g = open("/v1beta/$name")
            g.check("проверка файла")
            state = JSONObject(g.inputStream.bufferedReader().readText()).optString("state")
        }
        return Uploaded(name, uri)
    }

    fun generate(fileUri: String, model: String, prompt: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("file_data",
                    JSONObject().put("mime_type", "audio/aac").put("file_uri", fileUri)))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3))
        val c = open("/v1beta/models/$model:generateContent", "POST")
        c.doOutput = true
        c.readTimeout = 900_000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        c.check("анализ")
        val resp = JSONObject(c.inputStream.bufferedReader().readText())
        val cands = resp.optJSONArray("candidates")
        if (cands == null || cands.length() == 0)
            throw Exception("ИИ не вернул ответ: ${resp.optJSONObject("promptFeedback") ?: resp}")
        val parts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    /** Удаляет загруженный звук из Google (иначе он лежит там ещё 48 часов). Ошибки не важны. */
    fun delete(name: String) {
        try {
            val c = open("/v1beta/$name", "DELETE", tracked = false)
            c.responseCode
            c.disconnect()
        } catch (e: Exception) {
        }
    }

    companion object {
        /** Превращает AAC из mp4-контейнера в поток ADTS (.aac), который принимает Gemini. */
        fun toAdts(src: File, dst: File) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(src.absolutePath)
                var idx = -1
                var format: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; format = f; break }
                }
                if (idx < 0 || format == null) throw Exception("в видео нет звуковой дорожки")
                ex.selectTrack(idx)
                val csd = format.getByteBuffer("csd-0") ?: throw Exception("не удалось прочитать параметры звука")
                val b0 = csd.get(0).toInt() and 0xFF
                val b1 = csd.get(1).toInt() and 0xFF
                val aot = b0 shr 3
                val freq = ((b0 and 7) shl 1) or (b1 shr 7)
                val ch = (b1 shr 3) and 0xF
                val buf = ByteBuffer.allocate(1 shl 18)
                dst.outputStream().buffered().use { o ->
                    while (true) {
                        buf.clear()
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) break
                        val len = n + 7
                        val h = ByteArray(7)
                        h[0] = 0xFF.toByte()
                        h[1] = 0xF1.toByte()
                        h[2] = ((((aot - 1) and 3) shl 6) or (freq shl 2) or (ch shr 2)).toByte()
                        h[3] = (((ch and 3) shl 6) or (len shr 11)).toByte()
                        h[4] = ((len shr 3) and 0xFF).toByte()
                        h[5] = (((len and 7) shl 5) or 0x1F).toByte()
                        h[6] = 0xFC.toByte()
                        val arr = ByteArray(n)
                        buf.position(0)
                        buf.limit(n)
                        buf.get(arr, 0, n)
                        o.write(h)
                        o.write(arr)
                        ex.advance()
                    }
                }
            } finally {
                ex.release()
            }
        }
    }
}

fun aiPrompt(durMs: Long): String = """
Это аудио китайской дорамы с русской озвучкой, длительность ${fmt(durMs)}.
Время везде указывай в формате ЧЧ:ММ:СС от начала файла.
Задача: подготовить нарезку для TikTok (зритель досматривает часть и идёт за продолжением).
1) Найди 20-40 сильных клиффхэнгеров по ВСЕЙ серии: оборванная фраза, поворот сюжета, раскрытие тайны, конфликт на пике. Не реже одного примерно каждые 10 минут. Время ставь точно на конец реплики, в паузе речи, до начала следующей реплики.
2) Для каждого: t (время), score (1-10: насколько зритель захочет продолжить), teaser (текст финального экрана, до 6 слов, ЗАГЛАВНЫМИ, интригующий, без спойлера), next_hook (заголовок для начала следующей части, до 7 слов).
3) first_hook: заголовок для начала первой части (до 7 слов).
4) hashtags: 7 хештегов под жанр и сюжет (русские и 1-2 английских).
5) summary: 2 предложения о сюжете.
Верни ТОЛЬКО JSON без пояснений и без markdown:
{"summary":"...","first_hook":"...","hashtags":["#..."],"cliffhangers":[{"t":"00:12:34","score":8,"teaser":"...","next_hook":"..."}]}
""".trimIndent()
