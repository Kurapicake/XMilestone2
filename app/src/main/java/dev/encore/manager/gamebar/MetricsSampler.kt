package dev.encore.manager.gamebar

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import dev.encore.manager.data.RootShell

/**
 * Reads the numbers shown in the monitor pill.
 * Battery needs no root. CPU temperature and FPS read system files / dumpsys through root
 * and return null when the phone does not give a recognisable answer.
 */
class MetricsSampler(private val context: Context) {

    data class Battery(val percent: Int?, val tempC: Float?)

    private var layerPkg: String? = null

    /** The SurfaceFlinger layer that was drawing frames last time, so most polls need one dumpsys. */
    private var layer: String? = null

    /** Newest frame timestamp seen per layer: if it does not move between two polls, nothing is drawing. */
    private val lastNewest = HashMap<String, Long>()

    fun battery(): Battery {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Battery(null, null)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val c = if (temp != Int.MIN_VALUE) temp / 10f else null
        return Battery(pct, c)
    }

    /**
     * Current clock of the prime core in MHz (the cpufreq policy with the highest maximum), or
     * null when it cannot be read; the pill then shows "--".
     */
    suspend fun primeCpuMhz(): Int? {
        val r = RootShell.run(PRIME_CPU_CMD)
        if (!r.ok) return null
        return MetricParsers.primeCpuMhzFromPolicies(r.out)
    }

    private var zonesText: String? = null
    private var zonesAt = 0L

    /** The thermal zone dump, shared by the CPU and GPU cells so one tick runs it once. */
    private suspend fun zones(): String? {
        val now = SystemClock.elapsedRealtime()
        zonesText?.let { if (now - zonesAt < ZONES_CACHE_MS) return it }
        val r = RootShell.run(CPU_ZONES_CMD)
        if (!r.ok) return null
        zonesText = r.out
        zonesAt = now
        return r.out
    }

    suspend fun cpuTemp(): Int? = zones()?.let { MetricParsers.cpuTempFromZones(it) }

    /** RAM used as a percentage, read from /proc/meminfo. */
    suspend fun ramUsedPercent(): Int? {
        val r = RootShell.run("cat /proc/meminfo")
        return if (r.ok) MetricParsers.ramUsedPercentFromMeminfo(r.out) else null
    }

    /** Hottest GPU-looking thermal zone, in degrees Celsius, or null if none is found. */
    suspend fun gpuTemp(): Int? = zones()?.let { MetricParsers.gpuTempFromZones(it) }

    private var prevIdle: Long? = null
    private var prevTotal: Long? = null
    private var prevAtMs: Long = 0L

