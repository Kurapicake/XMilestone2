package dev.encore.manager.data

/** One CPU frequency policy (cluster). Order is the shell glob order, same as encore_profiler. */
data class Cluster(val policy: Int, val maxKhz: Long)

/**
 * Values for one profile. [floor] is "min" or "mid"; only Lite has a configurable floor
 * (LITE_FLOOR), the other profiles ignore it.
 */
data class ProfileTuning(
    val cpuCaps: List<Int>,
    val gpuCap: Int,
    val ddrCap: Int,
    val floor: String,
)

data class Tuning(
    val full: ProfileTuning,
    val lite: ProfileTuning,
    val balance: ProfileTuning,
    val processBoost: Boolean,
) {
    fun profile(index: Int): ProfileTuning = when (index) {
        0 -> full
        1 -> lite
        else -> balance
    }

    fun withProfile(index: Int, p: ProfileTuning): Tuning = when (index) {
        0 -> copy(full = p)
        1 -> copy(lite = p)
        else -> copy(balance = p)
    }
}

/**
 * Reads and edits /data/adb/.config/encore/tuning.conf.
 *
 * That file is *sourced by a shell script* (encore_profiler), so it is treated as code:
 * - only the variables managed here are touched, every other line/comment is left as is
 * - values are validated like the profiler does (integers 30..100) and only ever generated
 *   from numbers / fixed words, so nothing user-controlled reaches the shell
 * - commented-out assignments are ignored; the last active assignment wins (as in the shell)
 */
object TuningConf {

    val DEFAULT_FULL = ProfileTuning(listOf(100, 100, 100), 100, 100, "mid")
    val DEFAULT_LITE = ProfileTuning(listOf(90, 80, 80), 100, 100, "mid")
    val DEFAULT_BALANCE = ProfileTuning(listOf(80, 70, 70), 90, 90, "min")
    val DEFAULTS = Tuning(DEFAULT_FULL, DEFAULT_LITE, DEFAULT_BALANCE, processBoost = true)

    const val MIN_PCT = 30
    const val MAX_PCT = 100

    // ------------------------------------------------------------------ low-level text handling

