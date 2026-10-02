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
        val state = AppState(settings, luts)
        setContent {
            val dark = when (state.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; else -> isSystemInDarkTheme() }
            AppTheme(dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BackHandler(state.screen != Screen.Main) {
                        state.screen = if (state.screen == Screen.Limits) Screen.Settings else Screen.Main
                    }
                    AnimatedContent(state.screen, label = "nav", transitionSpec = {
                        val fwd = targetState.ordinal > initialState.ordinal
                        (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { if (fwd) it / 8 else -it / 8 }) togetherWith
                            fadeOut(tween(140))
                    }) { s ->
                        when (s) {
                            Screen.Main -> MainScreen(this@MainActivity, state, settings, luts)
                            Screen.Settings -> SettingsScreen(state, settings, luts)
                            Screen.Limits -> LimitsScreen { state.screen = Screen.Settings }
                        }
                    }
                }
            }
        }
    }
    fun keepScreenOn(on: Boolean) =
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

/* ---------------------------------------------------------------- Main */

@OptIn(UnstableApi::class)
@Composable
fun MainScreen(act: MainActivity, st: AppState, settings: Settings, luts: LutManager) {
    val ctx = LocalContext.current
    val exporter = remember { Exporter(ctx, settings, luts) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        act.lifecycleScope.launch {
            st.status = "Reading clip…"
            val errs = mutableListOf<String>()
            val found = withContext(Dispatchers.IO) { uris.mapNotNull { u ->
                try { VideoProbe.probe(ctx, u) } catch (e: Exception) { errs += (e.message ?: "Couldn't read this file"); null } } }
            st.videos = found; st.selected = 0; st.results = emptyList()
            // Keep the factor the user already chose; only fall back to the saved default if they haven't.
            if (!st.squeezeChosen) st.squeeze = settings.defaultSqueeze
            st.status = errs.joinToString("\n")
        }
    }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) try { st.lutId = luts.import(u, displayName(ctx, u)).id; st.lutList = luts.list() }
        catch (e: Exception) { st.status = "This LUT couldn't be loaded: ${e.message}" }
    }
    val pick = { picker.launch(arrayOf("video/*")) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { ExportBar(act, st, exporter, settings) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Anamorphic De-Squeeze", style = MaterialTheme.typography.headlineSmall)
                        Text("Restore the full width of your anamorphic footage",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { st.screen = Screen.Settings }, enabled = !st.busy) { Icon(Icons.Default.Settings, "Settings") }
                }
                Spacer(Modifier.height(12.dp)); FlareLine()
            }

            val v = st.videos.getOrNull(st.selected)
            if (v == null) EmptyPreview(pick) else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Preview(v, if (st.desqueezed) st.squeeze else 1f)
                    ViewToggle(st.desqueezed) { st.desqueezed = it }
                    ClipInfo(st, v, enabled = !st.busy, onChange = pick)
                }
            }

            Section("Squeeze factor", trailing = { Text(fmtSqueeze(st.squeeze), style = MaterialTheme.typography.titleLarge.merge(Mono),
                color = MaterialTheme.colorScheme.primary) }) {
                SqueezeChips(st)
                if (v != null) {
                    val mime = if (settings.codec == Codec.HEVC) "video/hevc" else "video/avc"
                    val (w, h, note) = remember(v, st.squeeze, settings.codec) { exporter.targetSize(v, st.squeeze, mime) }
                    Spacer(Modifier.height(4.dp))
                    Text("${v.displayW} × ${v.displayH}  becomes  $w × $h", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                    Text(note ?: "Full frame kept, nothing cropped.", style = MaterialTheme.typography.bodySmall,
                        color = if (note != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Section("Color") {
                PickerRow(null, st.lutList.firstOrNull { it.id == st.lutId }?.name ?: "No LUT",
                    listOf("No LUT") + st.lutList.map { it.name } + "Import a .cube file…", enabled = !st.busy) { i ->
                    when { i == 0 -> st.lutId = null; i <= st.lutList.size -> st.lutId = st.lutList[i - 1].id; else -> lutPicker.launch(arrayOf("*/*")) }
                }
                AnimatedVisibility(st.lutId != null) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Row { Text("Strength", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("${(st.strength * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium.merge(Mono)) }
                        Slider(st.strength, { st.strength = it }, enabled = !st.busy)
                        Text("The LUT is applied when you export; the preview shows framing only.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (st.status.isNotEmpty() && !st.busy)
                Text(st.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            AnimatedVisibility(st.results.isNotEmpty()) { Results(st.results, settings.folder) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun Section(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); trailing?.invoke()
        }
        content()
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

/** Hardware playback, stretched on screen. The animated width change shows exactly what de-squeezing does. */
@OptIn(UnstableApi::class)
@Composable
fun Preview(v: VideoInfo, factor: Float) {
    val ctx = LocalContext.current
    val player = remember(v.uri) { ExoPlayer.Builder(ctx).build().apply {
        setMediaItem(MediaItem.fromUri(v.uri)); repeatMode = ExoPlayer.REPEAT_MODE_ALL; volume = 0f; prepare(); play() } }
    DisposableEffect(player) { onDispose { player.release() } }
    val target = v.displayW * factor / v.displayH
    val ratio by animateFloatAsState(target, tween(380), label = "ratio")
    Box(Modifier.fillMaxWidth().aspectRatio(maxOf(ratio, 16f / 9f)).clip(RoundedCornerShape(20.dp)).background(Color.Black),
        contentAlignment = Alignment.Center) {
        val wide = ratio >= 16f / 9f
        AndroidView({ PlayerView(it).apply {
            this.player = player; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; useController = true; controllerShowTimeoutMs = 1500 } },
            if (wide) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true))
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

@Composable
fun ClipInfo(st: AppState, v: VideoInfo, enabled: Boolean, onChange: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(v.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(specLine(v), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onChange, enabled = enabled) { Text(if (st.videos.size > 1) "Change clips" else "Change") }
        }
        if (st.videos.size > 1) {
            Text("${st.videos.size} clips. The same settings apply to all.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(st.videos) { i, clip ->
                    FilterChip(selected = i == st.selected, onClick = { st.selected = i },
                        label = { Text(clip.name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)) })
                }
            }
        }
    }
}

fun specLine(v: VideoInfo) = buildString {
    append("${v.displayW}×${v.displayH}, ${v.codec} ${v.bitDepth}-bit, ${"%.2f".format(v.fps).trimEnd('0').trimEnd('.')} fps")
    if (v.colorInfo.isNotEmpty()) append(", ${v.colorInfo}")
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

@Composable
fun ExportBar(act: MainActivity, st: AppState, exporter: Exporter, settings: Settings) {
    val p by animateFloatAsState(st.progress, tween(300), label = "progress")
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp)) {
            AnimatedContent(st.busy, label = "bar") { busy ->
                if (busy) Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(st.status, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { st.job?.cancel(); st.busy = false; st.status = "Export cancelled."; act.keepScreenOn(false) },
                        Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Cancel export") }
                } else Button(
                    onClick = { startExport(act, st, exporter) }, enabled = st.videos.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                ) { Text(if (st.videos.size > 1) "Export ${st.videos.size} de-squeezed videos" else "Export de-squeezed video",
                    style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

fun startExport(act: MainActivity, st: AppState, exporter: Exporter) {
    val list = st.videos; act.keepScreenOn(true); st.busy = true; st.progress = 0f; st.results = emptyList(); st.status = ""
    st.job = act.lifecycleScope.launch {
        val log = mutableListOf<String>()
        list.forEachIndexed { i, vid ->
            st.status = if (list.size > 1) "Exporting ${i + 1} of ${list.size}" else "Exporting ${vid.name}"
            try {
                val r = exporter.export(ExportJob(vid, st.squeeze, st.lutId, st.strength)) { p -> st.progress = (i + p / 100f) / list.size }
                log += "✓  ${r.name}\n    ${r.width} × ${r.height}" + (r.note?.let { "\n    $it" } ?: "")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { log += "✗  ${vid.name}\n    ${e.message}" }
        }
        st.results = log; st.status = ""; st.busy = false; act.keepScreenOn(false)
    }
}

@Composable
fun Results(lines: List<String>, folder: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            Text("Saved to Movies/$folder. Your originals are untouched.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
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

/* ---------------------------------------------------------------- Settings */

@Composable
fun SettingsScreen(st: AppState, s: Settings, luts: LutManager) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val refresh = { tick++ }
    var renaming by remember { mutableStateOf<LutEntry?>(null) }
    var msg by remember { mutableStateOf("") }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) try { luts.import(u, displayName(ctx, u)); st.lutList = luts.list(); msg = "" }
        catch (e: Exception) { msg = "This LUT couldn't be loaded: ${e.message}" } }
    val c = MaterialTheme.colorScheme

    key(tick) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings") { st.screen = Screen.Main }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Group("Export") {
                PickerRow("Default squeeze", fmtSqueeze(s.defaultSqueeze), PRESETS.map { fmtSqueeze(it) }) {
                    s.defaultSqueeze = PRESETS[it]; if (!st.squeezeChosen) st.squeeze = PRESETS[it]; refresh() }
                PickerRow("Quality", s.quality.label, Quality.entries.map { it.label }) { s.quality = Quality.entries[it]; refresh() }
                PickerRow("Codec", if (s.codec == Codec.HEVC) "HEVC" else "H.264", Codec.entries.map { it.label }) { s.codec = Codec.entries[it]; refresh() }
                if (!Exporter.hasEncoder("video/hevc"))
                    Text("This phone has no HEVC encoder, so H.264 will be used.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                ToggleRow("Keep HDR", "10-bit output for HLG or PQ clips", s.keepHdr) { s.keepHdr = it; refresh() }
                ToggleRow("Preserve metadata", "Rotation, frame rate and original audio", s.preserveMeta) { s.preserveMeta = it; refresh() }
            }
            Group("This phone") {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
                    .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp)).clickable { st.screen = Screen.Limits }
                    .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Export limits", style = MaterialTheme.typography.bodyLarge)
                        Text("Maximum resolution, squeeze, frame rate and 10-bit support", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                    Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(20.dp).rotate(-90f), tint = c.onSurfaceVariant)
                }
                Text("Hardware acceleration is always on: video is decoded, scaled and encoded on the phone's media chip and GPU.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            }
            Group("LUT library") {
                if (st.lutList.isEmpty()) Text("No LUTs yet. Import 3D .cube files, such as DJI's official D-Log to Rec.709 LUT.",
                    style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                st.lutList.forEach { l ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceContainer)
                        .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { renaming = l }) { Text("Rename") }
                        TextButton(onClick = { luts.delete(l.id); if (st.lutId == l.id) st.lutId = null; st.lutList = luts.list() }) { Text("Delete", color = c.error) }
                    }
                }
                OutlinedButton(onClick = { lutPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)) { Text("Import .cube LUT") }
                if (msg.isNotEmpty()) Text(msg, color = c.error, style = MaterialTheme.typography.bodySmall)
            }
            Group("App") {
                var folder by remember { mutableStateOf(s.folder) }
                OutlinedTextField(folder, { folder = it.replace(Regex("[^A-Za-z0-9_ -]"), ""); if (folder.isNotBlank()) s.folder = folder.trim() },
                    label = { Text("Save to Movies/") }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                PickerRow("Theme", s.theme.label, ThemeMode.entries.map { it.label }) { s.theme = ThemeMode.entries[it]; st.theme = s.theme; refresh() }
            }
        }
    }
    }
    renaming?.let { l ->
        var name by remember { mutableStateOf(l.name) }
        AlertDialog(onDismissRequest = { renaming = null },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) luts.rename(l.id, name); st.lutList = luts.list(); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            title = { Text("Rename LUT") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) })
    }
}

