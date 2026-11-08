package dev.sangatai.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sangatai.app.domain.model.Capture
import dev.sangatai.app.domain.model.CaptureStatus
import dev.sangatai.app.ui.common.ConfirmDialog
import dev.sangatai.app.ui.common.Pill
import dev.sangatai.app.ui.common.relativeTime
import dev.sangatai.app.ui.theme.GlassPanel

/**
 * Everything the user said, newest first, with who it was saved to. Deleting one takes back
 * everything it added: tags, relations, ties, and people only it mentioned.
 */
@Composable
fun HistoryList(
    captures: List<Capture>,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (captures.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing said yet. What you tell SangatAI shows up here, exactly as you said it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(captures, key = { it.id }) { capture ->
            CaptureRow(capture, onRetry = { onRetry(capture.id) }, onDelete = { deleting = capture.id })
        }
    }

    deleting?.let { id ->
        ConfirmDialog(
            title = "Delete this?",
            body = "What you said is deleted, along with every tag, relation and tie it added. " +
                "People only it mentioned are removed too.",
            confirmText = "Delete",
            onConfirm = {
                deleting = null
                onDelete(id)
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun CaptureRow(capture: Capture, onRetry: () -> Unit, onDelete: () -> Unit) {
    var expanded by rememberSaveable(capture.id) { mutableStateOf(false) }
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .clickable { expanded = !expanded }
                .padding(start = 14.dp, top = 12.dp, bottom = 8.dp, end = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    relativeTime(capture.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                when (capture.status) {
                    CaptureStatus.PENDING -> Pill("waiting for Nano")
                    CaptureStatus.FAILED -> Pill(
                        "couldn't read",
                        color = MaterialTheme.colorScheme.error,
                        container = MaterialTheme.colorScheme.errorContainer,
                    )
                    CaptureStatus.DONE -> Unit
                }
                if (capture.status == CaptureStatus.FAILED) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                capture.summary?.takeIf { it.isNotBlank() && !expanded } ?: capture.rawText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp),
            )
            if (capture.people.isNotEmpty()) {
                Text(
                    capture.people.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 6.dp, end = 10.dp),
                )
            } else if (capture.status == CaptureStatus.DONE) {
                Text(
                    "Nobody mentioned",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (expanded && capture.error != null && capture.status != CaptureStatus.DONE) {
                Text(
                    capture.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp, end = 10.dp),
                )
            }
            Spacer(Modifier.padding(bottom = 4.dp))
        }
    }
}
