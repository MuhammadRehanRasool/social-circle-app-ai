package dev.braindump.splitter.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.ui.capture.MicButton
import dev.braindump.splitter.ui.capture.OnDeviceTag
import dev.braindump.splitter.ui.capture.SheetHandle
import dev.braindump.splitter.ui.capture.rememberVoiceToggle
import dev.braindump.splitter.ui.capture.rememberVoiceTranscriber
import dev.braindump.splitter.ui.capture.withLiveWords
import dev.braindump.splitter.ui.theme.AccentButton
import dev.braindump.splitter.ui.theme.Glass

/**
 * A chat about one idea: the note sits on top and rewrites itself live as each request
 * ("make it shorter", "add a section on X") is applied by Gemini Nano. The sheet keeps
 * the app in the foreground, which Nano needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefineSheet(
    idea: Idea,
    state: RefineState,
    onSend: (String) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
) {
    val glass = Glass.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val transcriber = rememberVoiceTranscriber()
    val listState = rememberLazyListState()
    var text by rememberSaveable(idea.id) { mutableStateOf("") }

    val mine = state.ideaId == idea.id
    val pendingAsk = state.pendingAsk?.takeIf { mine }
    val error = state.error?.takeIf { mine }
    val busy = state.pendingAsk != null
    val showSuggestions = idea.refinements.isEmpty() && pendingAsk == null

    val toggleVoice = rememberVoiceToggle(transcriber) { spoken ->
        text = if (text.isBlank()) spoken else "${text.trimEnd()} $spoken"
    }
    val send: (String) -> Unit = { ask ->
        val request = ask.trim()
        if (request.isNotEmpty() && !busy) {
            if (transcriber.isListening) transcriber.release()
            onClearError()
            onSend(request)
            text = ""
        }
    }

    // Items: the idea, one per turn, then the pending turn, the error, or the suggestions.
    val itemCount = 1 + idea.refinements.size +
        (if (pendingAsk != null) 1 else 0) + (if (error != null) 1 else 0) + (if (showSuggestions) 1 else 0)
    LaunchedEffect(itemCount) {
        if (itemCount > 1) listState.animateScrollToItem(itemCount - 1)
    }

    ModalBottomSheet(
        onDismissRequest = {
            transcriber.release()
            onDismiss()
        },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = glass.fillStrong,
        // Translucent containers have no matching "on" color, so name it explicitly.
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.refine_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                OnDeviceTag()
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "idea") { LiveIdea(idea) }

                itemsIndexed(idea.refinements, key = { index, _ -> "turn-$index" }) { _, turn ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AskBubble(turn.ask)
                        ReplyBubble(turn.reply)
                    }
                }

                if (pendingAsk != null) {
                    item(key = "pending") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AskBubble(pendingAsk)
                            ThinkingBubble()
                        }
                    }
                }

                if (error != null) {
                    item(key = "error") {
                        ErrorBubble(error, onRetry = state.failedAsk?.let { ask -> { send(ask) } })
                    }
                }

                if (showSuggestions) {
                    item(key = "suggestions") { Suggestions(enabled = !busy, onPick = send) }
                }
            }

            val listening = transcriber.isListening
            val shown = withLiveWords(text, transcriber)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicButton(listening = listening, level = { transcriber.level }, onClick = toggleVoice)
                OutlinedTextField(
                    value = shown,
                    onValueChange = { text = it },
                    readOnly = listening,
                    placeholder = {
                        Text(stringResource(if (listening) R.string.capture_listening else R.string.refine_hint))
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send(shown) }),
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = glass.inset,
                        unfocusedContainerColor = glass.inset,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                AccentButton(
                    onClick = { send(shown) },
                    enabled = shown.isNotBlank() && !busy,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.refine_send),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** The idea as it stands right now; it resizes smoothly as Gemini Nano rewrites it. */
@Composable
private fun LiveIdea(idea: Idea) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Glass.colors.inset)
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
            .padding(16.dp),
    ) {
        AnimatedContent(
            targetState = idea.title,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
            label = "title",
        ) { title ->
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        CategoryTag(idea.category, Modifier.padding(top = 6.dp))
        if (idea.body.isNotBlank()) MarkdownText(idea.body, Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun AskBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ReplyBubble(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
            .background(Glass.colors.inset)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ThinkingBubble() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
            .background(Glass.colors.inset)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Text(
            text = stringResource(R.string.refine_thinking),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun ErrorBubble(message: String, onRetry: (() -> Unit)?) {
    val error = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
            .background(error.copy(alpha = 0.1f))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = error,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(vertical = 4.dp),
        )
        if (onRetry != null) {
            Row(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = error, modifier = Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.idea_retry),
                    style = MaterialTheme.typography.labelLarge,
                    color = error,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/** One-tap starters for the first refinement. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(enabled: Boolean, onPick: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val suggestions = listOf(
        stringResource(R.string.refine_suggest_shorter),
        stringResource(R.string.refine_suggest_detail),
        stringResource(R.string.refine_suggest_steps),
        stringResource(R.string.refine_suggest_title),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        suggestions.forEach { suggestion ->
            Text(
                text = suggestion,
                style = MaterialTheme.typography.labelLarge,
                color = primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.1f))
                    .clickable(enabled = enabled, role = Role.Button) { onPick(suggestion) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
