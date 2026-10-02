package com.desqueeze.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        super.onCreate(savedInstanceState)
        val settings = Settings(this); val luts = LutManager(this)
        setContent {
            var theme by remember { mutableStateOf(settings.theme) }
            val dark = when (theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) { BackHandler { showSettings = false }
                        SettingsScreen(settings, luts, onTheme = { theme = it }) { showSettings = false } }
                    else MainScreen(this, settings, luts) { showSettings = true }
                }
            }
        }
    }
    fun keepScreenOn(on: Boolean) =
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

@OptIn(UnstableApi::class)
@Composable
fun MainScreen(act: MainActivity, settings: Settings, luts: LutManager, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    var videos by remember { mutableStateOf(listOf<VideoInfo>()) }
    var selected by remember { mutableIntStateOf(0) }
    var squeeze by remember { mutableFloatStateOf(settings.defaultSqueeze) }
    var desqueezed by remember { mutableStateOf(true) }
    var lutList by remember { mutableStateOf(luts.list()) }
    var lutId by remember { mutableStateOf<String?>(null) }
    var strength by remember { mutableFloatStateOf(1f) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val exporter = remember { Exporter(ctx, settings, luts) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        act.lifecycleScope.launch {
            val errs = mutableListOf<String>()
            val found = withContext(Dispatchers.IO) { uris.mapNotNull { u ->
                try { VideoProbe.probe(ctx, u) } catch (e: Exception) { errs += (e.message ?: "Unreadable file"); null } } }
            videos = found; selected = 0; squeeze = settings.defaultSqueeze
            status = if (errs.isEmpty()) "" else errs.joinToString("\n")
        }
    }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) try { lutId = luts.import(u, displayName(ctx, u)).id; lutList = luts.list() }
        catch (e: Exception) { status = "LUT error: ${e.message}" }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ANAMORPHIC DE-SQUEEZE", fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = openSettings, enabled = !busy) { Icon(Icons.Default.Settings, "Settings") }
        }
        Button(onClick = { picker.launch(arrayOf("video/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (videos.isEmpty()) "Select Video" else "Change Video(s)")
        }
        if (videos.size > 1) {
            Text("${videos.size} videos selected — same settings will be applied to all", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                videos.forEachIndexed { i, _ -> FilterChip(selected = i == selected, onClick = { selected = i }, label = { Text("${i + 1}") }) }
            }
        }
        val v = videos.getOrNull(selected)
        if (v != null) Text("${v.name}\n${v.summary}", style = MaterialTheme.typography.bodySmall)

        SqueezePicker(squeeze, enabled = !busy) { squeeze = it }
        if (v != null) Text("Output ≈ ${Exporter.even(v.displayW * squeeze)}×${Exporter.even(v.displayH.toFloat())}  (no cropping)",
            style = MaterialTheme.typography.labelMedium)

        Text("Video Preview", fontWeight = FontWeight.SemiBold)
        if (v != null) Preview(v, if (desqueezed) squeeze else 1f) else
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                Text("No video", color = Color.Gray) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "Squeezed", true to "De-squeezed").forEach { (d, label) ->
                if (desqueezed == d) Button(onClick = {}, Modifier.weight(1f)) { Text(label) }
                else OutlinedButton(onClick = { desqueezed = d }, Modifier.weight(1f)) { Text(label) }
            }
        }
        Text("Preview shows geometry only; LUT is applied on export.", style = MaterialTheme.typography.labelSmall)

        Text("LUT", fontWeight = FontWeight.SemiBold)
        Dropdown(lutList.firstOrNull { it.id == lutId }?.name ?: "None",
            listOf("None") + lutList.map { it.name } + "Import .cube…", enabled = !busy) { i ->
            when { i == 0 -> lutId = null; i <= lutList.size -> lutId = lutList[i - 1].id; else -> lutPicker.launch(arrayOf("*/*")) }
        }
        if (lutId != null) {
            Text("LUT Strength: ${(strength * 100).toInt()}%")
            Slider(strength, { strength = it }, enabled = !busy)
        }

        if (busy) { LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth()) }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)

        if (!busy) Button(onClick = {
            val list = videos; act.keepScreenOn(true); busy = true
            job = act.lifecycleScope.launch {
                val log = StringBuilder()
                list.forEachIndexed { i, vid ->
                    status = "Exporting ${i + 1}/${list.size}: ${vid.name}"
                    try {
                        val r = exporter.export(ExportJob(vid, squeeze, lutId, strength)) { p -> progress = (i + p / 100f) / list.size }
                        log.appendLine("✓ ${r.name} (${r.width}×${r.height})" + (r.note?.let { "\n   $it" } ?: ""))
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { log.appendLine("✗ ${vid.name}: ${e.message}") }
                }
                status = log.toString() + "Saved to Movies/${settings.folder}"
                busy = false; act.keepScreenOn(false)
            }
        }, enabled = videos.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (videos.size > 1) "EXPORT ${videos.size} DE-SQUEEZED VIDEOS" else "EXPORT DE-SQUEEZED VIDEO", fontWeight = FontWeight.Bold)
        } else OutlinedButton(onClick = { job?.cancel(); busy = false; status = "Cancelled"; act.keepScreenOn(false) },
            Modifier.fillMaxWidth().height(56.dp)) { Text("CANCEL") }
        Text("Keep the app open while exporting. Originals are never modified.", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SqueezePicker(value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    var custom by remember { mutableStateOf(PRESETS.none { kotlin.math.abs(it - value) < 0.001f }) }
    Text("Squeeze Factor", fontWeight = FontWeight.SemiBold)
    Dropdown(if (custom) "Custom (${fmtSqueeze(value)})" else fmtSqueeze(value),
        PRESETS.map { fmtSqueeze(it) } + "Custom…", enabled) { i ->
        if (i < PRESETS.size) { custom = false; onChange(PRESETS[i]) } else custom = true
    }
    if (custom) {
        var text by remember(value) { mutableStateOf("%.2f".format(value)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(value, { onChange((it * 100).toInt() / 100f) }, valueRange = 1f..3f, enabled = enabled, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(text, { t -> text = t; t.toFloatOrNull()?.takeIf { it in 1f..3f }?.let(onChange) },
                Modifier.width(90.dp), singleLine = true, enabled = enabled, suffix = { Text("×") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
}

@Composable
fun Dropdown(current: String, options: List<String>, enabled: Boolean = true, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$current  ▼") }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

/** Fast preview: hardware playback, stretched by the GPU compositor. Never touches the file. */
@OptIn(UnstableApi::class)
@Composable
fun Preview(v: VideoInfo, factor: Float) {
    val ctx = LocalContext.current
    val player = remember(v.uri) { ExoPlayer.Builder(ctx).build().apply {
        setMediaItem(MediaItem.fromUri(v.uri)); repeatMode = ExoPlayer.REPEAT_MODE_ALL; volume = 0f; prepare(); play() } }
    DisposableEffect(player) { onDispose { player.release() } }
    val ratio = v.displayW * factor / v.displayH
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView({ PlayerView(it).apply { this.player = player; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; useController = true } },
            Modifier.fillMaxSize())
    }
}

fun displayName(ctx: android.content.Context, u: Uri): String =
    ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: "LUT"

@Composable
fun SettingsScreen(s: Settings, luts: LutManager, onTheme: (ThemeMode) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }; fun refresh() { tick++ }
    var lutList by remember(tick) { mutableStateOf(luts.list()) }
    var renaming by remember { mutableStateOf<LutEntry?>(null) }
    var msg by remember { mutableStateOf("") }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) try { luts.import(u, displayName(ctx, u)); refresh() } catch (e: Exception) { msg = "LUT error: ${e.message}" } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        key(tick) {}
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text("Default squeeze factor"); Dropdown(fmtSqueeze(s.defaultSqueeze), PRESETS.map { fmtSqueeze(it) }) { s.defaultSqueeze = PRESETS[it]; refresh() }
        Text("Export quality"); Dropdown(s.quality.label, Quality.entries.map { it.label }) { s.quality = Quality.entries[it]; refresh() }
        Text("Codec"); Dropdown(s.codec.label, Codec.entries.map { it.label }) { s.codec = Codec.entries[it]; refresh() }
        if (!Exporter.hasEncoder("video/hevc")) Text("This phone has no HEVC encoder — H.264 will be used.", style = MaterialTheme.typography.labelSmall)
        Toggle("Keep HDR / 10-bit when source is HLG or PQ", s.keepHdr) { s.keepHdr = it; refresh() }
        Toggle("Preserve metadata (rotation, audio passthrough)", s.preserveMeta) { s.preserveMeta = it; refresh() }
        Text("Hardware acceleration: always on (MediaCodec + GPU).", style = MaterialTheme.typography.bodySmall)
        Text("Output folder (inside Movies/)")
        var folder by remember { mutableStateOf(s.folder) }
        OutlinedTextField(folder, { folder = it.replace(Regex("[^A-Za-z0-9_ -]"), ""); if (folder.isNotBlank()) s.folder = folder.trim() },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Theme"); Dropdown(s.theme.label, ThemeMode.entries.map { it.label }) { s.theme = ThemeMode.entries[it]; onTheme(s.theme); refresh() }

        HorizontalDivider()
        Text("LUT Library", fontWeight = FontWeight.Bold)
        Text("Import your own 3D .cube LUTs. No LUTs are bundled. To disable a LUT, choose “None” on the main screen.",
            style = MaterialTheme.typography.labelSmall)
        Button(onClick = { lutPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Import .cube LUT") }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
        lutList.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.name, Modifier.weight(1f))
                TextButton(onClick = { renaming = l }) { Text("Rename") }
                TextButton(onClick = { luts.delete(l.id); refresh() }) { Text("Delete") }
            }
        }
    }
    renaming?.let { l ->
        var name by remember { mutableStateOf(l.name) }
        AlertDialog(onDismissRequest = { renaming = null },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) luts.rename(l.id, name); renaming = null; refresh() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            title = { Text("Rename LUT") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) })
    }
}

@Composable
fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(value, onChange) }
