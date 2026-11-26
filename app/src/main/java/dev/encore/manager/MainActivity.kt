package dev.encore.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.encore.manager.ui.EncoreRoot
import dev.encore.manager.ui.EncoreTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EncoreTheme { EncoreRoot(vm) }
        }
        // Bring the Game Bar back if it is switched on but not running (e.g. after an update)
        lifecycleScope.launch { dev.encore.manager.gamebar.GameBarSetup.ensureRunning(applicationContext) }
        // Poll daemon status only while the app is visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLoop() }
        }
    }
}
