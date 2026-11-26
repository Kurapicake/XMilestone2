package dev.encore.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.encore.manager.AppSection
import dev.encore.manager.MainViewModel
import dev.encore.manager.SettingsSection

/**
 * App root once root access is granted: GameSpace is the default homepage (Master Prompt §3/§6);
 * the gear icon on GameSpace's top bar opens the internal Settings Manager. Both live inside
 * this one Activity/Composable tree — there is no second launcher, package or APK.
 */
@Composable
fun XArenaRoot(vm: MainViewModel) {
    val section by vm.section.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        containerColor = EncoreColors.TrueBlack,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(EncoreColors.TrueBlack)) {
            when (val s = section) {
                AppSection.GameSpace -> GameSpaceScreen(vm)
                is AppSection.SettingsManager -> SettingsManagerScaffold(vm, s.section)
            }
        }
    }
}

private data class SectionNavItem(val section: SettingsSection, val icon: ImageVector)

private val SETTINGS_NAV = listOf(
    SectionNavItem(SettingsSection.Home, Icons.Filled.Home),
    SectionNavItem(SettingsSection.Performance, Icons.Filled.Bolt),
    SectionNavItem(SettingsSection.Tuning, Icons.Filled.Tune),
    SectionNavItem(SettingsSection.GameMode, Icons.Filled.SportsEsports),
    SectionNavItem(SettingsSection.Battery, Icons.Filled.BatteryFull),
    SectionNavItem(SettingsSection.Thermals, Icons.Filled.Thermostat),
    SectionNavItem(SettingsSection.Advanced, Icons.Filled.Settings),
    SectionNavItem(SettingsSection.About, Icons.Filled.Info),
)

/**
 * Landscape Settings Manager: a fixed left sidebar (all 8 sections, Master Prompt §7/§13) and a
 * scrolling content pane on the right. The header's home icon returns to GameSpace without
 * closing and relaunching the app (Master Prompt §3: no new activity, no orientation change).
 */
@Composable
fun SettingsManagerScaffold(vm: MainViewModel, current: SettingsSection) {
    Row(Modifier.fillMaxSize()) {
        // ---- Left sidebar
        Column(
            Modifier
                .fillMaxHeight()
                .width(196.dp)
                .background(EncoreColors.Carbon)
                .border(width = 1.dp, color = EncoreColors.HairlineDim)
                .padding(vertical = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(EncoreColors.NeonRed, CutCornerShape(2.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "MANAGER",
                    style = MaterialTheme.typography.labelMedium,
                    color = EncoreColors.Silver,
                    letterSpacing = 1.5.sp,
                )
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(SETTINGS_NAV, key = { it.section }) { item ->
                    SidebarItem(
                        label = item.section.title,
                        icon = item.icon,
                        selected = item.section == current,
                        onClick = { vm.navigate(AppSection.SettingsManager(item.section)) },
                    )
                }
            }
        }

        // ---- Content pane
        Column(Modifier.fillMaxSize().background(EncoreColors.TrueBlack)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, top = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    ScreenTitle(current.title)
                }
                Box(
                    Modifier
                        .clip(CutCornerShape(8.dp))
                        .clickable { vm.navigateToGameSpace() }
                        .border(1.dp, EncoreColors.HairlineDim, CutCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Icon(Icons.Filled.Home, contentDescription = "Back to GameSpace", tint = EncoreColors.Silver)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                when (current) {
                    SettingsSection.Home -> DeviceOverviewScreen(vm)
                    SettingsSection.Performance -> PerformanceSectionScreen(vm)
                    SettingsSection.Tuning -> TuningScreen(vm)
                    SettingsSection.GameMode -> GameModeSectionScreen(vm)
                    SettingsSection.Battery -> BatterySectionScreen(vm)
                    SettingsSection.Thermals -> ThermalsSectionScreen(vm)
                    SettingsSection.Advanced -> AdvancedSectionScreen(vm)
                    SettingsSection.About -> AboutScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(CutCornerShape(8.dp))
            .background(if (selected) EncoreColors.NeonRed.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) EncoreColors.NeonRed.copy(alpha = 0.6f) else Color.Transparent,
                CutCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) EncoreColors.NeonRed else EncoreColors.TextLabel,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            color = if (selected) EncoreColors.TextPrimary else EncoreColors.TextLabel,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp,
        )
    }
}
