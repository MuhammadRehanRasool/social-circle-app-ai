package dev.braindump.splitter.ui.dashboard

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.domain.model.IdeaStatus
import dev.braindump.splitter.domain.model.Persona
import dev.braindump.splitter.domain.model.PersonaAspect
import dev.braindump.splitter.ui.theme.Glass
import dev.braindump.splitter.ui.theme.GlassPanel
import dev.braindump.splitter.ui.theme.rememberPressScale
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/**
 * "Your persona" on Home: collapsed, one line about how the user writes; expanded, every
 * trait Gemini Nano learned, a few numbers counted on the device, and the persona controls.
 */
@Composable
internal fun PersonaCard(
    state: PersonaUiState,
    sections: List<DaySection>,
    onRefresh: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val stats = remember(sections) { dumpStats(sections, todayEpochDay()) }
    val persona = state.persona
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.98f)
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chevron",
    )
    val dumpsToGo = Persona.MIN_DUMPS - stats.longDumps
    val context = LocalContext.current

    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale()
                scaleY = scale()
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = stringResource(if (expanded) R.string.persona_collapse else R.string.persona_expand),
            ) { expanded = !expanded },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.building) {
                        CircularProgressIndicator(
                            strokeWidth = 3.dp,
                            strokeCap = StrokeCap.Round,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        Text("🪞", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 14.dp, end = 8.dp),
                ) {
                    Text(stringResource(R.string.persona_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = when {
                            state.building -> stringResource(R.string.persona_building)
                            persona != null -> persona.summary.ifBlank { persona.traits.values.take(2).joinToString(" · ") }
                            dumpsToGo > 0 -> context.resources.getQuantityString(R.plurals.persona_locked, dumpsToGo, dumpsToGo)
                            else -> stringResource(R.string.persona_not_learned)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevron },
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)) +
                    fadeIn(tween(durationMillis = 220, delayMillis = 60)),
                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = IntSize.VisibilityThreshold)) +
                    fadeOut(tween(durationMillis = 120)),
            ) {
                PersonaDetails(state, stats, canLearn = dumpsToGo <= 0, onRefresh, onEnabledChange, onForget)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaDetails(
    state: PersonaUiState,
    stats: DumpStats,
    canLearn: Boolean,
    onRefresh: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onForget: () -> Unit,
) {
    val persona = state.persona
    val context = LocalContext.current
    Column(Modifier.padding(top = 14.dp)) {
        if (persona != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(Glass.colors.inset)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PersonaAspect.entries.forEach { aspect ->
                    val value = persona.traits[aspect] ?: return@forEach
                    Row {
                        Text(
                            text = stringResource(aspect.label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .width(84.dp)
                                .padding(top = 2.dp),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (stats.total > 0) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatChip(context.resources.getQuantityString(R.plurals.persona_stat_dumps, stats.total, stats.total))
                if (stats.streakDays > 1) StatChip(stringResource(R.string.persona_stat_streak, stats.streakDays))
                stats.peak?.let { StatChip(stringResource(it.label)) }
                if (stats.avgWords > 0) StatChip(stringResource(R.string.persona_stat_words, stats.avgWords))
            }
        }

        if (stats.categoryMix.isNotEmpty()) CategoryMix(stats.categoryMix, Modifier.padding(top = 12.dp))

        if (persona != null) {
            Row(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .toggleable(value = state.enabled, role = Role.Switch, onValueChange = onEnabledChange)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.persona_use), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.persona_use_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = null)
            }
            val date = remember(persona.updatedAt) { DateFormat.getMediumDateFormat(context).format(Date(persona.updatedAt)) }
            Text(
                text = context.resources.getQuantityString(R.plurals.persona_learned, persona.basedOn, persona.basedOn, date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        state.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (canLearn && !state.building) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionPill(
                    Icons.Filled.Refresh,
                    stringResource(if (persona == null) R.string.persona_learn else R.string.persona_relearn),
                    MaterialTheme.colorScheme.primary,
                    onRefresh,
                )
                if (persona != null) {
                    ActionPill(Icons.Filled.Delete, stringResource(R.string.persona_forget), MaterialTheme.colorScheme.error, onForget)
                }
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(Glass.colors.inset)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** A bar split by category, then the top few named under it. */
@Composable
private fun CategoryMix(mix: List<Pair<IdeaCategory, Int>>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
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
        val top = mix.take(3).map { (category, _) -> "${category.emoji} ${stringResource(category.label)}" }
        Text(
            text = stringResource(R.string.persona_mix, top.joinToString(" · ")),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@get:StringRes
private val PersonaAspect.label: Int
    get() = when (this) {
        PersonaAspect.VOICE -> R.string.persona_voice
        PersonaAspect.TONE -> R.string.persona_tone
        PersonaAspect.MOOD -> R.string.persona_mood
        PersonaAspect.STRUCTURE -> R.string.persona_structure
        PersonaAspect.DETAIL -> R.string.persona_detail
        PersonaAspect.FOCUS -> R.string.persona_focus
    }

internal enum class DayPart(@StringRes val label: Int) {
    MORNING(R.string.persona_stat_morning),
    AFTERNOON(R.string.persona_stat_afternoon),
    EVENING(R.string.persona_stat_evening),
    NIGHT(R.string.persona_stat_night),
}

/** Numbers about the dumps, counted on the device; no model involved. */
internal data class DumpStats(
    val total: Int = 0,
    /** Dumps long enough to learn the persona from. */
    val longDumps: Int = 0,
    /** Written notes per category, biggest first. */
    val categoryMix: List<Pair<IdeaCategory, Int>> = emptyList(),
    /** Days in a row with a dump, ending today or yesterday. */
    val streakDays: Int = 0,
    val peak: DayPart? = null,
    val avgWords: Int = 0,
)

internal fun dumpStats(sections: List<DaySection>, today: Long): DumpStats {
    val ideas = sections.flatMap { it.ideas }
    if (ideas.isEmpty()) return DumpStats()

    // Sections are newest first, one per day.
    var streak = 0
    var expected = sections.first().epochDay.takeIf { it >= today - 1 }
    for (section in sections) {
        if (expected == null || section.epochDay != expected) break
        streak++
        expected = section.epochDay - 1
    }

    val zone = ZoneId.systemDefault()
    val peak = ideas
        .groupingBy { idea ->
            when (Instant.ofEpochMilli(idea.createdAt).atZone(zone).hour) {
                in 5..11 -> DayPart.MORNING
                in 12..16 -> DayPart.AFTERNOON
                in 17..21 -> DayPart.EVENING
                else -> DayPart.NIGHT
            }
        }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    val words = ideas.sumOf { idea -> idea.rawInput.split(WORD_GAP).count { it.isNotBlank() } }

    return DumpStats(
        total = ideas.size,
        longDumps = ideas.count { it.rawInput.trim().length >= Persona.MIN_DUMP_CHARS },
        categoryMix = ideas
            .filter { it.status == IdeaStatus.READY }
            .groupingBy { it.category }
            .eachCount()
            .toList()
            .sortedByDescending { it.second },
        streakDays = streak,
        peak = peak,
        avgWords = words / ideas.size,
    )
}

private val WORD_GAP = Regex("""\s+""")
