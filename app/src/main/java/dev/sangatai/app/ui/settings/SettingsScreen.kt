package dev.sangatai.app.ui.settings

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sangatai.app.R
import dev.sangatai.app.data.ai.AiTrace
import dev.sangatai.app.data.ai.CrmPrompts
import dev.sangatai.app.data.ai.CrmTuning
import dev.sangatai.app.data.repository.StoredFile
import dev.sangatai.app.data.repository.WipeScope
import dev.sangatai.app.tile.SangatTileService
import dev.sangatai.app.ui.capture.QuickShortcuts
import dev.sangatai.app.ui.common.ConfirmDialog
import dev.sangatai.app.ui.common.Section
import dev.sangatai.app.ui.common.SettingRow
import dev.sangatai.app.ui.common.dateTime
import dev.sangatai.app.ui.common.glassFieldColors
import dev.sangatai.app.ui.theme.AccentButton
import dev.sangatai.app.ui.theme.GlassBackdrop
import dev.sangatai.app.ui.theme.GlassIconButton
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val tuning by viewModel.tuning.collectAsStateWithLifecycle()
    val usePreviewModel by viewModel.usePreviewModel.collectAsStateWithLifecycle()
    val trace by viewModel.lastTrace.collectAsStateWithLifecycle()
    val legacyNotes by viewModel.legacyNotes.collectAsStateWithLifecycle()

    SettingsScreen(
        tuning = tuning,
        usePreviewModel = usePreviewModel,
        trace = trace,
        legacyNotes = legacyNotes,
        viewModel = viewModel,
        onBack = onBack,
    )
}

/**
 * Shortcuts, the model, every prompt and number the AI runs with, and the user's data: export,
 * what's stored, and delete. Edits are a draft until Save, or saved on the way out.
 */
