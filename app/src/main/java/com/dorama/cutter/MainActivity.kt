package com.dorama.cutter

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale

private const val INTRO_S = 4L   // сколько секунд показывать «Поверните экран»
private const val END_S = 10L    // сколько секунд показывать финальный экран
private const val WINDOW_MS = 10 * 60_000L  // ИИ ищет клиффхэнгер за 10 минут до лимита части
private const val DEFAULT_TAGS =
    "#дорама #китайскаядорама #дорамы #сериал #русскаяозвучка #рекомендации #fyp"
private const val GEMINI = "https://generativelanguage.googleapis.com"

/** Клиффхэнгер, найденный ИИ. */
data class Hook(val tMs: Long, val score: Int, val teaser: String, val nextHook: String)

/** Одна часть: границы, заголовок начала и текст финального экрана. */
data class Part(val a: Long, val b: Long, val hook: String?, val teaser: String?)

/** Накладка на весь кадр 1080x1920: интро / плашка части / финальный экран. */
class PartOverlay(
    private val intro: Bitmap,
    private val main: Bitmap,
    private val end: Bitmap,
    private val introEndUs: Long,
    private val endStartUs: Long
) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap = when {
        presentationTimeUs < introEndUs -> intro
        presentationTimeUs >= endStartUs -> end
        else -> main
    }
}

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var srcUri: Uri? = null
    private var baseName = "video"
    private var durMs = 0L
    private var parts: List<Part> = emptyList()
    private var hooks: List<Hook> = emptyList()
    private var firstHook: String? = null
    private var aiSummary = ""
    private var curIdx = 0
    private var running = false
    private var aiMode = false
    private var cancelled = false
    private var transformer: Transformer? = null

    private lateinit var pickBtn: Button
    private lateinit var aiBtn: Button
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var infoTv: TextView
    private lateinit var titleEt: EditText
    private lateinit var tgEt: EditText
    private lateinit var tagsEt: EditText
    private lateinit var maxEt: EditText
    private lateinit var keyEt: EditText
    private lateinit var modelEt: EditText
    private lateinit var bar: ProgressBar
    private lateinit var statusTv: TextView
    private lateinit var resultTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        val sp = getSharedPreferences("s", MODE_PRIVATE)

        fun field(h: String, v: String = "") = EditText(this).apply {
            hint = h
            setText(v)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        pickBtn = Button(this).apply { text = "1. Выбрать видео"; setOnClickListener { onPickClick() } }
        infoTv = TextView(this).apply { text = "Видео не выбрано" }
        titleEt = field("Название дорамы")
        tgEt = field("Telegram, например @my_channel")
        tagsEt = field("Хештеги", DEFAULT_TAGS)
        maxEt = field("Макс. минут в части", "60").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { showPlan() }
            })
        }
        keyEt = field("API-ключ Gemini (aistudio.google.com/apikey)", sp.getString("key", "") ?: "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        modelEt = field("Модель Gemini", sp.getString("model", "gemini-3.5-flash") ?: "gemini-3.5-flash")
        aiBtn = Button(this).apply {
            text = "ИИ: найти места для разреза (необязательно)"
            setOnClickListener { onAiClick() }
        }
        val copyBtn = Button(this).apply {
            text = "Скопировать хештеги"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("tags", tagsEt.text.toString()))
                statusTv.text = "Хештеги скопированы"
            }
        }
        startBtn = Button(this).apply { text = "2. Начать нарезку"; setOnClickListener { onStartClick() } }
        stopBtn = Button(this).apply {
            text = "Остановить"
            isEnabled = false
            setOnClickListener { onStopClick() }
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        statusTv = TextView(this).apply {
            text = "Не закрывай приложение и держи телефон на зарядке."
        }
        resultTv = TextView(this).apply { setTextIsSelectable(true) }

        listOf<android.view.View>(
            pickBtn, infoTv, titleEt, tgEt, tagsEt, copyBtn, maxEt,
            keyEt, modelEt, aiBtn,
            startBtn, stopBtn, bar, statusTv, resultTv
        ).forEach { root.addView(it) }
    }

    // ---------- выбор файла ----------
    private fun onPickClick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(i, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let { onPicked(it) }
    }

    private fun onPicked(uri: Uri) {
        srcUri = uri
        hooks = emptyList()
        firstHook = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) {
                baseName = c.getString(i).substringBeforeLast('.')
                    .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            }
        }
        val r = MediaMetadataRetriever()
        durMs = try {
            r.setDataSource(this, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            r.release()
        }
        if (durMs <= 0) statusTv.text = "Не удалось прочитать длительность видео"
        showPlan()
    }

    // ---------- план частей ----------
    private fun planParts(): List<Part> {
        val maxMin = maxEt.text.toString().replace(',', '.').toDoubleOrNull() ?: 60.0
        // интро накладывается поверх видео и времени не добавляет; 1 с запаса до лимита
        val maxMs = ((maxMin * 60_000).toLong() - 1000).coerceAtLeast(60_000)
        val r = ArrayList<Part>()
        var p = 0L
        var hook = firstHook
        while (p < durMs) {
            if (durMs - p <= maxMs) {
                r.add(Part(p, durMs, hook, null))
                break
            }
            val limit = p + maxMs
            val c = hooks
                .filter { it.tMs > p + 60_000 && it.tMs in (limit - WINDOW_MS)..limit }
                .sortedWith(compareBy({ it.score }, { it.tMs }))
                .lastOrNull()
            val e = c?.tMs ?: limit
            r.add(Part(p, e, hook, c?.teaser?.ifBlank { null }))
            hook = c?.nextHook?.ifBlank { null }
            p = e
        }
        return r
    }

    private fun showPlan() {
        if (durMs <= 0) { infoTv.text = "Видео не выбрано"; return }
        val p = planParts()
        val ai = if (hooks.isNotEmpty()) " (с подсказками ИИ)" else ""
        infoTv.text = "Видео ${fmt(durMs)} → частей: ${p.size}$ai\n" +
            p.mapIndexed { i, x ->
                "Часть ${i + 1}: ${fmt(x.a)}–${fmt(x.b)}" + (x.teaser?.let { "  → $it" } ?: "")
            }.joinToString("\n")
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    // ---------- состояние ----------
    private fun setRunning(b: Boolean) {
        running = b
        pickBtn.isEnabled = !b
        startBtn.isEnabled = !b
        aiBtn.isEnabled = !b
        stopBtn.isEnabled = b
    }

    private fun fail(msg: String) {
        transformer = null
        aiMode = false
        handler.removeCallbacks(poll)
        setRunning(false)
        statusTv.text = "Ошибка: $msg"
    }

    private fun onStopClick() {
        cancelled = true
        transformer?.cancel()
        transformer = null
        aiMode = false
        handler.removeCallbacks(poll)
        setRunning(false)
        statusTv.text = "Остановлено"
    }

    private fun ui(msg: String) = runOnUiThread { if (!cancelled) statusTv.text = msg }

    // =====================================================================
    //                          ИИ-АНАЛИЗ (Gemini)
    // =====================================================================
    private fun onAiClick() {
        val key = keyEt.text.toString().trim()
        val uri = srcUri
        if (uri == null || durMs <= 0) { statusTv.text = "Сначала выбери видео"; return }
        if (key.isEmpty()) { statusTv.text = "Вставь API-ключ Gemini"; return }
        getSharedPreferences("s", MODE_PRIVATE).edit()
            .putString("key", key).putString("model", modelEt.text.toString().trim()).apply()
        cancelled = false
        setRunning(true)
        aiMode = true
        bar.progress = 0
        statusTv.text = "ИИ 1/4: достаю звук..."
        val out = File(cacheDir, "audio.mp4")
        if (out.exists()) out.delete()
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setRemoveVideo(true).build()
        val tr = Transformer.Builder(this)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    aiMode = false
                    handler.removeCallbacks(poll)
                    Thread { runAi(out, key) }.start()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    fail("звук: ${exportException.message} (код ${exportException.errorCode})")
                }
            })
            .build()
        transformer = tr
        try {
            tr.start(edited, out.absolutePath)
        } catch (e: Exception) {
            fail("${e.message}")
            return
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun runAi(src: File, key: String) {
        try {
            ui("ИИ 2/4: готовлю звук...")
            val aac = File(cacheDir, "audio.aac")
            toAdts(src, aac)
            src.delete()
            ui("ИИ 3/4: загружаю звук в Google (${aac.length() / 1_000_000} МБ), лучше по Wi-Fi...")
            val model = modelEt.text.toString().trim().ifEmpty { "gemini-3.5-flash" }
            val fileUri = uploadFile(aac, key)
            aac.delete()
            ui("ИИ 4/4: анализирую серию (1–5 минут)...")
            val answer = generate(fileUri, key, model)
            runOnUiThread { if (!cancelled) applyAi(answer) }
        } catch (e: Exception) {
            runOnUiThread { if (!cancelled) fail("ИИ: ${e.message}") }
        }
    }

    /** Превращает AAC из mp4-контейнера в поток ADTS (.aac), который принимает Gemini. */
    private fun toAdts(src: File, dst: File) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        var idx = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
        }
        if (idx < 0 || fmt == null) throw Exception("в видео нет звуковой дорожки")
        ex.selectTrack(idx)
        val csd = fmt.getByteBuffer("csd-0") ?: throw Exception("не удалось прочитать параметры звука")
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
        ex.release()
    }

    private fun errText(c: HttpURLConnection): String =
        try { c.errorStream?.bufferedReader()?.readText() ?: c.responseMessage } catch (e: Exception) { "HTTP ${c.responseCode}" }

    private fun uploadFile(f: File, key: String): String {
        val start = URL("$GEMINI/upload/v1beta/files").openConnection() as HttpURLConnection
        start.requestMethod = "POST"
        start.doOutput = true
        start.setRequestProperty("x-goog-api-key", key)
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        start.setRequestProperty("X-Goog-Upload-Command", "start")
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", f.length().toString())
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", "audio/aac")
        start.setRequestProperty("Content-Type", "application/json")
        start.outputStream.use { it.write("{\"file\":{\"display_name\":\"dorama\"}}".toByteArray()) }
        if (start.responseCode !in 200..299) throw Exception("загрузка: ${errText(start)}")
        val upUrl = start.getHeaderField("X-Goog-Upload-URL") ?: throw Exception("нет адреса загрузки")
        start.disconnect()

        val c = URL(upUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = 600_000
        c.setFixedLengthStreamingMode(f.length())
        c.setRequestProperty("X-Goog-Upload-Offset", "0")
        c.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        c.outputStream.use { o -> f.inputStream().use { it.copyTo(o, 64 * 1024) } }
        if (c.responseCode !in 200..299) throw Exception("загрузка: ${errText(c)}")
        val file = JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("file")
        val name = file.getString("name")
        val uri = file.getString("uri")
        var state = file.optString("state")
        var tries = 0
        while (state != "ACTIVE" && tries++ < 120) {
            if (state == "FAILED") throw Exception("Google не смог обработать звук")
            Thread.sleep(5000)
            val g = URL("$GEMINI/v1beta/$name").openConnection() as HttpURLConnection
            g.setRequestProperty("x-goog-api-key", key)
            state = JSONObject(g.inputStream.bufferedReader().readText()).optString("state")
        }
        if (state != "ACTIVE") throw Exception("звук не обработан за отведённое время")
        return uri
    }

    private fun prompt(): String = """
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

    private fun generate(fileUri: String, key: String, model: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("file_data",
                    JSONObject().put("mime_type", "audio/aac").put("file_uri", fileUri)))
                .put(JSONObject().put("text", prompt())))))
            .put("generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("temperature", 0.3))
        val c = URL("$GEMINI/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 30_000
        c.readTimeout = 900_000
        c.setRequestProperty("x-goog-api-key", key)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) throw Exception("анализ: ${errText(c)}")
        val resp = JSONObject(c.inputStream.bufferedReader().readText())
        val parts = resp.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false)) sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    private fun parseTime(s: String): Long? {
        val x = s.trim().split(":").map { it.trim().toDoubleOrNull() ?: return null }
        val sec = when (x.size) {
            3 -> x[0] * 3600 + x[1] * 60 + x[2]
            2 -> x[0] * 60 + x[1]
            else -> return null
        }
        return (sec * 1000).toLong()
    }

    private fun applyAi(answer: String) {
        try {
            val clean = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val o = JSONObject(clean)
            firstHook = o.optString("first_hook").trim().ifEmpty { null }
            aiSummary = o.optString("summary").trim()
            val list = ArrayList<Hook>()
            val arr = o.optJSONArray("cliffhangers")
            if (arr != null) for (i in 0 until arr.length()) {
                val h = arr.getJSONObject(i)
                val t = parseTime(h.optString("t")) ?: continue
                if (t < 60_000 || t > durMs - 60_000) continue
                list.add(Hook(t, h.optInt("score", 5),
                    h.optString("teaser").trim().take(48), h.optString("next_hook").trim()))
            }
            hooks = list.sortedBy { it.tMs }
            val tagsArr = o.optJSONArray("hashtags")
            if (tagsArr != null && tagsArr.length() > 0) {
                val tags = (0 until tagsArr.length()).map { tagsArr.getString(it).trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ") { if (it.startsWith("#")) it else "#$it" }
                if (tags.isNotEmpty()) tagsEt.setText(tags)
            }
            showPlan()
            resultTv.text = "О чём серия: $aiSummary\n\nНайдено клиффхэнгеров: ${hooks.size}\n" +
                hooks.joinToString("\n") { "${fmt(it.tMs)}  [${it.score}/10]  ${it.teaser}" }
            statusTv.text = "ИИ готов. Проверь план выше и жми «Начать нарезку»."
            bar.progress = 0
            setRunning(false)
        } catch (e: Exception) {
            fail("не удалось разобрать ответ ИИ: ${e.message}")
        }
    }

    // =====================================================================
    //                          НАРЕЗКА ВИДЕО
    // =====================================================================
    private fun onStartClick() {
        if (srcUri == null || durMs <= 0) { statusTv.text = "Сначала выбери видео"; return }
        cancelled = false
        parts = planParts()
        showPlan()
        resultTv.text = ""
        bar.progress = 0
        setRunning(true)
        startPart(0)
    }

    private fun startPart(idx: Int) {
        curIdx = idx
        val p = parts[idx]
        val n = parts.size
        val tg = tgEt.text.toString().trim()
        val next = "ЧАСТЬ ${idx + 2} — В ПРОФИЛЕ"
        val end1: String
        var end2: String? = null
        when {
            idx < n - 1 && p.teaser != null -> { end1 = p.teaser.uppercase(); end2 = next }
            idx < n - 1 -> end1 = next
            tg.isNotEmpty() -> end1 = "ПОЛНАЯ ВЕРСИЯ — $tg"
            else -> end1 = "БОЛЬШЕ ДОРАМ В ПРОФИЛЕ"
        }
        val overlay = PartOverlay(
            intro = makeBitmap(idx + 1, n, null, null, p.hook, true),
            main = makeBitmap(idx + 1, n, null, null, null, false),
            end = makeBitmap(idx + 1, n, end1, end2, null, false),
            introEndUs = INTRO_S * 1_000_000L,
            endStartUs = (p.b - p.a) * 1000L - END_S * 1_000_000L
        )
        val fx = listOf<Effect>(
            // 270° против часовой = 90° по часовой, как в версии для ПК
            ScaleAndRotateTransformation.Builder().setRotationDegrees(270f).build(),
            Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT),
            OverlayEffect(ImmutableList.of<TextureOverlay>(overlay))
        )
        val item = MediaItem.Builder()
            .setUri(srcUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(p.a)
                    .setEndPositionMs(p.b)
                    .build()
            )
            .build()
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), fx)).build()
        val out = File(cacheDir, "part.mp4")
        if (out.exists()) out.delete()

        val tr = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveAndNext(idx, out)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    fail("часть ${idx + 1}: ${exportException.message} (код ${exportException.errorCode})")
                }
            })
            .build()
        transformer = tr
        try {
            tr.start(edited, out.absolutePath)
        } catch (e: Exception) {
            fail("${e.message}")
            return
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val h = ProgressHolder()
            if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) {
                if (aiMode) {
                    bar.progress = h.progress
                    statusTv.text = "ИИ 1/4: достаю звук: ${h.progress}%"
                } else {
                    bar.progress = (curIdx * 100 + h.progress) / parts.size
                    statusTv.text = "Часть ${curIdx + 1} из ${parts.size}: ${h.progress}%"
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun saveAndNext(idx: Int, f: File) {
        transformer = null
        handler.removeCallbacks(poll)
        statusTv.text = "Сохраняю часть ${idx + 1}..."
        Thread {
            try {
                val num = (idx + 1).toString().padStart(2, '0')
                val v = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "${baseName}_part$num.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DoramaCutter")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                    ?: throw Exception("не удалось создать файл в галерее")
                contentResolver.openOutputStream(uri)!!.use { o ->
                    f.inputStream().use { it.copyTo(o) }
                }
                f.delete()
                runOnUiThread {
                    if (running) {
                        if (idx + 1 < parts.size) startPart(idx + 1) else allDone()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { fail("сохранение: ${e.message}") }
            }
        }.start()
    }

    // ---------- описания и хештеги ----------
    private fun caption(i: Int, n: Int): String {
        val title = titleEt.text.toString().trim()
        val tg = tgEt.text.toString().trim()
        val head = if (title.isNotEmpty()) "$title | Часть $i/$n" else "Часть $i/$n"
        val hook = parts.getOrNull(i - 1)?.hook
        val sb = StringBuilder(head)
        if (i == n) sb.append(" (финал)")
        if (hook != null) sb.append("\n🔥 ").append(hook)
        sb.append("\n")
        if (i < n) sb.append("\n👉 Следующая часть в профиле")
        if (tg.isNotEmpty()) sb.append("\n📲 Полная версия и новые дорамы в Telegram: ").append(tg)
        sb.append("\n\n").append(tagsEt.text.toString().trim())
        return sb.toString()
    }

    private fun allDone() {
        val n = parts.size
        val text = (1..n).joinToString("\n\n------------\n\n") { "=== ЧАСТЬ $it ===\n" + caption(it, n) }
        resultTv.text = "Хештеги: ${tagsEt.text}\n\n$text"
        try {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "${baseName}_описания.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DoramaCutter")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } catch (e: Exception) {
            // описания всё равно видны на экране
        }
        bar.progress = 100
        statusTv.text = "Готово! Видео: Movies/DoramaCutter, описания: Download/DoramaCutter"
        setRunning(false)
    }

    // ---------- рисование накладок (кадр 1080x1920) ----------
    /** Рисует плашку с текстом, возвращает Y её нижнего края. */
    private fun box(c: Canvas, text: String, size: Float, bg: Int, x: Float, top: Float, centered: Boolean): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
        }
        val pad = size * 0.3f
        val w = p.measureText(text)
        val fm = p.fontMetrics
        val left = if (centered) x - w / 2 - pad else x
        val bottom = top + (fm.descent - fm.ascent) + 2 * pad
        c.drawRect(left, top, left + w + 2 * pad, bottom, Paint().apply { color = bg })
        c.drawText(text, left + pad, top + pad - fm.ascent, p)
        return bottom
    }

    private fun wrap(text: String, size: Float, maxW: Float): List<String> {
        val p = Paint().apply { textSize = size; typeface = Typeface.DEFAULT_BOLD }
        val lines = ArrayList<String>()
        var cur = ""
        for (w in text.split(" ").filter { it.isNotEmpty() }) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isEmpty() || p.measureText(t) <= maxW) cur = t else { lines.add(cur); cur = w }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    /** Слой в горизонтальных координатах 1920x1080, повёрнутый так же, как видео. */
    private fun landscapeLayer(c: Canvas, i: Int, n: Int, end1: String?, end2: String?) {
        c.save()
        c.translate(1080f, 0f)
        c.rotate(90f)
        box(c, "ЧАСТЬ $i/$n", 54f, Color.argb(140, 0, 0, 0), 40f, 40f, false)
        val red = Color.argb(217, 230, 46, 77)
        if (end1 != null && end2 != null) {
            val b = box(c, end1, 58f, red, 960f, 790f, true)
            box(c, end2, 44f, red, 960f, b + 12f, true)
        } else if (end1 != null) {
            box(c, end1, 58f, red, 960f, 880f, true)
        }
        c.restore()
    }

    private fun makeBitmap(i: Int, n: Int, end1: String?, end2: String?, hook: String?, intro: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (intro) {
            c.drawColor(Color.argb(140, 0, 0, 0))
            var y = box(c, "ПОВЕРНИТЕ ЭКРАН", 84f, Color.argb(230, 230, 46, 77), 540f, 810f, true) + 20f
            y = box(c, "Часть $i из $n", 44f, Color.argb(180, 0, 0, 0), 540f, y, true) + 20f
            if (hook != null) {
                for (line in wrap(hook.uppercase(), 46f, 900f)) {
                    y = box(c, line, 46f, Color.argb(200, 0, 0, 0), 540f, y, true) + 8f
                }
            }
        }
        landscapeLayer(c, i, n, end1, end2)
        return bmp
    }

    override fun onDestroy() {
        transformer?.cancel()
        handler.removeCallbacks(poll)
        super.onDestroy()
    }
}