    /** Cuts a trailing shell comment (a '#' at word start, outside quotes). */
    internal fun stripComment(line: String): String {
        var quote = '\u0000'
        for (i in line.indices) {
            val c = line[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '\'' || c == '"') {
                quote = c
            } else if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) {
                return line.substring(0, i)
            }
        }
        return line
    }

    private fun tokenRegex(name: String) =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "=(\"[^\"]*\"|'[^']*'|[^\\s;]*)")

    private fun unquote(v: String): String =
        if (v.length >= 2 && (v.first() == '"' || v.first() == '\'') && v.last() == v.first()) {
            v.substring(1, v.length - 1)
        } else v

    /** Value of the last active `NAME=value` assignment, or null if there is none. */
    internal fun lastValue(text: String, name: String): String? {
        val re = tokenRegex(name)
        var found: String? = null
        for (line in text.lines()) {
            for (m in re.findAll(stripComment(line))) found = unquote(m.groupValues[1])
        }
        return found
    }

    /** Replaces the last active assignment of [name], or appends one at the end. */
    internal fun setVar(text: String, name: String, value: String): String {
        val re = tokenRegex(name)
        val lines = text.split("\n")
        for (idx in lines.indices.reversed()) {
            val line = lines[idx]
            val code = stripComment(line)
            val last = re.findAll(code).lastOrNull() ?: continue
            val newLine = code.substring(0, last.range.first) +
                name + "=" + value +
                code.substring(last.range.last + 1) +
                line.substring(code.length)
            val out = lines.toMutableList()
            out[idx] = newLine
            return out.joinToString("\n")
        }
        val base = if (text.isEmpty() || text.endsWith("\n")) text else text + "\n"
        return base + name + "=" + value + "\n"
    }

    // ------------------------------------------------------------------ validation (mirrors encore_profiler)

    private fun pct(v: String?): Int? {
        if (v == null || v.isEmpty() || !v.all { it in '0'..'9' }) return null
        val n = v.toIntOrNull() ?: return null
        return if (n in MIN_PCT..MAX_PCT) n else null
    }

    private fun capsList(v: String?): List<Int>? {
        if (v == null || v.isBlank()) return null
        val parts = v.trim().split(Regex("\\s+"))
        val out = ArrayList<Int>()
        for (p in parts) out.add(pct(p) ?: return null)
        return out
    }

    /** One percent per cluster; if there are fewer values than clusters the last one repeats. */
    fun fit(list: List<Int>, clusterCount: Int): List<Int> =
        List(clusterCount) { i -> list[minOf(i, list.lastIndex)] }

    // ------------------------------------------------------------------ parse / render

    private fun readProfile(text: String, prefix: String, def: ProfileTuning, n: Int, floorVar: String?): ProfileTuning {
        val caps = capsList(lastValue(text, prefix + "_CPU_CAPS")) ?: def.cpuCaps
        val gpu = pct(lastValue(text, prefix + "_GPU_CAP_PCT")) ?: def.gpuCap
        val ddr = pct(lastValue(text, prefix + "_DDR_CAP_PCT")) ?: def.ddrCap
        val floor = if (floorVar == null) def.floor else when (lastValue(text, floorVar)) {
            "min" -> "min"
            "mid" -> "mid"
            else -> def.floor
        }
        return ProfileTuning(fit(caps, n), gpu, ddr, floor)
    }

    fun parse(text: String, clusterCount: Int): Tuning {
        val n = clusterCount.coerceAtLeast(1)
        val boost = lastValue(text, "PROCESS_BOOST")
        return Tuning(
            full = readProfile(text, "FULL", DEFAULT_FULL, n, null),
            lite = readProfile(text, "LITE", DEFAULT_LITE, n, "LITE_FLOOR"),
            balance = readProfile(text, "BALANCE", DEFAULT_BALANCE, n, null),
            processBoost = boost == null || boost == "1",
        )
    }

    /** Throws IllegalArgumentException for values the profiler would reject. */
    private fun validate(t: Tuning) {
        for (p in listOf(t.full, t.lite, t.balance)) {
            require(p.cpuCaps.isNotEmpty() && p.cpuCaps.all { it in MIN_PCT..MAX_PCT }) { "CPU cap out of range" }
            require(p.gpuCap in MIN_PCT..MAX_PCT) { "GPU cap out of range" }
            require(p.ddrCap in MIN_PCT..MAX_PCT) { "DDR cap out of range" }
            require(p.floor == "min" || p.floor == "mid") { "bad floor" }
        }
    }

    /**
     * Returns [oldText] with the values of [new] applied. Only variables whose effective value
     * changes are written, so an untouched profile never gets pinned to explicit numbers.
     */
    fun render(oldText: String, new: Tuning, clusterCount: Int): String {
        validate(new)
        val n = clusterCount.coerceAtLeast(1)
        val old = parse(oldText, n)
        var text = oldText

        fun apply(prefix: String, o: ProfileTuning, p: ProfileTuning, floorVar: String?) {
            val oc = fit(o.cpuCaps, n)
            val nc = fit(p.cpuCaps, n)
            if (oc != nc) text = setVar(text, prefix + "_CPU_CAPS", "\"" + nc.joinToString(" ") + "\"")
            if (o.gpuCap != p.gpuCap) text = setVar(text, prefix + "_GPU_CAP_PCT", p.gpuCap.toString())
            if (o.ddrCap != p.ddrCap) text = setVar(text, prefix + "_DDR_CAP_PCT", p.ddrCap.toString())
            if (floorVar != null && o.floor != p.floor) text = setVar(text, floorVar, p.floor)
        }
        apply("FULL", old.full, new.full, null)
        apply("LITE", old.lite, new.lite, "LITE_FLOOR")
        apply("BALANCE", old.balance, new.balance, null)
        if (old.processBoost != new.processBoost) {
            text = setVar(text, "PROCESS_BOOST", if (new.processBoost) "1" else "0")
        }
        return text
    }
}
