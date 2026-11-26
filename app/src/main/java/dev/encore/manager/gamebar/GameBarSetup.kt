package dev.encore.manager.gamebar

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.encore.manager.GameBarStatus
import dev.encore.manager.data.RootShell

object GameBarPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("gamebar", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("enabled", value).apply()
    }

    // ---- monitor pill (compact translucent strip: prime-core CPU frequency, FPS, temperature, battery + clock)

    const val M_FPS = "m_fps"
    const val M_BATTERY = "m_battery"     // battery percentage + wall-clock time
    const val M_BATTEMP = "m_battemp"     // the pill's "Temp" cell (battery temperature)
    const val M_CPUTEMP = "m_cputemp"     // optional extra cell
    const val M_GPUTEMP = "m_gputemp"     // optional extra cell
    const val M_CPULOAD = "m_cpuload"     // the pill's "CPU" cell (prime-core frequency; key kept so saved settings survive)

    fun pillEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("pill", true)

    fun setPillEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("pill", value).apply()
    }

    // ---- where the user dragged things (px). Defaults apply until something was moved.

    /** Saved top-left of the monitor pill, or null if it was never moved. */
    fun pillPos(ctx: Context): Pair<Int, Int>? {
        val p = sp(ctx)
        return if (p.contains("pill_x") && p.contains("pill_y")) p.getInt("pill_x", 0) to p.getInt("pill_y", 0) else null
    }

    fun setPillPos(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt("pill_x", x).putInt("pill_y", y).apply()
    }

    fun resetPositions(ctx: Context) {
        // "handle_y" (removed edge handle) and "side" (removed left/right choice, the panel is
        // now always on the right) are legacy keys; nothing reads them, they are just cleared
        sp(ctx).edit().remove("handle_y").remove("side").remove("pill_x").remove("pill_y").apply()
    }

    /**
     * The five numbers of the compact pill are on by default; the two optional extra
     * temperature cells (CPU / GPU) are off until the user turns them on.
     */
    fun metricOn(ctx: Context, key: String): Boolean =
        sp(ctx).getBoolean(key, key != M_CPUTEMP && key != M_GPUTEMP)

    fun setMetric(ctx: Context, key: String, value: Boolean) {
        sp(ctx).edit().putBoolean(key, value).apply()
    }

    // ---- crosshair overlay (independent, non-touchable HUD overlay)

    const val CROSSHAIR_DOT = "dot"
    const val CROSSHAIR_CROSS = "cross"
    const val CROSSHAIR_CIRCLE_DOT = "circle_dot"

    const val CROSSHAIR_RED = "red"
    const val CROSSHAIR_CYAN = "cyan"
    const val CROSSHAIR_LIME = "lime"

    fun crosshairEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("crosshair", false)

    fun setCrosshairEnabled(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean("crosshair", value).apply()
    }

    fun crosshairStyle(ctx: Context): String = sp(ctx).getString("crosshair_style", CROSSHAIR_DOT) ?: CROSSHAIR_DOT

    fun setCrosshairStyle(ctx: Context, style: String) {
        sp(ctx).edit().putString("crosshair_style", style).apply()
    }

    /** Crosshair size in dp, 8..64. */
    fun crosshairSize(ctx: Context): Int = sp(ctx).getInt("crosshair_size", 24).coerceIn(8, 64)

    fun setCrosshairSize(ctx: Context, dp: Int) {
        sp(ctx).edit().putInt("crosshair_size", dp.coerceIn(8, 64)).apply()
    }

    fun crosshairColor(ctx: Context): String = sp(ctx).getString("crosshair_color", CROSSHAIR_RED) ?: CROSSHAIR_RED

    fun setCrosshairColor(ctx: Context, color: String) {
        sp(ctx).edit().putString("crosshair_color", color).apply()
    }

    // ---- GameSpace volume: remembers the pre-mute stream index so unmute restores it

    fun preMuteVolume(ctx: Context): Int? {
        val v = sp(ctx).getInt("pre_mute_volume", -1)
        return if (v >= 0) v else null
    }

    fun setPreMuteVolume(ctx: Context, index: Int?) {
        val e = sp(ctx).edit()
        if (index == null) e.remove("pre_mute_volume") else e.putInt("pre_mute_volume", index)
        e.apply()
    }
}

object GameBarSetup {

    /** Current state of the three things the game bar needs. Needs no root. */
    fun status(ctx: Context): GameBarStatus {
        val overlay = Settings.canDrawOverlays(ctx)

        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName,
        )
        val usage = mode == AppOpsManager.MODE_ALLOWED

        val notifications = Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        val writeSettings = Settings.System.canWrite(ctx)

        return GameBarStatus(overlay = overlay, usage = usage, notifications = notifications, writeSettings = writeSettings)
    }

    /**
     * Grants everything through root, so no trip through system settings is needed:
     * overlay + usage access + modify-system-settings (appops), notifications (runtime
     * permission) and a battery optimization exemption so the system does not stop the
     * service during a game. Each command runs on its own so one failure does not block
     * the others.
     */
    suspend fun grantViaRoot(ctx: Context) {
        val pkg = RootShell.quote(ctx.packageName)
        RootShell.run("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        RootShell.run("appops set $pkg GET_USAGE_STATS allow")
        RootShell.run("appops set $pkg WRITE_SETTINGS allow")
        if (Build.VERSION.SDK_INT >= 33) {
            RootShell.run("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        }
        RootShell.run("dumpsys deviceidle whitelist +${ctx.packageName}")
        // Let the system keep the service alive in the background (HiOS / Android 15 kill it otherwise)
        RootShell.run("appops set $pkg RUN_IN_BACKGROUND allow")
        RootShell.run("appops set $pkg RUN_ANY_IN_BACKGROUND allow")
    }

    /**
     * Brings the service back if the user has the Game Bar on but it is not running (after an app
     * update, a reboot, or the system killing the process). Does nothing if it already runs.
     * A plain startForegroundService can be refused when the app is in the background; then root
     * starts it, which is exempt from that restriction.
     */
    suspend fun ensureRunning(ctx: Context) {
        if (!GameBarPrefs.enabled(ctx) || GameBarService.running) return
        try {
            start(ctx)
        } catch (e: Exception) {
            android.util.Log.w("GameBarSetup", "startForegroundService refused, trying root", e)
            val cls = "${ctx.packageName}/${GameBarService::class.java.name}"
            RootShell.run("am start-foreground-service -n ${RootShell.quote(cls)}")
        }
    }

    fun start(ctx: Context) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, GameBarService::class.java))
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, GameBarService::class.java))
    }
}
