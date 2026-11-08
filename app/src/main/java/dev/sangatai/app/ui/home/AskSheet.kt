package dev.sangatai.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.sangatai.app.domain.model.AskOutcome
import dev.sangatai.app.domain.model.PersonMatch
import dev.sangatai.app.ui.capture.MicButton
import dev.sangatai.app.ui.capture.OnDeviceTag
import dev.sangatai.app.ui.capture.SheetHandle
import dev.sangatai.app.ui.capture.rememberVoiceToggle
import dev.sangatai.app.ui.capture.rememberVoiceTranscriber
import dev.sangatai.app.ui.capture.withLiveWords
import dev.sangatai.app.ui.common.Avatar
import dev.sangatai.app.ui.common.glassFieldColors
import dev.sangatai.app.ui.theme.Glass
import dev.sangatai.app.ui.theme.GlassPanel

/** "Who can help me with…": searches everyone's tags on the phone, then Gemini Nano explains. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskSheet(
    state: AskState,
    onAsk: (String) -> Unit,
    onOpenPerson: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val transcriber = rememberVoiceTranscriber()
    var text by rememberSaveable { mutableStateOf(state.query) }
    val toggleVoice = rememberVoiceToggle(transcriber) { spoken ->
        text = if (text.isBlank()) spoken else "${text.trimEnd()} $spoken"
        onAsk(text)
    }
    val shown = withLiveWords(text, transcriber)
    val submit = {
        if (transcriber.isListening) transcriber.release()
        if (shown.isNotBlank()) onAsk(shown.trim())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = Glass.colors.fillStrong,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Who can help?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                OnDeviceTag()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = shown,
                    onValueChange = { text = it },
                    readOnly = transcriber.isListening,
                    placeholder = { Text("fix my car, find a flat, learn guitar…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = submit, enabled = shown.isNotBlank()) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = glassFieldColors(),
                    modifier = Modifier.weight(1f),
                )
                MicButton(listening = transcriber.isListening, level = { transcriber.level }, onClick = toggleVoice)
            }

            when {
                state.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(
                        "Looking through your circle…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }

                else -> when (val outcome = state.outcome) {
                    null, AskOutcome.Empty -> Text(
                        "Matches come from the tags on each person, so the more you tell, the better it gets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AskOutcome.NoOne -> Text(
                        "Nobody in your circle fits that yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is AskOutcome.Unavailable -> Text(
                        outcome.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is AskOutcome.Matches -> {
                        outcome.degraded?.let {
                            Text(
                                "Gemini Nano wasn't available, so these come from tag search alone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(
                            Modifier.heightIn(min = 0.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            outcome.matches.forEach { match -> MatchRow(match, onClick = { onOpenPerson(match.personId) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchRow(match: PersonMatch, onClick: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(match.name, relation = null, size = 40.dp)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(match.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    match.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${(match.similarity * 100).toInt().coerceIn(0, 100)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
