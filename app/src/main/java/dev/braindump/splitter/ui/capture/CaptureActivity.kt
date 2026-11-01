package dev.braindump.splitter.ui.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.CaptureOutcome
import dev.braindump.splitter.ui.theme.BrainDumpTheme

/** Transparent host for [CaptureSheet], launched from the tile and launcher shortcuts. */
@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {

    private val viewModel: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startWithVoice = intent.getBooleanExtra(EXTRA_START_WITH_VOICE, false)
        setContent {
            BrainDumpTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(state) {
                    val finished = state as? CaptureUiState.Finished ?: return@LaunchedEffect
                    showOutcome(finished.outcome)
                }

                CaptureSheet(
                    startWithVoice = startWithVoice,
                    splitting = state != CaptureUiState.Editing,
                    hideOnSubmit = false,
                    onSubmit = viewModel::submit,
                    onDismiss = { if (!isFinishing) finish() },
                    // Slide the sheet away first; finishing straight away would cut it off mid-frame.
                    close = state is CaptureUiState.Finished,
                    overApps = true,
                )
            }
        }
    }

    private fun showOutcome(outcome: CaptureOutcome) {
        val message = when (outcome) {
            is CaptureOutcome.Split -> getString(R.string.capture_result_split, outcome.title)
            is CaptureOutcome.Queued -> getString(R.string.capture_result_queued)
            is CaptureOutcome.Failed -> getString(R.string.capture_result_failed)
            CaptureOutcome.Empty -> return
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_CAPTURE = "dev.braindump.splitter.action.CAPTURE"
        const val EXTRA_START_WITH_VOICE = "start_with_voice"

        fun intent(context: Context, startWithVoice: Boolean = false): Intent =
            Intent(context, CaptureActivity::class.java)
                .setAction(ACTION_CAPTURE) // pinned shortcuts require an action
                .putExtra(EXTRA_START_WITH_VOICE, startWithVoice)
    }
}
