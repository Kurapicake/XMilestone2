package dev.encore.manager.gamebar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

/**
 * Draws the crosshair at the exact center of whatever window hosts this view. The view itself
 * never receives touches — that is enforced by the hosting window's
 * `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` flags in [GameBarOverlay], not by anything here.
 */
class CrosshairView(context: Context) : View(context) {

    private var style: String = GameBarPrefs.CROSSHAIR_DOT
    private var sizePx: Float = dp(24)
    private var colorInt: Int = colorFor(GameBarPrefs.CROSSHAIR_RED)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2)
        strokeCap = Paint.Cap.ROUND
    }

    private fun dp(v: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics)

    fun configure(style: String, sizeDp: Int, colorName: String) {
        this.style = style
        this.sizePx = dp(sizeDp)
        this.colorInt = colorFor(colorName)
        fillPaint.color = colorInt
        strokePaint.color = colorInt
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = sizePx / 2f

        when (style) {
            GameBarPrefs.CROSSHAIR_CROSS -> {
                val gap = r * 0.35f
                canvas.drawLine(cx - r, cy, cx - gap, cy, strokePaint)
                canvas.drawLine(cx + gap, cy, cx + r, cy, strokePaint)
                canvas.drawLine(cx, cy - r, cx, cy - gap, strokePaint)
                canvas.drawLine(cx, cy + gap, cx, cy + r, strokePaint)
            }
            GameBarPrefs.CROSSHAIR_CIRCLE_DOT -> {
                canvas.drawCircle(cx, cy, r, strokePaint)
                canvas.drawCircle(cx, cy, dp(2), fillPaint)
            }
            else -> { // GameBarPrefs.CROSSHAIR_DOT
                canvas.drawCircle(cx, cy, r * 0.22f, fillPaint)
            }
        }
    }

    private companion object {
        fun colorFor(name: String): Int = when (name) {
            GameBarPrefs.CROSSHAIR_CYAN -> Color.parseColor("#00E5FF")
            GameBarPrefs.CROSSHAIR_LIME -> Color.parseColor("#39FF6A")
            else -> Color.parseColor("#FF1E38") // CROSSHAIR_RED
        }
    }
}
