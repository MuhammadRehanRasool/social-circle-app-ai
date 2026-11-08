package dev.sangatai.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import dev.sangatai.app.ui.home.HomeRoute
import dev.sangatai.app.ui.person.PersonRoute
import dev.sangatai.app.ui.settings.SettingsRoute
import dev.sangatai.app.ui.theme.SangatTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SangatTheme {
                // People opened from the circle, from each other's ties, and so on; last is on screen.
                var stack by rememberSaveable { mutableStateOf(longArrayOf()) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                // Keeps the home tab, filters and scroll while a person or settings is open.
                val saveableState = rememberSaveableStateHolder()

                val screen: Screen = when {
                    settingsOpen -> Screen.Settings
                    stack.isNotEmpty() -> Screen.Profile(stack.last(), depth = stack.size)
                    else -> Screen.Home
                }
                val open: (Long) -> Unit = { id -> if (stack.lastOrNull() != id) stack += id }
                val back: () -> Unit = {
                    when {
                        settingsOpen -> settingsOpen = false
                        stack.isNotEmpty() -> stack = stack.copyOf(stack.size - 1)
                    }
                }
                BackHandler(enabled = screen != Screen.Home, onBack = back)

                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val forward = targetState.depth > initialState.depth
                        val direction = if (forward) 1 else -1
                        (slideInHorizontally(tween(300)) { direction * it / 4 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(300)) { -direction * it / 4 } + fadeOut(tween(200)))
                    },
                    label = "screen",
                ) { shown ->
                    when (shown) {
                        Screen.Home -> saveableState.SaveableStateProvider("home") {
                            HomeRoute(onOpenPerson = open, onOpenSettings = { settingsOpen = true })
                        }
                        Screen.Settings -> SettingsRoute(onBack = back)
                        is Screen.Profile -> saveableState.SaveableStateProvider("person-${shown.depth}-${shown.personId}") {
                            PersonRoute(
                                personId = shown.personId,
                                // Also how a deleted or merged-away person leaves; one deeper in the
                                // stack leaves the same way once it's back on top.
                                onBack = {
                                    if (stack.lastOrNull() == shown.personId) stack = stack.copyOf(stack.size - 1)
                                },
                                onOpenPerson = open,
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface Screen {
    /** How deep in the stack, so the slide goes the right way. */
    val depth: Int

    data object Home : Screen {
        override val depth = 0
    }

    data object Settings : Screen {
        override val depth = 1
    }

    data class Profile(val personId: Long, override val depth: Int) : Screen
}
