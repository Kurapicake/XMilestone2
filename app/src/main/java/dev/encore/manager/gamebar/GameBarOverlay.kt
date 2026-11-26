package dev.encore.manager.gamebar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.encore.manager.R
import dev.encore.manager.data.EncoreRepository
import dev.encore.manager.data.RootShell
import dev.encore.manager.data.bool
import dev.encore.manager.data.withBool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The on-screen part of the GameSpace game bar, shown only over games from the Encore list:
 *  - a compact, translucent monitor pill (prime-core CPU frequency, FPS, temperature, battery and clock). Drag
 *    it to move it; tap it to open the Command Center panel. The pill is hidden for as long as
 *    the panel is open and comes back, at its saved position, when the panel closes,
 *  - an expanded "Command Center" panel, always docked to the right edge, with four tabs: Tweaks (Lite Mode, Do Not Disturb,
 *    Disable Tweaks), Hardware (brightness, volume, crosshair), Live Monitor (pick the pill's
 *    numbers) and Quick Actions (screenshot, clear apps),
 *  - an independent, non-touchable crosshair reticle, shown whenever it is enabled.
 * Built with plain Views because a Compose window inside a Service needs extra lifecycle
 * plumbing. All methods must be called on the main thread.
 */
class GameBarOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themed: Context = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
    private val sampler = MetricsSampler(context)

    private var currentPkg: String? = null
    private var panelView: View? = null
    private var pillView: View? = null
    private var crosshairView: CrosshairView? = null
    private var profileJob: Job? = null
    private var telemetryJob: Job? = null
    private var metricsJob: Job? = null

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    // ------------------------------------------------------------------ public

    /** Pass the game package that is now in the foreground, or null when none. */
    fun update(pkg: String?) {
        if (pkg == currentPkg) return
        currentPkg = pkg
        try {
            closePanel(restorePill = false)
            removePill()
            removeCrosshair()
            if (pkg != null) {
                showPill()
                if (GameBarPrefs.crosshairEnabled(context)) showCrosshair()
            }
        } catch (e: Exception) {
            // Never let a failed window setup escape: ensureShown() tries again a moment later
            Log.w(TAG, "update failed", e)
        }
    }

    /** True while the pill or the panel window is on screen. */
    fun isShown(): Boolean = pillView != null || panelView != null

    private var lastEnsureAt = 0L

    /**
     * Called about once a second by the service. If a game is in front but nothing is on screen
     * (the first attempt failed, or the pill was switched on later) it tries again, at most every
     * few seconds so a missing overlay permission does not spam the log.
     */
    fun ensureShown() {
        if (currentPkg == null) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnsureAt < ENSURE_GAP_MS) return
        lastEnsureAt = now
        try {
            if (pillView == null && panelView == null) showPill()
            if (crosshairView == null && GameBarPrefs.crosshairEnabled(context)) showCrosshair()
        } catch (e: Exception) {
            Log.w(TAG, "ensureShown failed", e)
        }
    }

    /** Runs one metric update; a failure in one number never stops the others. */
    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
        }
    }

    fun dispose() {
        closePanel(restorePill = false)
        removePill()
        removeCrosshair()
        currentPkg = null
    }

    // ------------------------------------------------------------------ windows

    private fun addWindow(view: View, lp: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, lp)
        true
    } catch (e: Exception) {
        // Most likely the overlay permission is missing
        Log.w(TAG, "addView failed", e)
        false
    }

    private fun removeWindow(view: View?) {
        if (view == null) return
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
    }

    // ------------------------------------------------------------------ chamfered cyberpunk drawables

    /** Rounded fill, used for pill-shaped and circular elements (the monitor pill, colour swatches). */
    private fun rounded(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(dp(strokeDp), stroke)
        }

    /** Angular, chamfered-corner fill used for every HUD panel, chip and card. */
    private fun chamfered(fill: Int, cutDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): Drawable =
        ChamferDrawable(fill, dp(cutDp).toFloat(), strokeColor, dp(strokeDp).toFloat())

    private class ChamferDrawable(
        private val fillColor: Int,
        private val cutPx: Float,
        private val strokeColor: Int,
        private val strokeWidthPx: Float,
    ) : Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor; style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }
        private val path = Path()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            buildPath(bounds)
        }

        private fun buildPath(b: Rect) {
            val l = b.left.toFloat()
            val t = b.top.toFloat()
            val r = b.right.toFloat()
            val bt = b.bottom.toFloat()
            val c = cutPx.coerceAtMost(min(r - l, bt - t) / 2f)
            path.reset()
            path.moveTo(l + c, t)
            path.lineTo(r - c, t)
            path.lineTo(r, t + c)
            path.lineTo(r, bt - c)
            path.lineTo(r - c, bt)
            path.lineTo(l + c, bt)
            path.lineTo(l, bt - c)
            path.lineTo(l, t + c)
            path.close()
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(path, fillPaint)
            if (strokeColor != 0) canvas.drawPath(path, strokePaint)
        }

        override fun setAlpha(alpha: Int) {
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fillPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ------------------------------------------------------------------ small view helpers

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(themed).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private class Stat(val root: LinearLayout, val value: TextView)

    /** Two stacked texts: a value on top, a small caption below. */
    private fun stat(caption: String, valueSp: Float, valueMinWidthDp: Int, valueColor: Int = WHITE): Stat {
        val value = label("--", valueSp, valueColor, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(valueMinWidthDp)
        }
        val cap = label(caption, 9f, GREY).apply { gravity = Gravity.CENTER }
        val root = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(value)
            addView(cap)
        }
        return Stat(root, value)
    }

    private fun tintSwitch(sw: Switch) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
        sw.trackTintList = ColorStateList(states, intArrayOf(NEON_RED, HAIRLINE))
        sw.thumbTintList = ColorStateList(states, intArrayOf(WHITE, GREY))
    }

    private fun seekBar(max: Int, progress: Int, onChange: (Int) -> Unit): SeekBar =
        SeekBar(themed).apply {
            // Pages add rows with a bare addView(view), which would otherwise give a SeekBar
            // WRAP_CONTENT width (and a cramped, hard-to-drag track). Every slider in the
            // GameSpace panel needs to span the page, so fix that here, once, for all of them.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            this.max = max.coerceAtLeast(1)
            this.progress = progress.coerceIn(0, this.max)
            progressTintList = ColorStateList.valueOf(NEON_RED)
            progressBackgroundTintList = ColorStateList.valueOf(HAIRLINE)
            thumbTintList = ColorStateList.valueOf(NEON_RED)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) onChange(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }

    // ------------------------------------------------------------------ monitor pill

    private class HudCell(val root: View, val value: TextView)

    /** Single-line white HUD text. The faint dark halo keeps it readable on bright game scenes. */
    private fun hudLabel(text: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(themed).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            maxLines = 1
            includeFontPadding = false
            setShadowLayer(dp(2).toFloat(), 0f, 0f, HUD_TEXT_HALO)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** Keeps a value from changing width as its digits change, so the pill never jitters. */
    private fun reserveWidth(tv: TextView, widestSample: String) {
        tv.minWidth = Math.ceil(tv.paint.measureText(widestSample).toDouble()).toInt()
    }

    /** One compact cell: a line icon on the left, a small caption over the live value. */
    private fun hudCell(iconRes: Int, caption: String, widestSample: String): HudCell {
        val icon = ImageView(themed).apply { setImageResource(iconRes) }
        val cap = hudLabel(caption, HUD_CAPTION_SP, HUD_CAPTION)
        val value = hudLabel("--", HUD_VALUE_SP, WHITE)
        reserveWidth(value, widestSample)
        val texts = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            val wrap = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(cap, wrap)
            addView(value, LinearLayout.LayoutParams(wrap))
        }
        val root = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(3) })
            addView(texts, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        return HudCell(root, value)
    }

    private class BatteryCell(val root: View, val percent: TextView, val clock: TextView)

    /** Battery outline with the percentage inside it, followed by the wall-clock time. */
    private fun batteryCell(): BatteryCell {
        val percent = hudLabel("--", 7f, WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(0, 3, HUD_ICON_STROKE, 1)
        }
        val nub = View(themed).apply { background = rounded(HUD_ICON_STROKE, 1) }
        val clock = hudLabel("--:--", HUD_VALUE_SP, WHITE)
        reserveWidth(clock, "88:88")
        val root = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(percent, LinearLayout.LayoutParams(dp(19), dp(11)))
            addView(nub, LinearLayout.LayoutParams(dp(2), dp(5)).apply { marginStart = dp(1) })
            addView(clock, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4) })
        }
        return BatteryCell(root, percent, clock)
    }

    private fun showPill() {
        val pkg = currentPkg ?: return
        if (!GameBarPrefs.pillEnabled(context)) return
        // Never a second pill window, and never a pill while the panel is open (for example when
        // a Live Monitor switch inside the panel asks for the pill to be rebuilt): closePanel()
        // brings it back, with the new settings, once the panel is gone.
        if (pillView != null || panelView != null) return

        val showCpuLoad = GameBarPrefs.metricOn(context, GameBarPrefs.M_CPULOAD)
        val showFps = GameBarPrefs.metricOn(context, GameBarPrefs.M_FPS)
        val showTemp = GameBarPrefs.metricOn(context, GameBarPrefs.M_BATTEMP)
        val showCpuTemp = GameBarPrefs.metricOn(context, GameBarPrefs.M_CPUTEMP)
        val showGpuTemp = GameBarPrefs.metricOn(context, GameBarPrefs.M_GPUTEMP)
        val showBat = GameBarPrefs.metricOn(context, GameBarPrefs.M_BATTERY)

        val cpuFreq = if (showCpuLoad) hudCell(R.drawable.ic_hud_cpu, "CPU", "3.35GHz") else null
        val fps = if (showFps) hudCell(R.drawable.ic_hud_fps, "FPS", "888FPS") else null
        val temp = if (showTemp) hudCell(R.drawable.ic_hud_temp, "Temp", "88.8°C") else null
        val cpuTemp = if (showCpuTemp) hudCell(R.drawable.ic_hud_temp, "CPU Temp", "88°C") else null
        val gpuTemp = if (showGpuTemp) hudCell(R.drawable.ic_hud_temp, "GPU Temp", "88°C") else null
        val battery = if (showBat) batteryCell() else null

        val row = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Dark glass: mostly see-through so the game stays visible, a faint hairline edge
            background = rounded(HUD_FILL, 50, HUD_BORDER, 1)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        var cells = 0
        fun addCell(v: View) {
            row.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (cells > 0) marginStart = dp(10) })
            cells++
        }
        cpuFreq?.let { addCell(it.root) }
        fps?.let { addCell(it.root) }
        temp?.let { addCell(it.root) }
        cpuTemp?.let { addCell(it.root) }
        gpuTemp?.let { addCell(it.root) }
        battery?.let { addCell(it.root) }
        // Every number switched off: keep a tiny tap target so the panel stays reachable
        if (cells == 0) addCell(hudLabel("Game Bar", HUD_VALUE_SP, WHITE))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // Until the user drags it somewhere, the pill sits centred along the top edge. Its width
        // is measured up front so it is centred on any screen size without a visible jump.
        val dm0 = context.resources.displayMetrics
        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val saved = GameBarPrefs.pillPos(context)
        lp.x = saved?.first ?: max(0, (dm0.widthPixels - row.measuredWidth) / 2)
        lp.y = saved?.second ?: dp(6)
        if (!addWindow(row, lp)) return
        pillView = row

        // Clamp to the screen once the size is known (also covers a rotation since it was saved)
        row.post {
            val dm = context.resources.displayMetrics
            val cx = lp.x.coerceIn(0, max(0, dm.widthPixels - row.width))
            val cy = lp.y.coerceIn(0, max(0, dm.heightPixels - row.height))
            if (cx != lp.x || cy != lp.y) {
                lp.x = cx
                lp.y = cy
                try {
                    wm.updateViewLayout(row, lp)
                } catch (e: Exception) {
                    Log.w(TAG, "clamp pill failed", e)
                }
            }
        }

        // Drag the pill anywhere (it stays inside the screen). A plain tap opens the panel.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        row.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && (abs(dx) > dp(8).toFloat() || abs(dy) > dp(8).toFloat())) dragging = true
                    if (dragging) {
                        val dm = context.resources.displayMetrics
                        lp.x = (startX + dx.toInt()).coerceIn(0, max(0, dm.widthPixels - row.width))
                        lp.y = (startY + dy.toInt()).coerceIn(0, max(0, dm.heightPixels - row.height))
                        try {
                            wm.updateViewLayout(row, lp)
                        } catch (e: Exception) {
                            Log.w(TAG, "move pill failed", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) GameBarPrefs.setPillPos(context, lp.x, lp.y) else openPanel()
                    true
                }
                else -> false
            }
        }

        val timeFormat = SimpleDateFormat(
            if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm",
            Locale.US,
        )
        metricsJob = scope.launch {
            while (isActive) {
                // FPS first: it is the slowest reading and the one people watch
                if (fps != null) {
                    safely("fps") { fps.value.text = sampler.fps(pkg)?.let { "${it}FPS" } ?: "--" }
                }
                // Prime-core clock from cpufreq
                if (cpuFreq != null) {
                    safely("cpu freq") { cpuFreq.value.text = sampler.primeCpuMhz()?.let { MetricParsers.formatCpuFreq(it) } ?: "--" }
                }
                if (temp != null || battery != null) {
                    safely("battery") {
                        val b = sampler.battery()
                        temp?.value?.text = b.tempC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "--"
                        battery?.percent?.text = b.percent?.toString() ?: "--"
                    }
                }
                if (battery != null) {
                    safely("clock") { battery.clock.text = timeFormat.format(Date()) }
                }
                if (cpuTemp != null) {
                    safely("cpu temp") { cpuTemp.value.text = sampler.cpuTemp()?.let { "$it°C" } ?: "--" }
                }
                if (gpuTemp != null) {
                    safely("gpu temp") { gpuTemp.value.text = sampler.gpuTemp()?.let { "$it°C" } ?: "--" }
                }
                delay(1000)
            }
        }
    }

    private fun removePill() {
        metricsJob?.cancel()
        metricsJob = null
        removeWindow(pillView)
        pillView = null
    }

    /** Rebuild the pill after the user changed which numbers it shows. */
    private fun refreshPill() {
        removePill()
        showPill()
    }

    // ------------------------------------------------------------------ crosshair overlay

    private fun showCrosshair() {
        if (crosshairView != null) return
        val v = CrosshairView(context)
        v.configure(
            GameBarPrefs.crosshairStyle(context),
            GameBarPrefs.crosshairSize(context),
            GameBarPrefs.crosshairColor(context),
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Fully click-through: unlike the pill/panel, the crosshair must never
            // intercept a touch anywhere on screen, even within its own (full-screen) bounds.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.CENTER
        if (addWindow(v, lp)) crosshairView = v
    }

    private fun removeCrosshair() {
        removeWindow(crosshairView)
        crosshairView = null
    }

    private fun refreshCrosshairConfig() {
        crosshairView?.configure(
            GameBarPrefs.crosshairStyle(context),
            GameBarPrefs.crosshairSize(context),
            GameBarPrefs.crosshairColor(context),
        )
    }

    // ------------------------------------------------------------------ panel

    private fun labelOf(pkg: String): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private class SwitchRow(val root: View, val sw: Switch)

    private fun switchRow(title: String, sub: String, checked: Boolean = false): SwitchRow {
        val sw = Switch(themed).apply { isChecked = checked }
        tintSwitch(sw)
        val texts = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 14f, WHITE, bold = true))
            addView(label(sub, 11f, GREY))
        }
        val row = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
        }
        return SwitchRow(row, sw)
    }

    private fun sectionLabel(text: String): TextView =
        label(text.uppercase(), 11f, NEON_RED, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(10), 0, dp(4))
        }

    private fun tab(title: String, sub: String): LinearLayout =
        LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            addView(label(title, 13f, WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(label(sub, 9f, GREY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }

    private fun styleTab(v: View, selected: Boolean) {
        v.background = chamfered(
            if (selected) OBSIDIAN_RAISED else CARBON, 10,
            if (selected) NEON_RED else HAIRLINE,
        )
    }

    private fun page(vararg rows: View): LinearLayout =
        LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            background = chamfered(CARBON, 14, 0xFF383B50.toInt())
            setPadding(dp(14), dp(4), dp(14), dp(8))
            visibility = View.GONE
            rows.forEach { addView(it) }
        }

    /** A small chamfered, selectable text chip (crosshair style picker). */
    private fun optionChip(text: String, selected: Boolean): TextView =
        label(text, 11f, if (selected) NEON_RED else GREY, bold = selected).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = chamfered(if (selected) OBSIDIAN_RAISED else CARBON, 6, if (selected) NEON_RED else HAIRLINE)
            isClickable = true
        }

    /** A small colored swatch button (crosshair color picker). */
    private fun colorSwatch(color: Int, selected: Boolean): View =
        View(themed).apply {
            background = rounded(color, 14, if (selected) WHITE else 0, 2)
            isClickable = true
        }

    /** A scroll view that never grows taller than the screen. */
    private class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
        }
    }

    private fun openPanel() {
        val pkg = currentPkg ?: return
        if (panelView != null) {
            closePanel()
            return
        }

        // ---- header
        val profile = label("Profile: …", 11f, GREY).apply { gravity = Gravity.END }
        val logo = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("ENCORE", 16f, WHITE, bold = true).apply { setTypeface(typeface, Typeface.BOLD_ITALIC) })
            addView(label("GameSpace", 10f, NEON_RED))
        }
        val gameInfo = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(label(labelOf(pkg), 14f, WHITE, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.END
            })
            addView(profile)
        }
        val header = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo)
            addView(View(themed), LinearLayout.LayoutParams(dp(12), 1))
            addView(gameInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ---- always-visible telemetry strip: FPS / CPU load / RAM / Temp
        val tFps = stat("FPS", 15f, 36, valueColor = NEON_RED)
        val tCpu = stat("CPU", 15f, 36)
        val tRam = stat("RAM", 15f, 36)
        val tTemp = stat("TEMP", 15f, 36, valueColor = AMBER)
        val telemetryRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            background = chamfered(0xE6050508.toInt(), 10, 0xFF4D0010.toInt())
            setPadding(dp(6), dp(8), dp(6), dp(8))
            listOf(tFps, tCpu, tRam, tTemp).forEachIndexed { i, s ->
                addView(s.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (i < 3) addView(View(themed).apply { setBackgroundColor(0x33FF1E38) }, LinearLayout.LayoutParams(dp(1), dp(24)))
            }
        }

        // ---- page: Tweaks (this game's overrides only; the global Disable Tweaks switch
        // lived here but was removed — it is still readable/writable via EncoreRepository
        // if a future screen wants it back)
        val lite = switchRow("Lite Mode", "Lower caps to run cooler").apply { sw.isEnabled = false }
        val dnd = switchRow("Do Not Disturb", "Mute notifications while playing").apply { sw.isEnabled = false }
        val tweaksPage = page(
            sectionLabel("This game"), lite.root, dnd.root,
        )

        // ---- page: Hardware (brightness, volume, crosshair)
        val canWriteSettings = BrightnessController.canWrite(context)
        val brightnessValue = label("", 11f, GREY)
        val brightnessBar = seekBar(
            max = BrightnessController.MAX - BrightnessController.MIN,
            progress = BrightnessController.current(context) - BrightnessController.MIN,
        ) { v ->
            val value = v + BrightnessController.MIN
            BrightnessController.set(context, value)
            brightnessValue.text = "$value / ${BrightnessController.MAX}"
        }
        brightnessValue.text = "${BrightnessController.current(context)} / ${BrightnessController.MAX}"
        val brightnessHeaderRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Explicit MATCH_PARENT: this row is later added to a page via a bare addView(it),
            // which would otherwise give it WRAP_CONTENT width and make the weight(1f) label
            // below a no-op (no extra space to distribute without a MATCH_PARENT parent).
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label("Brightness", 14f, WHITE, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(brightnessValue)
        }
        val autoBrightness = switchRow("Auto-Brightness", "Let the system manage brightness", BrightnessController.isAutomatic(context))
        autoBrightness.sw.setOnCheckedChangeListener { _, checked ->
            BrightnessController.setAutomatic(context, checked)
            brightnessBar.isEnabled = !checked
        }
        brightnessBar.isEnabled = canWriteSettings && !BrightnessController.isAutomatic(context)

        val volumeValue = label("", 11f, GREY)
        val volumeBar = seekBar(
            max = VolumeController.max(context) - VolumeController.min(context),
            progress = VolumeController.current(context) - VolumeController.min(context),
        ) { v ->
            val index = v + VolumeController.min(context)
            VolumeController.set(context, index)
            volumeValue.text = "$index / ${VolumeController.max(context)}"
        }
        volumeValue.text = "${VolumeController.current(context)} / ${VolumeController.max(context)}"
        val volumeHeaderRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(label("Media Volume", 14f, WHITE, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(volumeValue)
        }
        val quickMute = switchRow("Quick Mute", "Mute game audio, no system volume UI", VolumeController.isMuted(context))
        quickMute.sw.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                GameBarPrefs.setPreMuteVolume(context, VolumeController.current(context))
                VolumeController.set(context, VolumeController.min(context))
            } else {
                val restore = GameBarPrefs.preMuteVolume(context)
                    ?: (VolumeController.min(context) + (VolumeController.max(context) - VolumeController.min(context)) * 2 / 3)
                VolumeController.set(context, restore)
                GameBarPrefs.setPreMuteVolume(context, null)
            }
            val cur = VolumeController.current(context)
            volumeBar.progress = (cur - VolumeController.min(context)).coerceAtLeast(0)
            volumeValue.text = "$cur / ${VolumeController.max(context)}"
        }

        // Crosshair
        val crosshairToggle = switchRow(
            "Crosshair overlay",
            "Independent, non-touchable reticle centered on screen",
            GameBarPrefs.crosshairEnabled(context),
        )
        val styleRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            // Its chips use weight(1f) to split the row evenly, so it needs MATCH_PARENT width
            // for the same reason noted on brightnessHeaderRow/volumeHeaderRow above.
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val styleOptions = listOf(
            GameBarPrefs.CROSSHAIR_DOT to "Dot",
            GameBarPrefs.CROSSHAIR_CROSS to "Cross",
            GameBarPrefs.CROSSHAIR_CIRCLE_DOT to "Circle+Dot",
        )
        fun rebuildStyleRow() {
            styleRow.removeAllViews()
            val current = GameBarPrefs.crosshairStyle(context)
            styleOptions.forEachIndexed { i, (value, text) ->
                val chip = optionChip(text, value == current)
                chip.setOnClickListener {
                    GameBarPrefs.setCrosshairStyle(context, value)
                    refreshCrosshairConfig()
                    rebuildStyleRow()
                }
                styleRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                })
            }
        }
        rebuildStyleRow()

        val sizeValue = label("${GameBarPrefs.crosshairSize(context)} dp", 11f, GREY)
        val sizeRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Size", 12f, WHITE), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(themed), LinearLayout.LayoutParams(dp(8), 1))
            addView(sizeValue)
        }
        val sizeBar = seekBar(max = 56, progress = GameBarPrefs.crosshairSize(context) - 8) { v ->
            val sizeDp = v + 8
            GameBarPrefs.setCrosshairSize(context, sizeDp)
            sizeValue.text = "$sizeDp dp"
            refreshCrosshairConfig()
        }

        val colorRow = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val colorOptions = listOf(
            GameBarPrefs.CROSSHAIR_RED to NEON_RED,
            GameBarPrefs.CROSSHAIR_CYAN to CYAN,
            GameBarPrefs.CROSSHAIR_LIME to LIME,
        )
        fun rebuildColorRow() {
            colorRow.removeAllViews()
            val current = GameBarPrefs.crosshairColor(context)
            colorOptions.forEachIndexed { i, (value, argb) ->
                val swatch = colorSwatch(argb, value == current)
                swatch.setOnClickListener {
                    GameBarPrefs.setCrosshairColor(context, value)
                    refreshCrosshairConfig()
                    rebuildColorRow()
                }
                colorRow.addView(swatch, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    if (i > 0) marginStart = dp(10)
                })
            }
        }
        rebuildColorRow()

        crosshairToggle.sw.setOnCheckedChangeListener { _, checked ->
            GameBarPrefs.setCrosshairEnabled(context, checked)
            if (checked) showCrosshair() else removeCrosshair()
        }

        val hardwarePageRows = mutableListOf<View>(
            sectionLabel("Brightness"), brightnessHeaderRow, brightnessBar, autoBrightness.root,
        )
        if (!canWriteSettings) {
            val grant = Button(themed).apply {
                text = "GRANT \"MODIFY SYSTEM SETTINGS\""
                isAllCaps = false
                backgroundTintList = ColorStateList.valueOf(NEON_RED)
                setTextColor(WHITE)
            }
            grant.setOnClickListener {
                scope.launch {
                    GameBarSetup.grantViaRoot(context)
                    delay(300)
                    closePanel(restorePill = false)
                    openPanel()
                }
            }
            hardwarePageRows.add(grant)
            brightnessBar.isEnabled = false
            autoBrightness.sw.isEnabled = false
        }
        hardwarePageRows += listOf(
            sectionLabel("Media Volume"), volumeHeaderRow, volumeBar, quickMute.root,
            sectionLabel("Crosshair"), crosshairToggle.root, styleRow, sizeRow, sizeBar, colorRow,
        )
        val hardwarePage = page(*hardwarePageRows.toTypedArray())

        // ---- page: Live Monitor
        fun metricRow(title: String, sub: String, key: String): SwitchRow {
            val r = switchRow(title, sub, GameBarPrefs.metricOn(context, key))
            r.sw.setOnCheckedChangeListener { _, checked ->
                GameBarPrefs.setMetric(context, key, checked)
                refreshPill()
            }
            return r
        }
        val monitorPage = page(
            metricRow("CPU frequency", "Prime core clock · -- if it cannot be read", GameBarPrefs.M_CPULOAD).root,
            metricRow("FPS", "Shows -- when it cannot be read", GameBarPrefs.M_FPS).root,
            metricRow("Temperature", "Battery temperature, from Android", GameBarPrefs.M_BATTEMP).root,
            metricRow("Battery and clock", "Percentage inside the battery icon, then the time", GameBarPrefs.M_BATTERY).root,
            metricRow("CPU temperature", "Extra cell · shows -- if no CPU sensor is found", GameBarPrefs.M_CPUTEMP).root,
            metricRow("GPU temperature", "Extra cell · shows -- if no GPU sensor is found", GameBarPrefs.M_GPUTEMP).root,
        )

        // ---- page: Quick Actions
        val shot = Button(themed).apply {
            text = "Screenshot"
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(NEON_RED)
            setTextColor(WHITE)
        }
        val clear = Button(themed).apply {
            text = "Clear apps"
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(OBSIDIAN_RAISED)
            setTextColor(WHITE)
        }
        val actionRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(shot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val quickPage = page(actionRow)

        // ---- tabs
        val tabs = listOf(
            tab("Tweaks", "Lite · DND"),
            tab("Hardware", "Brightness · Volume"),
            tab("Monitor", "Pill numbers"),
            tab("Actions", "Utilities"),
        )
        val pages = listOf(tweaksPage, hardwarePage, monitorPage, quickPage)
        fun select(index: Int) {
            tabs.forEachIndexed { i, t -> styleTab(t, i == index) }
            pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        }
        tabs.forEachIndexed { i, t -> t.setOnClickListener { select(i) } }
        select(0)

        val tabRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            tabs.forEachIndexed { i, t ->
                addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                })
            }
        }

        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            background = chamfered(0xE6050508.toInt(), 20, 0xFF99001A.toInt())
            addView(header)
            addView(telemetryRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
            addView(tabRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
            pages.forEach { p ->
                addView(p, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) })
            }
        }

        val dm = context.resources.displayMetrics
        val panel = MaxHeightScrollView(themed, dm.heightPixels - dp(24)).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }

        // Tapping anywhere outside the panel closes it
        panel.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                closePanel()
                true
            } else false
        }

        val lp = WindowManager.LayoutParams(
            min(dp(500), dm.widthPixels - dp(24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        // The panel is always docked to the right edge; x is the gap to that edge
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        lp.x = dp(10)

        // The pill gives way to the panel: take its window away before the panel is added so
        // the two are never on screen together. If the panel cannot be added, bring it back.
        removePill()
        if (!addWindow(panel, lp)) {
            showPill()
            return
        }
        panelView = panel

        // Safety net: if the system removes the panel window behind our back, restore the pill.
        // When we close the panel ourselves panelView is already null (or a newer panel), so
        // this callback does nothing.
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (panelView === v) closePanel()
            }
        })

        // Drag the panel by its header
        var dragX = 0f
        var dragY = 0f
        var startX = 0
        var startY = 0
        header.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = ev.rawX
                    dragY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - dragX).toInt()
                    val dy = (ev.rawY - dragY).toInt()
                    // x is the gap to the right edge, so dragging left makes it bigger
                    lp.x = (startX - dx).coerceIn(0, max(0, dm.widthPixels - panel.width))
                    val maxY = max(0, (dm.heightPixels - panel.height) / 2)
                    lp.y = (startY + dy).coerceIn(-maxY, maxY)
                    try {
                        wm.updateViewLayout(panel, lp)
                    } catch (e: Exception) {
                        Log.w(TAG, "move panel failed", e)
                    }
                    true
                }
                else -> true
            }
        }

        // Live profile line + telemetry strip while the panel is open
        profileJob = scope.launch {
            while (isActive) {
                try {
                    profile.text = "Profile: " + EncoreRepository.currentProfile()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "profile read failed", e)
                }
                delay(800)
            }
        }
        telemetryJob = scope.launch {
            while (isActive) {
                safely("fps") { tFps.value.text = sampler.fps(pkg)?.toString() ?: "--" }
                safely("cpu load") { tCpu.value.text = sampler.cpuLoadPercent()?.let { "$it%" } ?: "--" }
                safely("ram") { tRam.value.text = sampler.ramUsedPercent()?.let { "$it%" } ?: "--" }
                safely("temp") { tTemp.value.text = sampler.cpuTemp()?.let { "$it°C" } ?: "--" }
                delay(500)
            }
        }

        // Load this game's flags and the global Disable Tweaks flag, then enable the switches
        scope.launch {
            val entry = EncoreRepository.loadGamelist()?.get(pkg) as? JsonObject
            if (panelView !== panel) return@launch // closed or switched game meanwhile
            if (entry == null) {
                profile.text = "Game is no longer in the Encore list"
            } else {
                lite.sw.isChecked = entry.bool("lite_mode")
                dnd.sw.isChecked = entry.bool("enable_dnd")
                lite.sw.setOnCheckedChangeListener { _, checked -> setFlag(pkg, "lite_mode", checked) }
                dnd.sw.setOnCheckedChangeListener { _, checked -> setFlag(pkg, "enable_dnd", checked) }
                lite.sw.isEnabled = true
                dnd.sw.isEnabled = true
            }
        }

        shot.setOnClickListener { takeScreenshot() }
        clear.setOnClickListener { clearBackground() }
    }

    /**
     * Closes the panel and, unless the caller is about to rebuild everything itself
     * ([restorePill] = false), brings the monitor pill back. The pill is only re-created when
     * the panel was really open, a game is still in front and no pill window exists yet.
     */
    private fun closePanel(restorePill: Boolean = true) {
        val wasOpen = panelView != null
        profileJob?.cancel()
        profileJob = null
        telemetryJob?.cancel()
        telemetryJob = null
        removeWindow(panelView)
        panelView = null
        if (wasOpen && restorePill && currentPkg != null && pillView == null) showPill()
    }

    // ------------------------------------------------------------------ actions

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setFlag(pkg: String, field: String, value: Boolean) {
        scope.launch {
            val result = EncoreRepository.updateGame(pkg) { cur -> cur?.withBool(field, value) }
            if (result == null) toast("Could not update the game list")
        }
    }

    private fun takeScreenshot() {
        scope.launch {
            // Hide our own windows so they are not in the picture
            closePanel()
            pillView?.visibility = View.INVISIBLE
            crosshairView?.visibility = View.INVISIBLE
            delay(400)

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = "/sdcard/Pictures/Screenshots"
            val path = "$dir/EncoreGameBar_$stamp.png"
            val r = RootShell.run(
                "mkdir -p ${RootShell.quote(dir)} && screencap -p ${RootShell.quote(path)} && chmod 666 ${RootShell.quote(path)}"
            )

            pillView?.visibility = View.VISIBLE
            crosshairView?.visibility = View.VISIBLE
            if (r.ok) {
                MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/png"), null)
                toast("Screenshot saved")
            } else {
                toast("Screenshot failed")
            }
        }
    }

    private fun clearBackground() {
        scope.launch {
            // Android's own "kill everything that is safe to kill (cached processes)"
            val r = RootShell.run("am kill-all")
            toast(if (r.ok) "Background apps cleared" else "Could not clear background apps")
        }
    }

    private companion object {
        const val TAG = "GameBarOverlay"
        const val ENSURE_GAP_MS = 3000L

        // Cyberpunk AMOLED Neon Red palette (plain ARGB ints — this file draws with the
        // classic Android view system, not Compose, so it can't reach into ui/Theme.kt).
        const val CARBON = 0xFF0D0D10.toInt()
        const val OBSIDIAN_RAISED = 0xFF1B1B21.toInt()
        const val HAIRLINE = 0xFF2A2A32.toInt()
        const val NEON_RED = 0xFFFF1E38.toInt()
        const val AMBER = 0xFFFFB300.toInt()
        const val CYAN = 0xFF00E5FF.toInt()
        const val LIME = 0xFF39FF6A.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFFB8B8C0.toInt()

        // Monitor pill ("dark glass" HUD): ~40% black so the game shows through, hairline edge,
        // white line icons / text with a faint halo for contrast on bright scenes.
        const val HUD_FILL = 0x66080A0E
        const val HUD_BORDER = 0x40FFFFFF
        const val HUD_ICON_STROKE = 0xE6FFFFFF.toInt()
        const val HUD_CAPTION = 0xD9FFFFFF.toInt()
        const val HUD_TEXT_HALO = 0xA6000000.toInt()
        const val HUD_CAPTION_SP = 8f
        const val HUD_VALUE_SP = 9.5f
    }
}
