package dev.braindump.splitter.ui.settings

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.braindump.splitter.R
import dev.braindump.splitter.data.ai.AiSettings
import dev.braindump.splitter.data.ai.AiTuning
import dev.braindump.splitter.tile.BrainDumpTileService
import dev.braindump.splitter.ui.capture.QuickShortcuts
import dev.braindump.splitter.ui.dashboard.DaySection
import dev.braindump.splitter.ui.dashboard.exportFileName
import dev.braindump.splitter.ui.dashboard.exportMarkdown
import dev.braindump.splitter.ui.dashboard.saveExport
import dev.braindump.splitter.ui.dashboard.shareExport
import dev.braindump.splitter.ui.theme.AccentButton
import dev.braindump.splitter.ui.theme.Glass
import dev.braindump.splitter.ui.theme.GlassBackdrop
import dev.braindump.splitter.ui.theme.GlassIconButton
import dev.braindump.splitter.ui.theme.GlassPanel
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val usePreviewModel by viewModel.usePreviewModel.collectAsStateWithLifecycle()
    val tuning by viewModel.tuning.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()

    SettingsScreen(
        usePreviewModel = usePreviewModel,
        onUsePreviewModelChange = viewModel::setUsePreviewModel,
        tuning = tuning,
        onSaveTuning = viewModel::saveTuning,
        onResetTuning = viewModel::resetTuning,
        sections = sections,
        onBack = onBack,
    )
}

/**
 * Export, shortcuts, the model choice, and every prompt and limit the AI runs with.
 * Edits are kept as a draft and saved with the Save button, or on the way out.
 */
