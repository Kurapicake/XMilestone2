package dev.encore.manager

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.encore.manager.data.DEFAULT_GAME_ENTRY
import dev.encore.manager.data.EncoreRepository
import dev.encore.manager.data.GameBanners
import dev.encore.manager.data.HardwareInfoRepository
import dev.encore.manager.data.Prefs
import dev.encore.manager.data.ProfileTuning
import dev.encore.manager.data.RootShell
import dev.encore.manager.data.Tuning
import dev.encore.manager.data.TuningRepository
import dev.encore.manager.data.bool
import dev.encore.manager.gamebar.GameBarPrefs
import dev.encore.manager.gamebar.GameBarSetup
import dev.encore.manager.data.withBool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ------------------------------------------------------------ root / navigation

    private val _root = MutableStateFlow<RootState>(RootState.Checking)
    val root: StateFlow<RootState> = _root.asStateFlow()

    private val _section = MutableStateFlow<AppSection>(AppSection.GameSpace)
    val section: StateFlow<AppSection> = _section.asStateFlow()

    /** GameSpace's own bottom bar (Library / Performance) — independent of [section]. */
    private val _gameSpaceTab = MutableStateFlow(GameSpaceTab.Library)
    val gameSpaceTab: StateFlow<GameSpaceTab> = _gameSpaceTab.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        checkRoot()
    }

    fun checkRoot() {
        _root.value = RootState.Checking
        viewModelScope.launch {
            val granted = RootShell.hasRoot()
            _root.value = if (granted) RootState.Granted else RootState.Denied
            if (granted) {
                loadStatic()
                refreshLive()
                loadPrefs()
                loadGames()
                loadHardware()
            }
        }
    }

    /** Switches to GameSpace (the app's home) or opens/switches a Settings Manager section. */
    fun navigate(to: AppSection) {
        _section.value = to
        when (to) {
            AppSection.GameSpace -> loadGames()
            is AppSection.SettingsManager -> when (to.section) {
                SettingsSection.Home -> loadHardware()
                SettingsSection.Performance -> {
                    loadPrefs()
                    loadGames()
                }
                SettingsSection.Tuning -> {
                    loadTuning()
                    loadGames()
                }
                SettingsSection.GameMode -> {
                    loadPrefs()
                    refreshGameBar()
                }
                SettingsSection.Battery -> loadHardware()
                SettingsSection.Thermals -> loadHardware()
                SettingsSection.Advanced -> {
                    loadPrefs()
                    refreshGameBar()
                    loadLogs()
                }
                SettingsSection.About -> Unit
            }
        }
    }

    /** Back arrow / home icon in the Settings Manager header. */
    fun navigateToGameSpace() = navigate(AppSection.GameSpace)

    fun selectGameSpaceTab(t: GameSpaceTab) {
        _gameSpaceTab.value = t
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * Collected only while the activity is STARTED (see MainActivity). Daemon status and the
     * hardware snapshot are both cheap, single-shot root-shell reads, and GameSpace's top bar
     * plus the Device Overview / Battery / Thermals sections all need live numbers, so this
     * keeps refreshing regardless of which screen is currently shown.
     */
    suspend fun pollLoop() {
        while (true) {
            if (_root.value == RootState.Granted) {
                refreshLive()
                loadHardware()
            }
            delay(2000)
        }
    }

    // ------------------------------------------------------------ hardware snapshot

    private val _hardware = MutableStateFlow(HardwareSnapshot())
    val hardware: StateFlow<HardwareSnapshot> = _hardware.asStateFlow()

    fun loadHardware() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val battery = HardwareInfoRepository.batteryFromIntent(ctx)
            val fccMicroAh = HardwareInfoRepository.batteryFccMicroAh()
            val designMicroAh = HardwareInfoRepository.batteryDesignMicroAh()
            _hardware.value = HardwareSnapshot(
                cpuGovernor = HardwareInfoRepository.cpuGovernor(),
                cpuUsagePercent = HardwareInfoRepository.cpuUsagePercent(),
                cpuClockMhz = HardwareInfoRepository.cpuClockMhz(),
                cpuTempC = HardwareInfoRepository.cpuTempC(),
                gpuTempC = HardwareInfoRepository.gpuTempC(),
                batteryTempC = battery.tempC,
                batteryPercent = battery.percent,
                batteryCycleCount = HardwareInfoRepository.batteryCycleCount(),
                batteryFccMah = fccMicroAh?.let { (it / 1000L).toInt() },
                batteryDesignMah = designMicroAh?.let { (it / 1000L).toInt() },
                batteryVoltageMv = HardwareInfoRepository.batteryVoltageMv(),
                batteryCurrentUa = HardwareInfoRepository.batteryCurrentUa(),
                batteryCharging = battery.charging,
            )
        }
    }

    // ------------------------------------------------------------ home

    private val _home = MutableStateFlow(HomeInfo())
    val home: StateFlow<HomeInfo> = _home.asStateFlow()

    private suspend fun loadStatic() {
        val installed = EncoreRepository.isModuleInstalled()
        val info = EncoreRepository.staticInfo()
        _home.update {
            it.copy(
                moduleInstalled = installed,
                moduleVersion = info.moduleVersion,
                chipset = info.chipset,
                kernel = info.kernel,
            )
        }
    }

    private suspend fun refreshLive() {
        val pid = EncoreRepository.daemonPid()
        val profile = EncoreRepository.currentProfile()
        _home.update { it.copy(daemonPid = pid, profile = profile) }
    }

    // ------------------------------------------------------------ games

    private var apps: List<Pair<String, String>> = emptyList() // package to label
    private var gamelist: JsonObject = JsonObject(emptyMap())

    private val _games = MutableStateFlow<List<GameEntry>>(emptyList())
    val games: StateFlow<List<GameEntry>> = _games.asStateFlow()

    private val _gamesLoading = MutableStateFlow(false)
    val gamesLoading: StateFlow<Boolean> = _gamesLoading.asStateFlow()

    private val _gamesError = MutableStateFlow<String?>(null)
    val gamesError: StateFlow<String?> = _gamesError.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(q: String) {
        _query.value = q
        rebuildGames()
    }

    fun loadGames() {
        viewModelScope.launch {
            _gamesLoading.value = true
            _gamesError.value = null
            val gl = EncoreRepository.loadGamelist()
            if (gl == null) {
                _gamesError.value = "gamelist.json is unreadable or malformed. It will not be modified."
            } else {
                gamelist = gl
                apps = withContext(Dispatchers.IO) { queryApps(gl) }
                rebuildGames()
            }
            _gamesLoading.value = false
        }
    }

    /**
     * Same list the WebUI shows (`pm list packages -3`, i.e. non-system apps), plus any
     * system app that is already in gamelist.json so it can still be managed.
     */
    @Suppress("DEPRECATION")
    private fun queryApps(gl: JsonObject): List<Pair<String, String>> {
        val ctx = getApplication<Application>()
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0)
            .filter { it.packageName != ctx.packageName }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || gl.containsKey(it.packageName) }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
    }

    private fun rebuildGames() {
        val q = _query.value.trim().lowercase()
        val list = apps.mapNotNull { (pkg, label) ->
            if (q.isNotEmpty() && !label.lowercase().contains(q) && !pkg.lowercase().contains(q)) {
                return@mapNotNull null
            }
            val entry = gamelist[pkg] as? JsonObject
            GameEntry(
                pkg = pkg,
                label = label,
                enabled = entry != null,
                liteMode = entry?.bool("lite_mode") ?: false,
                dnd = entry?.bool("enable_dnd") ?: false,
            )
        }
        _games.value = list.sortedWith(
            compareByDescending<GameEntry> { it.enabled }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label.ifEmpty { it.pkg } }
        )
    }

    fun setGameEnabled(pkg: String, enabled: Boolean) {
        viewModelScope.launch {
            applyGameResult(
                EncoreRepository.updateGame(pkg) { cur -> if (enabled) (cur ?: DEFAULT_GAME_ENTRY) else null }
            )
        }
    }

    fun setGameLiteMode(pkg: String, value: Boolean) = setGameFlag(pkg, "lite_mode", value)

    fun setGameDnd(pkg: String, value: Boolean) = setGameFlag(pkg, "enable_dnd", value)

    private fun setGameFlag(pkg: String, field: String, value: Boolean) {
        viewModelScope.launch {
            // cur == null (game not enabled) stays null, which is a harmless no-op removal
            applyGameResult(EncoreRepository.updateGame(pkg) { cur -> cur?.withBool(field, value) })
        }
    }

    private suspend fun applyGameResult(result: JsonObject?) {
        if (result == null) {
            _message.value = "Could not update gamelist.json"
            val gl = EncoreRepository.loadGamelist()
            if (gl != null) gamelist = gl
        } else {
            gamelist = result
        }
        rebuildGames()
    }

    // ------------------------------------------------------------ settings

    private val _prefs = MutableStateFlow<Prefs?>(null)
    val prefs: StateFlow<Prefs?> = _prefs.asStateFlow()

    private val _prefsError = MutableStateFlow(false)
    val prefsError: StateFlow<Boolean> = _prefsError.asStateFlow()

    fun loadPrefs() {
        viewModelScope.launch { reloadPrefs() }
    }

    private suspend fun reloadPrefs() {
        val p = EncoreRepository.loadPrefs()
        _prefs.value = p
        _prefsError.value = p == null
    }

    fun setEnforceLite(v: Boolean) = writePref { EncoreRepository.setBoolPref("enforce_lite_mode", v) }

    fun setLogLevel(level: Int) = writePref { EncoreRepository.setLogLevel(level) }

    private fun writePref(block: suspend () -> Boolean) {
        viewModelScope.launch {
            if (!block()) _message.value = "Could not update config.json"
            reloadPrefs()
        }
    }

    private val _logExport = MutableStateFlow<LogExport>(LogExport.Idle)
    val logExport: StateFlow<LogExport> = _logExport.asStateFlow()

    fun exportLogs() {
        viewModelScope.launch {
            _logExport.value = LogExport.Running
            val r = EncoreRepository.saveLogs()
            _logExport.value = if (r.ok) {
                LogExport.Done(r.out.trim())
            } else {
                LogExport.Failed(r.err.ifBlank { r.out }.trim().ifEmpty { "Unknown error" })
            }
        }
    }

    fun dismissExport() {
        _logExport.value = LogExport.Idle
    }

    // ------------------------------------------------------------ tuning (tuning.conf)

    private val _tuning = MutableStateFlow<TuningUiState?>(null)
    val tuning: StateFlow<TuningUiState?> = _tuning.asStateFlow()

    private val _tuningError = MutableStateFlow<String?>(null)
    val tuningError: StateFlow<String?> = _tuningError.asStateFlow()

    private val _profilerOutput = MutableStateFlow<String?>(null)
    val profilerOutput: StateFlow<String?> = _profilerOutput.asStateFlow()

    fun loadTuning() {
        viewModelScope.launch {
            // Rebuild the per-game files for encore_profiler from gamelist.json (best effort)
            runCatching { EncoreRepository.syncGameTuningFiles() }
            val clusters = TuningRepository.clusters()
            if (clusters.isEmpty()) {
                _tuningError.value = "No CPU frequency policies found under /sys/devices/system/cpu/cpufreq"
            } else {
                _tuningError.value = null
                _tuning.value = TuningUiState(clusters, TuningRepository.load(clusters.size))
            }
        }
    }

    fun saveTuning(t: Tuning) {
        viewModelScope.launch {
            val state = _tuning.value ?: return@launch
            val ok = TuningRepository.save(t, state.clusters.size)
            _message.value = if (ok) {
                "Saved to tuning.conf. Applies on the next profile change."
            } else {
                "Could not write tuning.conf"
            }
            _tuning.value = state.copy(tuning = TuningRepository.load(state.clusters.size))
        }
    }

    /** sub is "debug" (preview of frequency ranges) or "verify" (is the last profile still applied). */
    fun runProfiler(sub: String) {
        viewModelScope.launch {
            _profilerOutput.value = "Running…"
            _profilerOutput.value = TuningRepository.profilerOutput(sub)
        }
    }

    fun dismissProfilerOutput() {
        _profilerOutput.value = null
    }

    // ------------------------------------------------------------ per-app tuning (gamelist.json)

    private val _selectedTuningGame = MutableStateFlow<String?>(null)
    val selectedTuningGame: StateFlow<String?> = _selectedTuningGame.asStateFlow()

    /** first = this game's Performance override (null = uses the built-in default), second = Lite. */
    private val _gameTuning = MutableStateFlow<Pair<ProfileTuning?, ProfileTuning?>?>(null)
    val gameTuning: StateFlow<Pair<ProfileTuning?, ProfileTuning?>?> = _gameTuning.asStateFlow()

    private val _gameTuningLoading = MutableStateFlow(false)
    val gameTuningLoading: StateFlow<Boolean> = _gameTuningLoading.asStateFlow()

    fun selectTuningGame(pkg: String?) {
        _selectedTuningGame.value = pkg
        _gameTuning.value = null
        if (pkg == null) return
        viewModelScope.launch {
            _gameTuningLoading.value = true
            val n = (_tuning.value?.clusters?.size ?: TuningRepository.clusters().size).coerceAtLeast(1)
            _gameTuning.value = EncoreRepository.gameTuning(pkg, n)
            _gameTuningLoading.value = false
        }
    }

    fun saveGameTuning(pkg: String, full: ProfileTuning?, lite: ProfileTuning?) {
        viewModelScope.launch {
            val result = EncoreRepository.setGameTuning(pkg, full, lite)
            if (result == null) {
                _message.value = "Could not update gamelist.json"
            } else {
                _message.value = "Saved. Applies the next time this game enters that profile."
                _gameTuning.value = full to lite
            }
        }
    }

    /** Clears this game's override(s) so it falls back to the built-in Performance/Lite defaults. */
    fun resetGameTuning(pkg: String) {
        viewModelScope.launch {
            val result = EncoreRepository.setGameTuning(pkg, null, null)
            if (result == null) {
                _message.value = "Could not update gamelist.json"
            } else {
                _message.value = "Reset to defaults for this game."
                _gameTuning.value = null to null
            }
        }
    }

    // ------------------------------------------------------------ game bar

    private val _gbEnabled = MutableStateFlow(GameBarPrefs.enabled(app))
    val gameBarEnabled: StateFlow<Boolean> = _gbEnabled.asStateFlow()

    private val _gbStatus = MutableStateFlow(GameBarStatus())
    val gameBarStatus: StateFlow<GameBarStatus> = _gbStatus.asStateFlow()

    fun refreshGameBar() {
        _gbStatus.value = GameBarSetup.status(getApplication<Application>())
    }

    fun grantGameBarPermissions() {
        viewModelScope.launch {
            GameBarSetup.grantViaRoot(getApplication<Application>())
            delay(300)
            refreshGameBar()
        }
    }

    fun setGameBarEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            if (enabled) {
                GameBarSetup.grantViaRoot(ctx)
                delay(300)
                val st = GameBarSetup.status(ctx)
                _gbStatus.value = st
                if (!st.overlay || !st.usage) {
                    _message.value = "Overlay and usage access are required for the Game Bar"
                    return@launch
                }
                GameBarPrefs.setEnabled(ctx, true)
                _gbEnabled.value = true
                GameBarSetup.start(ctx)
            } else {
                GameBarPrefs.setEnabled(ctx, false)
                _gbEnabled.value = false
                GameBarSetup.stop(ctx)
            }
        }
    }

    /** Stops and starts the service again (status row in Settings). Only if the Game Bar is on. */
    fun restartGameBar() {
        if (!_gbEnabled.value) return
        val ctx = getApplication<Application>()
        GameBarSetup.stop(ctx)
        viewModelScope.launch {
            delay(300)
            GameBarSetup.ensureRunning(ctx)
        }
    }

    private val _gbPill = MutableStateFlow(GameBarPrefs.pillEnabled(app))
    val monitorPillEnabled: StateFlow<Boolean> = _gbPill.asStateFlow()

    fun setMonitorPillEnabled(enabled: Boolean) {
        val ctx = getApplication<Application>()
        GameBarPrefs.setPillEnabled(ctx, enabled)
        _gbPill.value = enabled
        // Restart so the pill appears or disappears right away
        if (_gbEnabled.value) {
            GameBarSetup.stop(ctx)
            GameBarSetup.start(ctx)
        }
    }

    fun resetGameBarPositions() {
        val ctx = getApplication<Application>()
        GameBarPrefs.resetPositions(ctx)
        _message.value = "Pill and panel positions reset"
        if (_gbEnabled.value) {
            GameBarSetup.stop(ctx)
            GameBarSetup.start(ctx)
        }
    }

    // ------------------------------------------------------------ logs

    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText.asStateFlow()

    fun loadLogs() {
        viewModelScope.launch {
            _logText.value = EncoreRepository.tailLog() ?: "(no log file yet)"
        }
    }

    // ------------------------------------------------------------ GameSpace: selected game

    private val _selectedGame = MutableStateFlow<String?>(null)
    val selectedGame: StateFlow<String?> = _selectedGame.asStateFlow()

    /** Bumped on every banner save/clear so Compose keys reading the (unchanged) file path refresh. */
    private val _bannerVersion = MutableStateFlow(0)
    val bannerVersion: StateFlow<Int> = _bannerVersion.asStateFlow()

    private val _launchError = MutableStateFlow<String?>(null)
    val launchError: StateFlow<String?> = _launchError.asStateFlow()

    /** Selects a game in the GameSpace library sidebar; defaults to the first enabled game once loaded. */
    fun selectGame(pkg: String) {
        _selectedGame.value = pkg
    }

    fun reportLaunchFailure(message: String) {
        _launchError.value = message
    }

    fun dismissLaunchError() {
        _launchError.value = null
    }

    fun setGameBanner(pkg: String, source: Uri) {
        viewModelScope.launch {
            val ok = GameBanners.saveBanner(getApplication(), pkg, source)
            if (ok) {
                _bannerVersion.update { it + 1 }
            } else {
                _message.value = "Could not read that image"
            }
        }
    }

    fun clearGameBanner(pkg: String) {
        GameBanners.clearBanner(getApplication(), pkg)
        _bannerVersion.update { it + 1 }
    }

    /**
     * Installed APK size for the selected game, in bytes, or null if it cannot be read (the
     * app may have no installed-size API available on this Android version, or the package may
     * no longer be installed). This is the installed application's own size, not its full data
     * + cache usage, which Android does not expose without the Usage Access permission's
     * StorageStatsManager query per user-visible storage volume; see GameSpaceScreen for how it
     * is labelled to avoid overclaiming completeness (Master Prompt §6C).
     */
    suspend fun appSizeBytes(pkg: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = getApplication<Application>()
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            val apk = java.io.File(info.sourceDir)
            if (apk.exists()) apk.length() else null
        }.getOrNull()
    }
}
