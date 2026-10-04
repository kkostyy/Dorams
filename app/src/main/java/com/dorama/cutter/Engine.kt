package com.dorama.cutter

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection

/**
 * Выполняет ИИ-анализ и нарезку внутри [CutService], независимо от экрана.
 * Все поля меняются только в главном потоке. Каждая задача получает номер [gen]:
 * колбэки старой (остановленной) задачи сверяют его и ничего не делают.
 */
object Engine {
    enum class Kind { IDLE, AI, CUT }

    var kind = Kind.IDLE; private set
    /** 0..100; -1 — сколько ждать, неизвестно. */
    var progress = 0; private set
    var status = "Готов к работе"; private set
    var isError = false; private set
    /** Описания после успешной нарезки. */
    var captions: String? = null; private set
    /** Последняя нарезка и номер части (с 0), с которой её можно продолжить. */
    var lastJob: CutJob? = null; private set
    var resumeFrom: Int? = null; private set
    /** Результат ИИ, который экран ещё не забрал. */
    var aiResult: AiResult? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var gen = 0
    private var launchedGen = -1
    private var svc: CutService? = null
    private var aiJob: AiJob? = null
    private var transformer: Transformer? = null
    @Volatile private var conn: HttpURLConnection? = null
    private var wake: PowerManager.WakeLock? = null
    private var curIdx = 0

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun emit() = listeners.toList().forEach { it() }

    private fun set(p: Int, s: String) {
        progress = p
        status = s
        isError = false
        emit()
    }

    /** Из фонового потока: выполнить в главном, если задача всё ещё та же. */
    private fun post(g: Int, block: () -> Unit) {
        main.post { if (g == gen) block() }
    }

    fun startCut(c: Context, job: CutJob) {
        if (kind != Kind.IDLE) return
        lastJob = job
        resumeFrom = null
        captions = null
        begin(c, Kind.CUT, "Запускаю нарезку...")
    }

    fun startAi(c: Context, job: AiJob) {
        if (kind != Kind.IDLE) return
        aiJob = job
        begin(c, Kind.AI, "ИИ 1/4: достаю звук...")
    }

    private fun begin(c: Context, k: Kind, s: String) {
        gen++
        kind = k
        set(0, s)
        c.startForegroundService(Intent(c, CutService::class.java))
    }

