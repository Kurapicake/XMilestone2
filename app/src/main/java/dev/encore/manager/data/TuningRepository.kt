package dev.encore.manager.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Root-side access for the Tuning tab. Pure text logic lives in [TuningConf]. */
object TuningRepository {

    private val mutex = Mutex()

    private fun q(s: String) = RootShell.quote(s)

    /**
     * CPU policies in shell glob order (the same order encore_profiler uses for its
     * "little, mid, prime" list) with their hardware max frequency in kHz.
     */
    suspend fun clusters(): List<Cluster> {
        val r = RootShell.run(
            "for p in /sys/devices/system/cpu/cpufreq/policy*; do " +
                "[ -d \"\$p\" ] && echo \"\${p##*policy} \$(cat \"\$p/cpuinfo_max_freq\")\"; done"
        )
        return r.out.lines().mapNotNull { line ->
            val parts = line.trim().split(" ")
            val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val khz = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            Cluster(id, khz)
        }
    }

    private suspend fun readConf(): String = EncoreRepository.readFile(EncorePaths.TUNING_CONF) ?: ""

    suspend fun load(clusterCount: Int): Tuning = TuningConf.parse(readConf(), clusterCount)

    /**
     * Writes only the changed variables. The first time, the original file is kept as
     * tuning.conf.bak. The new file is written next to it and moved into place so the
     * profiler never sources a half-written file.
     */
    suspend fun save(t: Tuning, clusterCount: Int): Boolean = mutex.withLock {
        val old = readConf()
        val new = try {
            TuningConf.render(old, t, clusterCount)
        } catch (e: IllegalArgumentException) {
            return@withLock false
        }
        if (new == old) return@withLock true

        val f = EncorePaths.TUNING_CONF
        val bak = "$f.bak"
        val tmp = "$f.tmp"
        RootShell.run("[ -f ${q(f)} ] && [ ! -f ${q(bak)} ] && cp ${q(f)} ${q(bak)}")
        RootShell.run("printf '%s' ${q(new)} > ${q(tmp)} && mv -f ${q(tmp)} ${q(f)}").ok
    }

    /**
     * Runs a read-only profiler subcommand: "debug" (shows the frequency ranges each profile
     * would use) or "verify" (checks whether the last applied profile is still in effect).
     * Run with plain `sh`, so it does not depend on the file's exec bit.
     */
    suspend fun profilerOutput(sub: String): String {
        if (sub != "debug" && sub != "verify") return ""
        val r = RootShell.run("sh ${q(EncorePaths.PROFILER)} $sub 2>&1")
        return (r.out + (if (r.err.isNotBlank()) "\n" + r.err else "")).trim().ifEmpty { "(no output)" }
    }
}
