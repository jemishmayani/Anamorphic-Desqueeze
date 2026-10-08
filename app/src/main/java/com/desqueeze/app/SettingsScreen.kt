package com.desqueeze.app

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val REPO_URL = "https://github.com/jemishmayani/Anamorphic-Desqueeze"
const val SUPPORT_URL = "https://buymeacoffee.com/jemishmayani"

fun openUrl(ctx: android.content.Context, url: String) {
    try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}

/* ------------------------------------------------------------------ update check (GitHub build only) */

sealed class UpdateResult {
    data class Available(val version: String, val url: String, val notes: String) : UpdateResult()
    data object UpToDate : UpdateResult()
    data class Failed(val why: String) : UpdateResult()
}

object Updates {
    suspend fun check(current: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://api.github.com/repos/jemishmayani/Anamorphic-Desqueeze/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            when (val code = c.responseCode) {
                200 -> {
                    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val tag = j.optString("tag_name").removePrefix("v")
                    if (newer(tag, current)) UpdateResult.Available(tag, j.optString("html_url", "$REPO_URL/releases"), j.optString("body"))
                    else UpdateResult.UpToDate
                }
                404 -> UpdateResult.Failed("No public release found. Updates can only be checked once the GitHub repository is public.")
                403 -> UpdateResult.Failed("GitHub is limiting requests right now. Try again in a while.")
                else -> UpdateResult.Failed("GitHub answered $code.")
            }
        } catch (e: Exception) { UpdateResult.Failed("Couldn't reach GitHub. Check your connection.") }
    }

    /** True when version [a] (e.g. "1.10") is newer than [b] (e.g. "1.9"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }; val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 }); if (d != 0) return d > 0 }
        return false
    }
}

/* ------------------------------------------------------------------ building blocks */

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = c.primary, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(20.dp)), content = content)
    }
}

@Composable
fun SettingRow(
    icon: ImageVector, title: String, subtitle: String? = null, value: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary, divider: Boolean = true, onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = tint)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 150.dp))
            when {
                trailing != null -> { Spacer(Modifier.width(8.dp)); trailing() }
                onClick != null -> Icon(AppIcons.Chevron, null, Modifier.padding(start = 4.dp).size(18.dp), tint = c.onSurfaceVariant)
            }
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 66.dp), color = c.outlineVariant)
    }
}

/** A row that opens a small menu of choices. */
@Composable
fun SettingChoiceRow(icon: ImageVector, title: String, subtitle: String?, value: String, options: List<String>,
                     tint: Color = MaterialTheme.colorScheme.primary, divider: Boolean = true, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(icon, title, subtitle, value, tint, divider, onClick = { open = true })
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.CenterEnd)) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

/* ------------------------------------------------------------------ settings */

