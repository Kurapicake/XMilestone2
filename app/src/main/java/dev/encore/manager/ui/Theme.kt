package dev.encore.manager.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Cyberpunk AMOLED Neon Red — the single fixed palette for Encore Manager.
 *
 * The theme deliberately ignores the system light/dark setting and dynamic color: a tuning
 * utility's UI should look the same (and burn as little AMOLED power as possible) regardless
 * of wallpaper or OS theme.
 */
object EncoreColors {
    // ---- Core AMOLED surfaces
    val TrueBlack = Color(0xFF000000)
    val Carbon = Color(0xFF0D0D10)
    val Obsidian = Color(0xFF141419)
    val ObsidianRaised = Color(0xFF1B1B21)
    val HairlineDim = Color(0xFF2A2A32)

    // ---- Neon Red / Crimson accents
    val NeonRed = Color(0xFFFF1E38)
    val ElectricRed = Color(0xFFFF003C)
    val Crimson = Color(0xFF99001A)
    val CrimsonDeep = Color(0xFF4D0010)

    // ---- Typography / status colors
    val TextPrimary = Color(0xFFF5F5F7)
    val TextLabel = Color(0xFF8E8E93)
    val Amber = Color(0xFFFFB300)
    val Lime = Color(0xFF39FF6A)
    val Cyan = Color(0xFF00E5FF)

    // ---- XArena Xtended metallic-silver brand accent (logo, headers, section dividers)
    val Silver = Color(0xFFC9CED6)
    val SilverDim = Color(0xFF7D828C)

    // ---- Crosshair swatches (also mirrored as plain ARGB ints in the GameSpace overlay)
    val CrosshairNeonRed = NeonRed
    val CrosshairCyan = Cyan
    val CrosshairLime = Lime

    /** Diagonal carbon-fiber style surface gradient used behind hero panels. */
    val CarbonGradient = Brush.linearGradient(listOf(Carbon, TrueBlack, Obsidian))

    /** Neon red glow gradient for active/selected chips and buttons. */
    fun glow(strength: Float = 1f) = Brush.linearGradient(
        listOf(NeonRed.copy(alpha = 0.95f * strength), ElectricRed.copy(alpha = 0.55f * strength))
    )

    /** Metallic silver-to-red brand gradient for the XArena Xtended wordmark and section headers. */
    val BrandGradient = Brush.linearGradient(listOf(Silver, NeonRed, ElectricRed))
}

private val EncoreDarkScheme = darkColorScheme(
    primary = EncoreColors.NeonRed,
    onPrimary = Color.Black,
    primaryContainer = EncoreColors.CrimsonDeep,
    onPrimaryContainer = EncoreColors.TextPrimary,
    secondary = EncoreColors.Crimson,
    onSecondary = EncoreColors.TextPrimary,
    secondaryContainer = EncoreColors.Obsidian,
    onSecondaryContainer = EncoreColors.TextPrimary,
    tertiary = EncoreColors.Amber,
    onTertiary = Color.Black,
    background = EncoreColors.TrueBlack,
    onBackground = EncoreColors.TextPrimary,
    surface = EncoreColors.Carbon,
    onSurface = EncoreColors.TextPrimary,
    surfaceVariant = EncoreColors.Obsidian,
    onSurfaceVariant = EncoreColors.TextLabel,
    surfaceContainerLowest = EncoreColors.TrueBlack,
    surfaceContainerLow = EncoreColors.Carbon,
    surfaceContainer = EncoreColors.Obsidian,
    surfaceContainerHigh = EncoreColors.ObsidianRaised,
    surfaceContainerHighest = EncoreColors.ObsidianRaised,
    error = EncoreColors.ElectricRed,
    onError = Color.Black,
    errorContainer = EncoreColors.CrimsonDeep,
    onErrorContainer = EncoreColors.TextPrimary,
    outline = EncoreColors.NeonRed.copy(alpha = 0.45f),
    outlineVariant = EncoreColors.HairlineDim,
)

/** Angular, chamfered geometry across every Material3 surface in the app. */
private val EncoreShapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(10.dp),
    large = CutCornerShape(16.dp),
    extraLarge = CutCornerShape(24.dp),
)

private val EncoreTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp,
            color = EncoreColors.TextPrimary,
        ),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, color = EncoreColors.TextPrimary),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, color = EncoreColors.TextPrimary),
        labelMedium = base.labelMedium.copy(
            color = EncoreColors.TextLabel,
            letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Medium,
        ),
        bodyMedium = base.bodyMedium.copy(color = EncoreColors.TextPrimary),
    )
}

/** Monospace style used for telemetry readouts (frequencies, PIDs, raw log text). */
val TelemetryTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    color = EncoreColors.NeonRed,
)

@Composable
fun EncoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EncoreDarkScheme,
        shapes = EncoreShapes,
        typography = EncoreTypography,
        content = content,
    )
}

/**
 * A chamfered "HUD" panel: carbon/obsidian fill, a thin glowing neon-red (or custom) border and
 * cut corners. The single visual building block reused across Home, Games, Tuning and Settings.
 */
@Composable
fun NeonPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = EncoreColors.NeonRed.copy(alpha = 0.55f),
    fill: Color = EncoreColors.Carbon,
    shape: Shape = CutCornerShape(14.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, borderColor, shape),
    ) { content() }
}

/** Small pulsing dot used to mark a live/active status (daemon running, overlay on, etc.). */
@Composable
fun PulseDot(modifier: Modifier = Modifier, color: Color = EncoreColors.NeonRed, active: Boolean = true) {
    if (!active) {
        Box(modifier.size(8.dp).clip(CircleShape).background(EncoreColors.HairlineDim))
        return
    }
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier
            .size(8.dp)
            .shadow(6.dp, CircleShape, ambientColor = color, spotColor = color)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}
