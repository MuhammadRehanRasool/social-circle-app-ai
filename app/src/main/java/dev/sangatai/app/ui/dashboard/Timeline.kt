package dev.braindump.splitter.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.ui.theme.Glass
import dev.braindump.splitter.ui.theme.GlassPanel
import dev.braindump.splitter.ui.theme.rememberPressScale
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Zoom levels, finest first, like the Photos app. */
enum class TimelineMode(@StringRes val label: Int) {
    DAYS(R.string.timeline_days),
    MONTHS(R.string.timeline_months),
    YEARS(R.string.timeline_years),
}

@Immutable
data class MonthSummary(
    val month: YearMonth,
    val count: Int,
    /** Categories by how many ideas they hold, largest first. */
    val mix: List<Pair<IdeaCategory, Int>>,
    /** Newest few titles, for a glance at what the month was about. */
    val highlights: List<String>,
)

@Immutable
data class YearSummary(
    val year: Int,
    val count: Int,
    val mix: List<Pair<IdeaCategory, Int>>,
    /** Ideas per month, January first. */
    val perMonth: List<Int>,
)

/** Sections arrive newest first, so months and years come out newest first too. */
internal fun summarizeMonths(sections: List<DaySection>): List<MonthSummary> {
    val byMonth = LinkedHashMap<YearMonth, MutableList<Idea>>()
    for (section in sections) {
        byMonth.getOrPut(YearMonth.from(LocalDate.ofEpochDay(section.epochDay))) { ArrayList() } += section.ideas
    }
    return byMonth.map { (month, ideas) ->
        MonthSummary(
            month = month,
            count = ideas.size,
            mix = ideas.groupingBy { it.category }.eachCount().sortedMix(),
            highlights = ideas.take(MONTH_HIGHLIGHTS).map { it.displayTitle },
        )
    }
}

internal fun summarizeYears(months: List<MonthSummary>): List<YearSummary> =
    months.groupBy { it.month.year }.map { (year, inYear) ->
        val perMonth = IntArray(12)
        inYear.forEach { perMonth[it.month.monthValue - 1] = it.count }
        val mix = HashMap<IdeaCategory, Int>()
        inYear.forEach { month -> month.mix.forEach { (category, count) -> mix[category] = (mix[category] ?: 0) + count } }
        YearSummary(year, inYear.sumOf { it.count }, mix.sortedMix(), perMonth.toList())
    }

/** Every category that has ideas, with counts, for the filter chips. */
internal fun categoryCounts(sections: List<DaySection>): List<Pair<IdeaCategory, Int>> =
    sections.asSequence().flatMap { it.ideas }.groupingBy { it.category }.eachCount().sortedMix()

internal fun filterSections(sections: List<DaySection>, category: IdeaCategory?): List<DaySection> =
    if (category == null) {
        sections
    } else {
        sections.mapNotNull { section ->
            section.ideas.filter { it.category == category }.takeIf { it.isNotEmpty() }?.let { section.copy(ideas = it) }
        }
    }

private fun Map<IdeaCategory, Int>.sortedMix(): List<Pair<IdeaCategory, Int>> =
    entries.sortedByDescending { it.value }.map { it.key to it.value }

/** Moving to a finer level zooms in; moving to a coarser one zooms out. */
internal fun AnimatedContentTransitionScope<TimelineMode>.zoomTransition(): ContentTransform {
    val zoomingIn = targetState.ordinal < initialState.ordinal
    val spec = tween<Float>(ZOOM_MS, easing = FastOutSlowInEasing)
    return (fadeIn(spec) + scaleIn(spec, initialScale = if (zoomingIn) 0.92f else 1.08f)) togetherWith
        (fadeOut(tween(ZOOM_MS / 2)) + scaleOut(spec, targetScale = if (zoomingIn) 1.08f else 0.92f))
}

