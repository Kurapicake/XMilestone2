package dev.encore.manager.gamebar

import android.content.Context
import android.provider.Settings

/**
 * Wraps `Settings.System.SCREEN_BRIGHTNESS` / `SCREEN_BRIGHTNESS_MODE` for the GameSpace
 * brightness slider. Every write requires `WRITE_SETTINGS`; [canWrite] reflects whether the
 * app currently holds it (granted the same way as the overlay and usage-access permissions,
 * via [GameBarSetup.grantViaRoot]). Reads never require the permission.
 */
object BrightnessController {

    const val MIN = 1
    const val MAX = 255

    fun canWrite(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    /** True while the system is managing brightness automatically. */
    fun isAutomatic(ctx: Context): Boolean = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    /** Current screen brightness, 1..255. Falls back to a mid value if it cannot be read. */
    fun current(ctx: Context): Int = runCatching {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128).coerceIn(MIN, MAX)

    /**
     * Sets brightness directly (switches the mode to manual first, since a value written while
     * automatic mode is active is overridden by the system's own light-sensor curve almost
     * immediately). Returns false if `WRITE_SETTINGS` is not held.
     */
    fun set(ctx: Context, value: Int): Boolean {
        if (!canWrite(ctx)) return false
        return runCatching {
            if (isAutomatic(ctx)) {
                Settings.System.putInt(
                    ctx.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
            }
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(MIN, MAX))
        }.isSuccess
    }

    /** Switches auto-brightness on or off. Returns false if `WRITE_SETTINGS` is not held. */
    fun setAutomatic(ctx: Context, auto: Boolean): Boolean {
        if (!canWrite(ctx)) return false
        return runCatching {
            Settings.System.putInt(
                ctx.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }.isSuccess
    }
}
