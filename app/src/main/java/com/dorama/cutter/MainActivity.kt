package com.dorama.cutter

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("s", MODE_PRIVATE) }

    private lateinit var col: LinearLayout
    private lateinit var videoName: TextView
    private lateinit var videoMeta: TextView
    private lateinit var pickBtn: TextView
    private lateinit var titleEt: EditText
    private lateinit var tgEt: EditText
    private lateinit var tagsEt: EditText
    private lateinit var maxEt: EditText
    private lateinit var rateEt: EditText
    private lateinit var keyEt: EditText
    private lateinit var modelEt: EditText
    private lateinit var aiBtn: TextView
    private lateinit var aiSummary: TextView
    private lateinit var planReset: TextView
    private lateinit var planInfo: TextView
    private lateinit var planBox: LinearLayout
    private lateinit var spaceTv: TextView
    private lateinit var resultHead: View
    private lateinit var resultCard: LinearLayout
    private lateinit var resultTv: TextView
    private lateinit var statusTv: TextView
    private lateinit var pctTv: TextView
    private lateinit var meter: Meter
    private lateinit var startBtn: TextView
    private lateinit var stopBtn: TextView
    private lateinit var resumeBtn: TextView
    private lateinit var toastTv: TextView

    private val onEngine: () -> Unit = { renderEngine() }
    private val hideToast = Runnable {
        toastTv.animate().alpha(0f).translationY(ui.dpf(10)).setDuration(220)
            .withEndAction { toastTv.visibility = View.GONE }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        col = ui.column().apply { setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(220)) }
        val scroll = ScrollView(this).apply {
            addView(col)
            isVerticalScrollBarEnabled = false
        }
        val root = FrameLayout(this).apply { setBackgroundColor(ui.bg) }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        buildHeader()
        buildVideo()
        buildDesign()
        buildCut()
        buildAi()
        buildPlan()
        buildResult()
        val bar = buildBar()
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        // пока открыта клавиатура, нижняя панель не закрывает поля ввода
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            root.getWindowVisibleDisplayFrame(r)
            val h = root.rootView.height
            val v = if (h - r.bottom > h / 4) View.GONE else View.VISIBLE
            if (bar.visibility != v) bar.visibility = v
        }

        maxEt.onChange { renderPlan() }
        rateEt.onChange { renderSpace() }
        Engine.listen(onEngine)
        renderVideo()
        renderPlan()
    }

    override fun onPause() {
        savePrefs()
        super.onPause()
    }

    override fun onDestroy() {
        // задача продолжается в CutService, экран просто отписывается
        Engine.unlisten(onEngine)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // =====================================================================
    //                              ЭКРАН
    // =====================================================================
    private fun section(title: String, extra: View? = null): View {
        val r = ui.row()
        r.addView(ui.h3(title), ui.lp(0, WRAP_CONTENT, weight = 1f))
        if (extra != null) r.addView(extra)
        col.addView(r, ui.lp(top = ui.dp(22), bottom = ui.dp(8)))
        return r
    }

    private fun card(): LinearLayout = ui.card().also { col.addView(it, ui.lp(bottom = ui.dp(8))) }

    private fun buildHeader() {
        val r = ui.row()
        val logo = ui.text("✂", 22f, ui.onAccent, true).apply {
            gravity = Gravity.CENTER
            background = ui.shape(ui.accent, ui.dpf(12))
        }
        r.addView(logo, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val t = ui.column()
        t.addView(ui.text("Dorama Cutter", 22f, bold = true))
        t.addView(ui.text("Нарезка дорам на части для TikTok", 14f, ui.muted))
        r.addView(t, ui.lp(0, WRAP_CONTENT, weight = 1f).apply { marginStart = ui.dp(12) })
        col.addView(r)
    }

    private fun buildVideo() {
        section("Видео")
        val c = card()
        val r = ui.row().apply { setPadding(0, ui.dp(10), 0, 0) }
        val ic = ui.text("▶", 18f, ui.accent, true).apply {
            gravity = Gravity.CENTER
            background = ui.shape(ui.accentSoft, ui.dpf(12))
        }
        r.addView(ic, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val who = ui.column()
        videoName = ui.text("", 17f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        videoMeta = ui.text("", 14f, ui.muted)
        who.addView(videoName)
        who.addView(videoMeta)
        r.addView(who, ui.lp(0, WRAP_CONTENT, weight = 1f).apply { marginStart = ui.dp(12) })
        c.addView(r)
        pickBtn = ui.button("Выбрать видео", Btn.PRIMARY) { onPickClick() }
        c.addView(pickBtn, ui.lp(top = ui.dp(12)))
    }

    private fun buildDesign() {
        section("Оформление")
        val c = card()
        titleEt = ui.field(c, "Название дорамы", prefs.getString("title", "") ?: "",
            hintText = "Например, Как дракон влюбился")
        tgEt = ui.field(c, "Telegram-канал", prefs.getString("tg", "") ?: "", hintText = "@my_channel")
        tagsEt = ui.field(c, "Хештеги", prefs.getString("tags", DEFAULT_TAGS) ?: DEFAULT_TAGS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        c.addView(ui.button("Скопировать хештеги", Btn.SOFT) {
            copy(tagsEt.text.toString(), "Хештеги скопированы")
        }, ui.lp(top = ui.dp(10)))
    }

    private fun buildCut() {
        section("Нарезка")
        val c = card()
        val two = ui.row().apply { gravity = Gravity.TOP }
        val left = ui.column()
        val right = ui.column()
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        maxEt = ui.field(left, "Макс. минут в части", prefs.getString("max", "60") ?: "60", num)
        rateEt = ui.field(right, "Битрейт, Мбит/с", prefs.getString("rate", "5") ?: "5", num)
        two.addView(left, ui.lp(0, WRAP_CONTENT, weight = 1f))
        two.addView(right, ui.lp(0, WRAP_CONTENT, weight = 1f).apply { marginStart = ui.dp(8) })
        c.addView(two)
        c.addView(ui.hint("Битрейт выше — картинка лучше, но файлы тяжелее. Для TikTok хватает 4–6 Мбит/с."),
            ui.lp(top = ui.dp(8)))
    }

    private fun buildAi() {
        section("ИИ-анализ", ui.chip("необязательно", ui.muted, ui.line))
        val c = card()
        keyEt = ui.field(c, "API-ключ Gemini", prefs.getString("key", "") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            "aistudio.google.com/apikey")
        modelEt = ui.field(c, "Модель Gemini", prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        aiBtn = ui.button("Найти места для разреза", Btn.SOFT) { onAiClick() }
        c.addView(aiBtn, ui.lp(top = ui.dp(12)))
        c.addView(ui.hint("Звук серии (150–350 МБ) загрузится в Google — лучше по Wi-Fi. " +
            "ИИ найдёт клиффхэнгеры, придумает заголовки, тексты финального экрана и хештеги. " +
            "Ошибка 404 — смени модель на актуальную из AI Studio."), ui.lp(top = ui.dp(8)))
        aiSummary = ui.text("", 15f).apply {
            background = ui.shape(ui.accentSoft, ui.dpf(10))
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            visibility = View.GONE
        }
        c.addView(aiSummary, ui.lp(top = ui.dp(10)))
    }

    private fun buildPlan() {
        planReset = ui.text("Сбросить правки", 14f, ui.accent, true).apply {
            setPadding(ui.dp(8), ui.dp(2), 0, ui.dp(2))
            setOnClickListener {
                Session.manualCuts = null
                renderPlan()
                toast("План пересчитан автоматически")
            }
        }
        section("План нарезки", planReset)
        planInfo = ui.text("", 15f, ui.muted)
        col.addView(planInfo, ui.lp(bottom = ui.dp(8)))
        planBox = ui.column()
        col.addView(planBox)
        spaceTv = ui.text("", 14f, ui.muted)
        col.addView(spaceTv, ui.lp(top = ui.dp(4)))
    }

    private fun buildResult() {
        resultHead = section("Описания для TikTok")
        resultCard = card()
        resultTv = ui.text("", 15f).apply {
            setTextIsSelectable(true)
            setPadding(0, ui.dp(10), 0, 0)
        }
        resultCard.addView(resultTv)
        resultCard.addView(ui.button("Скопировать все описания", Btn.SOFT) {
            copy(resultTv.text.toString(), "Описания скопированы")
        }, ui.lp(top = ui.dp(12)))
        resultHead.visibility = View.GONE
        resultCard.visibility = View.GONE
    }

    /** Нижняя панель: статус, прогресс и кнопки — всегда на экране. */
    private fun buildBar(): View {
        val wrap = ui.column()
        toastTv = ui.text("", 15f, ui.bg).apply {
            background = ui.shape(ui.ink, ui.dpf(99))
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        wrap.addView(toastTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = ui.dp(12)
            marginStart = ui.dp(16)
            marginEnd = ui.dp(16)
        })
        val bar = ui.column().apply {
            val r = ui.dpf(20)
            background = GradientDrawable().apply {
                setColor(ui.cardBg)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            elevation = ui.dpf(12)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        }
        val sr = ui.row()
        statusTv = ui.text("", 15f).apply { maxLines = 3 }
        pctTv = ui.text("", 16f, ui.accent, true)
        sr.addView(statusTv, ui.lp(0, WRAP_CONTENT, weight = 1f))
        sr.addView(pctTv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = ui.dp(8) })
        bar.addView(sr)
        meter = Meter(this, ui.line, ui.accent)
        bar.addView(meter, ui.lp(h = ui.dp(8), top = ui.dp(10)))
        resumeBtn = ui.button("", Btn.OK) { resumable()?.let { launch(it) } }
        bar.addView(resumeBtn, ui.lp(top = ui.dp(10)))
        val bs = ui.row()
        startBtn = ui.button("Начать нарезку", Btn.PRIMARY) { onStartClick() }
        stopBtn = ui.button("Остановить", Btn.DANGER) { Engine.stop() }
        bs.addView(startBtn, ui.lp(0, WRAP_CONTENT, weight = 1f))
        bs.addView(stopBtn, ui.lp(0, WRAP_CONTENT, weight = 1f).apply { marginStart = ui.dp(8) })
        bar.addView(bs, ui.lp(top = ui.dp(10)))
        wrap.addView(bar)
        // контент не должен прятаться под панелью
        bar.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val pb = bottom - top + ui.dp(24)
            if (col.paddingBottom != pb) col.setPadding(col.paddingLeft, col.paddingTop, col.paddingRight, pb)
        }
        return wrap
    }

    private fun toast(msg: String) {
        handler.removeCallbacks(hideToast)
        toastTv.animate().cancel()
        toastTv.text = msg
        toastTv.visibility = View.VISIBLE
        toastTv.alpha = 0f
        toastTv.translationY = ui.dpf(20)
        toastTv.animate().alpha(1f).translationY(0f).setDuration(250).start()
        handler.postDelayed(hideToast, 2500)
    }

    private fun copy(text: String, msg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        toast(msg)
    }

    /** Нижняя шторка в стиле LOGOPED. [onOk] возвращает текст ошибки или null, если всё хорошо. */
    private inner class Sheet(title: String) {
        val dlg = Dialog(this@MainActivity)
        val body: LinearLayout = ui.column()
        private val err = ui.text("", 14f, ui.bad).apply { visibility = View.GONE }

        init {
            val box = ui.column().apply {
                val r = ui.dpf(20)
                background = GradientDrawable().apply {
                    setColor(ui.cardBg)
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(20))
            }
            val grab = View(this@MainActivity).apply { background = ui.shape(ui.line, ui.dpf(3)) }
            box.addView(grab, LinearLayout.LayoutParams(ui.dp(40), ui.dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = ui.dp(12)
            })
            box.addView(ui.text(title, 20f, bold = true))
            box.addView(body)
            box.addView(err, ui.lp(top = ui.dp(10)))
            dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dlg.setContentView(ScrollView(this@MainActivity).apply { addView(box) })
            dlg.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                decorView.setPadding(0, 0, 0, 0)
                setLayout(MATCH_PARENT, WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setWindowAnimations(android.R.style.Animation_InputMethod)
                setDimAmount(0.4f)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }

        fun show(cancel: String, ok: String, okStyle: Btn = Btn.PRIMARY, onOk: () -> String?) {
            val r = ui.row()
            r.addView(ui.button(cancel, Btn.PLAIN) { dlg.dismiss() }, ui.lp(0, WRAP_CONTENT, weight = 1f))
            r.addView(ui.button(ok, okStyle) {
                val e = onOk()
                if (e == null) dlg.dismiss() else { err.text = e; err.visibility = View.VISIBLE }
            }, ui.lp(0, WRAP_CONTENT, weight = 1f).apply { marginStart = ui.dp(8) })
            body.addView(r, ui.lp(top = ui.dp(16)))
            dlg.show()
        }
    }

    // =====================================================================
    //                              ВИДЕО
    // =====================================================================
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
        // доступ к файлу нужен и сервису, даже если экран закроют
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
        Session.uri = uri
        Session.hooks = emptyList()
        Session.firstHook = null
        Session.summary = ""
        Session.manualCuts = null
        Session.name = "video"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) Session.name = c.getString(i)
        }
        Session.baseName = Session.name.substringBeforeLast('.')
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            fun num(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0L
            Session.durMs = num(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val w = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rot = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            Session.landscape = if (rot == 90L || rot == 270L) h >= w else w >= h
        } catch (e: Exception) {
            Session.durMs = 0L
        } finally {
            r.release()
        }
        if (Session.durMs <= 0) toast("Не удалось прочитать длительность видео")
        aiSummary.visibility = View.GONE
        renderVideo()
        renderPlan()
    }

    private fun renderVideo() {
        if (Session.uri == null) {
            videoName.text = "Видео не выбрано"
            videoMeta.text = "Выбери серию из галереи или файлов"
            pickBtn.text = "Выбрать видео"
            ui.style(pickBtn, Btn.PRIMARY)
        } else {
            videoName.text = Session.name
            videoMeta.text = fmt(Session.durMs) + " · " +
                if (Session.landscape) "горизонтальное, повернём в 9:16" else "вертикальное, без поворота"
            pickBtn.text = "Выбрать другое видео"
            ui.style(pickBtn, Btn.SOFT)
        }
        if (Session.summary.isNotEmpty()) {
            aiSummary.text = "О чём серия: ${Session.summary}\n\nКлиффхэнгеров найдено: ${Session.hooks.size}"
            aiSummary.visibility = View.VISIBLE
        }
    }

    // =====================================================================
    //                              ПЛАН
    // =====================================================================
    private fun maxMs(): Long {
        val m = maxEt.text.toString().replace(',', '.').toDoubleOrNull() ?: 60.0
        // интро накладывается поверх видео и времени не добавляет; 1 с запаса до лимита
        return ((m * 60_000).toLong() - 1000).coerceAtLeast(60_000)
    }

    private fun mbps(): Double =
        (rateEt.text.toString().replace(',', '.').toDoubleOrNull() ?: 5.0).coerceIn(0.5, 50.0)

    private fun cuts(): List<Cut> = Session.manualCuts ?: autoCuts(Session.durMs, maxMs(), Session.hooks)

    private fun currentParts(): List<Part> =
        if (Session.durMs <= 0) emptyList() else partsFrom(Session.durMs, cuts(), Session.firstHook)

    private fun partsWord(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "часть"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "части"
        else -> "частей"
    }

    private fun renderPlan() {
        planBox.removeAllViews()
        planReset.visibility = if (Session.manualCuts != null) View.VISIBLE else View.GONE
        val parts = currentParts()
        if (parts.isEmpty()) {
            planInfo.visibility = View.GONE
            planBox.addView(ui.text("Выбери видео — здесь появится план частей", 16f, ui.muted).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(16), ui.dp(30), ui.dp(16), ui.dp(30))
            })
            renderSpace()
            renderEngine()
            return
        }
        val notes = listOfNotNull(
            if (Session.hooks.isNotEmpty()) "с подсказками ИИ" else null,
            if (Session.manualCuts != null) "есть ручные правки" else null
        )
        planInfo.text = "${parts.size} ${partsWord(parts.size)} · ${fmt(Session.durMs)}" +
            notes.joinToString("") { " · $it" }
        planInfo.visibility = View.VISIBLE
        parts.forEachIndexed { i, p -> planBox.addView(partRow(i, p, parts.size), ui.lp(bottom = ui.dp(8))) }
        planBox.addView(ui.hint("Нажми на часть, чтобы поправить точку разреза или тексты."))
        renderSpace()
        renderEngine()
    }

    private fun partRow(i: Int, p: Part, n: Int): View {
        val over = p.b - p.a > maxMs() + 1000
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            background = ui.ripple(ui.cardBg, ui.dpf(14), 1, ui.border)
            elevation = if (ui.dark) 0f else ui.dpf(1)
            setOnClickListener { editPart(i) }
        }
        val t = ui.column().apply { minimumWidth = ui.dp(64) }
        t.addView(ui.text(fmt(p.a), 16f, bold = true))
        t.addView(ui.text("${(p.b - p.a + 30_000) / 60_000} мин", 13f, ui.muted))
        r.addView(t)
        val barColor = when {
            over -> ui.bad
            p.manual -> ui.warn
            p.fromAi -> ui.accent
            else -> ui.line
        }
        r.addView(View(this).apply { background = ui.shape(barColor, ui.dpf(3)) },
            LinearLayout.LayoutParams(ui.dp(5), MATCH_PARENT).apply {
                marginStart = ui.dp(6)
                marginEnd = ui.dp(12)
            })
        val who = ui.column()
        who.addView(ui.text("Часть ${i + 1}" + if (i == n - 1 && n > 1) " · финал" else "", 17f, bold = true))
        who.addView(ui.text("${fmt(p.a)} – ${fmt(p.b)}", 14f, ui.muted))
        p.hook?.let { who.addView(ui.text("🔥 $it", 14f), ui.lp(top = ui.dp(4))) }
        p.teaser?.let { who.addView(ui.text("→ ${it.uppercase()}", 14f, ui.accent, true), ui.lp(top = ui.dp(2))) }
        r.addView(who, ui.lp(0, WRAP_CONTENT, weight = 1f))
        val chip = when {
            over -> ui.chip("> лимита", ui.bad, ui.badSoft)
            p.manual -> ui.chip("вручную", ui.warn, ui.warnSoft)
            p.fromAi -> ui.chip("ИИ", ui.accent, ui.accentSoft)
            else -> null
        }
        if (chip != null) r.addView(chip, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = ui.dp(8)
        })
        return r
    }

    private fun editPart(i: Int) {
        if (Engine.kind != Engine.Kind.IDLE) { toast("Дождись окончания текущей задачи"); return }
        val parts = currentParts()
        val p = parts[i]
        val n = parts.size
        val last = i == n - 1
        val s = Sheet("Часть ${i + 1} из $n")
        s.body.addView(ui.text("${fmt(p.a)} – ${fmt(p.b)}", 15f, ui.muted))
        val endEt = if (!last) ui.field(s.body, "Конец части (ч:мм:сс)", fmt(p.b)) else null
        val hookEt = ui.field(s.body, "Заголовок в начале части", p.hook ?: "", hintText = "до 7 слов")
        val teaserEt = if (!last) ui.field(s.body, "Текст финального экрана", p.teaser ?: "",
            hintText = "до 6 слов, интригующий") else null
        if (last) s.body.addView(ui.hint("Последняя часть: на финальном экране будет Telegram-канал " +
            "или «Больше дорам в профиле»."), ui.lp(top = ui.dp(10)))
        s.show("Отмена", "Сохранить") {
            val auto = cuts()
            val cs = auto.toMutableList()
            if (endEt != null && teaserEt != null) {
                val t = parseTime(endEt.text.toString()) ?: return@show "Время пиши как ч:мм:сс, например 0:58:40"
                val lo = p.a + 60_000
                val hi = (if (i + 1 < cs.size) cs[i + 1].tMs else Session.durMs) - 60_000
                if (t !in lo..hi) return@show "Конец части должен быть между ${fmt(lo)} и ${fmt(hi)}"
                val old = cs[i]
                val teaser = teaserEt.text.toString().trim().ifEmpty { null }
                if (t != old.tMs || teaser != old.teaser) cs[i] = old.copy(tMs = t, teaser = teaser, manual = true)
            }
            val hook = hookEt.text.toString().trim().ifEmpty { null }
            if (i == 0) Session.firstHook = hook
            else if (hook != cs[i - 1].nextHook) cs[i - 1] = cs[i - 1].copy(nextHook = hook)
            if (cs != auto) Session.manualCuts = cs
            renderPlan()
            toast("Часть ${i + 1} обновлена")
            null
        }
    }

    private class Estimate(val total: Long, val maxPart: Long)

    private fun estimate(parts: List<Part>, from: Int): Estimate {
        val bytesPerMs = (mbps() * 1_000_000 + 160_000) / 8 / 1000
        val sizes = parts.drop(from).map { ((it.b - it.a) * bytesPerMs).toLong() }
        return Estimate(sizes.sum(), sizes.maxOrNull() ?: 0L)
    }

    private fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    private fun gb(b: Long) = String.format(Locale("ru"), "%.1f ГБ", b / 1e9)

    private fun renderSpace() {
        val parts = currentParts()
        if (parts.isEmpty()) { spaceTv.visibility = View.GONE; return }
        val e = estimate(parts, 0)
        val free = freeBytes()
        spaceTv.visibility = View.VISIBLE
        spaceTv.text = "Нужно ≈ ${gb(e.total)} в галерее (+${gb(e.maxPart)} временно) · свободно ${gb(free)}"
        spaceTv.setTextColor(if (e.total + e.maxPart > free) ui.bad else ui.muted)
    }

    // =====================================================================
    //                              ЗАДАЧИ
    // =====================================================================
    private fun savePrefs() {
        prefs.edit()
            .putString("title", titleEt.text.toString().trim())
            .putString("tg", tgEt.text.toString().trim())
            .putString("tags", tagsEt.text.toString().trim())
            .putString("max", maxEt.text.toString().trim())
            .putString("rate", rateEt.text.toString().trim())
            .putString("key", keyEt.text.toString().trim())
            .putString("model", modelEt.text.toString().trim())
            .apply()
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    private fun onAiClick() {
        val uri = Session.uri
        if (uri == null || Session.durMs <= 0) { toast("Сначала выбери видео"); return }
        val key = keyEt.text.toString().trim()
        if (key.isEmpty()) { toast("Вставь API-ключ Gemini"); return }
        savePrefs()
        askNotifications()
        val model = modelEt.text.toString().trim().ifEmpty { DEFAULT_MODEL }
        Engine.startAi(this, AiJob(uri, key, model, Session.durMs))
    }

    private fun applyAi(r: AiResult) {
        Session.hooks = r.hooks
        Session.firstHook = r.firstHook
        Session.summary = r.summary
        Session.manualCuts = null
        r.tags?.let { tagsEt.setText(it) }
        renderVideo()
        renderPlan()
        toast("ИИ подготовил план нарезки")
    }

    private fun onStartClick() {
        val parts = currentParts()
        if (parts.isEmpty()) { toast("Сначала выбери видео"); return }
        savePrefs()
        val e = estimate(parts, 0)
        val free = freeBytes()
        if (e.total + e.maxPart > free) {
            val s = Sheet("Может не хватить места")
            s.body.addView(ui.text("Для всех частей нужно примерно ${gb(e.total + e.maxPart)}, " +
                "а свободно ${gb(free)}. Уменьши битрейт или освободи место.", 16f), ui.lp(top = ui.dp(8)))
            s.show("Отмена", "Всё равно начать") { launch(0); null }
        } else {
            launch(0)
        }
    }

    private fun launch(from: Int) {
        val uri = Session.uri ?: return
        askNotifications()
        Engine.startCut(this, CutJob(
            uri, Session.baseName, currentParts(), from, Session.landscape, mbps(),
            titleEt.text.toString().trim(), tgEt.text.toString().trim(), tagsEt.text.toString().trim()
        ))
    }

    /** С какой части можно продолжить: только то же видео и тот же план. */
    private fun resumable(): Int? {
        val j = Engine.lastJob ?: return null
        val from = Engine.resumeFrom ?: return null
        if (j.uri != Session.uri || j.parts != currentParts()) return null
        return from
    }

    private fun renderEngine() {
        Engine.aiResult?.let {
            Engine.aiResult = null
            applyAi(it)
            return
        }
        val busy = Engine.kind != Engine.Kind.IDLE
        val p = Engine.progress
        statusTv.text = if (!busy && Session.uri == null && !Engine.isError && Engine.captions == null)
            "Выбери видео, чтобы начать" else Engine.status
        statusTv.setTextColor(if (Engine.isError) ui.bad else ui.ink)
        statusTv.typeface = if (Engine.isError) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        meter.value = if (busy) p else p.coerceAtLeast(0)
        pctTv.text = if (busy && p >= 0) "$p%" else ""
        startBtn.enable(!busy)
        stopBtn.enable(busy)
        pickBtn.enable(!busy)
        aiBtn.enable(!busy)
        val r = if (busy) null else resumable()
        resumeBtn.visibility = if (r != null) View.VISIBLE else View.GONE
        if (r != null) resumeBtn.text = "Продолжить с части ${r + 1}"
        val cap = Engine.captions
        resultHead.visibility = if (cap != null) View.VISIBLE else View.GONE
        resultCard.visibility = if (cap != null) View.VISIBLE else View.GONE
        if (cap != null && resultTv.text.toString() != cap) resultTv.text = cap
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
