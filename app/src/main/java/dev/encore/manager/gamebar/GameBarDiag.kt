package dev.encore.manager.gamebar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the Game Bar is doing right now, for the status line in Settings. Lives in memory for the
 * life of the app process; the service and the UI run in the same process, so no IPC is needed.
 */
object GameBarDiag {

    data class State(
        val serviceRunning: Boolean = false,
        /** Package of the app currently in front, from usage events. */
        val foreground: String? = null,
        /** true / false once the game list was read for [foreground]; null while unknown or unreadable. */
        val inGameList: Boolean? = null,
        val overlayShown: Boolean = false,
        /** Last FPS reading detail, e.g. which layer was used and how old its newest frame was. */
        val fpsInfo: String? = null,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(block: (State) -> State) {
        _state.update(block)
    }
}
