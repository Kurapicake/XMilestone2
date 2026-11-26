package dev.encore.manager.gamebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import dev.encore.manager.MainActivity
import dev.encore.manager.R
import dev.encore.manager.data.EncoreRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches which app is in the foreground and shows the game bar only while that app is in
 * Encore's game list (gamelist.json), i.e. the same set of games the Encore daemon handles.
 */
class GameBarService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: GameBarOverlay? = null
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        GameBarDiag.update { it.copy(serviceRunning = true, lastError = null) }
        startAsForeground()
        val o = GameBarOverlay(applicationContext, scope)
        overlay = o
        pollJob = scope.launch { pollLoop(o) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        pollJob?.cancel()
        overlay?.dispose()
        overlay = null
        scope.cancel()
        GameBarDiag.update { GameBarDiag.State() }
        super.onDestroy()
    }

    private suspend fun pollLoop(overlay: GameBarOverlay) {
        val usm = getSystemService(UsageStatsManager::class.java)

        // What is on screen, from usage events
        var foreground: String? = null
        // `foreground` if it is in the Encore game list, else null. This is what the overlay follows.
        var game: String? = null
        // The package the game list was last read for. null = nothing read yet for the current app.
        var checkedFor: String? = null
        var checkedAt = 0L
        // Rechecks done for the current non-game app (see needsRecheck below)
        var rechecks = 0
        // First pass looks back a few hours so a game that is already running is found
        var begin = System.currentTimeMillis() - 6L * 60 * 60 * 1000

        while (scope.isActive) {
            try {
                val now = System.currentTimeMillis()
                foreground = withContext(Dispatchers.IO) { scanEvents(usm, begin, now, foreground) }
                begin = now - 1000
                val fg = foreground
                val tick = SystemClock.elapsedRealtime()

                if (fg == null) {
                    game = null
                    checkedFor = null
                    rechecks = 0
                } else {
                    // Read the game list when the app in front changed. If the app is not in the list,
                    // read it again a few times soon after (2 s apart) and then every 15 s: the very
                    // first read right as a game launches can fail while root is busy, and the user can
                    // also add the game to the list while it is already running.
                    val changed = fg != checkedFor
                    if (changed) {
                        game = null // never keep following the previous app while this one is unchecked
                        rechecks = 0
                    }
                    val gap = if (rechecks < 3) RECHECK_FAST_MS else RECHECK_SLOW_MS
                    val needsRecheck = !changed && game == null && tick - checkedAt >= gap
                    if (changed || needsRecheck) {
                        val keys = EncoreRepository.gameKeysOrNull()
                        if (keys == null) {
                            // Unreadable right now: leave checkedFor alone so the next tick tries again
                            GameBarDiag.update { it.copy(inGameList = null) }
                        } else {
                            game = if (fg in keys) fg else null
                            checkedFor = fg
                            checkedAt = tick
                            if (needsRecheck) rechecks++
                        }
                    }
                }

                GameTuningApplier.onGameChanged(game)

                overlay.update(game)
                overlay.ensureShown()
                val shown = overlay.isShown()
                GameBarDiag.update {
                    it.copy(
                        foreground = fg,
                        inGameList = if (fg != null && checkedFor == fg) game != null else null,
                        overlayShown = shown,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. usage access revoked: keep the service alive and retry
                Log.w(TAG, "poll failed", e)
                GameBarDiag.update { it.copy(lastError = e.javaClass.simpleName + ": " + (e.message ?: "")) }
            }
            delay(1000)
        }
    }

    private fun scanEvents(usm: UsageStatsManager, begin: Long, end: Long, start: String?): String? {
        var fg = start
        val events = usm.queryEvents(begin, end) ?: return fg
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> fg = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (fg == e.packageName) fg = null
            }
        }
        return fg
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Game Bar", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Game Bar active")
            .setContentText("Shows up when a game from your Encore list is open")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            GameBarDiag.update { it.copy(lastError = "startForeground: " + e.javaClass.simpleName) }
        }
    }

    companion object {
        private const val TAG = "GameBarService"
        private const val CHANNEL_ID = "gamebar"
        private const val NOTIFICATION_ID = 1001
        private const val RECHECK_FAST_MS = 2_000L
        private const val RECHECK_SLOW_MS = 15_000L

        /** True while an instance of this service is alive in this process. */
        @Volatile
        var running: Boolean = false
            private set
    }
}
