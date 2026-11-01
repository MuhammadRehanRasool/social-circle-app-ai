package dev.braindump.splitter.ui.capture

import android.Manifest
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.braindump.splitter.R
import dev.braindump.splitter.ui.theme.AccentButton
import dev.braindump.splitter.ui.theme.Glass
import kotlinx.coroutines.launch

/**
 * One-screen capture: type or dictate, hit Split. Used by the dashboard buttons and by the
 * transparent [CaptureActivity] behind the Quick Settings tile, widget and launcher shortcuts.
 *
 * @param splitting shows the in-sheet progress state while Gemini Nano works.
 * @param hideOnSubmit close the sheet as soon as the idea is handed off.
 * @param close slides the sheet away, then calls [onDismiss].
 * @param overApps the sheet floats over another app (whose window is blurred behind it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(
    startWithVoice: Boolean,
    splitting: Boolean,
    hideOnSubmit: Boolean,
    onSubmit: (String, Bitmap?) -> Unit,
    onDismiss: () -> Unit,
    close: Boolean = false,
    overApps: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val transcriber = rememberVoiceTranscriber()
    val glass = Glass.colors
    var text by rememberSaveable { mutableStateOf("") }
    var voiceAutoStarted by rememberSaveable { mutableStateOf(false) }
    // Deliberately not saveable: the pic never leaves memory and dies with the sheet.
    var image by remember { mutableStateOf<Bitmap?>(null) }

    val toggleVoice = rememberVoiceToggle(transcriber) { spoken ->
        text = if (text.isBlank()) spoken else "${text.trimEnd()} $spoken"
    }
    val shownText: () -> String = { withLiveWords(text, transcriber) }
    val submit: () -> Unit = {
        // Splitting mid-dictation keeps the phrase still being spoken.
        val idea = shownText().trim()
        if (idea.isNotEmpty() || image != null) {
            if (transcriber.isListening) transcriber.release()
            onSubmit(idea, image)
            if (hideOnSubmit) {
                text = ""
                image = null
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
    }

    LaunchedEffect(close) {
        if (close) {
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = glass.fillStrong,
        // Translucent containers have no matching "on" color, so name it explicitly.
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        // Over another app, the window blur already sets the sheet apart.
        scrimColor = Color.Black.copy(alpha = if (overApps) 0.12f else 0.32f),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.capture_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                OnDeviceTag()
            }

            val listening = transcriber.isListening
            val shown = shownText()
            OutlinedTextField(
                value = shown,
                onValueChange = { text = it },
                readOnly = listening || splitting,
                placeholder = {
                    Text(stringResource(if (listening) R.string.capture_listening else R.string.capture_hint))
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                maxLines = 8,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = glass.inset,
                    unfocusedContainerColor = glass.inset,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 132.dp)
                    .focusRequester(focusRequester),
            )

            image?.let { pic ->
                Box {
                    Image(
                        bitmap = pic.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                    IconButton(
                        onClick = { image = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f)),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.capture_remove_image),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = splitting,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith
                        (fadeOut(tween(150)) + slideOutVertically { -it / 3 })
                },
                label = "controls",
            ) { isSplitting ->
                if (isSplitting) {
                    SplittingIndicator()
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MicButton(listening = listening, level = { transcriber.level }, onClick = toggleVoice)
                        if (!listening) AttachButton(onPicked = { image = it })
                        AnimatedVisibility(visible = listening, enter = fadeIn(), exit = fadeOut()) {
                            Text(
                                text = stringResource(R.string.capture_listening),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        AccentButton(onClick = submit, enabled = shown.isNotBlank() || image != null) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.capture_submit), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            if (startWithVoice && !voiceAutoStarted) {
                voiceAutoStarted = true
                toggleVoice()
            } else {
                // The field may not be attached on the very first frame of the sheet.
                runCatching { focusRequester.requestFocus() }
            }
        }
    }
}

/**
 * The mic tap: stops dictation, or starts it after asking for the microphone if needed. Each
 * finished phrase goes to [onFinal].
 */
@Composable
internal fun rememberVoiceToggle(transcriber: VoiceTranscriber, onFinal: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnFinal by rememberUpdatedState(onFinal)
    val showMessage: (String) -> Unit = { message ->
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val startListening: () -> Unit = {
        transcriber.start(onFinal = { currentOnFinal(it) }, onMessage = showMessage)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else showMessage(context.getString(R.string.voice_permission_denied))
    }
    return {
        when {
            transcriber.isListening -> transcriber.stop()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startListening()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** While dictating, the live words follow what's already committed. */
internal fun withLiveWords(text: String, transcriber: VoiceTranscriber): String {
    val partial = transcriber.partialText
    return if (transcriber.isListening && partial.isNotEmpty()) "${text.trimEnd()} $partial".trimStart() else text
}

@Composable
internal fun SheetHandle() {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 14.dp)
            .size(width = 36.dp, height = 5.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
    )
}

@Composable
internal fun OnDeviceTag() {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Glass.colors.inset)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = stringResource(R.string.capture_on_device),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Mic toggle. While listening, a ring ripples outward and a halo swells with the voice; both
 * are read in the draw phase so level changes never recompose the sheet.
 */
@Composable
internal fun MicButton(listening: Boolean, level: () -> Float, onClick: () -> Unit) {
    val glass = Glass.colors
    val primary = MaterialTheme.colorScheme.primary
    val container by animateColorAsState(if (listening) primary else glass.inset, label = "mic")
    Box(
        modifier = Modifier
            .size(64.dp)
            .then(if (listening) listeningHalo(primary, level) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(container)
                .border(1.dp, glass.border, CircleShape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = listening, label = "micIcon") { on ->
                if (on) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.capture_voice_stop),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.capture_voice),
                        tint = primary,
                    )
                }
            }
        }
    }
}

/** Only composed while listening, so the ripple stops ticking frames when the mic is off. */
@Composable
private fun listeningHalo(color: Color, level: () -> Float): Modifier {
    val ripple = rememberInfiniteTransition(label = "ripple")
    val wave by ripple.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "wave",
    )
    return Modifier.drawBehind {
        val base = 24.dp.toPx()
        drawCircle(color.copy(alpha = 0.28f * (1f - wave)), radius = base * (1f + wave * 0.9f))
        drawCircle(color.copy(alpha = 0.18f), radius = base * (1.1f + level() * 0.6f))
    }
}

@Composable
private fun SplittingIndicator() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.capture_splitting),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
        )
    }
}

/** Plus-a-pic: snap one (in-memory preview, no file) or pull one from the gallery, downscaled. */
@Composable
private fun AttachButton(onPicked: (Bitmap) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let(onPicked)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = maxOf(info.size.width, info.size.height) / MAX_IMAGE_SIDE
                if (scale > 1) decoder.setTargetSampleSize(scale)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.onSuccess(onPicked).onFailure {
            Toast.makeText(context, R.string.capture_image_failed, Toast.LENGTH_SHORT).show()
        }
    }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.capture_attach),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.capture_camera)) },
                onClick = {
                    menu = false
                    camera.launch(null)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.capture_gallery)) },
                onClick = {
                    menu = false
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
    }
}

private const val MAX_IMAGE_SIDE = 1024
