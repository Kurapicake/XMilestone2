package dev.encore.manager.gamebar

/**
 * Pure text parsing for the monitor pill. No Android classes here so it can be unit tested.
 * Everything returns null when the input is not recognised; the pill then shows "--"
 * instead of a guess.
 */
object MetricParsers {

    private const val SF_PENDING = Long.MAX_VALUE

    /**
     * Layer names from the output of `dumpsys SurfaceFlinger --list` that belong to [pkg],
     * best candidate first. Games draw into a SurfaceView layer, so those come first.
     *
     * Two output shapes are understood:
     *  - Android 15 style: `RequestedLayerState{856a341 SurfaceView[pkg/Act]#12 parentId=5 z=1}`
     *  - older style: the bare layer name on its own line.
     * The returned name is exactly what is printed (the leading hash and the `#id` are part of it).
     */
    fun layerCandidates(list: String, pkg: String): List<String> {
        val names = list.lineSequence()
            .map { layerNameOf(it.trim()) }
            .filter { it.isNotEmpty() && it.contains(pkg) }
            .filterNot { it.startsWith("Background for") || it.startsWith("Bounds for") }
            .distinct()
            .toList()
        val surfaceViews = names.filter { it.contains("SurfaceView[") || it.startsWith("SurfaceView") }
        return surfaceViews + names.filterNot { it in surfaceViews }
    }

    private fun layerNameOf(line: String): String {
        val prefix = "RequestedLayerState{"
        if (!line.startsWith(prefix)) return line
        var inner = line.removePrefix(prefix)
        val cut = inner.indexOf(" parentId=")
        if (cut >= 0) inner = inner.substring(0, cut)
        return inner.removeSuffix("}").trim()
    }

    /** The same name without a trailing `#id`, or null if it has none. */
    fun withoutIdSuffix(name: String): String? {
        val m = Regex("^(.*)#\\d+$").find(name)
        return m?.groupValues?.get(1)
    }

    /**
     * Spellings to try with `dumpsys SurfaceFlinger --latency`, most literal first: as printed,
     * without `#id`, without the leading hash, and without both. Which one Android accepts
     * differs between versions, and a wrong one simply prints no frames.
     */
    fun nameVariants(name: String): List<String> {
        val noHash = Regex("^[0-9a-f]{3,8} (.+)$").find(name)?.groupValues?.get(1)
        val out = mutableListOf(name)
        withoutIdSuffix(name)?.let { out.add(it) }
        if (noHash != null) {
            out.add(noHash)
            withoutIdSuffix(noHash)?.let { out.add(it) }
        }
        return out.distinct()
    }

    /** FPS over the newest second of frames, and the timestamp (ns) of the newest frame. */
    data class LatencyStats(val fps: Int, val newestNs: Long)

