package dev.encore.manager.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import dev.encore.manager.gamebar.MetricParsers

/**
 * Read-only hardware metrics for the Settings Manager's Home (Device Overview), Battery and
 * Thermals sections, and the GameSpace top bar.
 *
 * Nothing here ever writes to a file or changes a setting — it only reads. Every value is
 * best-effort: a sysfs node that is missing, unreadable, or outside a plausible range yields
 * null, which the UI shows as "N/A" rather than a guessed number (see Master Prompt §12).
 *
 * `charge_full` / `charge_full_design` / `cycle_count` under `/sys/class/power_supply/*` are
 * standard Linux power-supply-class attributes (not device-specific guesses), but the exact
 * power-supply node name ("battery" vs "bms" vs something else) does vary by OEM/chipset. A
 * short list of the most common names is tried per field; if a field stays "N/A" on your
 * Infinix GT 30 Pro build, run `cat /sys/class/power_supply/*/uevent` as root once to find the
 * real node name and it can be added to the candidate list below.
 */
object HardwareInfoRepository {

    // CPU load is a delta between two /proc/stat samples. This repository keeps its own
    // baseline, deliberately separate from gamebar.MetricsSampler's (that one belongs to a
    // single Game Bar overlay instance and must not be shared with the Settings/GameSpace UI,
    // which polls on a different schedule).
    @Volatile private var prevIdle: Long? = null
    @Volatile private var prevTotal: Long? = null
    @Volatile private var prevAtMs: Long = 0L
    private const val CPU_BASELINE_MAX_AGE_MS = 10_000L

    /** e.g. "sugov_ext". Read-only — never written, by this app or by the profiles it applies. */
    suspend fun cpuGovernor(): String? {
        val r = RootShell.run("cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null")
        val v = r.out.trim()
        return if (r.ok && v.isNotEmpty()) v else null
    }

    /** Busy percentage since the previous call; null on the first call or after a long gap. */
    suspend fun cpuUsagePercent(): Int? {
        val r = RootShell.run("cat /proc/stat")
        if (!r.ok) return null
        val ticks = MetricParsers.procStatCpuTicks(r.out) ?: return null
        val (idle, total) = ticks
        val now = SystemClock.elapsedRealtime()
        val pi = prevIdle
        val pt = prevTotal
        val stale = now - prevAtMs > CPU_BASELINE_MAX_AGE_MS
        prevIdle = idle
        prevTotal = total
        prevAtMs = now
        if (pi == null || pt == null || stale) return null
        return MetricParsers.cpuLoadPercent(pi, pt, idle, total)
    }

    /** Current clock of the prime (highest cpuinfo_max_freq) cluster, in MHz. */
    suspend fun cpuClockMhz(): Int? {
        val r = RootShell.run(
            "( for p in /sys/devices/system/cpu/cpufreq/policy*; do m=; c=; " +
                "read m < \$p/cpuinfo_max_freq; read c < \$p/scaling_cur_freq; " +
                "echo \"\$m \$c\"; done ) 2>/dev/null"
        )
        if (!r.ok) return null
        return MetricParsers.primeCpuMhzFromPolicies(r.out)
    }

    suspend fun cpuTempC(): Int? = zones()?.let { MetricParsers.cpuTempFromZones(it) }

    suspend fun gpuTempC(): Int? = zones()?.let { MetricParsers.gpuTempFromZones(it) }

    private suspend fun zones(): String? {
        val r = RootShell.run(
            "( for z in /sys/class/thermal/thermal_zone*; do t=; v=; " +
                "read t < \$z/type; read v < \$z/temp; echo \"\$t \$v\"; done ) 2>/dev/null"
        )
        return if (r.ok) r.out else null
    }

    /** Percent, °C and charging status, all from the sticky battery broadcast — no root needed. */
    fun batteryFromIntent(context: Context): BatteryIntentReading {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatteryIntentReading(null, null, null)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val c = if (temp != Int.MIN_VALUE) temp / 10f else null
        val charging = if (status >= 0) {
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } else null
        return BatteryIntentReading(pct, c, charging)
    }

    data class BatteryIntentReading(val percent: Int?, val tempC: Float?, val charging: Boolean?)

    suspend fun batteryCycleCount(): Int? = firstPlausibleLong(
        listOf("/sys/class/power_supply/battery/cycle_count", "/sys/class/power_supply/bms/cycle_count"),
        0L..5000L,
    )?.toInt()

    /** micro-amp-hours; convert to mAh (÷1000) before showing. */
    suspend fun batteryFccMicroAh(): Long? = firstPlausibleLong(
        listOf("/sys/class/power_supply/battery/charge_full", "/sys/class/power_supply/bms/charge_full"),
        100_000L..20_000_000L,
    )

    suspend fun batteryDesignMicroAh(): Long? = firstPlausibleLong(
        listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
        ),
        100_000L..20_000_000L,
    )

    /** millivolts. */
    suspend fun batteryVoltageMv(): Int? {
        val uv = firstPlausibleLong(listOf("/sys/class/power_supply/battery/voltage_now"), 1_000_000L..6_000_000L)
            ?: return null
        return (uv / 1000L).toInt()
    }

    /** micro-amps; positive while charging, negative while discharging on most kernels. */
    suspend fun batteryCurrentUa(): Int? {
        val r = RootShell.run("cat /sys/class/power_supply/battery/current_now 2>/dev/null")
        if (!r.ok) return null
        return r.out.trim().toLongOrNull()?.let { v ->
            if (v == 0L || (v > -10_000_000L && v < 10_000_000L)) v.toInt() else null
        }
    }

    private suspend fun firstPlausibleLong(paths: List<String>, range: LongRange): Long? {
        for (p in paths) {
            val r = RootShell.run("cat ${RootShell.quote(p)} 2>/dev/null")
            val v = r.out.trim().toLongOrNull()
            if (r.ok && v != null && v in range) return v
        }
        return null
    }
}
