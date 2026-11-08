package dev.sangatai.app.ui.person

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sangatai.app.domain.model.Connection
import dev.sangatai.app.domain.model.Interaction
import dev.sangatai.app.domain.model.Person
import dev.sangatai.app.domain.model.PersonDetail
import dev.sangatai.app.ui.common.Avatar
import dev.sangatai.app.ui.common.ConfirmDialog
import dev.sangatai.app.ui.common.Pill
import dev.sangatai.app.ui.common.PersonPickerDialog
import dev.sangatai.app.ui.common.RelationGroup
import dev.sangatai.app.ui.common.RelationPill
import dev.sangatai.app.ui.common.Section
import dev.sangatai.app.ui.common.SettingRow
import dev.sangatai.app.ui.common.TextInputDialog
import dev.sangatai.app.ui.common.colors
import dev.sangatai.app.ui.common.dateTime
import dev.sangatai.app.ui.common.relativeTime
import dev.sangatai.app.ui.common.tagText
import dev.sangatai.app.ui.theme.GlassBackdrop
import dev.sangatai.app.ui.theme.GlassIconButton
import dev.sangatai.app.ui.theme.GlassPanel

@Composable
fun PersonRoute(
    personId: Long,
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    viewModel: PersonViewModel = hiltViewModel(key = "person-$personId"),
) {
    LaunchedEffect(personId) { viewModel.show(personId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val everyone by viewModel.everyone.collectAsStateWithLifecycle()

    when (val s = state) {
        PersonUiState.Loading -> Box(Modifier.fillMaxSize()) { GlassBackdrop() }
        // Deleted, or merged into someone else: nothing left to show.
        PersonUiState.Gone -> LaunchedEffect(Unit) { onBack() }
        is PersonUiState.Shown -> PersonScreen(
            detail = s.detail,
            everyone = everyone,
            actions = PersonActions(
                onBack = onBack,
                onOpenPerson = onOpenPerson,
                onRename = viewModel::rename,
                onSetRelation = viewModel::setRelation,
                onAddTag = viewModel::addTag,
                onRemoveTag = viewModel::removeTag,
                onRestoreTag = viewModel::restoreTag,
                onSetLabels = viewModel::setLabels,
                onMergeIn = viewModel::mergeIn,
                onDeleteInteraction = viewModel::deleteInteraction,
                onDelete = { alsoRants ->
                    viewModel.delete(alsoRants)
                    onBack()
                },
            ),
        )
    }
}

class PersonActions(
    val onBack: () -> Unit,
    val onOpenPerson: (Long) -> Unit,
    val onRename: (String) -> Unit,
    val onSetRelation: (String?) -> Unit,
    val onAddTag: (String) -> Unit,
    val onRemoveTag: (String) -> Unit,
    val onRestoreTag: (String) -> Unit,
    val onSetLabels: (Long, List<String>) -> Unit,
    val onMergeIn: (Long) -> Unit,
    val onDeleteInteraction: (Long) -> Unit,
    val onDelete: (Boolean) -> Unit,
)

/** Which dialog is open. Saved as a string so it survives rotation. */
private object Dialogs {
    const val RENAME = "rename"
    const val RELATION = "relation"
    const val TAG = "tag"
    const val LINK = "link"
    const val MERGE = "merge"
    const val DELETE = "delete"
    const val LABELS = "labels:" // + other person's id
    const val NEW_LINK = "newlink:" // + other person's id
    const val FORGET = "forget:" // + interaction id
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonScreen(detail: PersonDetail, everyone: List<Person>, actions: PersonActions) {
    val person = detail.person
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val close = { dialog = null }

    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(onClick = actions.onBack, strong = false) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.weight(1f))
                GlassIconButton(onClick = { dialog = Dialogs.DELETE }, strong = false, onClickLabel = "Delete") {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "header") { Header(person, detail, onRename = { dialog = Dialogs.RENAME }, onRelation = { dialog = Dialogs.RELATION }) }

                item(key = "tags") {
                    Section("What you've said about them") {
                        Column(Modifier.padding(14.dp)) {
                            if (person.tags.isEmpty()) {
                                Text(
                                    "No tags yet. They come from what you say, or add your own.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = if (person.tags.isEmpty()) 8.dp else 0.dp),
                            ) {
                                person.tags.forEach { tag ->
                                    TagChip(
                                        text = tagText(tag.tag) + if (tag.count > 1) " ×${tag.count}" else "",
                                        mine = tag.byUser,
                                        onRemove = { actions.onRemoveTag(tag.tag) },
                                    )
                                }
                                Pill(
                                    "+ tag",
                                    color = MaterialTheme.colorScheme.primary,
                                    onClick = { dialog = Dialogs.TAG },
                                )
                            }
                            if (person.suppressedTags.isNotEmpty()) {
                                Text(
                                    "Removed (tap to allow again)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    person.suppressedTags.sorted().forEach { tag ->
                                        Pill(tagText(tag), onClick = { actions.onRestoreTag(tag) })
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "connections") {
                    Section("Connected to (${detail.connections.size})") {
                        Column {
                            detail.connections.forEach { connection ->
                                ConnectionRow(
                                    connection = connection,
                                    relation = everyone.firstOrNull { it.id == connection.otherId }?.relation,
                                    onOpen = { actions.onOpenPerson(connection.otherId) },
                                    onEdit = { dialog = Dialogs.LABELS + connection.otherId },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                            SettingRow(
                                title = "Link to someone",
                                subtitle = "Say how they know each other: married, siblings, coworkers…",
                                onClick = { dialog = Dialogs.LINK },
                                titleColor = MaterialTheme.colorScheme.primary,
                                trailing = { Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            )
                        }
                    }
                }

                item(key = "timeline-title") {
                    Text(
                        "Timeline (${detail.interactions.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                if (detail.interactions.isEmpty()) {
                    item(key = "timeline-empty") {
                        Text(
                            "Nothing said about them yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(detail.interactions, key = { "i${it.id}" }) { interaction ->
                    InteractionCard(interaction, onForget = { dialog = Dialogs.FORGET + interaction.id })
                }

                item(key = "manage") {
                    Section("Manage") {
                        Column {
                            SettingRow(
                                title = "Merge someone into ${person.name}",
                                subtitle = "Same person under two names? Their timeline, tags and ties move here.",
                                onClick = { dialog = Dialogs.MERGE },
                            )
                            SettingRow(
                                title = "Delete ${person.name}",
                                onClick = { dialog = Dialogs.DELETE },
                                titleColor = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                item(key = "bottom") { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }

    val open = dialog ?: return
    val others = everyone.filter { it.id != person.id }
    when {
        open == Dialogs.RENAME -> TextInputDialog(
            title = "Rename",
            initial = person.name,
            label = "Name",
            supporting = "Longer names heard later won't replace this.",
            onConfirm = {
                actions.onRename(it)
                close()
            },
            onDismiss = close,
        )

        open == Dialogs.RELATION -> TextInputDialog(
            title = "Who is ${person.name} to you?",
            initial = if (person.relationByUser) person.relation.orEmpty() else "",
            label = "cousin, boss, gym buddy…",
            supporting = if (person.relationByUser) {
                "Leave empty to go back to what you've said about them."
            } else {
                person.relation?.let { "You said: $it. What you type here wins." }
            },
            allowBlank = true,
            onConfirm = {
                actions.onSetRelation(it.ifBlank { null })
                close()
            },
            onDismiss = close,
        )

        open == Dialogs.TAG -> TextInputDialog(
            title = "Add a tag",
            initial = "",
            label = "Tag",
            supporting = "Short, like car expert or knows landlords",
            confirmText = "Add",
            onConfirm = {
                actions.onAddTag(it)
                close()
            },
            onDismiss = close,
        )

        open == Dialogs.LINK -> PersonPickerDialog(
            title = "Link ${person.name} to…",
            people = others,
            onPick = { dialog = Dialogs.NEW_LINK + it.id },
            onDismiss = close,
        )

        open.startsWith(Dialogs.NEW_LINK) || open.startsWith(Dialogs.LABELS) -> {
            val otherId = open.substringAfter(':').toLongOrNull()
            val other = otherId?.let { id -> everyone.firstOrNull { it.id == id } }
            if (other == null) {
                LaunchedEffect(open) { close() }
                return
            }
            val connection = detail.connections.firstOrNull { it.otherId == other.id }
            TextInputDialog(
                title = "${person.name} and ${other.name}",
                initial = connection?.labels?.joinToString(", ").orEmpty(),
                label = "How they're connected",
                supporting = buildString {
                    append("Comma-separated, like married or coworkers.")
                    if (!connection?.aiLabels.isNullOrEmpty()) append(" You've said: ${connection!!.aiLabels.joinToString(", ")}.")
                    if (connection != null) append(" Empty removes your labels.")
                },
                allowBlank = connection != null,
                onConfirm = { text ->
                    actions.onSetLabels(other.id, text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                    close()
                },
                onDismiss = close,
            )
        }

        open == Dialogs.MERGE -> PersonPickerDialog(
            title = "Merge into ${person.name}",
            people = others,
            onPick = {
                actions.onMergeIn(it.id)
                close()
            },
            onDismiss = close,
        )

        open == Dialogs.DELETE -> {
            var alsoRants by rememberSaveable { mutableStateOf(false) }
            ConfirmDialog(
                title = "Delete ${person.name}?",
                body = "Their tags, timeline and ties are deleted for good.",
                confirmText = "Delete",
                onConfirm = {
                    close()
                    actions.onDelete(alsoRants)
                },
                onDismiss = close,
                extra = {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { alsoRants = !alsoRants },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoRants, onCheckedChange = { alsoRants = it })
                        Text(
                            "Also delete everything I said that mentions them, so their name is gone from the raw text too",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
            )
        }

        open.startsWith(Dialogs.FORGET) -> {
            val interactionId = open.substringAfter(':').toLongOrNull()
            ConfirmDialog(
                title = "Forget this?",
                body = "It stops counting for ${person.name}: the tags and relations it gave them are taken back. " +
                    "What you said stays under What you said.",
                confirmText = "Forget",
                onConfirm = {
                    interactionId?.let(actions.onDeleteInteraction)
                    close()
                },
                onDismiss = close,
            )
        }

        else -> LaunchedEffect(open) { close() }
    }
}

@Composable
private fun Header(person: Person, detail: PersonDetail, onRename: () -> Unit, onRelation: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(person.name, person.relation, size = 88.dp)
        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .clickable(onClick = onRename),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(person.name, style = MaterialTheme.typography.headlineMedium)
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Rename",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RelationPill(person.relation, onClick = onRelation)
            val group = RelationGroup.of(person.relation)
            if (group != RelationGroup.UNKNOWN) {
                Text(
                    group.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = group.colors().fill,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        if (person.aliases.isNotEmpty()) {
            Text(
                "Also heard as ${person.aliases.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            buildString {
                append("Came up ${person.mentionCount}×")
                append(" · last ${relativeTime(person.lastInteractionAt)}")
                append(" · ${detail.connections.size} ${if (detail.connections.size == 1) "tie" else "ties"}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun TagChip(text: String, mine: Boolean, onRemove: () -> Unit) {
    GlassPanel(shape = androidx.compose.foundation.shape.CircleShape, strong = mine) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove $text", modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun ConnectionRow(connection: Connection, relation: String?, onOpen: () -> Unit, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(connection.otherName, relation, size = 36.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(connection.otherName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val labels = connection.allLabels
            Text(
                text = buildString {
                    if (labels.isNotEmpty()) append(labels.joinToString(", "))
                    if (connection.coMentions > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("named together ${connection.coMentions}×")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (labels.isNotEmpty()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit how they're connected", modifier = Modifier.size(18.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
    }
}

@Composable
private fun InteractionCard(interaction: Interaction, onForget: () -> Unit) {
    var expanded by rememberSaveable(interaction.id) { mutableStateOf(false) }
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            Modifier
                .clickable { expanded = !expanded }
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) dateTime(interaction.timestamp) else relativeTime(interaction.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onForget, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Forget this",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                if (expanded) interaction.rawText else interaction.summary ?: interaction.rawText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 10.dp, top = 2.dp),
            )
            if (interaction.tagsAdded.isNotEmpty()) {
                Text(
                    interaction.tagsAdded.joinToString(" · ") { tagText(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = 10.dp),
                )
            }
        }
    }
}
