package dev.encore.manager.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Every path below is exactly what the Encore Tweaks 5.2.2 WebUI uses. */
object EncorePaths {
    const val CONFIG_DIR = "/data/adb/.config/encore"
    const val CONFIG_JSON = "$CONFIG_DIR/config.json"
    const val GAMELIST_JSON = "$CONFIG_DIR/gamelist.json"
    const val CURRENT_PROFILE = "$CONFIG_DIR/current_profile"
    const val SOC_RECOGNITION = "$CONFIG_DIR/soc_recognition"
    const val LOG_FILE = "$CONFIG_DIR/encore.log"
    const val MODULE_DIR = "/data/adb/modules/encore"
    const val MODULE_PROP = "$MODULE_DIR/module.prop"
    const val UTILITY = "$MODULE_DIR/system/bin/encore_utility"
    const val PROFILER = "$MODULE_DIR/system/bin/encore_profiler"
    const val TUNING_CONF = "$CONFIG_DIR/tuning.conf"
}

data class Prefs(
    val enforceLiteMode: Boolean,
    val logLevel: Int,
    val useDeviceMitigation: Boolean,
    val disableTweaks: Boolean,
)

data class StaticInfo(
    val moduleVersion: String,
    val chipset: String,
    val kernel: String,
)

/** Default gamelist entry, same shape the WebUI writes. */
val DEFAULT_GAME_ENTRY: JsonObject = JsonObject(
    linkedMapOf<String, JsonElement>(
        "lite_mode" to JsonPrimitive(false),
        "enable_dnd" to JsonPrimitive(false),
    )
)

fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

/** Copy with one boolean replaced; all other (unknown) keys are preserved. */
fun JsonObject.withBool(key: String, value: Boolean): JsonObject {
    val m = LinkedHashMap<String, JsonElement>(this)
    m[key] = JsonPrimitive(value)
    return JsonObject(m)
}

/**
 * All state of Encore Tweaks lives in files under /data/adb/.config/encore, read and written
 * by the daemon. Like the WebUI, this class only edits those files. Every write is a
 * read-modify-write that preserves keys this app does not know about (for example
 * `cpu_governor` in config.json).
 */
object EncoreRepository {

