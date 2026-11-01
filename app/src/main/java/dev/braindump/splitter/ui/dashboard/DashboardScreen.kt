package dev.braindump.splitter.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.ui.capture.CaptureSheet
import dev.braindump.splitter.ui.theme.AccentButton
import dev.braindump.splitter.ui.theme.Glass
import dev.braindump.splitter.ui.theme.GlassBackdrop
import dev.braindump.splitter.ui.theme.GlassIconButton
import dev.braindump.splitter.ui.theme.GlassPanel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DashboardRoute(onOpenSettings: () -> Unit, viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onForeground()
    }

    DashboardScreen(
        state = state,
        onCapture = viewModel::capture,
        onRetry = viewModel::retry,
        onDelete = viewModel::delete,
        onCategoryChange = viewModel::setCategory,
        onRefine = viewModel::refine,
        onClearRefineError = viewModel::clearRefineError,
        persona = PersonaActions(
            onRefresh = viewModel::refreshPersona,
            onEnabledChange = viewModel::setPersonaEnabled,
            onForget = viewModel::forgetPersona,
        ),
        onOpenSettings = onOpenSettings,
    )
}

/** What the persona card can ask for. */
class PersonaActions(
    val onRefresh: () -> Unit,
    val onEnabledChange: (Boolean) -> Unit,
    val onForget: () -> Unit,
)

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onCapture: (String, android.graphics.Bitmap?) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCategoryChange: (Long, IdeaCategory) -> Unit,
    onRefine: (Long, String) -> Unit,
    onClearRefineError: () -> Unit,
    persona: PersonaActions,
    onOpenSettings: () -> Unit,
) {
    // Null while closed; otherwise whether the sheet starts dictating.
    var captureWithVoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // The idea whose refine chat is open.
    var refiningId by rememberSaveable { mutableStateOf<Long?>(null) }
    var savedMode by rememberSaveable { mutableStateOf(TimelineMode.DAYS) }
    var filter by rememberSaveable { mutableStateOf<IdeaCategory?>(null) }
    // Where to land after zooming in from a month or a year card.
    var monthTarget by remember { mutableStateOf<YearMonth?>(null) }
    var yearTarget by remember { mutableStateOf<Int?>(null) }

    val hasIdeas = state.sections.isNotEmpty()
    val mode = if (hasIdeas) savedMode else TimelineMode.DAYS
    val categories = remember(state.sections) { categoryCounts(state.sections) }
    // A filter whose last idea was deleted or recategorized would leave an empty screen.
    val activeFilter = filter?.takeIf { selected -> categories.any { it.first == selected } }
    val sections = remember(state.sections, activeFilter) { filterSections(state.sections, activeFilter) }
    val months = remember(sections) { summarizeMonths(sections) }
    val years = remember(months) { summarizeYears(months) }

    val listState = rememberLazyListState()
    val monthsState = rememberLazyGridState()
    val yearsState = rememberLazyListState()
    val fabExpanded by remember(mode) { derivedStateOf { mode != TimelineMode.DAYS || listState.firstVisibleItemIndex == 0 } }
    val scrolled by remember(mode) {
        derivedStateOf {
            when (mode) {
                TimelineMode.DAYS -> listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                TimelineMode.MONTHS -> monthsState.firstVisibleItemIndex > 0 || monthsState.firstVisibleItemScrollOffset > 0
                TimelineMode.YEARS -> yearsState.firstVisibleItemIndex > 0 || yearsState.firstVisibleItemScrollOffset > 0
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        Scaffold(
            containerColor = Color.Transparent,
            // A transparent container has no matching "on" color, so name it explicitly.
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
                TopBar(
                    aiStatus = state.aiStatus,
                    scrolled = scrolled,
                    onOpenSettings = onOpenSettings,
                ) {
                    if (hasIdeas) {
                        TimelineSwitcher(
                            mode = mode,
                            onModeChange = { savedMode = it },
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                        )
                        CategoryFilterRow(
                            categories = categories,
                            selected = activeFilter,
                            onSelect = { filter = it },
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            },
            floatingActionButton = {
                CaptureButtons(
                    expanded = fabExpanded,
                    onType = { captureWithVoice = false },
                    onVoice = { captureWithVoice = true },
                )
            },
        ) { innerPadding ->
            val bottomPadding = innerPadding.calculateBottomPadding() + 104.dp
            AnimatedContent(
                targetState = mode,
                transitionSpec = { zoomTransition() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                label = "timeline",
            ) { shown ->
                when (shown) {
                    TimelineMode.DAYS -> DaysList(
                        state = state,
                        sections = sections,
                        listState = listState,
                        scrollToMonth = monthTarget,
                        onScrollHandled = { monthTarget = null },
                        bottomPadding = bottomPadding,
                        onRetry = onRetry,
                        onDelete = onDelete,
                        onCategoryChange = onCategoryChange,
                        onOpenRefine = { refiningId = it },
                        persona = persona,
                    )

                    TimelineMode.MONTHS -> MonthsGrid(
                        months = months,
                        state = monthsState,
                        scrollToYear = yearTarget,
                        onScrollHandled = { yearTarget = null },
                        onOpenMonth = { month ->
                            monthTarget = month
                            savedMode = TimelineMode.DAYS
                        },
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
                    )

                    TimelineMode.YEARS -> YearsList(
                        years = years,
                        state = yearsState,
                        onOpenYear = { year ->
                            yearTarget = year
                            savedMode = TimelineMode.MONTHS
                        },
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomPadding),
                    )
                }
            }
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

    refiningId?.let { id ->
        val idea = remember(state.sections, id) {
            state.sections.firstNotNullOfOrNull { section -> section.ideas.firstOrNull { it.id == id } }
        }
        if (idea != null) {
            RefineSheet(
                idea = idea,
                state = state.refine,
                onSend = { ask -> onRefine(id, ask) },
                onClearError = onClearRefineError,
                onDismiss = { refiningId = null },
            )
        } else if (!state.isLoading) {
            // Deleted while the chat was open.
            LaunchedEffect(id) { refiningId = null }
        }
    }
}

/** The day-by-day list: today's view, and where month cards zoom into. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DaysList(
    state: DashboardUiState,
    sections: List<DaySection>,
    listState: LazyListState,
    scrollToMonth: YearMonth?,
    onScrollHandled: () -> Unit,
    bottomPadding: Dp,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCategoryChange: (Long, IdeaCategory) -> Unit,
    onOpenRefine: (Long) -> Unit,
    persona: PersonaActions,
) {
    val today = todayEpochDay()
    val aiStatus = state.aiStatus
    val showBanner = aiStatus is AiStatus.Unavailable || aiStatus is AiStatus.Downloading
    val showEmpty = !state.isLoading && state.sections.isEmpty()
    val showPersona = state.sections.isNotEmpty()

    LaunchedEffect(scrollToMonth) {
        val month = scrollToMonth ?: return@LaunchedEffect
        // Item order below: banner, empty state, persona, then a header plus its ideas per day.
        var index = (if (showBanner) 1 else 0) + (if (showEmpty) 1 else 0) + (if (showPersona) 1 else 0)
        for (section in sections) {
            if (YearMonth.from(LocalDate.ofEpochDay(section.epochDay)) == month) break
            index += 1 + section.ideas.size
        }
        listState.scrollToItem(index)
        onScrollHandled()
    }

    // Top padding goes on the list itself so sticky headers pin below the bar.
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding),
    ) {
        if (showBanner) {
            item(key = "ai-banner", contentType = "banner") {
                AiBanner(
                    aiStatus,
                    Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        if (showEmpty) {
            item(key = "empty", contentType = "empty") {
                EmptyState(Modifier.fillParentMaxHeight(0.7f))
            }
        }

        if (showPersona) {
            item(key = "persona", contentType = "persona") {
                // All ideas, not the filtered ones: the persona is about the whole person.
                PersonaCard(
                    state = state.persona,
                    sections = state.sections,
                    onRefresh = persona.onRefresh,
                    onEnabledChange = persona.onEnabledChange,
                    onForget = persona.onForget,
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        sections.forEach { section ->
            stickyHeader(key = "day-${section.epochDay}", contentType = "header") {
                DayHeader(epochDay = section.epochDay, today = today)
            }
            items(
                items = section.ideas,
                key = { it.id },
                contentType = { "idea" },
            ) { idea ->
                IdeaCard(
                    idea = idea,
                    onRetry = onRetry,
                    onDelete = onDelete,
                    onCategoryChange = onCategoryChange,
                    onRefine = onOpenRefine,
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Large title over the backdrop, with the timeline controls under it. It turns into a
 * frosted bar once content scrolls beneath it.
 */
@Composable
private fun TopBar(
    aiStatus: AiStatus,
    scrolled: Boolean,
    onOpenSettings: () -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val glass = Glass.colors
    val edge = MaterialTheme.colorScheme.outlineVariant
    val barAlpha by animateFloatAsState(if (scrolled) 1f else 0f, tween(250), label = "bar")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(glass.fillStrong, alpha = barAlpha)
                drawLine(
                    color = edge,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                    alpha = barAlpha * 0.6f,
                )
            }
            .statusBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                StatusChip(aiStatus, Modifier.padding(top = 6.dp))
            }
            GlassIconButton(onClick = onOpenSettings, strong = false) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }
        controls()
    }
}

@Composable
private fun StatusChip(status: AiStatus, modifier: Modifier = Modifier) {
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

@Composable
private fun CaptureButtons(expanded: Boolean, onType: () -> Unit, onVoice: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            onClick = onVoice,
            size = 56.dp,
            onClickLabel = stringResource(R.string.dashboard_voice),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = stringResource(R.string.dashboard_voice),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        AccentButton(
            onClick = onType,
            modifier = Modifier.height(56.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.dashboard_fab))
            AnimatedVisibility(
                visible = expanded,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_fab),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun aiStatusLabel(status: AiStatus): String = when (status) {
    AiStatus.Unknown -> stringResource(R.string.ai_status_unknown)
    AiStatus.Checking -> stringResource(R.string.ai_status_checking)
    is AiStatus.Downloading -> status.percent
        ?.let { stringResource(R.string.ai_status_downloading_percent, it) }
        ?: stringResource(R.string.ai_status_downloading)
    is AiStatus.Ready -> status.modelName
        ?.let { stringResource(R.string.ai_status_ready_model, it) }
        ?: stringResource(R.string.ai_status_ready)
    is AiStatus.Unavailable -> stringResource(R.string.ai_status_unavailable)
}

@Composable
private fun AiBanner(status: AiStatus, modifier: Modifier = Modifier) {
    when (status) {
        is AiStatus.Downloading -> GlassPanel(modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = aiStatusLabel(status),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                val progressModifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                val percent = status.percent
                if (percent != null) {
                    val progress by animateFloatAsState(percent / 100f, label = "download")
                    LinearProgressIndicator(progress = { progress }, modifier = progressModifier)
                } else {
                    LinearProgressIndicator(modifier = progressModifier)
                }
            }
        }

        is AiStatus.Unavailable -> GlassPanel(modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Column {
                    Text(stringResource(R.string.ai_status_unavailable), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = status.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        else -> Unit
    }
}

@Composable
private fun DayHeader(epochDay: Long, today: Long) {
    val label = when (epochDay) {
        today -> stringResource(R.string.day_today)
        today - 1 -> stringResource(R.string.day_yesterday)
        else -> remember(epochDay, today) {
            val date = LocalDate.ofEpochDay(epochDay)
            val pattern = if (date.year == LocalDate.ofEpochDay(today).year) "EEEE, MMM d" else "EEE, MMM d, yyyy"
            date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        GlassPanel(shape = CircleShape, strong = true) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val float = rememberInfiniteTransition(label = "float")
    val offset by float.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "offset",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GlassPanel(
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer { translationY = offset.dp.toPx() },
                shape = CircleShape,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_tile),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp),
                )
            }
            Text(
                text = stringResource(R.string.dashboard_empty_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = stringResource(R.string.dashboard_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