/** Photos-style segmented control with a sliding thumb. */
@Composable
internal fun TimelineSwitcher(mode: TimelineMode, onModeChange: (TimelineMode) -> Unit, modifier: Modifier = Modifier) {
    val thumbOffset by animateDpAsState(
        targetValue = SEGMENT_WIDTH * mode.ordinal,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "thumb",
    )
    GlassPanel(modifier, shape = CircleShape) {
        Box(Modifier.padding(3.dp)) {
            Box(
                Modifier
                    .offset(x = thumbOffset)
                    .size(SEGMENT_WIDTH, SEGMENT_HEIGHT)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Row {
                TimelineMode.entries.forEach { option ->
                    val selected = option == mode
                    val textColor by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        label = "segment",
                    )
                    Box(
                        modifier = Modifier
                            .size(SEGMENT_WIDTH, SEGMENT_HEIGHT)
                            .clip(CircleShape)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onModeChange(option) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(option.label), style = MaterialTheme.typography.labelLarge, color = textColor)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CategoryFilterRow(
    categories: List<Pair<IdeaCategory, Int>>,
    selected: IdeaCategory?,
    onSelect: (IdeaCategory?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(
                text = stringResource(R.string.filter_all),
                tint = MaterialTheme.colorScheme.primary,
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(categories, key = { it.first.name }) { (category, count) ->
            FilterChip(
                text = "${category.emoji} ${stringResource(category.label)} $count",
                tint = category.color,
                selected = selected == category,
                // Tapping the active chip again clears the filter.
                onClick = { onSelect(if (selected == category) null else category) },
            )
        }
    }
}

@Composable
private fun FilterChip(text: String, tint: Color, selected: Boolean, onClick: () -> Unit) {
    val glass = Glass.colors
    val background by animateColorAsState(if (selected) tint else glass.fill, label = "chip")
    val content by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
        label = "chipText",
    )
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(background)
            .then(if (selected) Modifier else Modifier.border(1.dp, glass.border, CircleShape))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Months as cards; tapping one zooms into its days. */
@Composable
internal fun MonthsGrid(
    months: List<MonthSummary>,
    state: LazyGridState,
    scrollToYear: Int?,
    onScrollHandled: () -> Unit,
    onOpenMonth: (YearMonth) -> Unit,
    contentPadding: PaddingValues,
) {
    LaunchedEffect(scrollToYear) {
        val year = scrollToYear ?: return@LaunchedEffect
        val index = months.indexOfFirst { it.month.year == year }
        if (index >= 0) state.scrollToItem(index)
        onScrollHandled()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(months, key = { it.month.toString() }) { summary ->
            MonthCard(summary, onClick = { onOpenMonth(summary.month) })
        }
    }
}

@Composable
private fun MonthCard(summary: MonthSummary, onClick: () -> Unit) {
    val locale = Locale.getDefault()
    TimelineCard(onClick) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = summary.month.month.getDisplayName(TextStyle.FULL, locale),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "${summary.month.year} · ${ideaCount(summary.count)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CategoryBar(summary.mix, Modifier.padding(vertical = 10.dp))
            summary.highlights.forEach { title ->
                Text(
                    text = "• $title",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Years as big cards with a month-by-month activity strip; tapping one zooms into its months. */
@Composable
internal fun YearsList(
    years: List<YearSummary>,
    state: LazyListState,
    onOpenYear: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(years, key = { it.year }) { summary ->
            YearCard(summary, onClick = { onOpenYear(summary.year) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YearCard(summary: YearSummary, onClick: () -> Unit) {
    TimelineCard(onClick) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(summary.year.toString(), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                Text(
                    text = ideaCount(summary.count),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MonthStrip(summary.perMonth, Modifier.padding(top = 14.dp))
            CategoryBar(summary.mix, Modifier.padding(top = 14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                summary.mix.forEach { (category, count) ->
                    Text(
                        text = "${category.emoji} ${stringResource(category.label)} $count",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(category.color.copy(alpha = 0.18f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** Twelve bars, one per month, that grow in when the card appears. */
@Composable
private fun MonthStrip(perMonth: List<Int>, modifier: Modifier = Modifier) {
    val locale = Locale.getDefault()
    val peak = (perMonth.maxOrNull() ?: 0).coerceAtLeast(1)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val grow by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "grow",
    )
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        perMonth.forEachIndexed { index, count ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(STRIP_HEIGHT)
                        .clip(CircleShape)
                        .background(track),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (count > 0) {
                        val fraction = (count.toFloat() / peak).coerceAtLeast(0.12f)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction)
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0.5f, 1f)
                                    scaleY = grow
                                }
                                .clip(CircleShape)
                                .background(primary),
                        )
                    }
                }
                Text(
                    text = Month.of(index + 1).getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A thin bar split by category share. */
@Composable
private fun CategoryBar(mix: List<Pair<IdeaCategory, Int>>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        mix.forEach { (category, count) ->
            Box(
                Modifier
                    .weight(count.toFloat())
                    .fillMaxHeight()
                    .background(category.color),
            )
        }
    }
}

@Composable
private fun TimelineCard(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.97f)
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        content()
    }
}

@Composable
private fun ideaCount(count: Int): String =
    LocalContext.current.resources.getQuantityString(R.plurals.timeline_ideas, count, count)

private val SEGMENT_WIDTH = 76.dp
private val SEGMENT_HEIGHT = 32.dp
private val STRIP_HEIGHT = 44.dp
private const val ZOOM_MS = 320
private const val MONTH_HIGHLIGHTS = 3
