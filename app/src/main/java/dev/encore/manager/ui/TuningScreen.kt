package dev.encore.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.encore.manager.GameEntry
import dev.encore.manager.MainViewModel
import dev.encore.manager.TuningUiState
import dev.encore.manager.data.Cluster
import dev.encore.manager.data.ProfileTuning
import dev.encore.manager.data.TuningConf
import kotlin.math.roundToInt

/** The two per-app profiles. Balanced is no longer edited here — it stays global, below. */
private val APP_PROFILE_NAMES = listOf("Performance", "Lite")

private val APP_PROFILE_DEFAULTS = listOf(TuningConf.DEFAULT_FULL, TuningConf.DEFAULT_LITE)

private fun clusterLabel(index: Int, count: Int, c: Cluster): String {
    val name = when {
        count == 3 -> listOf("Little", "Mid", "Prime")[index]
        count == 2 -> listOf("Little", "Prime")[index]
        count == 1 -> "CPU"
        else -> "Cluster $index"
    }
    return "$name (policy${c.policy})"
}

@Composable
fun TuningScreen(vm: MainViewModel) {
    val state by vm.tuning.collectAsState()
    val error by vm.tuningError.collectAsState()
    val s = state

    when {
        s != null -> TuningEditor(vm, s)
        error != null -> Text(
            error ?: "",
            color = EncoreColors.ElectricRed,
            modifier = Modifier.padding(16.dp),
        )
        else -> Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = EncoreColors.NeonRed)
        }
    }
}

@Composable
private fun TuningEditor(vm: MainViewModel, s: TuningUiState) {
    val output by vm.profilerOutput.collectAsState()
    var draft by remember(s) { mutableStateOf(s.tuning) }

    val balanced = draft.balance
    val dirty = draft != s.tuning

    fun updateBalanced(np: ProfileTuning) {
        draft = draft.copy(balance = np)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Tuning")

        NeonPanel(Modifier.fillMaxWidth()) {
            Text(
                "Balanced is the one profile tuned globally here, as a percent of each part's " +
                    "maximum, saved to tuning.conf. Performance and Lite are tuned per app, in the " +
                    "Per-App Profiles section below — each applies the next time its game enters that profile.",
                style = MaterialTheme.typography.bodySmall,
                color = EncoreColors.TextLabel,
                modifier = Modifier.padding(12.dp),
            )
        }

        // ---- Tactical Tuning Deck: collapsible angular HUD panels (Balanced, global) ----

        HudSection(title = "CPU CLUSTERS · BALANCED", subtitle = "${s.clusters.size} cluster(s) detected") {
            s.clusters.forEachIndexed { i, c ->
                val value = balanced.cpuCaps.getOrElse(i) { balanced.cpuCaps.last() }
                CapSlider(
                    label = clusterLabel(i, s.clusters.size, c),
                    value = value,
                    maxKhz = c.maxKhz,
                ) { v ->
                    val caps = balanced.cpuCaps.toMutableList()
                    while (caps.size < s.clusters.size) caps.add(caps.last())
                    caps[i] = v
                    updateBalanced(balanced.copy(cpuCaps = caps))
                }
                if (i < s.clusters.lastIndex) Spacer(Modifier.size(4.dp))
            }
        }

        HudSection(title = "GPU / MEMORY · BALANCED", subtitle = "MediaTek frequency caps") {
            CapSlider(label = "GPU", value = balanced.gpuCap, maxKhz = null) { updateBalanced(balanced.copy(gpuCap = it)) }
            Spacer(Modifier.size(4.dp))
            CapSlider(label = "DDR memory", value = balanced.ddrCap, maxKhz = null) { updateBalanced(balanced.copy(ddrCap = it)) }
        }

        HudSection(title = "PROCESS BOOST", subtitle = null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Boost game process", style = MaterialTheme.typography.bodyLarge, color = EncoreColors.TextPrimary)
                    Text(
                        "Raise priority and protect the running game while in performance mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = EncoreColors.TextLabel,
                    )
                }
                Switch(
                    checked = draft.processBoost,
                    onCheckedChange = { draft = draft.copy(processBoost = it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = EncoreColors.NeonRed,
                        checkedBorderColor = EncoreColors.NeonRed,
                        uncheckedThumbColor = EncoreColors.TextLabel,
                        uncheckedTrackColor = EncoreColors.Obsidian,
                        uncheckedBorderColor = EncoreColors.HairlineDim,
                    ),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val d = TuningConf.DEFAULT_BALANCE
                    updateBalanced(d.copy(cpuCaps = TuningConf.fit(d.cpuCaps, s.clusters.size)))
                },
                modifier = Modifier.weight(1f),
                shape = CutCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EncoreColors.TextPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, EncoreColors.HairlineDim),
            ) { Text("RESET BALANCED") }
            Button(
                onClick = { vm.saveTuning(draft) },
                enabled = dirty,
                modifier = Modifier.weight(1f),
                shape = CutCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EncoreColors.NeonRed,
                    contentColor = Color.Black,
                    disabledContainerColor = EncoreColors.HairlineDim,
                    disabledContentColor = EncoreColors.TextLabel,
                ),
            ) { Text("SAVE", fontWeight = FontWeight.Bold) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.runProfiler("debug") }, modifier = Modifier.weight(1f)) {
                Text("PREVIEW RANGES", color = EncoreColors.Cyan)
            }
            TextButton(onClick = { vm.runProfiler("verify") }, modifier = Modifier.weight(1f)) {
                Text("VERIFY APPLIED", color = EncoreColors.Cyan)
            }
        }
        Text(
            "Preview shows the exact frequency ranges from the saved file. Verify checks whether the last applied profile is still in effect. Both are read-only.",
            style = MaterialTheme.typography.bodySmall,
            color = EncoreColors.TextLabel,
        )

        Spacer(Modifier.size(4.dp))
        PerAppTuningSection(vm, s.clusters)
        Spacer(Modifier.size(8.dp))
    }

    val out = output
    if (out != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissProfilerOutput() },
            containerColor = EncoreColors.Obsidian,
            shape = CutCornerShape(16.dp),
            title = { Text("Encore Profiler", color = EncoreColors.TextPrimary) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(out, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = EncoreColors.Lime)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.dismissProfilerOutput() }) { Text("CLOSE", color = EncoreColors.NeonRed) }
            },
        )
    }
}