    /**
     * Stats from the output of `dumpsys SurfaceFlinger --latency <layer>`. The first line is the
     * refresh period, then one line per recent frame (up to 127) with three timestamps in
     * nanoseconds (desired present, actual present, frame ready). The second column is used.
     * Frames that have not been presented yet carry 0 or Long.MAX_VALUE and are skipped.
     *
     * The history is a ring buffer that simply stops changing when a layer stops drawing (loading
     * screen, paused game, a surface the game replaced). Its numbers then look perfectly normal,
     * so the caller must look at [LatencyStats.newestNs] to tell "still drawing" from "frozen".
     * Returns null when there are fewer than two usable frames; fps is 0 when the newest second
     * holds fewer than two frames.
     */
    fun latencyStats(text: String): LatencyStats? {
        val times = ArrayList<Long>()
        text.lineSequence().drop(1).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                val t = parts[1].toLongOrNull()
                if (t != null && t > 0 && t != SF_PENDING) times.add(t)
            }
        }
        if (times.size < 2) return null
        times.sort()
        val newest = times.last()
        val window = times.filter { newest - it <= 1_000_000_000L }
        if (window.size < 2) return LatencyStats(0, newest)
        val spanSeconds = (window.last() - window.first()) / 1e9
        if (spanSeconds <= 0.0) return LatencyStats(0, newest)
        val fps = (window.size - 1) / spanSeconds
        return LatencyStats(Math.round(fps).toInt().coerceIn(0, 240), newest)
    }

    /** Just the FPS number of [latencyStats]. */
    fun fpsFromLatency(text: String): Int? = latencyStats(text)?.fps

    /**
     * The aggregate `cpu ` line of /proc/stat as (idleTicks, totalTicks). CPU load is a delta
     * between two samples (see [MetricsSampler.cpuLoadPercent]), never a value from one sample.
     */
    fun procStatCpuTicks(text: String): Pair<Long, Long>? {
        val line = text.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
        val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        // user nice system idle iowait irq softirq [steal [guest [guest_nice]]]
        if (fields.size < 4) return null
        val idle = fields[3] + fields.getOrElse(4) { 0L } // idle + iowait
        val total = fields.sum()
        return idle to total
    }

    /** Busy percentage between two /proc/stat samples, or null if the ticks did not advance. */
    fun cpuLoadPercent(prevIdle: Long, prevTotal: Long, idle: Long, total: Long): Int? {
        val dTotal = total - prevTotal
        val dIdle = idle - prevIdle
        if (dTotal <= 0L || dIdle < 0L) return null
        val busy = ((dTotal - dIdle) * 100 / dTotal)
        return busy.toInt().coerceIn(0, 100)
    }

    /**
     * Hottest GPU-looking thermal zone, in degrees Celsius. Same input/shape as
     * [cpuTempFromZones]; returns null when no zone name looks GPU-related.
     */
    fun gpuTempFromZones(text: String): Int? {
        val gpuLike = Regex("(?i)(gpu)")
        var best: Double? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val cut = line.lastIndexOf(' ')
            if (cut > 0) {
                val type = line.substring(0, cut).trim()
                val value = line.substring(cut + 1).toDoubleOrNull()
                if (value != null && gpuLike.containsMatchIn(type)) {
                    val c = if (value >= 1000.0) value / 1000.0 else value
                    if (c in 10.0..120.0 && (best == null || c > best!!)) best = c
                }
            }
        }
        return best?.let { Math.round(it).toInt() }
    }

    /**
     * RAM used, as a percentage, from the contents of /proc/meminfo ("MemTotal:" and
     * "MemAvailable:" lines, both in kB). Falls back to MemFree when MemAvailable is absent
     * (very old kernels). Returns null if the file could not be parsed.
     */
    fun ramUsedPercentFromMeminfo(text: String): Int? {
        fun field(name: String): Long? {
            val m = Regex("^$name:\\s*(\\d+)\\s*kB", RegexOption.MULTILINE).find(text)
            return m?.groupValues?.get(1)?.toLongOrNull()
        }
        val total = field("MemTotal") ?: return null
        if (total <= 0L) return null
        val available = field("MemAvailable") ?: field("MemFree") ?: return null
        val usedPct = ((total - available) * 100 / total)
        return usedPct.toInt().coerceIn(0, 100)
    }

    /**
     * Hottest CPU-looking thermal zone, in degrees Celsius. Input is one line per zone from
     * /sys/class/thermal: "<type> <temp>". Kernel temps are normally millidegrees.
     */
    fun cpuTempFromZones(text: String): Int? {
        val cpuLike = Regex("(?i)(cpu|soc_max|tcpu)")
        var best: Double? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            val cut = line.lastIndexOf(' ')
            if (cut > 0) {
                val type = line.substring(0, cut).trim()
                val value = line.substring(cut + 1).toDoubleOrNull()
                if (value != null && cpuLike.containsMatchIn(type)) {
                    val c = if (value >= 1000.0) value / 1000.0 else value
                    if (c in 10.0..120.0 && (best == null || c > best!!)) best = c
                }
            }
        }
        return best?.let { Math.round(it).toInt() }
    }

    /**
     * Prime-core clock in MHz from [MetricsSampler.PRIME_CPU_CMD] output: one line per cpufreq
     * policy, `<cpuinfo_max_freq kHz> <scaling_cur_freq kHz>`. The prime core is the policy with
     * the highest max frequency (the last one wins a tie, as the prime cluster is listed last).
     * Lines without a numeric maximum are skipped. Null when the prime clock is unreadable or
     * implausible (outside 100 to 6000 MHz), so the pill shows "--" instead of a guess.
     */
    fun primeCpuMhzFromPolicies(text: String): Int? {
        var bestMax = -1L
        var bestCur: Long? = null
        for (raw in text.lineSequence()) {
            val parts = raw.trim().split(Regex("\\s+"))
            val max = parts[0].toLongOrNull() ?: continue
            if (max <= 0L || max < bestMax) continue
            // The prime policy is chosen by its maximum alone; if its current clock cannot be
            // read the result is null, never the clock of another cluster.
            bestMax = max
            bestCur = if (parts.size == 2) parts[1].toLongOrNull() else null
        }
        val cur = bestCur ?: return null
        val mhz = Math.round(cur / 1000.0).toInt()
        return if (mhz in 100..6000) mhz else null
    }

    /** 3050 -> "3.05GHz", 960 -> "960MHz". */
    fun formatCpuFreq(mhz: Int): String =
        if (mhz >= 1000) String.format(java.util.Locale.US, "%.2fGHz", mhz / 1000.0) else "${mhz}MHz"

}
