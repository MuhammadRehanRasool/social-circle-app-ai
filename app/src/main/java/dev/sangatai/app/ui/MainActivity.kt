package dev.braindump.splitter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import dev.braindump.splitter.ui.dashboard.DashboardRoute
import dev.braindump.splitter.ui.settings.SettingsRoute
import dev.braindump.splitter.ui.theme.BrainDumpTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BrainDumpTheme {
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                // Keeps the dashboard's filter, timeline mode and scroll while settings is open.
                val saveableState = rememberSaveableStateHolder()
                AnimatedContent(
                    targetState = settingsOpen,
                    transitionSpec = {
                        val direction = if (targetState) 1 else -1
                        (slideInHorizontally(tween(300)) { direction * it / 4 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(300)) { -direction * it / 4 } + fadeOut(tween(200)))
                    },
                    label = "screen",
                ) { open ->
                    if (open) {
                        SettingsRoute(onBack = { settingsOpen = false })
                    } else {
                        saveableState.SaveableStateProvider("dashboard") {
                            DashboardRoute(onOpenSettings = { settingsOpen = true })
                        }
                    }
                }
            }
        }
    }
}
