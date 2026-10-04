package com.dorama.cutter

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

enum class Btn { PRIMARY, SOFT, DANGER, OK, PLAIN }

/** Виджеты в стиле LOGOPED: мягкий фон, белые карточки, синий акцент, тёмная тема. Цвета — res/values(-night)/colors.xml. */
class Ui(private val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Int) = v * density

    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private fun col(id: Int) = ctx.getColor(id)
    val bg = col(R.color.bg)
    val cardBg = col(R.color.card)
    val border = col(R.color.card_border)
    val ink = col(R.color.text)
    val muted = col(R.color.muted)
    val line = col(R.color.line)
    val accent = col(R.color.accent)
    val accentSoft = col(R.color.accent_soft)
    val onAccent = col(R.color.on_accent)
    val ok = col(R.color.ok)
    val okSoft = col(R.color.ok_soft)
    val bad = col(R.color.bad)
    val badSoft = col(R.color.bad_soft)
    val warn = col(R.color.warn)
    val warnSoft = col(R.color.warn_soft)

    fun shape(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun ripple(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = RippleDrawable(
        ColorStateList.valueOf((ink and 0x00FFFFFF) or 0x1F000000),
        shape(color, radius, stroke, strokeColor),
        shape(Color.WHITE, radius)
    )

    fun lp(w: Int = MATCH_PARENT, h: Int = WRAP_CONTENT, top: Int = 0, bottom: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top; bottomMargin = bottom }

    fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun text(s: CharSequence, size: Float = 17f, color: Int = ink, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.12f)
    }

    /** Заголовок раздела: мелкий, серый, ЗАГЛАВНЫМИ. */
    fun h3(s: String) = text(s.uppercase(Locale("ru")), 13f, muted, true).apply { letterSpacing = 0.04f }

    fun hint(s: String) = text(s, 14f, muted)

    fun chip(s: String, fg: Int, bgColor: Int) = text(s, 13f, fg, true).apply {
        background = shape(bgColor, dpf(99))
        setPadding(dp(10), dp(4), dp(10), dp(4))
        maxLines = 1
    }

    fun card() = column().apply {
        background = shape(cardBg, dpf(14), 1, border)
        elevation = if (dark) 0f else dpf(1)
        setPadding(dp(14), dp(4), dp(14), dp(14))
    }

    fun button(label: String, style: Btn, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(14), dp(14), dp(14), dp(14))
        style(this, style)
        setOnClickListener { onClick() }
    }

    fun style(v: TextView, s: Btn) {
        val (b, f) = when (s) {
            Btn.PRIMARY -> accent to onAccent
            Btn.SOFT -> accentSoft to accent
            Btn.DANGER -> badSoft to bad
            Btn.OK -> okSoft to ok
            Btn.PLAIN -> line to ink
        }
        v.background = ripple(b, dpf(12))
        v.setTextColor(f)
    }

    /** Подпись + поле ввода (скруглённое, с синей рамкой в фокусе). */
    fun field(
        parent: LinearLayout, label: String, value: String,
        type: Int = InputType.TYPE_CLASS_TEXT, hintText: String = ""
    ): EditText {
        parent.addView(text(label, 14f, muted), lp(top = dp(12)))
        val e = EditText(ctx).apply {
            setText(value)
            hint = hintText
            inputType = type
            textSize = 17f
            setTextColor(ink)
            setHintTextColor(muted)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(bg, dpf(10), dp(2), accent))
                addState(intArrayOf(), shape(bg, dpf(10), dp(1), line))
            }
            setPadding(dp(12), dp(11), dp(12), dp(11))
            minHeight = dp(46)
        }
        parent.addView(e, lp(top = dp(4)))
        return e
    }
}

fun View.enable(on: Boolean) {
    isEnabled = on
    alpha = if (on) 1f else 0.45f
}

fun EditText.onChange(f: () -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) = f()
})

/** Скруглённая полоска прогресса; value = -1 — бегущий отрезок (ждём, сколько — неизвестно). */
class Meter(ctx: Context, private val track: Int, private val fill: Int) : View(ctx) {
    var value = 0
        set(v) {
            if (field != v) { field = v; invalidate() }
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        paint.color = track
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, paint)
        paint.color = fill
        if (value >= 0) {
            val x = w * value.coerceAtMost(100) / 100f
            if (x > 0) {
                rect.set(0f, 0f, maxOf(x, h), h)
                c.drawRoundRect(rect, r, r, paint)
            }
        } else {
            clip.reset()
            clip.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            val seg = w * 0.3f
            val t = (SystemClock.uptimeMillis() % 1400) / 1400f
            val x = -seg + (w + seg) * t
            rect.set(x, 0f, x + seg, h)
            c.drawRoundRect(rect, r, r, paint)
            c.restore()
            postInvalidateOnAnimation()
        }
    }
}
