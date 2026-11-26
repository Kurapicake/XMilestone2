package dev.encore.manager

import dev.encore.manager.data.Cluster
import dev.encore.manager.data.Tuning

sealed interface RootState {
    data object Checking : RootState
    data object Granted : RootState
    data object Denied : RootState
}

sealed interface LogExport {
    data object Idle : LogExport
    data object Running : LogExport
    data class Done(val path: String) : LogExport
    data class Failed(val message: String) : LogExport
}

data class HomeInfo(
    val daemonPid: String? = null,
    val profile: String = "…",
    val moduleVersion: String = "…",
    val chipset: String = "…",
    val kernel: String = "…",
    val moduleInstalled: Boolean = true,
)

data class GameEntry(
    val pkg: String,
    val label: String,
    val enabled: Boolean,
    val liteMode: Boolean,
    val dnd: Boolean,
)

data class GameBarStatus(
    val overlay: Boolean = false,
    val usage: Boolean = false,
    val notifications: Boolean = false,
    /** Settings.System.canWrite — needed by the GameSpace brightness slider. */
    val writeSettings: Boolean = false,
)

data class TuningUiState(
    val clusters: List<Cluster>,
    val tuning: Tuning,
)

/**
 * Read-only hardware snapshot backing the Settings Manager's Home (Device Overview),
 * Battery and Thermals sections, and the GameSpace top bar. Every field is null when the
 * corresponding node/API is unavailable on this device — the UI shows "N/A" rather than a
 * guessed value. See [dev.encore.manager.data.HardwareInfoRepository] for how each is read.
 */
data class HardwareSnapshot(
    /** e.g. "sugov_ext". Read-only everywhere in the app; never written. */
    val cpuGovernor: String? = null,
    val cpuUsagePercent: Int? = null,
    /** Current clock of the highest-max-frequency cluster (the "prime" cluster), in MHz. */
    val cpuClockMhz: Int? = null,
    val cpuTempC: Int? = null,
    val gpuTempC: Int? = null,
    val batteryTempC: Float? = null,
    val batteryPercent: Int? = null,
    val batteryCycleCount: Int? = null,
    /** Full Charge Capacity, in mAh. */
    val batteryFccMah: Int? = null,
    /** Design (factory-rated) capacity, in mAh. */
    val batteryDesignMah: Int? = null,
    val batteryVoltageMv: Int? = null,
    /** Micro-amps; positive while charging, negative while discharging. Null if unavailable. */
    val batteryCurrentUa: Int? = null,
    val batteryCharging: Boolean? = null,
) {
    /** FCC / Design × 100, rounded, only when both readings exist and design > 0. */
    val batteryHealthPercent: Int?
        get() {
            val fcc = batteryFccMah ?: return null
            val design = batteryDesignMah ?: return null
            if (design <= 0) return null
            return (fcc * 100 / design).coerceIn(0, 100)
        }
}

/** Top-level destination: the app opens straight into [GameSpace]; the gear icon opens [SettingsManager]. */
sealed interface AppSection {
    data object GameSpace : AppSection
    data class SettingsManager(val section: SettingsSection = SettingsSection.Home) : AppSection
}

/** The 8 Settings Manager sections, left-nav order matches the approved design. */
enum class SettingsSection(val title: String) {
    Home("Home"),
    Performance("Performance"),
    Tuning("Tuning"),
    GameMode("Game Mode"),
    Battery("Battery"),
    Thermals("Thermals"),
    Advanced("Advanced"),
    About("About"),
}

/** GameSpace's own bottom bar: just Library and Performance (session analytics), per spec. */
enum class GameSpaceTab(val title: String) {
    Library("Library"),
    Performance("Performance"),
}