@Composable
private fun SettingsScreen(
    tuning: CrmTuning,
    usePreviewModel: Boolean,
    trace: AiTrace?,
    legacyNotes: Int,
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Field key to the text being edited; a HashMap so it survives rotation.
    var edits by rememberSaveable { mutableStateOf(HashMap<String, String>()) }
    val draft = remember(tuning, edits) { applyEdits(tuning, edits) }
    val dirty = edits.isNotEmpty()
    val save = {
        if (dirty) viewModel.saveTuning(draft)
        edits = HashMap()
    }
    val leave = {
        save()
        onBack()
    }
    BackHandler(onBack = leave)

    fun edit(key: String, value: String) {
        edits = HashMap(edits).apply { put(key, value) }
    }

    fun shown(key: String, saved: String): String = edits[key] ?: saved

    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var inventory by remember { mutableStateOf<List<StoredFile>?>(null) }
    var showTrace by rememberSaveable { mutableStateOf(false) }

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = viewModel.export(uri)
                Toast.makeText(context, if (ok) "Exported" else "Couldn't write the file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(onClick = leave, strong = false) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                )
                AnimatedVisibility(visible = dirty, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    AccentButton(onClick = save, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                        Icon(Icons.Filled.Done, contentDescription = null)
                        Text("Save", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "data") {
                    Section("Your data") {
                        Column {
                            SettingRow(
                                title = "Export everything",
                                subtitle = "One JSON file: people, relations, what you said, ties and every setting. " +
                                    "Saved only where you choose.",
                                onClick = { exportFile.launch("sangatai-${LocalDate.now()}.json") },
                            )
                            SettingRow(
                                title = "What's stored on this phone",
                                subtitle = "Every file the app and its libraries keep",
                                onClick = { scope.launch { inventory = viewModel.inventory() } },
                            )
                            if (legacyNotes > 0) {
                                SettingRow(
                                    title = "Delete old notes ($legacyNotes)",
                                    subtitle = "Notes from before SangatAI mapped people. They're not shown anywhere; " +
                                        "export first to keep them.",
                                    onClick = { confirm = CONFIRM_NOTES },
                                    titleColor = MaterialTheme.colorScheme.error,
                                )
                            }
                            SettingRow(
                                title = "Delete my circle",
                                subtitle = "Every person, relation, tie and everything you said. Settings stay.",
                                onClick = { confirm = CONFIRM_GRAPH },
                                titleColor = MaterialTheme.colorScheme.error,
                            )
                            SettingRow(
                                title = "Delete everything",
                                subtitle = "All data, every setting and cache. Like a fresh install.",
                                onClick = { confirm = CONFIRM_ALL },
                                titleColor = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                item(key = "shortcuts") { ShortcutsSection() }

                item(key = "model") {
                    Section("Model") {
                        Column {
                            SettingRow(
                                title = "Try Gemini Nano preview builds first",
                                subtitle = "If one fails, the next installed build takes over. All on-device.",
                                onClick = { viewModel.setUsePreviewModel(!usePreviewModel) },
                                trailing = { Switch(checked = usePreviewModel, onCheckedChange = null) },
                            )
                            SettingRow(
                                title = "Last exchange with Gemini Nano",
                                subtitle = trace?.let { "${it.label} · ${dateTime(it.at)}" }
                                    ?: "Nothing yet. Kept in memory only, for tuning prompts.",
                                enabled = trace != null,
                                onClick = { showTrace = true },
                            )
                            SettingRow(
                                title = "Rebuild search index",
                                subtitle = "Re-embeds everyone's tags. Needed after changing tag fade or tags embedded.",
                                onClick = {
                                    viewModel.reembedAll { count ->
                                        Toast.makeText(context, "Re-embedded $count people", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                trailing = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            )
                        }
                    }
                }

                item(key = "prompts") {
                    Section("Prompts") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                "What Gemini Nano is told. What you said goes between <<< and >>>. The app reads " +
                                    "back the JSON keys shown in each prompt, so keep them when editing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PromptField(
                                label = "Finding people and relations",
                                value = shown(KEY_INGEST, tuning.ingestPrompt),
                                onValueChange = { edit(KEY_INGEST, it) },
                                onReset = { edit(KEY_INGEST, CrmPrompts.INGEST) },
                            )
                            PromptField(
                                label = "Who can help",
                                value = shown(KEY_ASK, tuning.askPrompt),
                                onValueChange = { edit(KEY_ASK, it) },
                                onReset = { edit(KEY_ASK, CrmPrompts.ASK) },
                            )
                        }
                    }
                }

                item(key = "numbers") {
                    Section("Numbers") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            NUMBER_FIELDS.forEach { field ->
                                NumberField(
                                    field = field,
                                    value = shown(field.key, field.format(tuning)),
                                    onValueChange = { edit(field.key, it) },
                                )
                            }
                        }
                    }
                }

                item(key = "reset") {
                    Section("Defaults") {
                        SettingRow(
                            title = "Reset prompts and numbers",
                            subtitle = "Puts every prompt and number back to the built-in values.",
                            onClick = {
                                edits = HashMap()
                                viewModel.resetTuning()
                            },
                            trailing = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        )
                    }
                }

                item(key = "bottom") { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }

    when (confirm) {
        CONFIRM_NOTES -> ConfirmDialog(
            title = "Delete old notes?",
            body = "$legacyNotes notes are deleted for good.",
            confirmText = "Delete",
            onConfirm = {
                confirm = null
                viewModel.deleteLegacyNotes()
            },
            onDismiss = { confirm = null },
        )
        CONFIRM_GRAPH -> ConfirmDialog(
            title = "Delete your circle?",
            body = "Every person, relation, tie and everything you said is deleted for good. This can't be undone.",
            confirmText = "Delete",
            onConfirm = {
                confirm = null
                viewModel.wipe(WipeScope.SOCIAL_GRAPH) { Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show() }
            },
            onDismiss = { confirm = null },
        )
        CONFIRM_ALL -> ConfirmDialog(
            title = "Delete everything?",
            body = "All data, every setting and cache are deleted for good. This can't be undone.",
            confirmText = "Delete everything",
            onConfirm = {
                confirm = null
                edits = HashMap()
                viewModel.wipe(WipeScope.EVERYTHING) { Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show() }
            },
            onDismiss = { confirm = null },
        )
    }

    inventory?.let { files -> InventoryDialog(files, onDismiss = { inventory = null }) }

    if (showTrace) {
        val shownTrace = trace
        if (shownTrace == null) {
            LaunchedEffect(Unit) { showTrace = false }
        } else {
            TraceDialog(shownTrace, onClear = {
                viewModel.clearTrace()
                showTrace = false
            }, onDismiss = { showTrace = false })
        }
    }
}

@Composable
private fun InventoryDialog(files: List<StoredFile>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stored on this phone") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "${files.size} files, ${Formatter.formatShortFileSize(context, files.sumOf { it.bytes })}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(files, key = { it.path }) { file ->
                    Column {
                        Text(file.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text(
                            "${Formatter.formatShortFileSize(context, file.bytes)} · ${file.owner}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TraceDialog(trace: AiTrace, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${trace.label}${if (trace.retry) " (retry)" else ""}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TraceBlock("Instruction", trace.instruction)
                TraceBlock("Sent", trace.userTurn)
                TraceBlock("Answer", trace.output ?: "(empty)")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onClear) { Text("Forget it") } },
    )
}

@Composable
private fun TraceBlock(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ShortcutsSection() {
    val context = LocalContext.current
    val canPin = remember(context) { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }
    val canAddTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    if (!canPin && !canAddTile) return

    Section("Shortcuts") {
        Column {
            if (canAddTile) {
                SettingRow(
                    title = "Add Quick Settings tile",
                    subtitle = "Swipe down and tell SangatAI about someone from anywhere",
                    onClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestAddTile(context) },
                )
            }
            if (canPin) {
                SettingRow(
                    title = "Add voice shortcut to home screen",
                    onClick = { QuickShortcuts.pinToHomeScreen(context, voice = true) },
                )
                SettingRow(
                    title = "Add type shortcut to home screen",
                    onClick = { QuickShortcuts.pinToHomeScreen(context, voice = false) },
                )
            }
        }
    }
}

@Composable
private fun PromptField(label: String, value: String, onValueChange: (String) -> Unit, onReset: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                text = "Reset",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 6,
            maxLines = 16,
            shape = RoundedCornerShape(16.dp),
            colors = glassFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .padding(top = 6.dp),
        )
    }
}

@Composable
private fun NumberField(field: NumberSpec, value: String, onValueChange: (String) -> Unit) {
    val valid = field.parse(value) != null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(field.label) },
        supportingText = { Text(if (valid) field.help else "Enter a number from ${field.rangeLabel()}") },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (field.decimal) KeyboardType.Decimal else KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        colors = glassFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One numeric knob: how to show it, check it, and write it back. Ranges match [CrmTuning.sanitized]. */
private class NumberSpec(
    val key: String,
    val label: String,
    val help: String,
    val range: ClosedFloatingPointRange<Float>,
    val read: (CrmTuning) -> Number,
    val write: (CrmTuning, Float) -> CrmTuning,
    val decimal: Boolean = false,
) {
    fun format(tuning: CrmTuning): String = read(tuning).toString()

    fun parse(text: String): Float? = text.trim().toFloatOrNull()
        ?.takeIf { it in range && (decimal || it % 1f == 0f) }

    fun rangeLabel(): String = if (decimal) {
        "${range.start}–${range.endInclusive}"
    } else {
        "${range.start.toInt()}–${range.endInclusive.toInt()}"
    }
}

private fun ints(
    key: String,
    label: String,
    help: String,
    range: IntRange,
    read: (CrmTuning) -> Int,
    write: (CrmTuning, Int) -> CrmTuning,
) = NumberSpec(key, label, help, range.first.toFloat()..range.last.toFloat(), read, { t, v -> write(t, v.toInt()) })

private fun decimals(
    key: String,
    label: String,
    help: String,
    range: ClosedFloatingPointRange<Float>,
    read: (CrmTuning) -> Float,
    write: (CrmTuning, Float) -> CrmTuning,
) = NumberSpec(key, label, help, range, read, write, decimal = true)

private val NUMBER_FIELDS = listOf(
    decimals("ingest_temperature", "Finding people: temperature", "Lower sticks to your words and the JSON shape.",
        0f..2f, { it.ingestTemperature }, { t, v -> t.copy(ingestTemperature = v) }),
    ints("ingest_top_k", "Finding people: top K", "How many likely words the model picks from at each step.",
        1..128, { it.ingestTopK }, { t, v -> t.copy(ingestTopK = v) }),
    ints("ingest_tokens", "Finding people: max output tokens", "Too low and long rants get cut off mid-JSON.",
        64..4_096, { it.ingestMaxOutputTokens }, { t, v -> t.copy(ingestMaxOutputTokens = v) }),
    decimals("ask_temperature", "Who can help: temperature", "Lower keeps reasons tied to the tags.",
        0f..2f, { it.askTemperature }, { t, v -> t.copy(askTemperature = v) }),
    ints("ask_top_k", "Who can help: top K", "How many likely words the model picks from at each step.",
        1..128, { it.askTopK }, { t, v -> t.copy(askTopK = v) }),
    ints("ask_tokens", "Who can help: max output tokens", "Room for the matches and their reasons.",
        64..4_096, { it.askMaxOutputTokens }, { t, v -> t.copy(askMaxOutputTokens = v) }),
    ints("attempts", "Attempts per model", "Tries before moving on to the other installed Nano builds.",
        1..5, { it.attempts }, { t, v -> t.copy(attempts = v) }),
    ints("max_rant", "Longest thing read (characters)", "Longer is cut. Too long and Gemini Nano refuses it.",
        100..32_000, { it.maxRantChars }, { t, v -> t.copy(maxRantChars = v) }),
    ints("max_query", "Longest question (characters)", "For Who can help.",
        20..4_000, { it.maxQueryChars }, { t, v -> t.copy(maxQueryChars = v) }),
    ints("tags_per_mention", "Tags per person per rant", "Extra tags the model invents are dropped.",
        1..20, { it.maxTagsPerMention }, { t, v -> t.copy(maxTagsPerMention = v) }),
    ints("tags_per_person", "Tags per person", "Over this, the weakest tags from rants go. Yours never do.",
        5..500, { it.maxTagsPerPerson }, { t, v -> t.copy(maxTagsPerPerson = v) }),
    decimals("half_life", "Tag fade (days)", "A tag counts half as much after this long unsaid. 0 never fades.",
        0f..3_650f, { it.tagHalfLifeDays }, { t, v -> t.copy(tagHalfLifeDays = v) }),
    ints("embed_tags", "Tags in search", "How many of someone's strongest tags Who can help searches.",
        1..100, { it.embedTags }, { t, v -> t.copy(embedTags = v) }),
    ints("pool", "Search pool", "Nearest people found before reranking.",
        1..500, { it.retrievalPool }, { t, v -> t.copy(retrievalPool = v) }),
    ints("shortlist", "Shortlist for Gemini Nano", "Candidates the model chooses from.",
        1..50, { it.shortlistSize }, { t, v -> t.copy(shortlistSize = v) }),
    decimals("lexical", "Exact word boost", "Added per tag word that's literally in the question.",
        0f..1f, { it.lexicalBoost }, { t, v -> t.copy(lexicalBoost = v) }),
    decimals("min_similarity", "Minimum similarity", "Candidates below this are dropped (−1 to 1).",
        -1f..1f, { it.minSimilarity }, { t, v -> t.copy(minSimilarity = v) }),
    ints("max_matches", "Matches shown", "Most people Who can help suggests.",
        1..10, { it.maxMatches }, { t, v -> t.copy(maxMatches = v) }),
    ints("candidate_tags", "Tags per candidate", "Tags shown to the model per person.",
        1..40, { it.candidateTags }, { t, v -> t.copy(candidateTags = v) }),
    ints("candidate_chars", "Candidate block (characters)", "Cap on everything sent about candidates.",
        200..16_000, { it.candidateBlockChars }, { t, v -> t.copy(candidateBlockChars = v) }),
    ints("clique", "Biggest group that makes ties", "Naming more people than this at once doesn't tie them all together.",
        2..100, { it.maxCoMentionClique }, { t, v -> t.copy(maxCoMentionClique = v) }),
)

private const val KEY_INGEST = "ingest_prompt"
private const val KEY_ASK = "ask_prompt"
private const val CONFIRM_NOTES = "notes"
private const val CONFIRM_GRAPH = "graph"
private const val CONFIRM_ALL = "all"

/** The saved tuning with every valid edit applied; invalid numbers keep their saved value. */
private fun applyEdits(saved: CrmTuning, edits: Map<String, String>): CrmTuning {
    var result = saved
    edits[KEY_INGEST]?.let { result = result.copy(ingestPromptOverride = it) }
    edits[KEY_ASK]?.let { result = result.copy(askPromptOverride = it) }
    NUMBER_FIELDS.forEach { field ->
        val value = edits[field.key]?.let(field::parse) ?: return@forEach
        result = field.write(result, value)
    }
    return result
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestAddTile(context: Context) {
    val statusBarManager = context.getSystemService(StatusBarManager::class.java) ?: return
    statusBarManager.requestAddTileService(
        ComponentName(context, SangatTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added. Swipe down to tell SangatAI about someone."
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The tile is already in Quick Settings"
            else -> "Tile wasn't added"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
