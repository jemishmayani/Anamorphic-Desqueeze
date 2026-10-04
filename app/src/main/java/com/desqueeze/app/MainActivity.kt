package com.desqueeze.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = Settings(this); val luts = LutManager(this)
        // One state per process: an export running in the background is still here when you come back.
        val state = ExportController.state ?: AppState(settings, luts).also { ExportController.state = it }
        if (ExportController.exporter == null) ExportController.exporter = Exporter(applicationContext, settings, luts)
        this.settings = settings; this.state = state
        handleShare(intent)
        Diag.init(this)
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        if (crashFile.exists()) { state.crashLog = crashFile.readText(); crashFile.delete(); Diag.previousExit(this) }
        else state.crashLog = Diag.previousExit(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                crashFile.writeText(Diag.header(this) + "\n\nThread: ${t.name}\n\nLast export steps:\n" + Diag.lastSteps() +
                    "\n\n" + e.stackTraceToString().take(6000))
            } catch (_: Throwable) {}
            previous?.uncaughtException(t, e)
        }
        setContent {
            val dark = when (state.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; else -> isSystemInDarkTheme() }
            AppTheme(dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    state.crashLog?.let { log -> CrashDialog(log) { state.crashLog = null } }
                    BackHandler(state.screen != Screen.Main) {
                        state.screen = if (state.screen == Screen.Limits || state.screen == Screen.Luts) Screen.Settings else Screen.Main
                    }
                    AnimatedContent(state.screen, label = "nav", transitionSpec = {
                        val fwd = targetState.ordinal > initialState.ordinal
                        (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (fwd) it / 8 else -it / 8 }) togetherWith
                            fadeOut(tween(140))
                    }) { s ->
                        if (s == Screen.Main) MainScreen(this@MainActivity, state, settings, luts)
                        // Settings-type pages stay a comfortable reading width on tablets and in landscape.
                        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                                when (s) {
                                    Screen.Settings -> SettingsScreen(state, settings, luts)
                                    Screen.Limits -> LimitsScreen { state.screen = Screen.Settings }
                                    Screen.Luts -> LutLibraryScreen(state, luts) { state.screen = Screen.Settings }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private lateinit var settings: Settings
    private lateinit var state: AppState

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); setIntent(intent); handleShare(intent)
    }

    /** "Share to De-Squeeze": add the shared videos and jump to the clip list. */
    private fun handleShare(intent: android.content.Intent?) {
        if (intent == null || intent.getBooleanExtra("handled", false)) return
        val uris: List<Uri> = when (intent.action) {
            android.content.Intent.ACTION_SEND -> listOfNotNull(androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, Uri::class.java))
            android.content.Intent.ACTION_SEND_MULTIPLE -> androidx.core.content.IntentCompat.getParcelableArrayListExtra(intent, android.content.Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        intent.putExtra("handled", true)
        state.screen = Screen.Main; state.step = Step.Clips
        importClips(this, state, settings, uris, append = state.videos.isNotEmpty())
    }

    fun keepScreenOn(on: Boolean) =
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

/* ---------------------------------------------------------------- Main */

@Composable
fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); trailing?.invoke()
        }
        content()
    }

/** The app icon, drawn live: oval bokeh crossed by an anamorphic flare. */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF141A22))) {
        val w = size.width; val h = size.height
        drawLine(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF8CC4FF), Color.Transparent)),
            Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = h * 0.035f)
        drawOval(Color(0xFFE8EEF5), Offset(w * 0.24f, h * 0.36f), Size(w * 0.52f, h * 0.28f), style = Stroke(h * 0.06f))
        drawOval(Color.White, Offset(w * 0.44f, h * 0.475f), Size(w * 0.12f, h * 0.05f))
    }
}

