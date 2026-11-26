package dev.encore.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.encore.manager.MainViewModel

/**
 * Application identity, developer credit, project description, compatibility and the original
 * Encore acknowledgment (Master Prompt §15). All text below is the exact formal content from
 * the master prompt — nothing paraphrased or shortened. The application version is read from
 * the real build (PackageInfo); the module version comes from the installed module's own
 * metadata, same as everywhere else in the app. Neither is invented.
 */
@Composable
fun AboutScreen(vm: MainViewModel) {
    val home by vm.home.collectAsState()
    val context = LocalContext.current
    val appVersion = remember {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "N/A"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NeonPanel(Modifier.fillMaxWidth(), shape = CutCornerShape(20.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                XArenaWordmark()
                Spacer(Modifier.height(4.dp))
                Text(
                    "XTENDED GAMING EXPERIENCE",
                    style = MaterialTheme.typography.labelMedium,
                    color = EncoreColors.Silver,
                    letterSpacing = 2.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        AboutSection("Application Information") {
            TelemetryRow("APPLICATION NAME", "XArena Xtended")
            TelemetryRow("APPLICATION VERSION", appVersion)
            TelemetryRow("MODULE VERSION", home.moduleVersion)
        }

        AboutSection("Developer Information") {
            TelemetryRow("DEVELOPER", "kuronand2")
            TelemetryRow("ROLE", "Sole Developer, Maintainer, and Tester")
        }

        AboutSection("Project Description") {
            BodyParagraph(
                "XArena Xtended is an independently developed Android gaming performance and " +
                    "device management application specifically designed for the Infinix GT 30 Pro (X6873).",
            )
            BodyParagraph(
                "The application provides an integrated platform for gaming-focused system " +
                    "monitoring, performance management, hardware tuning, and gaming assistance " +
                    "features. Its objective is to deliver a unified and optimized gaming management " +
                    "experience tailored to the hardware and software capabilities of the Infinix GT 30 Pro.",
            )
            BodyParagraph("XArena Xtended is independently developed, maintained, and tested solely by kuronand2.")
        }

        AboutSection("Supported Devices") {
            BodyParagraph("XArena Xtended is exclusively developed for the Infinix GT 30 Pro (X6873).")
            BodyParagraph(
                "All official variants of the Infinix GT 30 Pro are intended to be supported, " +
                    "provided they meet the application's system requirements and supported hardware " +
                    "interface requirements.",
            )
            BodyParagraph("Compatibility with other device models is not guaranteed.")
        }

        AboutSection("Original Project and Acknowledgments") {
            BodyParagraph("XArena Xtended is based on and builds upon the original Encore project.")
            BodyParagraph(
                "Special acknowledgment and credit are given to the original project and its " +
                    "developer for the underlying work, implementation, and foundation upon which " +
                    "XArena Xtended is developed.",
            )
            TelemetryRow("ORIGINAL PROJECT", "Encore")
            TelemetryRow("WEBSITE", "encore.rem01gaming.dev")
            TelemetryRow("REPOSITORY", "github.com/Rem01Gaming/encore")
        }

        AboutSection("License and Open-Source Acknowledgments") {
            BodyParagraph(
                "Encore Tweaks is licensed under the Apache License 2.0. See the NOTICE file " +
                    "bundled with this app for the full list of third-party licenses and attributions.",
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun XArenaWordmark() {
    Row {
        Text("XARENA ", style = MaterialTheme.typography.headlineMedium, color = EncoreColors.Silver, fontWeight = FontWeight.Black)
        Text("XTENDED", style = MaterialTheme.typography.headlineMedium, color = EncoreColors.NeonRed, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = EncoreColors.NeonRed,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
private fun BodyParagraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = EncoreColors.TextPrimary)
}