@Composable
fun SettingsScreen(st: AppState, s: Settings, luts: LutManager) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    var tick by remember { mutableIntStateOf(0) }
    val refresh = { tick++ }
    var customDefault by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var update by remember { mutableStateOf<UpdateResult?>(null) }
    var checking by remember { mutableStateOf(false) }
    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" } }
    fun checkUpdates() { checking = true; scope.launch { update = Updates.check(version); checking = false } }

    key(tick) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings") { st.screen = Screen.Main }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {

            // App header
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceContainer)
                .border(1.dp, c.outlineVariant, RoundedCornerShape(24.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.size(56.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Anamorphic De-Squeeze", style = MaterialTheme.typography.titleMedium)
                    Text("Version $version  ${BuildConfig.DIST}", style = MaterialTheme.typography.bodySmall.merge(Mono), color = c.onSurfaceVariant)
                }
            }

            SettingsGroup("Defaults") {
                val isPreset = PRESETS.any { kotlin.math.abs(it - s.defaultSqueeze) < 0.001f }
                SettingChoiceRow(AppIcons.Aspect, "Default squeeze", "Used for new clips", fmtSqueeze(s.defaultSqueeze) + if (isPreset) "" else " (custom)",
                    PRESETS.map { fmtSqueeze(it) } + "Custom value…") {
                    if (it < PRESETS.size) { s.defaultSqueeze = PRESETS[it]; if (!st.squeezeChosen) st.newClipSqueeze = PRESETS[it]; refresh() } else customDefault = true
                }
                val methodLabels = listOf("Recommended per clip", "Always Lossless", "Always Re-encode")
                val methodIdx = if (s.followRecommendation) 0 else if (s.mode == ExportMode.LOSSLESS) 1 else 2
                SettingChoiceRow(AppIcons.Spark, "Export method", "What new clips start with", methodLabels[methodIdx], methodLabels, divider = false) { i ->
                    s.followRecommendation = i == 0; st.followRecommendation = i == 0
                    if (i == 1) { s.mode = ExportMode.LOSSLESS; st.mode = ExportMode.LOSSLESS }
                    if (i == 2) { s.mode = ExportMode.REENCODE; st.mode = ExportMode.REENCODE }
                    refresh()
                }
            }

            SettingsGroup("Re-encode") {
                SettingChoiceRow(AppIcons.Sliders, "Quality", "Higher means larger files", s.quality.label, Quality.entries.map { it.label }) {
                    s.quality = Quality.entries[it]; st.quality = s.quality; refresh() }
                SettingChoiceRow(AppIcons.Codec, "Codec", if (Exporter.hasEncoder("video/hevc")) "HEVC is smaller at the same quality" else "No HEVC encoder on this phone",
                    if (s.codec == Codec.HEVC) "HEVC" else "H.264", Codec.entries.map { it.label }) { s.codec = Codec.entries[it]; st.codec = s.codec; refresh() }
                SettingRow(AppIcons.Hdr, "Keep HDR", "10-bit output for HLG / HDR10 clips", tint = Warm, onClick = { s.keepHdr = !s.keepHdr; st.keepHdrSetting = s.keepHdr; refresh() },
                    trailing = { Switch(s.keepHdr, { s.keepHdr = it; st.keepHdrSetting = it; refresh() }) })
                SettingRow(AppIcons.Info, "Preserve metadata", "Rotation, frame rate and original audio", divider = false,
                    onClick = { s.preserveMeta = !s.preserveMeta; refresh() }, trailing = { Switch(s.preserveMeta, { s.preserveMeta = it; refresh() }) })
            }

            SettingsGroup("Output & looks") {
                SettingRow(AppIcons.Folder, "Save to", "Exports go to your Movies folder", "Movies/${s.folder}", onClick = { folderDialog = true })
                SettingRow(AppIcons.Palette, "LUT library", "Import, rename and delete .cube LUTs",
                    "${st.lutList.size} LUT${if (st.lutList.size == 1) "" else "s"}", tint = Warm, onClick = { st.lutsBack = Screen.Settings; st.screen = Screen.Luts })
                SettingChoiceRow(AppIcons.Moon, "Theme", null, s.theme.label, ThemeMode.entries.map { it.label }) {
                    s.theme = ThemeMode.entries[it]; st.theme = s.theme; refresh() }
                AccentPicker(st.accent) { s.accent = it; st.accent = it }
            }

            SettingsGroup("This phone") {
                SettingRow(AppIcons.Pulse, "Device diagnostics", "Hardware video support, limits and what they mean for you",
                    divider = false, onClick = { st.screen = Screen.Limits })
            }

            SettingsGroup("About & support") {
                SettingRow(AppIcons.Coffee, "Support the development", "Buy me a coffee if this app helps you", tint = Warm,
                    onClick = { openUrl(ctx, SUPPORT_URL) })
                if (BuildConfig.UPDATE_CHECK) {
                    SettingRow(AppIcons.Update, "Check for updates", when (val u = update) {
                        null -> "Asks GitHub for the latest release"
                        is UpdateResult.Available -> "Version ${u.version} is available"
                        UpdateResult.UpToDate -> "You have the latest version"
                        is UpdateResult.Failed -> u.why
                    }, onClick = { if (!checking) checkUpdates() },
                        trailing = if (checking || update is UpdateResult.Available) { {
                            if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else TextButton(onClick = { (update as? UpdateResult.Available)?.let { openUrl(ctx, it.url) } }) { Text("Get") }
                        } } else null)
                } else {
                    SettingRow(AppIcons.Update, "Updates", "Delivered by Google Play", onClick = {
                        openUrl(ctx, "https://play.google.com/store/apps/details?id=${ctx.packageName}") })
                }
                SettingRow(AppIcons.Doc, "What's new", "Changes in every version", onClick = { openUrl(ctx, "$REPO_URL/blob/main/CHANGELOG.md") })
                SettingRow(AppIcons.Code, "Source code", "On GitHub", onClick = { openUrl(ctx, REPO_URL) })
                SettingRow(AppIcons.Shield, "Privacy", "No accounts, ads or tracking", onClick = { info = "Privacy" to PRIVACY_TEXT })
                SettingRow(AppIcons.Doc, "Open-source licenses", null, divider = false, onClick = { info = "Open-source licenses" to LICENSES_TEXT })
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Made with ", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Icon(AppIcons.Heart, null, Modifier.size(12.dp), tint = Warm)
                Text(" by Jemish Mayani", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
    }

    if (customDefault) SqueezeDialog(s.defaultSqueeze, onDismiss = { customDefault = false }) { value ->
        s.defaultSqueeze = value; if (!st.squeezeChosen) st.newClipSqueeze = value; customDefault = false; refresh()
    }
    if (folderDialog) {
        var name by remember { mutableStateOf(s.folder) }
        AlertDialog(onDismissRequest = { folderDialog = false }, title = { Text("Save to folder") },
            text = { OutlinedTextField(name, { name = it.replace(Regex("[^A-Za-z0-9_ -]"), "") }, singleLine = true,
                prefix = { Text("Movies/") }, shape = RoundedCornerShape(12.dp)) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) s.folder = name.trim(); folderDialog = false; refresh() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("Cancel") } })
    }
    info?.let { (title, body) ->
        AlertDialog(onDismissRequest = { info = null }, title = { Text(title) },
            text = { Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Close") } })
    }
}

/* ------------------------------------------------------------------ LUT library */

@Composable
fun LutLibraryScreen(st: AppState, luts: LutManager, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var renaming by remember { mutableStateOf<LutEntry?>(null) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }   // text, isError
    val importLuts = rememberLutImporter(st, luts) { r -> msg = r.summary to (r.added.isEmpty() && r.failed.isNotEmpty()) }
    Column(Modifier.fillMaxSize()) {
        TopBar("LUT library", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("3D .cube LUTs you import are stored privately in the app. None are bundled; for log footage, import your camera maker's official log-to-Rec.709 LUT.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Button(onClick = { msg = null; importLuts() }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp),
                enabled = !st.lutLoading) { Text("Import .cube LUTs") }
            Text("You can pick several files at once. Duplicates already in your library are skipped.",
                style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            val m = msg
            if (st.lutLoading) LoadingRow("Importing LUTs…")
            else if (m != null) Text(m.first, color = if (m.second) c.error else c.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (st.lutList.isEmpty()) Text("No LUTs yet.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            else SettingsGroup("${st.lutList.size} LUT${if (st.lutList.size == 1) "" else "s"}") {
                st.lutList.forEachIndexed { i, l ->
                    SettingRow(AppIcons.Palette, l.name, st.videos.count { st.lutFor(it) == l.id }.let { n -> if (n == 0) null else if (st.videos.size == 1) "In use" else "Used by $n clip${if (n == 1) "" else "s"}" }, tint = Warm, divider = i < st.lutList.size - 1,
                        trailing = { Row {
                            TextButton(onClick = { renaming = l }) { Text("Rename") }
                            TextButton(onClick = { luts.delete(l.id); LutThumbs.forget(l.id); st.forgetLut(l.id); st.lutList = luts.list() }) { Text("Delete", color = c.error) }
                        } })
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

val PRIVACY_TEXT = """
Anamorphic De-Squeeze works entirely on your phone.

• No accounts, ads, analytics or tracking.
• Your videos and LUTs never leave your device. Exports are saved to your Movies folder; originals are never modified.
• The app only reads the files you choose with Android's file picker.
• Crash and diagnostic reports stay on your phone. They are only shared if you copy and send one yourself.
• The GitHub version connects to the internet only when you tap "Check for updates", to ask GitHub for the latest release. The Google Play version doesn't use the internet at all.
• Links (support page, source code) open in your browser.
""".trimIndent()

val LICENSES_TEXT = """
This app is built with open-source software:

• AndroidX Media3 (ExoPlayer, Transformer, Effect): Apache License 2.0
• Jetpack Compose and AndroidX libraries: Apache License 2.0
• Kotlin and kotlinx.coroutines: Apache License 2.0

Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
""".trimIndent()

/** Accent colour swatches; "Wallpaper" follows the system's Material You colours (Android 12+). */
@Composable
fun AccentPicker(current: Accent, onPick: (Accent) -> Unit) {
    val c = MaterialTheme.colorScheme
    val options = Accent.entries.filter { it != Accent.DYNAMIC || android.os.Build.VERSION.SDK_INT >= 31 }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("Accent colour", style = MaterialTheme.typography.bodyLarge)
        Text(if (current == Accent.DYNAMIC) "Matches your wallpaper" else current.label,
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            options.forEach { a ->
                val on = a == current
                Box(Modifier.size(38.dp).clip(CircleShape)
                    .border(2.dp, if (on) c.onSurface else Color.Transparent, CircleShape)
                    .padding(4.dp).clip(CircleShape)
                    .background(if (a == Accent.DYNAMIC) androidx.compose.ui.graphics.Brush.sweepGradient(
                        listOf(Color(0xFF5AA9FF), Color(0xFF4FD8A4), Color(0xFFFFB454), Color(0xFFFF7FA3), Color(0xFF5AA9FF)))
                        else androidx.compose.ui.graphics.SolidColor(Color(a.argb)))
                    .clickable { onPick(a) }
                    .semantics { contentDescription = a.label + if (on) ", selected" else "" },
                    contentAlignment = Alignment.Center) {
                    if (on) Icon(AppIcons.Check, null, Modifier.size(16.dp),
                        tint = if (a == Accent.DYNAMIC || Color(a.argb).luminance() > 0.45f) Color(0xFF0B0D10) else Color.White)
                }
            }
        }
    }
}
