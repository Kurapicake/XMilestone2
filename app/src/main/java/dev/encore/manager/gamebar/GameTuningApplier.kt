package dev.encore.manager.gamebar

import android.util.Log
import dev.encore.manager.data.EncoreRepository

/**
 * Keeps the per-game tuning files in step with gamelist.json.
 *
 * Per-game Performance/Lite values no longer travel through tuning.conf. The Encore Manager app
 * writes one small file per customized game ([EncoreRepository.GAME_TUNING_DIR]) when a value is
 * saved, and encore_profiler looks up the game in front and reads that file at the moment it
 * applies a profile, so the first switch into a game already uses the right numbers and nothing
 * here has to be faster than the daemon.
 *
 * What is left for this object is housekeeping: whenever the detected foreground game changes
 * (and once when the Game Bar starts) the files are rebuilt from gamelist.json, which also picks
 * up an entry that was edited or removed outside this app, such as in the module's WebUI.
 * Balanced stays global in tuning.conf and is never touched here.
 */
object GameTuningApplier {
    private const val TAG = "GameTuningApplier"

    /** A value no real package name or null can equal, so the very first call always syncs. */
    private var lastSyncedFor: String? = UNSET

    /** Call whenever the detected foreground listed game changes (including to/from null). */
    suspend fun onGameChanged(pkg: String?) {
        if (pkg == lastSyncedFor) return
        val synced = try {
            EncoreRepository.syncGameTuningFiles()
        } catch (e: Exception) {
            Log.w(TAG, "sync failed for $pkg", e)
            false
        }
        // Only remember success; on failure the next tick (same pkg) tries again.
        if (synced) lastSyncedFor = pkg
    }

    private const val UNSET = "\u0000unset"
}