/**
 * An angular, chamfered HUD panel with a collapsible body — the "Tactical Tuning Deck" look.
 * Starts expanded; tapping the header toggles it. No fabricated controls live inside: every
 * section maps 1:1 onto a field [TuningRepository]/[TuningConf] actually reads and writes.
 */
@Composable
private fun HudSection(title: String, subtitle: String?, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = { expanded = !expanded }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = EncoreColors.NeonRed,
                        fontWeight = FontWeight.Bold,
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = EncoreColors.TextLabel)
                    }
                }
                Text(
                    if (expanded) "▲" else "▼",
                    color = EncoreColors.TextLabel,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (expanded) {
                Spacer(Modifier.size(10.dp))
                Column(content = { content() })
            }
        }
    }
}

@Composable
private fun CapSlider(label: String, value: Int, maxKhz: Long?, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = EncoreColors.TextPrimary, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "$value%",
                    style = MaterialTheme.typography.titleMedium,
                    color = EncoreColors.NeonRed,
                    fontWeight = FontWeight.Bold,
                )
                if (maxKhz != null) {
                    // Approximation: the profiler snaps to the nearest real frequency step
                    Text(
                        "≈ ${(maxKhz * value / 100 / 1000)} MHz",
                        style = MaterialTheme.typography.bodySmall,
                        color = EncoreColors.TextLabel,
                    )
                }
            }
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(TuningConf.MIN_PCT, TuningConf.MAX_PCT)) },
            valueRange = TuningConf.MIN_PCT.toFloat()..TuningConf.MAX_PCT.toFloat(),
            steps = TuningConf.MAX_PCT - TuningConf.MIN_PCT - 1,
            colors = SliderDefaults.colors(
                thumbColor = EncoreColors.NeonRed,
                activeTrackColor = EncoreColors.NeonRed,
                inactiveTrackColor = EncoreColors.HairlineDim,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/**
 * Per-app Performance/Lite tuning. Balanced stays global (edited above); these two profiles
 * are instead tuned per installed game and saved into that game's gamelist.json entry, under
 * "full_tuning" / "lite_tuning". On save they are also written to a small per-game file that
 * encore_profiler reads, for the game in front, at the moment it applies a profile; the Game
 * Bar is not needed for that (see [EncoreRepository.syncGameTuningFiles]).
 */
@Composable
private fun PerAppTuningSection(vm: MainViewModel, clusters: List<Cluster>) {
    val games by vm.games.collectAsState()
    val selected by vm.selectedTuningGame.collectAsState()
    val override by vm.gameTuning.collectAsState()
    val loading by vm.gameTuningLoading.collectAsState()
    val enabledGames = games.filter { it.enabled }

    HudSection(title = "PER-APP PROFILES", subtitle = "Performance & Lite, tuned per game") {
        if (enabledGames.isEmpty()) {
            Text(
                "No games enabled yet. Turn a game on in the Games tab, then come back here to " +
                    "tune its Performance and Lite values.",
                style = MaterialTheme.typography.bodySmall,
                color = EncoreColors.TextLabel,
            )
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                enabledGames.forEach { g ->
                    NeonFilterChip(
                        selected = selected == g.pkg,
                        label = g.label.ifEmpty { g.pkg },
                    ) { vm.selectTuningGame(if (selected == g.pkg) null else g.pkg) }
                }
            }

            val pkg = selected
            val currentOverride = override
            if (pkg != null) {
                Spacer(Modifier.size(10.dp))
                if (loading || currentOverride == null) {
                    CircularProgressIndicator(
                        color = EncoreColors.NeonRed,
                        modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                } else {
                    AppProfileEditor(vm, pkg, currentOverride, clusters)
                }
            }
        }
    }
}

/** Editor for one game's Performance + Lite override, shown once a game is picked above. */
@Composable
private fun AppProfileEditor(
    vm: MainViewModel,
    pkg: String,
    initial: Pair<ProfileTuning?, ProfileTuning?>,
    clusters: List<Cluster>,
) {
    var which by remember(pkg) { mutableStateOf(0) }
    var fullDraft by remember(pkg, initial) {
        val d = initial.first ?: APP_PROFILE_DEFAULTS[0]
        mutableStateOf(d.copy(cpuCaps = TuningConf.fit(d.cpuCaps, clusters.size)))
    }
    var liteDraft by remember(pkg, initial) {
        val d = initial.second ?: APP_PROFILE_DEFAULTS[1]
        mutableStateOf(d.copy(cpuCaps = TuningConf.fit(d.cpuCaps, clusters.size)))
    }
    val usingDefault = if (which == 0) initial.first == null else initial.second == null
    val p = if (which == 0) fullDraft else liteDraft

    fun update(np: ProfileTuning) {
        if (which == 0) fullDraft = np else liteDraft = np
    }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            APP_PROFILE_NAMES.forEachIndexed { i, name ->
                NeonFilterChip(selected = which == i, label = name) { which = i }
            }
        }
        if (usingDefault) {
            Text(
                "Using the built-in default for this game — not customized yet.",
                style = MaterialTheme.typography.bodySmall,
                color = EncoreColors.TextLabel,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
        }

        Spacer(Modifier.size(8.dp))
        clusters.forEachIndexed { i, c ->
            val value = p.cpuCaps.getOrElse(i) { p.cpuCaps.last() }
            CapSlider(label = clusterLabel(i, clusters.size, c), value = value, maxKhz = c.maxKhz) { v ->
                val caps = p.cpuCaps.toMutableList()
                while (caps.size < clusters.size) caps.add(caps.last())
                caps[i] = v
                update(p.copy(cpuCaps = caps))
            }
            if (i < clusters.lastIndex) Spacer(Modifier.size(4.dp))
        }
        Spacer(Modifier.size(8.dp))
        CapSlider(label = "GPU", value = p.gpuCap, maxKhz = null) { update(p.copy(gpuCap = it)) }
        Spacer(Modifier.size(4.dp))
        CapSlider(label = "DDR memory", value = p.ddrCap, maxKhz = null) { update(p.copy(ddrCap = it)) }

        if (which == 1) {
            Spacer(Modifier.size(10.dp))
            Text(
                "LITE FLOOR",
                style = MaterialTheme.typography.labelMedium,
                color = EncoreColors.NeonRed,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonFilterChip(selected = p.floor == "min", label = "Lowest step") { update(p.copy(floor = "min")) }
                NeonFilterChip(selected = p.floor == "mid", label = "Middle step") { update(p.copy(floor = "mid")) }
            }
        }

        Spacer(Modifier.size(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { vm.resetGameTuning(pkg) },
                modifier = Modifier.weight(1f),
                shape = CutCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = EncoreColors.TextPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, EncoreColors.HairlineDim),
            ) { Text("RESET TO DEFAULT") }
            Button(
                onClick = {
                    // A profile that was never customized and still equals the built-in default
                    // stays "not customized" (null) instead of being pinned to today's numbers.
                    val defFull = APP_PROFILE_DEFAULTS[0].let { it.copy(cpuCaps = TuningConf.fit(it.cpuCaps, clusters.size)) }
                    val defLite = APP_PROFILE_DEFAULTS[1].let { it.copy(cpuCaps = TuningConf.fit(it.cpuCaps, clusters.size)) }
                    vm.saveGameTuning(
                        pkg,
                        if (initial.first == null && fullDraft == defFull) null else fullDraft,
                        if (initial.second == null && liteDraft == defLite) null else liteDraft,
                    )
                },
                modifier = Modifier.weight(1f),
                shape = CutCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EncoreColors.NeonRed,
                    contentColor = Color.Black,
                ),
            ) { Text("SAVE", fontWeight = FontWeight.Bold) }
        }
    }
}