    /**
     * CPU load as a percentage, from the delta between this call and the previous one. Returns
     * null on the first call (there is nothing to diff against yet), if /proc/stat could not be
     * read, or if the previous reading is older than [CPU_BASELINE_MAX_AGE_MS] (for example from
     * an earlier game session): a delta over such a long gap would be an average, not the load
     * now, so that reading only re-establishes the baseline. The pill and the open panel share
     * this one baseline, but never run at the same time (the pill is hidden while the panel is open).
     */
    suspend fun cpuLoadPercent(): Int? {
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

    /**
     * Frames per second of the game, or null if no layer of the app can be read.
     *
     * Reading the frame history alone is not enough: when a layer stops drawing its history stops
     * changing and would keep reporting the same FPS forever. So a layer only counts as live if
     * its newest frame moved since the last poll or is only a moment old. If the remembered layer
     * is not live (idle, or replaced by a new surface), every layer of the app is looked at and the
     * liveliest one wins. A readable layer that is not drawing reports 0.
     */
    suspend fun fps(pkg: String): Int? {
        if (layerPkg != pkg) {
            layerPkg = pkg
            layer = null
            lastNewest.clear()
        }
        if (lastNewest.size > 32) lastNewest.clear()

        layer?.let { known ->
            val stats = latencyStats(known)
            if (stats != null && isLive(known, stats)) {
                report(known, stats)
                return stats.fps
            }
        }
        return searchLayers(pkg)
    }

    private suspend fun searchLayers(pkg: String): Int? {
        val list = RootShell.run("dumpsys SurfaceFlinger --list")
        if (!list.ok) return null
        var best: Pair<String, MetricParsers.LatencyStats>? = null
        var idle: Pair<String, MetricParsers.LatencyStats>? = null
        for (candidate in MetricParsers.layerCandidates(list.out, pkg).take(MAX_LAYERS)) {
            val (name, stats) = readLayer(candidate) ?: continue
            if (isLive(name, stats)) {
                if (best == null || stats.newestNs > best.second.newestNs) best = name to stats
            } else if (idle == null) {
                idle = name to stats
            }
        }
        best?.let { (name, stats) ->
            layer = name
            report(name, stats)
            return stats.fps
        }
        idle?.let { (name, stats) ->
            layer = name
            report(name, stats)
            return 0
        }
        layer = null
        GameBarDiag.update { it.copy(fpsInfo = "no readable layer for $pkg") }
        return null
    }

    /** First spelling of the layer name that `--latency` accepts, with its stats. */
    private suspend fun readLayer(candidate: String): Pair<String, MetricParsers.LatencyStats>? {
        for (name in MetricParsers.nameVariants(candidate)) {
            val stats = latencyStats(name)
            if (stats != null) return name to stats
        }
        return null
    }

    private suspend fun latencyStats(name: String): MetricParsers.LatencyStats? {
        val r = RootShell.run("dumpsys SurfaceFlinger --latency ${RootShell.quote(name)}")
        return if (r.ok) MetricParsers.latencyStats(r.out) else null
    }

    private fun isLive(name: String, stats: MetricParsers.LatencyStats): Boolean {
        val previous = lastNewest.put(name, stats.newestNs)
        if (previous != null && previous != stats.newestNs) return true
        // SurfaceFlinger stamps frames with CLOCK_MONOTONIC, the same clock as System.nanoTime().
        // If that ever stops being true the age is nonsense and the "moved since last poll" rule
        // above still decides, one poll later.
        val ageNs = System.nanoTime() - stats.newestNs
        return ageNs in 0..LIVE_AGE_NS
    }

    private fun report(name: String, stats: MetricParsers.LatencyStats) {
        val ageMs = (System.nanoTime() - stats.newestNs) / 1_000_000
        GameBarDiag.update { it.copy(fpsInfo = "${stats.fps} fps · newest frame ${ageMs} ms old · $name") }
    }

    private companion object {
        const val CPU_BASELINE_MAX_AGE_MS = 10_000L
        const val ZONES_CACHE_MS = 1_000L

        /** A layer whose newest frame is younger than this is drawing right now. */
        const val LIVE_AGE_NS = 1_500_000_000L

        /** How many of the app's layers are examined when looking for the one that draws. */
        const val MAX_LAYERS = 3

        // One line per cpufreq policy: "<cpuinfo_max_freq> <scaling_cur_freq>" in kHz. Only shell
        // builtins (read / echo) in a subshell: no extra processes per tick and no variable left
        // behind in the shared root shell. A file that cannot be read gives an empty field, which
        // MetricParsers skips.
        const val PRIME_CPU_CMD =
            "( for p in /sys/devices/system/cpu/cpufreq/policy*; do m=; c=; " +
                "read m < \$p/cpuinfo_max_freq; read c < \$p/scaling_cur_freq; " +
                "echo \"\$m \$c\"; done ) 2>/dev/null"

        // One "<type> <temp>" line per thermal zone, again with builtins only (the old version
        // started two `cat` processes per zone).
        const val CPU_ZONES_CMD =
            "( for z in /sys/class/thermal/thermal_zone*; do t=; v=; " +
                "read t < \$z/type; read v < \$z/temp; echo \"\$t \$v\"; done ) 2>/dev/null"
    }
}
