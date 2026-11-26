package dev.encore.manager.gamebar

import android.content.Context
import android.media.AudioManager

/**
 * Wraps `AudioManager.STREAM_MUSIC` for the GameSpace volume slider. Needs no special
 * permission. Every change passes flag `0` so it never pops the system volume UI or plays the
 * "adjust" chime over the game.
 */
object VolumeController {

    private fun am(ctx: Context): AudioManager? =
        ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun max(ctx: Context): Int = am(ctx)?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15

    fun min(ctx: Context): Int {
        val manager = am(ctx) ?: return 0
        return runCatching { manager.getStreamMinVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
    }

    fun current(ctx: Context): Int = am(ctx)?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

    fun set(ctx: Context, index: Int) {
        val manager = am(ctx) ?: return
        val clamped = index.coerceIn(min(ctx), max(ctx))
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0) }
    }

    fun isMuted(ctx: Context): Boolean = current(ctx) <= min(ctx)
}
