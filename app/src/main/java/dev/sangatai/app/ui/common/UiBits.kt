package dev.sangatai.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sangatai.app.domain.model.AiStatus
import dev.sangatai.app.domain.model.Person
import dev.sangatai.app.ui.theme.Glass
import dev.sangatai.app.ui.theme.GlassPanel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** "Sara Khan" -> "SK", "ali" -> "A". */
fun initialsOf(name: String): String {
    val words = name.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase(Locale.getDefault())
        else -> (words.first().take(1) + words.last().take(1)).uppercase(Locale.getDefault())
    }
}

/** "just now", "3h ago", "yesterday", "5d ago", then a date. */
fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - then).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        minutes < 48 * 60 -> "yesterday"
        minutes < 30 * 24 * 60 -> "${minutes / (24 * 60)}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(then))
    }
}

fun dateTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))

/** "car_expert" -> "car expert". */
fun tagText(tag: String): String = tag.replace('_', ' ')

/** A round avatar with initials, colored by who they are to the user. */
@Composable
fun Avatar(name: String, relation: String?, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val colors = RelationGroup.of(relation).colors()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.fill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            color = colors.onFill,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.36f).sp,
            maxLines = 1,
        )
    }
}

/** A small rounded label. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color = Glass.colors.inset,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Who someone is to the user, tinted with their circle's color. */
@Composable
fun RelationPill(relation: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val colors = RelationGroup.of(relation).colors()
    Pill(
        text = relation ?: "relation?",
        modifier = modifier,
        color = if (relation == null) MaterialTheme.colorScheme.onSurfaceVariant else colors.fill,
        container = colors.fill.copy(alpha = 0.14f),
        onClick = onClick,
    )
}

@Composable
fun aiStatusLabel(status: AiStatus): String = when (status) {
    AiStatus.Unknown -> "On-device · offline"
    AiStatus.Checking -> "Waking up Gemini Nano…"
    is AiStatus.Downloading -> status.percent?.let { "Downloading Gemini Nano… $it%" } ?: "Downloading Gemini Nano…"
    is AiStatus.Ready -> status.modelName?.let { "Ready · $it" } ?: "Gemini Nano ready · on-device"
    is AiStatus.Unavailable -> "AI offline · what you say is still saved"
}

@Composable
fun StatusChip(status: AiStatus, modifier: Modifier = Modifier) {
    val glass = Glass.colors
    val dotColor by animateColorAsState(
        targetValue = when (status) {
            is AiStatus.Ready -> glass.success
            is AiStatus.Unavailable -> MaterialTheme.colorScheme.error
            AiStatus.Checking, is AiStatus.Downloading -> glass.warning
            AiStatus.Unknown -> MaterialTheme.colorScheme.outline
        },
        label = "dot",
    )
    val busy = status is AiStatus.Checking || status is AiStatus.Downloading
    GlassPanel(modifier, shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(dotColor, busy)
            AnimatedContent(
                targetState = aiStatusLabel(status),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                label = "status",
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, busy: Boolean) {
    val dot = Modifier
        .size(8.dp)
        .clip(CircleShape)
    if (!busy) {
        Box(dot.background(color))
        return
    }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        dot
            .graphicsLayer { this.alpha = alpha }
            .background(color),
    )
}

/** A titled glass card, the building block of the settings and profile screens. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(top = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        GlassPanel(Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor.copy(alpha = alpha))
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 12.dp)) { trailing() }
        }
    }
}

@Composable
fun glassFieldColors() = Glass.colors.let { glass ->
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = glass.inset,
        unfocusedContainerColor = glass.inset,
        errorContainerColor = glass.inset,
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

/** Asks for one line of text. [onConfirm] gets it trimmed; blank is allowed only if [allowBlank]. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Save",
    supporting: String? = null,
    allowBlank: Boolean = false,
    extraField: (@Composable () -> Unit)? = null,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val ok = allowBlank || value.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    supportingText = if (supporting != null) {
                        { Text(supporting) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                extraField?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = ok) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick someone from [people], with a search box. */
@Composable
fun PersonPickerDialog(
    title: String,
    people: List<Person>,
    onPick: (Person) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(people, query) {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) people else people.filter { it.name.lowercase(Locale.getDefault()).contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(shown, key = { it.id }) { person ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(person) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(person.name, person.relation, size = 32.dp)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(person.name, style = MaterialTheme.typography.bodyLarge)
                                person.relation?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (shown.isEmpty()) {
                    Text(
                        "Nobody matches",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
