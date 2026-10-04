package com.dorama.cutter

import android.net.Uri
import org.json.JSONObject
import java.util.Locale

const val INTRO_S = 4L   // сколько секунд показывать «Поверните экран»
const val END_S = 10L    // сколько секунд показывать финальный экран
const val WINDOW_MS = 10 * 60_000L  // ИИ ищет клиффхэнгер за 10 минут до лимита части
const val DEFAULT_TAGS =
    "#дорама #китайскаядорама #дорамы #сериал #русскаяозвучка #рекомендации #fyp"
const val DEFAULT_MODEL = "gemini-3.5-flash"

/** Клиффхэнгер, найденный ИИ. */
data class Hook(val tMs: Long, val score: Int, val teaser: String, val nextHook: String)

/** Точка разреза (конец части): текст финального экрана и заголовок следующей части. */
data class Cut(
    val tMs: Long,
    val teaser: String?,
    val nextHook: String?,
    val fromAi: Boolean,
    val manual: Boolean = false
)

/** Одна часть: границы, заголовок начала и текст финального экрана. */
data class Part(
    val a: Long,
    val b: Long,
    val hook: String?,
    val teaser: String?,
    val fromAi: Boolean,
    val manual: Boolean
)

data class AiResult(val summary: String, val firstHook: String?, val hooks: List<Hook>, val tags: String?)

data class AiJob(val uri: Uri, val key: String, val model: String, val durMs: Long)

data class CutJob(
    val uri: Uri,
    val baseName: String,
    val parts: List<Part>,
    val startIdx: Int,
    val landscape: Boolean,
    val mbps: Double,
    val title: String,
    val tg: String,
    val tags: String
)

/** Выбранное видео и план. Живёт, пока жив процесс (переживает пересоздание экрана). */
object Session {
    var uri: Uri? = null
    var name = ""
    var baseName = "video"
    var durMs = 0L
    var landscape = true
    var hooks: List<Hook> = emptyList()
    var firstHook: String? = null
    var summary = ""
    /** Точки разреза, поправленные руками; null — план считается автоматически. */
    var manualCuts: List<Cut>? = null
}

fun fmt(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
}

fun parseTime(s: String): Long? {
    val x = s.trim().split(":").map { it.trim().toDoubleOrNull() ?: return null }
    val sec = when (x.size) {
        3 -> x[0] * 3600 + x[1] * 60 + x[2]
        2 -> x[0] * 60 + x[1]
        else -> return null
    }
    return (sec * 1000).toLong()
}

/** Режет по лимиту; если есть подсказки ИИ — на самом сильном клиффхэнгере перед лимитом. */
fun autoCuts(durMs: Long, maxMs: Long, hooks: List<Hook>): List<Cut> {
    val r = ArrayList<Cut>()
    var p = 0L
    while (durMs - p > maxMs) {
        val limit = p + maxMs
        val c = hooks
            .filter { it.tMs > p + 60_000 && it.tMs in (limit - WINDOW_MS)..limit }
            .sortedWith(compareBy({ it.score }, { it.tMs }))
            .lastOrNull()
        val e = c?.tMs ?: limit
        r.add(Cut(e, c?.teaser?.ifBlank { null }, c?.nextHook?.ifBlank { null }, c != null))
        p = e
    }
    return r
}

fun partsFrom(durMs: Long, cuts: List<Cut>, firstHook: String?): List<Part> {
    val r = ArrayList<Part>()
    var a = 0L
    var hook = firstHook
    for (c in cuts) {
        r.add(Part(a, c.tMs, hook, c.teaser, c.fromAi, c.manual))
        a = c.tMs
        hook = c.nextHook
    }
    r.add(Part(a, durMs, hook, null, false, false))
    return r
}

fun parseAi(answer: String, durMs: Long): AiResult {
    val clean = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val o = JSONObject(clean)
    val list = ArrayList<Hook>()
    val arr = o.optJSONArray("cliffhangers")
    if (arr != null) for (i in 0 until arr.length()) {
        val h = arr.getJSONObject(i)
        val t = parseTime(h.optString("t")) ?: continue
        if (t < 60_000 || t > durMs - 60_000) continue
        list.add(Hook(t, h.optInt("score", 5),
            h.optString("teaser").trim().take(48), h.optString("next_hook").trim()))
    }
    var tags: String? = null
    val tagsArr = o.optJSONArray("hashtags")
    if (tagsArr != null && tagsArr.length() > 0) {
        tags = (0 until tagsArr.length()).map { tagsArr.getString(it).trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { if (it.startsWith("#")) it else "#$it" }
            .ifEmpty { null }
    }
    return AiResult(
        o.optString("summary").trim(),
        o.optString("first_hook").trim().ifEmpty { null },
        list.sortedBy { it.tMs },
        tags
    )
}

/** Описание для TikTok, i — номер части с 1. */
fun caption(job: CutJob, i: Int): String {
    val n = job.parts.size
    val head = if (job.title.isNotEmpty()) "${job.title} | Часть $i/$n" else "Часть $i/$n"
    val hook = job.parts.getOrNull(i - 1)?.hook
    val sb = StringBuilder(head)
    if (i == n) sb.append(" (финал)")
    if (hook != null) sb.append("\n🔥 ").append(hook)
    sb.append("\n")
    if (i < n) sb.append("\n👉 Следующая часть в профиле")
    if (job.tg.isNotEmpty()) sb.append("\n📲 Полная версия и новые дорамы в Telegram: ").append(job.tg)
    sb.append("\n\n").append(job.tags)
    return sb.toString()
}

fun allCaptions(job: CutJob): String =
    (1..job.parts.size).joinToString("\n\n------------\n\n") { "=== ЧАСТЬ $it ===\n" + caption(job, it) }
