package dev.sangatai.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sangatai.app.R
import dev.sangatai.app.domain.model.AiStatus
import dev.sangatai.app.domain.model.IngestOutcome
import dev.sangatai.app.domain.model.Person
import dev.sangatai.app.domain.model.SocialGraph
import dev.sangatai.app.ui.capture.CaptureSheet
import dev.sangatai.app.ui.common.Avatar
import dev.sangatai.app.ui.common.Pill
import dev.sangatai.app.ui.common.RelationGroup
import dev.sangatai.app.ui.common.RelationPill
import dev.sangatai.app.ui.common.StatusChip
import dev.sangatai.app.ui.common.TextInputDialog
import dev.sangatai.app.ui.common.colors
import dev.sangatai.app.ui.common.relativeTime
import dev.sangatai.app.ui.common.tagText
import dev.sangatai.app.ui.graph.NetworkGraph
import dev.sangatai.app.ui.theme.AccentButton
import dev.sangatai.app.ui.theme.GlassBackdrop
import dev.sangatai.app.ui.theme.GlassIconButton
import dev.sangatai.app.ui.theme.GlassPanel
import kotlinx.coroutines.delay

enum class HomeTab(val label: String) { CIRCLE("Circle"), PEOPLE("People"), SAID("What you said") }

@Composable
fun HomeRoute(
    onOpenPerson: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val ask by viewModel.ask.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onForeground()
    }

    HomeScreen(
        state = state,
        result = result,
        ask = ask,
        onCapture = viewModel::capture,
        onDismissResult = viewModel::dismissResult,
        onAsk = viewModel::ask,
        onClearAsk = viewModel::clearAsk,
        onRetryCapture = viewModel::retryCapture,
        onDeleteCapture = viewModel::deleteCapture,
        onAddPerson = { name, relation -> viewModel.addPerson(name, relation, onOpenPerson) },
        onOpenPerson = onOpenPerson,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    result: CaptureResult?,
    ask: AskState,
    onCapture: (String) -> Unit,
    onDismissResult: () -> Unit,
    onAsk: (String) -> Unit,
    onClearAsk: () -> Unit,
    onRetryCapture: (Long) -> Unit,
    onDeleteCapture: (Long) -> Unit,
    onAddPerson: (String, String) -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.CIRCLE) }
    // Null while closed; otherwise whether the sheet starts dictating.
    var captureWithVoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var asking by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var hidden by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var recenter by remember { mutableIntStateOf(0) }

    val graph = state.graph
    val visibleGroups = remember(hidden) { RelationGroup.entries.filterNot { it.name in hidden }.toSet() }
    val selected = remember(graph, selectedId) { selectedId?.let { id -> graph.people.firstOrNull { it.id == id } } }
    // Someone deleted, or hidden by a filter, can't stay selected.
    LaunchedEffect(selected, visibleGroups) {
        if (selectedId != null && (selected == null || RelationGroup.of(selected.relation) !in visibleGroups)) {
            if (!state.loading) selectedId = null
        }
    }
    // A finished result stays a while, then gets out of the way.
    LaunchedEffect(result) {
        if (result is CaptureResult.Done) {
            delay(RESULT_SHOWN_MS)
            onDismissResult()
        }
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            TopBar(
                people = graph.people.size,
                ties = graph.edges.size,
                aiStatus = state.aiStatus,
                onAsk = { asking = true },
                onOpenSettings = onOpenSettings,
            )
            TabSwitcher(tab = tab, onTab = { tab = it }, modifier = Modifier.padding(horizontal = 16.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "tab",
                ) { shown ->
                    when (shown) {
                        HomeTab.CIRCLE -> CircleTab(
                            graph = graph,
                            loading = state.loading,
                            selectedId = selectedId,
                            hidden = hidden,
                            visibleGroups = visibleGroups,
                            recenter = recenter,
                            onSelect = { selectedId = it },
                            onOpen = onOpenPerson,
                            onToggleGroup = { group ->
                                hidden = if (group.name in hidden) hidden - group.name else hidden + group.name
                            },
                            onRecenter = { recenter++ },
                        )
                        HomeTab.PEOPLE -> PeopleList(
                            people = graph.people,
                            loading = state.loading,
                            onOpen = onOpenPerson,
                            onAdd = { adding = true },
                        )
                        HomeTab.SAID -> HistoryList(
                            captures = state.captures,
                            onRetry = onRetryCapture,
                            onDelete = onDeleteCapture,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = result != null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                // Keeps showing the last result while it animates away.
                val shown = remember { mutableStateOf<CaptureResult?>(null) }
                if (result != null) shown.value = result
                shown.value?.let { ResultCard(it, onOpenPerson = onOpenPerson, onDismiss = onDismissResult) }
            }
            AnimatedVisibility(
                visible = tab == HomeTab.CIRCLE && selected != null && result == null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                val shown = remember { mutableStateOf<Person?>(null) }
                if (selected != null) shown.value = selected
                shown.value?.let { person ->
                    SelectedCard(
                        person = person,
                        ties = graph.edges.count { it.a == person.id || it.b == person.id },
                        onOpen = { onOpenPerson(person.id) },
                        onClose = { selectedId = null },
                    )
                }
            }
            CaptureButtons(
                onType = { captureWithVoice = false },
                onVoice = { captureWithVoice = true },
                modifier = Modifier.align(Alignment.End),
            )
        }
    }

    captureWithVoice?.let { voice ->
        CaptureSheet(
            startWithVoice = voice,
            splitting = false,
            hideOnSubmit = true,
            onSubmit = onCapture,
            onDismiss = { captureWithVoice = null },
        )
    }

    if (asking) {
        AskSheet(
            state = ask,
            onAsk = onAsk,
            onOpenPerson = { id ->
                asking = false
                onOpenPerson(id)
            },
            onDismiss = {
                asking = false
                onClearAsk()
            },
        )
    }

    if (adding) {
        AddPersonDialog(
            onAdd = { name, relation ->
                adding = false
                onAddPerson(name, relation)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun TopBar(
    people: Int,
    ties: Int,
    aiStatus: AiStatus,
    onAsk: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("SangatAI", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = when (people) {
                    0 -> "Your circle, mapped from what you say"
                    1 -> "1 person · $ties ${if (ties == 1) "tie" else "ties"}"
                    else -> "$people people · $ties ${if (ties == 1) "tie" else "ties"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusChip(aiStatus, Modifier.padding(top = 6.dp))
        }
        GlassIconButton(onClick = onAsk, strong = false, onClickLabel = "Who can help?") {
            Icon(Icons.Filled.Search, contentDescription = "Who can help?")
        }
        Spacer(Modifier.width(8.dp))
        GlassIconButton(onClick = onOpenSettings, strong = false) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings")
        }
    }
}

@Composable
private fun TabSwitcher(tab: HomeTab, onTab: (HomeTab) -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier, shape = CircleShape) {
        Row(Modifier.padding(4.dp)) {
            HomeTab.entries.forEach { entry ->
                val on = entry == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .then(if (on) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier)
                        .clickable(role = Role.Tab) { onTab(entry) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CircleTab(
    graph: SocialGraph,
    loading: Boolean,
    selectedId: Long?,
    hidden: List<String>,
    visibleGroups: Set<RelationGroup>,
    recenter: Int,
    onSelect: (Long?) -> Unit,
    onOpen: (Long) -> Unit,
    onToggleGroup: (RelationGroup) -> Unit,
    onRecenter: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        NetworkGraph(
            graph = graph,
            selectedId = selectedId,
            visibleGroups = visibleGroups,
            onSelect = onSelect,
            onOpen = onOpen,
            recenter = recenter,
        )
        if (graph.people.isEmpty() && !loading) {
            EmptyCircle(Modifier.align(Alignment.Center))
        } else if (graph.people.isNotEmpty()) {
            val counts = remember(graph) { graph.people.groupingBy { RelationGroup.of(it.relation) }.eachCount() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RelationGroup.entries.forEach { group ->
                    val count = counts[group] ?: 0
                    if (count > 0) {
                        GroupChip(group, count, on = group.name !in hidden, onClick = { onToggleGroup(group) })
                    }
                }
            }
            GlassIconButton(
                onClick = onRecenter,
                strong = false,
                onClickLabel = "Recenter",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 52.dp, end = 16.dp),
            ) {
                Icon(Icons.Filled.Home, contentDescription = "Recenter", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun GroupChip(group: RelationGroup, count: Int, on: Boolean, onClick: () -> Unit) {
    val colors = group.colors()
    GlassPanel(shape = CircleShape, strong = true) {
        Row(
            modifier = Modifier
                .clickable(role = Role.Checkbox, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (on) colors.fill else colors.fill.copy(alpha = 0.25f)),
            )
            Text(
                text = "${group.label} $count",
                style = MaterialTheme.typography.labelMedium,
                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun EmptyCircle(modifier: Modifier = Modifier) {
    GlassPanel(modifier.padding(32.dp)) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your circle starts here", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Tap the mic and talk about your day, like:\n" +
                    "“Had chai with my cousin Bilal, his wife Hina just started at Sara's company.”\n\n" +
                    "Gemini Nano picks out the people, who they are to you, and how they know each other. " +
                    "Everything stays on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SelectedCard(person: Person, ties: Int, onOpen: () -> Unit, onClose: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), strong = true) {
        Row(
            modifier = Modifier
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(person.name, person.relation, size = 44.dp)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        person.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    RelationPill(person.relation, Modifier.padding(start = 8.dp))
                }
                Text(
                    text = buildString {
                        append("Came up ${person.mentionCount}×")
                        append(" · last ${relativeTime(person.lastInteractionAt)}")
                        if (ties > 0) append(" · $ties ${if (ties == 1) "tie" else "ties"}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val tags = person.tags.take(3)
                if (tags.isNotEmpty()) {
                    Text(
                        tags.joinToString(" · ") { tagText(it.tag) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }
    }
}

@Composable
private fun ResultCard(result: CaptureResult, onOpenPerson: (Long) -> Unit, onDismiss: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth(), strong = true) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            when (result) {
                is CaptureResult.Reading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp, end = 12.dp),
                    ) {
                        Text("Finding the people in it…", style = MaterialTheme.typography.titleSmall)
                        Text(
                            result.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                is CaptureResult.Done -> Row(verticalAlignment = Alignment.Top) {
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(end = 4.dp),
                    ) {
                        OutcomeBody(result.outcome, onOpenPerson)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OutcomeBody(outcome: IngestOutcome, onOpenPerson: (Long) -> Unit) {
    when (outcome) {
        is IngestOutcome.Saved -> {
            if (outcome.people.isEmpty()) {
                Text("Nobody mentioned", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Saved anyway. Name someone and they'll show up in your circle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return
            }
            val newCount = outcome.people.count { it.isNew }
            Text(
                text = buildString {
                    append(if (outcome.people.size == 1) "1 person" else "${outcome.people.size} people")
                    if (newCount > 0) append(" · $newCount new")
                },
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                outcome.people.forEach { person ->
                    val colors = RelationGroup.of(person.relation).colors()
                    Pill(
                        text = buildString {
                            append(person.name)
                            person.relation?.let { append(" · $it") }
                            if (person.isNew) append(" ✦")
                        },
                        color = colors.fill,
                        container = colors.fill.copy(alpha = 0.14f),
                        onClick = { onOpenPerson(person.id) },
                    )
                }
            }
            outcome.links.take(4).forEach { link ->
                Text(
                    "${link.a} ↔ ${link.b}: ${link.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        is IngestOutcome.Queued -> {
            Text("Saved, waiting for Gemini Nano", style = MaterialTheme.typography.titleSmall)
            Text(
                outcome.reason ?: "It'll be read the next time the model is ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is IngestOutcome.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Column(Modifier.padding(start = 10.dp)) {
                Text("Saved, but couldn't read it", style = MaterialTheme.typography.titleSmall)
                Text(
                    (outcome.reason ?: "Gemini Nano failed") + ". Retry it under What you said.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IngestOutcome.Empty -> Text("Nothing to save", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun CaptureButtons(onType: () -> Unit, onVoice: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(onClick = onType, size = 56.dp, onClickLabel = "Type") {
            Icon(Icons.Filled.Add, contentDescription = "Type", tint = MaterialTheme.colorScheme.primary)
        }
        AccentButton(
            onClick = onVoice,
            modifier = Modifier.height(56.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null)
            Text(
                "Tell",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun AddPersonDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var relation by rememberSaveable { mutableStateOf("") }
    TextInputDialog(
        title = "Add someone",
        initial = "",
        label = "Name",
        confirmText = "Add",
        onConfirm = { name -> onAdd(name, relation.trim()) },
        onDismiss = onDismiss,
        extraField = {
            androidx.compose.material3.OutlinedTextField(
                value = relation,
                onValueChange = { relation = it },
                label = { Text("Who are they to you? (optional)") },
                placeholder = { Text("cousin, boss, gym buddy…") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

private const val RESULT_SHOWN_MS = 9_000L