    /** Сервис вышел на передний план — запускаем задачу. */
    fun attach(s: CutService) {
        if (kind == Kind.IDLE) { s.finished(null, null); return }
        svc = s
        if (launchedGen == gen) return
        launchedGen = gen
        wake = (s.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DoramaCutter:job")
            .apply { acquire(8 * 3600_000L) }
        if (kind == Kind.CUT) startPart(lastJob!!.startIdx) else extractAudio(aiJob!!)
    }

    fun detach(s: CutService) {
        if (svc !== s) return
        svc = null
        if (kind != Kind.IDLE) stop()
    }

    fun stop() {
        if (kind == Kind.IDLE) return
        if (kind == Kind.CUT) resumeFrom = curIdx
        gen++
        transformer?.cancel()
        val c = conn
        conn = null
        if (c != null) Thread { try { c.disconnect() } catch (e: Exception) {} }.start()
        finish("Остановлено", false, null)
    }

    private fun fail(msg: String, resume: Int?) {
        resumeFrom = resume
        gen++
        finish("Ошибка: $msg", true, "Ошибка" to msg)
    }

    private fun finish(s: String, error: Boolean, notice: Pair<String, String>?) {
        kind = Kind.IDLE
        transformer = null
        main.removeCallbacks(poll)
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        status = s
        isError = error
        val sv = svc
        svc = null
        emit()
        sv?.finished(notice?.first, notice?.second)
    }

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val h = ProgressHolder()
            if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) {
                if (kind == Kind.AI) {
                    set(h.progress, "ИИ 1/4: достаю звук: ${h.progress}%")
                } else {
                    val n = lastJob!!.parts.size
                    set((curIdx * 100 + h.progress) / n, "Часть ${curIdx + 1} из $n: ${h.progress}%")
                }
            }
            main.postDelayed(this, 500)
        }
    }

    private fun run(tr: Transformer, item: EditedMediaItem, out: File) {
        transformer = tr
        try {
            tr.start(item, out.absolutePath)
        } catch (e: Exception) {
            fail("${e.message}", if (kind == Kind.CUT) curIdx else null)
            return
        }
        main.removeCallbacks(poll)
        main.post(poll)
    }

    // =====================================================================
    //                          ИИ-АНАЛИЗ (Gemini)
    // =====================================================================
    private fun extractAudio(job: AiJob) {
        val c = svc!!
        val g = gen
        val dir = c.cacheDir
        val out = File(dir, "audio.mp4").apply { delete() }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(job.uri)).setRemoveVideo(true).build()
        val tr = Transformer.Builder(c)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (g != gen) return
                    transformer = null
                    main.removeCallbacks(poll)
                    Thread { aiNetwork(job, out, dir, g) }.start()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    if (g == gen) fail("звук: ${exportException.message} (код ${exportException.errorCode})", null)
                }
            })
            .build()
        run(tr, edited, out)
    }

    private fun aiNetwork(job: AiJob, src: File, dir: File, g: Int) {
        val aac = File(dir, "audio.aac")
        val api = Gemini(job.key, alive = { g == gen }, track = { if (g == gen) conn = it })
        try {
            post(g) { set(-1, "ИИ 2/4: готовлю звук...") }
            Gemini.toAdts(src, aac)
            src.delete()
            val mb = aac.length() / 1_000_000
            val file = api.upload(aac) { p -> post(g) { set(p, "ИИ 3/4: загружаю звук в Google ($mb МБ): $p%") } }
            aac.delete()
            post(g) { set(-1, "ИИ 4/4: анализирую серию (1–5 минут)...") }
            val answer = try {
                api.generate(file.uri, job.model, aiPrompt(job.durMs))
            } finally {
                api.delete(file.name)
            }
            val r = parseAi(answer, job.durMs)
            post(g) {
                aiResult = r
                progress = 100
                finish("ИИ готов: клиффхэнгеров ${r.hooks.size}. Проверь план и жми «Начать нарезку».", false, null)
            }
        } catch (e: Exception) {
            post(g) { fail("ИИ: ${e.message}", null) }
        } finally {
            conn = null
            src.delete()
            aac.delete()
        }
    }

    // =====================================================================
    //                          НАРЕЗКА ВИДЕО
    // =====================================================================
    private fun startPart(idx: Int) {
        val job = lastJob!!
        val c = svc!!
        val g = gen
        curIdx = idx
        val p = job.parts[idx]
        val n = job.parts.size
        val next = "ЧАСТЬ ${idx + 2} — В ПРОФИЛЕ"
        val (end1, end2) = when {
            idx < n - 1 && p.teaser != null -> p.teaser.uppercase() to next
            idx < n - 1 -> next to null
            job.tg.isNotEmpty() -> "ПОЛНАЯ ВЕРСИЯ — ${job.tg}" to null
            else -> "БОЛЬШЕ ДОРАМ В ПРОФИЛЕ" to null
        }
        val overlay = PartOverlay(
            intro = Frames.make(idx + 1, n, null, null, p.hook, true, job.landscape),
            main = Frames.make(idx + 1, n, null, null, null, false, job.landscape),
            end = Frames.make(idx + 1, n, end1, end2, null, false, job.landscape),
            introEndUs = INTRO_S * 1_000_000L,
            endStartUs = (p.b - p.a) * 1000L - END_S * 1_000_000L
        )
        val fx = ArrayList<Effect>()
        // 270° против часовой = 90° по часовой, как в версии для ПК
        if (job.landscape) fx.add(ScaleAndRotateTransformation.Builder().setRotationDegrees(270f).build())
        fx.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT))
        fx.add(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)))
        val item = MediaItem.Builder()
            .setUri(job.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(p.a)
                    .setEndPositionMs(p.b)
                    .build()
            )
            .build()
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), fx)).build()
        val out = File(c.cacheDir, "part.mp4").apply { delete() }
        val encoders = DefaultEncoderFactory.Builder(c)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate((job.mbps * 1_000_000).toInt()).build()
            )
            .build()

        val tr = Transformer.Builder(c)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (g != gen) return
                    transformer = null
                    main.removeCallbacks(poll)
                    savePart(c, job, idx, out, g)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    out.delete()
                    if (g == gen) fail("часть ${idx + 1}: ${exportException.message} (код ${exportException.errorCode})", idx)
                }
            })
            .build()
        set(idx * 100 / n, "Часть ${idx + 1} из $n: начинаю...")
        run(tr, edited, out)
    }

    private fun savePart(c: Context, job: CutJob, idx: Int, f: File, g: Int) {
        val n = job.parts.size
        set((idx + 1) * 100 / n, "Сохраняю часть ${idx + 1} в галерею...")
        Thread {
            try {
                val num = (idx + 1).toString().padStart(2, '0')
                saveToMedia(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "${job.baseName}_part$num.mp4",
                    "video/mp4", "Movies/DoramaCutter", { g == gen }) { o -> f.inputStream().use { it.copyTo(o) } }
                post(g) { if (idx + 1 < n) startPart(idx + 1) else allDone(c, job) }
            } catch (e: Exception) {
                post(g) { fail("сохранение части ${idx + 1}: ${e.message}", idx) }
            } finally {
                f.delete()
            }
        }.start()
    }

    /**
     * Файл виден в галерее только после полной записи (IS_PENDING).
     * При ошибке или остановке недописанный файл удаляется.
     */
    private fun saveToMedia(
        c: Context, collection: Uri, name: String, mime: String, dir: String,
        alive: () -> Boolean, write: (OutputStream) -> Unit
    ) {
        val r = c.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(collection, v) ?: throw Exception("не удалось создать файл")
        try {
            (r.openOutputStream(uri) ?: throw Exception("не удалось открыть файл")).use(write)
            if (!alive()) throw Cancelled()
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { r.delete(uri, null, null) } catch (x: Exception) {}
            throw e
        }
    }

    private fun allDone(c: Context, job: CutJob) {
        val text = allCaptions(job)
        captions = text
        resumeFrom = null
        try {
            saveToMedia(c, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${job.baseName}_описания.txt",
                "text/plain", "Download/DoramaCutter", { true }) { it.write(text.toByteArray()) }
        } catch (e: Exception) {
            // описания всё равно видны на экране
        }
        progress = 100
        finish("Готово! Видео: Movies/DoramaCutter, описания: Download/DoramaCutter", false,
            "Нарезка готова" to "Частей: ${job.parts.size}. Они в галерее, папка DoramaCutter")
    }
}