    private val mutex = Mutex()
    private val packageRegex = Regex("^[A-Za-z]\\w*(\\.\\w+)+$")

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  " // same indent as the WebUI's JSON.stringify(x, null, 2)
    }

    private fun q(s: String) = RootShell.quote(s)

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    // ---------------------------------------------------------------- file helpers

    suspend fun readFile(path: String): String? {
        val r = RootShell.run("[ -f ${q(path)} ] && cat ${q(path)}")
        return if (r.ok) r.out else null
    }

    /** In-place truncate-and-write, identical to the WebUI (`echo '...' > file`). */
    private suspend fun writeFile(path: String, content: String): Boolean =
        RootShell.run("printf '%s\\n' ${q(content)} > ${q(path)}").ok

    // ---------------------------------------------------------------- status

    suspend fun isModuleInstalled(): Boolean =
        RootShell.run("[ -d ${q(EncorePaths.MODULE_DIR)} ]").ok

    suspend fun daemonPid(): String? {
        val r = RootShell.run("/system/bin/toybox pidof encored")
        val pid = r.out.trim()
        return if (r.ok && pid.isNotEmpty()) pid else null
    }

    suspend fun currentProfile(): String =
        when (readFile(EncorePaths.CURRENT_PROFILE)?.trim()) {
            "0" -> "Initializing"
            "1" -> "Performance"
            "2" -> "Balanced"
            "3" -> "Powersave"
            else -> "Unknown"
        }

    suspend fun staticInfo(): StaticInfo {
        val version = readFile(EncorePaths.MODULE_PROP)
            ?.let { Regex("^version=(.*)$", RegexOption.MULTILINE).find(it)?.groupValues?.get(1)?.trim() }
            ?: "unknown"
        val soc = when (readFile(EncorePaths.SOC_RECOGNITION)?.trim()) {
            "1" -> "MediaTek"
            "2" -> "Snapdragon"
            "3" -> "Exynos"
            "4" -> "Unisoc"
            "5" -> "Tensor"
            "6" -> "Intel"
            "7" -> "Tegra"
            "8" -> "Kirin"
            else -> ""
        }
        val platform = RootShell.run("getprop ro.board.platform").out.trim()
        val kernel = RootShell.run("uname -r -m").out.trim()
        return StaticInfo(
            moduleVersion = version,
            chipset = "$soc $platform".trim().ifEmpty { "unknown" },
            kernel = kernel.ifEmpty { "unknown" },
        )
    }

    // ---------------------------------------------------------------- config.json

    private suspend fun loadConfigRaw(): JsonObject? =
        readFile(EncorePaths.CONFIG_JSON)?.let { parseObject(it) }

    private fun toPrefs(cfg: JsonObject): Prefs {
        val p = cfg["preferences"] as? JsonObject
        fun bool(key: String) = (p?.get(key) as? JsonPrimitive)?.booleanOrNull ?: false
        val level = (p?.get("log_level") as? JsonPrimitive)?.intOrNull ?: 5
        return Prefs(
            enforceLiteMode = bool("enforce_lite_mode"),
            logLevel = level.coerceIn(0, 5),
            useDeviceMitigation = bool("use_device_mitigation"),
            disableTweaks = bool("disable_tweaks"),
        )
    }

    /** null = config.json missing or not valid JSON (nothing is ever written in that case). */
    suspend fun loadPrefs(): Prefs? = loadConfigRaw()?.let { toPrefs(it) }

    suspend fun setBoolPref(key: String, value: Boolean): Boolean =
        setPrefElement(key, JsonPrimitive(value))

    suspend fun setLogLevel(level: Int): Boolean {
        if (level !in 0..5) return false
        return setPrefElement("log_level", JsonPrimitive(level))
    }

    private suspend fun setPrefElement(key: String, value: JsonPrimitive): Boolean =
        mutex.withLock {
            val cfg = loadConfigRaw() ?: return@withLock false
            val prefs = LinkedHashMap<String, JsonElement>()
            (cfg["preferences"] as? JsonObject)?.let { prefs.putAll(it) }
            // Same defaults the WebUI fills in before saving
            prefs.putIfAbsent("use_device_mitigation", JsonPrimitive(false))
            prefs.putIfAbsent("disable_tweaks", JsonPrimitive(false))
            prefs.putIfAbsent("enforce_lite_mode", JsonPrimitive(false))
            prefs.putIfAbsent("log_level", JsonPrimitive(5))
            prefs[key] = value

            val newCfg = LinkedHashMap<String, JsonElement>(cfg)
            newCfg["preferences"] = JsonObject(prefs)
            writeFile(
                EncorePaths.CONFIG_JSON,
                json.encodeToString(JsonObject.serializer(), JsonObject(newCfg)),
            )
        }

    // ---------------------------------------------------------------- gamelist.json

    /**
     * Missing file -> empty list (same as the WebUI). Unreadable/malformed -> null, and
     * [updateGame] refuses to write in that case so the file is never clobbered.
     */
    suspend fun loadGamelist(): JsonObject? {
        val text = readFile(EncorePaths.GAMELIST_JSON) ?: return JsonObject(emptyMap())
        return parseObject(text)
    }

    /**
     * Package names in gamelist.json for the Game Bar. Unlike [loadGamelist] this tells a failed
     * read apart from an empty list: null means "could not be read, try again", while a missing
     * or empty file gives an empty set. (The shell command succeeds with no output when the file
     * is missing, so only a real failure, such as a busy or timed-out root shell, gives null.)
     */
    suspend fun gameKeysOrNull(): Set<String>? {
        val path = q(EncorePaths.GAMELIST_JSON)
        val r = RootShell.run("if [ -f $path ]; then cat $path; fi")
        if (!r.ok) return null
        if (r.out.isBlank()) return emptySet()
        return parseObject(r.out)?.keys
    }

    /**
     * Applies [transform] to one game's entry. Input is the current entry (null = not in
     * list); returning null removes the entry. Returns the new full list, or null on failure.
     */
    suspend fun updateGame(pkg: String, transform: (JsonObject?) -> JsonObject?): JsonObject? {
        if (!packageRegex.matches(pkg)) return null
        return mutex.withLock {
            val current = loadGamelist() ?: return@withLock null
            val updated = LinkedHashMap<String, JsonElement>(current)
            val newEntry = transform(current[pkg] as? JsonObject)
            if (newEntry == null) updated.remove(pkg) else updated[pkg] = newEntry
            val result = JsonObject(updated)
            val ok = writeFile(
                EncorePaths.GAMELIST_JSON,
                json.encodeToString(JsonObject.serializer(), result),
            )
            // Keep the per-game files for encore_profiler in step (also removes the file of a
            // game that was just switched off). Best effort here; setGameTuning checks it.
            if (ok) syncGameTuningFilesFrom(result)
            if (ok) result else null
        }
    }

    // ---------------------------------------------------------------- per-game tuning overrides

    private const val KEY_FULL_TUNING = "full_tuning"
    private const val KEY_LITE_TUNING = "lite_tuning"

    /** Reads one override object; returns null if absent or malformed (never throws/crashes). */
    private fun JsonObject.toProfileTuningOrNull(hasFloor: Boolean): ProfileTuning? {
        val caps = (this["cpu_caps"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.coerceIn(TuningConf.MIN_PCT, TuningConf.MAX_PCT) }
            ?.takeIf { it.isNotEmpty() } ?: return null
        val gpu = (this["gpu_cap"] as? JsonPrimitive)?.intOrNull
            ?.coerceIn(TuningConf.MIN_PCT, TuningConf.MAX_PCT) ?: return null
        val ddr = (this["ddr_cap"] as? JsonPrimitive)?.intOrNull
            ?.coerceIn(TuningConf.MIN_PCT, TuningConf.MAX_PCT) ?: return null
        val floor = if (!hasFloor) "mid" else when ((this["floor"] as? JsonPrimitive)?.contentOrNull) {
            "min" -> "min"
            else -> "mid"
        }
        return ProfileTuning(caps, gpu, ddr, floor)
    }

    private fun ProfileTuning.toOverrideJson(includeFloor: Boolean): JsonObject = JsonObject(
        linkedMapOf<String, JsonElement>(
            "cpu_caps" to JsonArray(cpuCaps.map { JsonPrimitive(it) }),
            "gpu_cap" to JsonPrimitive(gpuCap),
            "ddr_cap" to JsonPrimitive(ddrCap),
        ).apply { if (includeFloor) put("floor", JsonPrimitive(floor)) }
    )

    /**
     * This game's saved Performance/Lite overrides from gamelist.json, fit to [clusterCount].
     * A null in either slot means "use the built-in default for that profile" (see
     * [TuningConf.DEFAULT_FULL] / [TuningConf.DEFAULT_LITE]). A game not in the list, or an
     * unreadable gamelist.json, returns (null, null) rather than throwing.
     */
    suspend fun gameTuning(pkg: String, clusterCount: Int): Pair<ProfileTuning?, ProfileTuning?> {
        val entry = loadGamelist()?.get(pkg) as? JsonObject ?: return null to null
        val full = (entry[KEY_FULL_TUNING] as? JsonObject)?.toProfileTuningOrNull(hasFloor = false)
            ?.let { it.copy(cpuCaps = TuningConf.fit(it.cpuCaps, clusterCount)) }
        val lite = (entry[KEY_LITE_TUNING] as? JsonObject)?.toProfileTuningOrNull(hasFloor = true)
            ?.let { it.copy(cpuCaps = TuningConf.fit(it.cpuCaps, clusterCount)) }
        return full to lite
    }

    /**
     * Saves (or, when null, clears) this game's Performance/Lite overrides. Only touches
     * [KEY_FULL_TUNING] / [KEY_LITE_TUNING]; every other field of the entry (lite_mode,
     * enable_dnd, and any key this app does not know about) is preserved untouched. If the
     * game is not currently enabled (entry missing), this is a no-op — per-app tuning can
     * only be set for a game already in gamelist.json.
     */
    suspend fun setGameTuning(pkg: String, full: ProfileTuning?, lite: ProfileTuning?): JsonObject? {
        val result = updateGame(pkg) { cur ->
            cur?.let { base ->
                val m = LinkedHashMap<String, JsonElement>(base)
                if (full == null) m.remove(KEY_FULL_TUNING) else m[KEY_FULL_TUNING] = full.toOverrideJson(includeFloor = false)
                if (lite == null) m.remove(KEY_LITE_TUNING) else m[KEY_LITE_TUNING] = lite.toOverrideJson(includeFloor = true)
                JsonObject(m)
            }
        } ?: return null
        // The values only reach the game through the per-game file, so a failed file write
        // counts as a failed save (the user sees the error and can simply tap Save again).
        return if (mutex.withLock { syncGameTuningFilesFrom(result) }) result else null
    }

    // ---------------------------------------------------------------- per-game files for encore_profiler

    /**
     * encore_profiler cannot parse JSON, and it must pick the numbers at the very moment it
     * applies a profile, so every customized game also gets a tiny shell-style file,
     * [GAME_TUNING_DIR]/<package>.conf, holding only FULL_* / LITE_* assignments (numbers and
     * the fixed words min/mid). gamelist.json stays the single source of truth: these files are
     * always rebuilt from it and files without a matching customized entry are deleted.
     */
    internal const val GAME_TUNING_DIR = "${EncorePaths.CONFIG_DIR}/game_tuning"

    private fun Int.pct() = coerceIn(TuningConf.MIN_PCT, TuningConf.MAX_PCT)

    /** Text of one game's file, or null when the game has no override at all. */
    internal fun gameTuningConf(full: ProfileTuning?, lite: ProfileTuning?): String? {
        if (full == null && lite == null) return null
        val sb = StringBuilder("# Written by Encore Manager from gamelist.json. Do not edit.\n")
        fun add(prefix: String, t: ProfileTuning) {
            if (t.cpuCaps.isNotEmpty()) {
                sb.append(prefix).append("_CPU_CAPS=\"").append(t.cpuCaps.joinToString(" ") { it.pct().toString() }).append("\"\n")
            }
            sb.append(prefix).append("_GPU_CAP_PCT=").append(t.gpuCap.pct()).append('\n')
            sb.append(prefix).append("_DDR_CAP_PCT=").append(t.ddrCap.pct()).append('\n')
        }
        if (full != null) add("FULL", full)
        if (lite != null) {
            add("LITE", lite)
            sb.append("LITE_FLOOR=").append(if (lite.floor == "min") "min" else "mid").append('\n')
        }
        return sb.toString()
    }

    /**
     * One root command that (1) writes every wanted file via a temporary name and mv, so
     * the profiler never sees a half-written file, (2) deletes every other file in the folder,
     * and (3) once only, removes the FULL_* / LITE_* lines that older versions of this app
     * copied into tuning.conf; they would otherwise act as stray global values now that
     * per-game values no longer go through that file. Exit status is 0 only if the writes worked.
     */
    internal fun gameTuningSyncCommand(
        dir: String,
        tuningConf: String,
        wanted: Map<String, String>,
    ): String {
        val d = q(dir)
        val marker = q("$dir/.migrated")
        val conf = q(tuningConf)
        val writes = wanted.entries.joinToString("") { (pkg, text) ->
            val f = q("$dir/$pkg.conf")
            val t = q("$dir/$pkg.conf.tmp")
            " && printf '%s' ${q(text)} > $t && mv -f $t $f"
        }
        val keep = wanted.keys.joinToString(" | ") { q("$it.conf") }
        val caseBody = if (wanted.isEmpty()) "*) rm -f \"\$f\" ;;" else "$keep) ;; *) rm -f \"\$f\" ;;"
        val migrate = "if [ ! -f $marker ]; then " +
            "if [ ! -f $conf ] || sed -i -E '/^(FULL|LITE)_(CPU_CAPS|GPU_CAP_PCT|DDR_CAP_PCT)=/d;/^LITE_FLOOR=/d' $conf; " +
            "then : > $marker; fi; fi"
        return "( mkdir -p $d$writes; rc=\$?; $migrate; " +
            "for f in $d/*; do case \"\${f##*/}\" in $caseBody esac; done; [ \"\$rc\" -eq 0 ] ) 2>/dev/null"
    }

    /** Rebuilds the per-game files from [list] (the current gamelist.json content). */
    private suspend fun syncGameTuningFilesFrom(list: JsonObject): Boolean {
        val wanted = LinkedHashMap<String, String>()
        for ((pkg, element) in list) {
            if (!packageRegex.matches(pkg)) continue
            val entry = element as? JsonObject ?: continue
            val full = (entry[KEY_FULL_TUNING] as? JsonObject)?.toProfileTuningOrNull(hasFloor = false)
            val lite = (entry[KEY_LITE_TUNING] as? JsonObject)?.toProfileTuningOrNull(hasFloor = true)
            val text = gameTuningConf(full, lite) ?: continue
            wanted[pkg] = text
        }
        return RootShell.run(gameTuningSyncCommand(GAME_TUNING_DIR, EncorePaths.TUNING_CONF, wanted)).ok
    }

    /**
     * Rebuilds the per-game files from the current gamelist.json. Returns false when the list
     * cannot be read (nothing is deleted then) or the files could not be written.
     */
    suspend fun syncGameTuningFiles(): Boolean = mutex.withLock {
        val list = loadGamelist() ?: return@withLock false
        syncGameTuningFilesFrom(list)
    }

    // ---------------------------------------------------------------- logs / system

    suspend fun tailLog(lines: Int = 300): String? {
        val r = RootShell.run("[ -f ${q(EncorePaths.LOG_FILE)} ] && tail -n $lines ${q(EncorePaths.LOG_FILE)}")
        return if (r.ok) r.out else null
    }

    /** Runs the module's own exporter; stdout is the path of the saved tar.gz. */
    suspend fun saveLogs(): RootShell.Result = RootShell.run("${q(EncorePaths.UTILITY)} save_logs")
}