@Composable
fun SettingsScreen(
    usePreviewModel: Boolean,
    onUsePreviewModelChange: (Boolean) -> Unit,
    tuning: AiTuning,
    onSaveTuning: (AiTuning) -> Unit,
    onResetTuning: () -> Unit,
    sections: List<DaySection>,
    onBack: () -> Unit,
) {
    // Field key to the text being edited; a HashMap so it survives rotation.
    var edits by rememberSaveable { mutableStateOf(HashMap<String, String>()) }
    val draft = remember(tuning, edits) { applyEdits(tuning, edits) }
    val dirty = edits.isNotEmpty()
    val save = {
        if (dirty) onSaveTuning(draft)
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                )
                AnimatedVisibility(visible = dirty, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    AccentButton(onClick = save, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                        Icon(Icons.Filled.Done, contentDescription = null)
                        Text(
                            text = stringResource(R.string.settings_save),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "export") { ExportSection(sections) }
                item(key = "shortcuts") { ShortcutsSection() }

                item(key = "model") {
                    Section(R.string.settings_section_model) {
                        Column {
                            SettingRow(
                                title = stringResource(R.string.dashboard_use_preview),
                                subtitle = stringResource(R.string.settings_use_preview_body),
                                onClick = { onUsePreviewModelChange(!usePreviewModel) },
                                trailing = { Switch(checked = usePreviewModel, onCheckedChange = null) },
                            )
                            Text(
                                text = stringResource(R.string.settings_model_specs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            )
                        }
                    }
                }

                item(key = "prompts") {
                    Section(R.string.settings_section_prompts) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = stringResource(R.string.settings_prompts_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PromptField(
                                label = R.string.settings_note_prompt,
                                value = shown(KEY_NOTE_PROMPT, tuning.notePrompt),
                                onValueChange = { edit(KEY_NOTE_PROMPT, it) },
                                onReset = { edit(KEY_NOTE_PROMPT, AiTuning().notePrompt) },
                            )
                            PromptField(
                                label = R.string.settings_refine_prompt,
                                value = shown(KEY_REFINE_PROMPT, tuning.refinePrompt),
                                onValueChange = { edit(KEY_REFINE_PROMPT, it) },
                                onReset = { edit(KEY_REFINE_PROMPT, AiTuning().refinePrompt) },
                            )
                        }
                    }
                }

                item(key = "limits") {
                    Section(R.string.settings_section_limits) {
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
                    Section(R.string.settings_section_reset) {
                        SettingRow(
                            title = stringResource(R.string.settings_reset_all),
                            subtitle = stringResource(R.string.settings_reset_all_body),
                            onClick = {
                                edits = HashMap()
                                onResetTuning()
                            },
                            trailing = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        )
                    }
                }

                item(key = "bottom") { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }
}

@Composable
private fun ExportSection(sections: List<DaySection>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allSections by rememberUpdatedState(sections)
    val saveExportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = saveExport(context, uri, exportMarkdown(context, allSections))
                Toast.makeText(context, if (saved) R.string.export_saved else R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val hasIdeas = sections.isNotEmpty()

    Section(R.string.settings_section_export) {
        Column {
            SettingRow(
                title = stringResource(R.string.export_share),
                subtitle = if (hasIdeas) null else stringResource(R.string.settings_export_empty),
                enabled = hasIdeas,
                onClick = {
                    if (!shareExport(context, exportMarkdown(context, sections))) {
                        Toast.makeText(context, R.string.export_too_big, Toast.LENGTH_LONG).show()
                        saveExportFile.launch(exportFileName())
                    }
                },
                trailing = { Icon(Icons.Filled.Share, contentDescription = null) },
            )
            SettingRow(
                title = stringResource(R.string.export_save),
                enabled = hasIdeas,
                onClick = { saveExportFile.launch(exportFileName()) },
                trailing = { Icon(Icons.Filled.Done, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun ShortcutsSection() {
    val context = LocalContext.current
    val canPin = remember(context) { ShortcutManagerCompat.isRequestPinShortcutSupported(context) }
    val canAddTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    if (!canPin && !canAddTile) return

    Section(R.string.settings_section_shortcuts) {
        Column {
            if (canAddTile) {
                SettingRow(
                    title = stringResource(R.string.dashboard_add_tile),
                    onClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestAddTile(context) },
                )
            }
            if (canPin) {
                SettingRow(
                    title = stringResource(R.string.dashboard_pin_voice),
                    onClick = { QuickShortcuts.pinToHomeScreen(context, voice = true) },
                )
                SettingRow(
                    title = stringResource(R.string.dashboard_pin_type),
                    onClick = { QuickShortcuts.pinToHomeScreen(context, voice = false) },
                )
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            text = stringResource(title),
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
private fun SettingRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
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
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
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
private fun PromptField(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.settings_reset),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onReset)
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
            colors = fieldColors(),
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
        label = { Text(stringResource(field.label)) },
        supportingText = {
            Text(
                if (valid) {
                    stringResource(field.help)
                } else {
                    stringResource(R.string.settings_out_of_range, field.rangeLabel())
                }
            )
        },
        isError = !valid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (field.decimal) KeyboardType.Decimal else KeyboardType.Number),
        shape = RoundedCornerShape(16.dp),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun fieldColors() = Glass.colors.let { glass ->
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = glass.inset,
        unfocusedContainerColor = glass.inset,
        errorContainerColor = glass.inset,
        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

/** One numeric limit: how to show it, check it, and write it back. */
private class NumberSpec(
    val key: String,
    @StringRes val label: Int,
    @StringRes val help: Int,
    val range: ClosedFloatingPointRange<Float>,
    val read: (AiTuning) -> Number,
    val write: (AiTuning, Float) -> AiTuning,
    val decimal: Boolean = false,
) {
    fun format(tuning: AiTuning): String = read(tuning).toString()

    fun parse(text: String): Float? = text.trim().toFloatOrNull()
        ?.takeIf { it in range && (decimal || it % 1f == 0f) }

    fun rangeLabel(): String = if (decimal) {
        "${range.start}–${range.endInclusive}"
    } else {
        "${range.start.toInt()}–${range.endInclusive.toInt()}"
    }
}

private fun IntRange.asFloats() = first.toFloat()..last.toFloat()

private val NUMBER_FIELDS = listOf(
    NumberSpec("max_output_tokens", R.string.settings_output_tokens, R.string.settings_output_tokens_help,
        AiSettings.OUTPUT_TOKENS.asFloats(), { it.maxOutputTokens }, { t, v -> t.copy(maxOutputTokens = v.toInt()) }),
    NumberSpec("temperature", R.string.settings_temperature, R.string.settings_temperature_help,
        AiSettings.TEMPERATURE, { it.temperature }, { t, v -> t.copy(temperature = v) }, decimal = true),
    NumberSpec("top_k", R.string.settings_top_k, R.string.settings_top_k_help,
        AiSettings.TOP_K.asFloats(), { it.topK }, { t, v -> t.copy(topK = v.toInt()) }),
    NumberSpec("attempts", R.string.settings_attempts, R.string.settings_attempts_help,
        AiSettings.ATTEMPTS.asFloats(), { it.attempts }, { t, v -> t.copy(attempts = v.toInt()) }),
    NumberSpec("max_input_chars", R.string.settings_input_chars, R.string.settings_input_chars_help,
        AiSettings.INPUT_CHARS.asFloats(), { it.maxInputChars }, { t, v -> t.copy(maxInputChars = v.toInt()) }),
    NumberSpec("max_title_chars", R.string.settings_title_chars, R.string.settings_title_chars_help,
        AiSettings.TITLE_CHARS.asFloats(), { it.maxTitleChars }, { t, v -> t.copy(maxTitleChars = v.toInt()) }),
    NumberSpec("max_ask_chars", R.string.settings_ask_chars, R.string.settings_ask_chars_help,
        AiSettings.ASK_CHARS.asFloats(), { it.maxAskChars }, { t, v -> t.copy(maxAskChars = v.toInt()) }),
    NumberSpec("max_refine_dump_chars", R.string.settings_refine_dump_chars, R.string.settings_refine_dump_chars_help,
        AiSettings.ASK_CHARS.asFloats(), { it.maxRefineDumpChars }, { t, v -> t.copy(maxRefineDumpChars = v.toInt()) }),
)

private const val KEY_NOTE_PROMPT = "note_prompt"
private const val KEY_REFINE_PROMPT = "refine_prompt"

/** The saved tuning with every valid edit applied; invalid numbers keep their saved value. */
private fun applyEdits(saved: AiTuning, edits: Map<String, String>): AiTuning {
    var result = saved
    edits[KEY_NOTE_PROMPT]?.let { result = result.copy(notePrompt = it) }
    edits[KEY_REFINE_PROMPT]?.let { result = result.copy(refinePrompt = it) }
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
        ComponentName(context, BrainDumpTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.dashboard_tile_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.dashboard_tile_already_added
            else -> R.string.dashboard_tile_not_added
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