@Composable
fun ExportingPlaceholder(ratio: Float) {
    Box(Modifier.fillMaxWidth().aspectRatio(maxOf(ratio, 16f / 9f)).clip(RoundedCornerShape(20.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FlareLine(Modifier.width(160.dp))
            Spacer(Modifier.height(12.dp))
            Text("Preview paused while exporting", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyPreview(onPick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(20.dp)).clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(width = 120.dp, height = 40.dp)) {
                drawLine(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, c.primary, Color.Transparent)),
                    Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3f)
                val ow = size.width * 0.56f
                drawOval(c.onSurface.copy(alpha = 0.85f), Offset((size.width - ow) / 2, 2f), Size(ow, size.height - 4f), style = Stroke(5f))
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onPick, shape = RoundedCornerShape(14.dp)) { Text("Choose video") }
            Spacer(Modifier.height(8.dp))
            Text("MP4 or MOV, HEVC or H.264. Pick several to batch.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewToggle(desqueezed: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(false to "Squeezed", true to "De-squeezed").forEachIndexed { i, (d, label) ->
            SegmentedButton(selected = desqueezed == d, onClick = { onChange(d) },
                shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MethodToggle(mode: ExportMode, enabled: Boolean, onChange: (ExportMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(ExportMode.LOSSLESS to "Lossless", ExportMode.REENCODE to "Re-encode").forEachIndexed { i, (m, label) ->
            SegmentedButton(selected = mode == m, onClick = { onChange(m) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
        }
    }
}

@Composable
fun CrashDialog(log: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onClose,
        title = { Text("The app closed unexpectedly") },
        text = { Column {
            Text("Copy this report and send it to get the problem fixed.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Text(log.take(1500), style = MaterialTheme.typography.bodySmall.merge(Mono),
                modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
        } },
        confirmButton = { TextButton(onClick = {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash report", log)); onClose()
        }) { Text("Copy report") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
}

fun specLine(v: VideoInfo) = buildString {
    append("${v.displayW}×${v.displayH}, ${v.codec} ${v.bitDepth}-bit")
    v.footage.chroma?.let { append(" $it") }
    if (v.fps > 0) append(", ${fmtFps(v.fps)}")
    lookLabel(v.footage.gamma)?.let { append(", $it") }
    v.footage.camera?.let { append(", $it") }
    if (!v.hasAudio) append(", no audio")
}

@Composable
fun SqueezeChips(st: AppState) {
    val presetMatch = PRESETS.any { kotlin.math.abs(it - st.squeeze) < 0.001f }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(PRESETS) { _, p ->
            val sel = !st.customSqueeze && kotlin.math.abs(p - st.squeeze) < 0.001f
            FilterChip(selected = sel, enabled = !st.busy,
                onClick = { st.squeeze = p; st.customSqueeze = false; st.squeezeChosen = true },
                label = { Text(fmtSqueeze(p), style = LocalTextStyle.current.merge(Mono)) },
                leadingIcon = if (sel) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null)
        }
        item {
            FilterChip(selected = st.customSqueeze || !presetMatch, enabled = !st.busy,
                onClick = { st.customSqueeze = true; st.squeezeChosen = true }, label = { Text("Custom") })
        }
    }
    AnimatedVisibility(st.customSqueeze || !presetMatch) {
        var text by remember(st.squeeze) { mutableStateOf("%.2f".format(st.squeeze)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            Slider(st.squeeze, { st.squeeze = Math.round(it * 100) / 100f; st.squeezeChosen = true },
                valueRange = 1f..3f, enabled = !st.busy, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(text, { t -> text = t; t.toFloatOrNull()?.takeIf { it in 1f..3f }?.let { st.squeeze = it; st.squeezeChosen = true } },
                Modifier.width(96.dp), singleLine = true, enabled = !st.busy, suffix = { Text("×") }, textStyle = LocalTextStyle.current.merge(Mono),
                shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
}

/** Starts exporting every clip in the background (keeps running with the screen off or in another app). */
fun startExport(ctx: android.content.Context, st: AppState, exporter: Exporter) = ExportController.start(ctx, st, exporter)

/** Reads clips (from the picker or "Share to De-Squeeze") and adds them to the list. */
fun importClips(ctx: android.content.Context, st: AppState, settings: Settings, uris: List<Uri>, append: Boolean) {
    if (uris.isEmpty()) return
    ExportController.scope.launch {
        st.status = "Reading clips…"
        val errs = mutableListOf<String>()
        val found = withContext(Dispatchers.IO) {
            uris.mapNotNull { u -> try { VideoProbe.probe(ctx, u) } catch (e: Exception) { errs += (e.message ?: "Couldn't read this file"); null } }
        }
        val known = if (append) st.videos.map { it.uri }.toSet() else emptySet()
        val fresh = found.filter { it.uri !in known }
        val before = if (append) st.videos.size else 0
        st.videos = (if (append) st.videos else emptyList()) + fresh
        if (fresh.isNotEmpty()) { st.selected = before.coerceAtMost(st.videos.size - 1); st.memory.positionMs = 0 }
        if (!st.busy) st.results = emptyList()
        // Keep the factor the user already chose; only fall back to the saved default if they haven't.
        if (!st.squeezeChosen) st.newClipSqueeze = settings.defaultSqueeze
        // New clips get the current factor; clips already in the list keep their own.
        found.forEach { st.clipSqueeze.putIfAbsent(st.keyOf(it), st.newClipSqueeze) }
        st.status = errs.joinToString("\n")
    }
}

@Composable
fun Results(lines: List<String>, folder: String) {
    val ok = lines.any { it.startsWith("✓") }
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lines.forEach { line ->
            val good = line.startsWith("✓")
            Surface(color = if (good) c.primaryContainer else c.errorContainer, shape = RoundedCornerShape(16.dp)) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = if (good) c.onPrimaryContainer else c.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
        }
        if (ok) Text("Saved to Movies/$folder. Your originals are untouched.", style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        else Text("Nothing was saved. Your originals are untouched.", style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/* ---------------------------------------------------------------- shared bits */

@Composable
fun PickerRow(label: String?, value: String, options: List<String>, enabled: Boolean = true, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
                .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp))
                .clickable(enabled = enabled) { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (label != null) Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = if (label != null) c.onSurfaceVariant else c.onSurface,
                modifier = if (label == null) Modifier.weight(1f) else Modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.KeyboardArrowDown, null, tint = c.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

fun displayName(ctx: android.content.Context, u: Uri): String =
    ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: "LUT"

@Composable
fun TopBar(title: String, onBack: () -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.statusBarsPadding().padding(top = 4.dp, end = 20.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) = Column {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun ToggleRow(label: String, detail: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
        .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp)).clickable { onChange(!value) }
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

/** Pick any squeeze factor from 1.00× to 3.00× with a slider or by typing it. */
@Composable
fun SqueezeDialog(initial: Float, onDismiss: () -> Unit, onDone: (Float) -> Unit) {
    var value by remember { mutableFloatStateOf(initial.coerceIn(1f, 3f)) }
    var text by remember { mutableStateOf("%.2f".format(value)) }
    val valid = text.replace(',', '.').toFloatOrNull()?.takeIf { it in 1f..3f }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default squeeze factor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Used for every new clip until you pick another factor.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(fmtSqueeze(value), style = MaterialTheme.typography.headlineMedium.merge(Mono), color = MaterialTheme.colorScheme.primary)
                Slider(value, { value = Math.round(it * 100) / 100f; text = "%.2f".format(value) }, valueRange = 1f..3f)
                OutlinedTextField(text, { t -> text = t; t.replace(',', '.').toFloatOrNull()?.takeIf { it in 1f..3f }?.let { value = it } },
                    singleLine = true, suffix = { Text("×") }, isError = valid == null, shape = RoundedCornerShape(12.dp),
                    supportingText = { Text(if (valid == null) "Enter a value from 1.00 to 3.00" else "1.00 to 3.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = LocalTextStyle.current.merge(Mono))
            }
        },
        confirmButton = { TextButton(onClick = { valid?.let { onDone(Math.round(it * 100) / 100f) } }, enabled = valid != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
