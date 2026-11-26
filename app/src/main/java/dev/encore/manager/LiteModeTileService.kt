package dev.encore.manager

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.encore.manager.data.EncoreRepository
import dev.encore.manager.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile that toggles "Enforce Lite Mode" (preferences.enforce_lite_mode). */
class LiteModeTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { render(EncoreRepository.loadPrefs()) }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val current = EncoreRepository.loadPrefs()
            if (current == null) {
                render(null)
                return@launch
            }
            EncoreRepository.setBoolPref("enforce_lite_mode", !current.enforceLiteMode)
            render(EncoreRepository.loadPrefs())
        }
    }

    private fun render(prefs: Prefs?) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_label)
        when {
            prefs == null -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "No root / config"
            }
            prefs.enforceLiteMode -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "On"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Off"
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
