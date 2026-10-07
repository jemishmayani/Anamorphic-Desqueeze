package com.desqueeze.app

import android.graphics.Bitmap
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Small previews of the current clip through each LUT, cached so scrolling the picker stays cheap. */
object LutThumbs {
    private const val MAX_W = 192
    private val cache = LruCache<String, Bitmap>(48)
    private val small = LruCache<String, Bitmap>(16)

    /**
     * The clip thumbnail at tile size, turned by [rotation] and stretched to the de-squeezed shape [ratio]
     * (width / height), so tiles look like the preview. Shared by every LUT tile of that clip.
     */
    fun base(key: String, src: Bitmap, ratio: Float, rotation: Int): Bitmap = small.get(key) ?: run {
        val turned = if (rotation % 360 != 0)
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true)
        else src
        val r = if (ratio > 0f) ratio else turned.width / turned.height.toFloat()
        val w = (if (r >= 1f) MAX_W.toFloat() else MAX_W * r).toInt().coerceAtLeast(1)
        val h = (if (r >= 1f) MAX_W / r else MAX_W.toFloat()).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(turned, w, h, true).also { small.put(key, it) }
    }

    fun cached(lutId: String, clipKey: String): Bitmap? = cache.get("$lutId|$clipKey")

    /** Renders [src] through the LUT at full strength. Call off the main thread. */
    fun render(luts: LutManager, lutId: String, clipKey: String, src: Bitmap, ratio: Float, rotation: Int): Bitmap? {
        cached(lutId, clipKey)?.let { return it }
        return try {
            val b = base(clipKey, src, ratio, rotation)
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val out = luts.load(lutId).applyTo(px, 1f)
            Bitmap.createBitmap(out, b.width, b.height, Bitmap.Config.ARGB_8888).also { cache.put("$lutId|$clipKey", it) }
        } catch (_: Exception) { null }
    }

    /** Drops cached previews of a LUT (after it's deleted or replaced). */
    fun forget(lutId: String) {
        cache.snapshot().keys.filter { it.startsWith("$lutId|") }.forEach { cache.remove(it) }
    }
}

/**
 * Launcher for importing one or many .cube files at once. Reads them off the main thread, refreshes the
 * library and reports a short summary through [onDone]. A single new LUT is passed back so the caller can select it.
 */
@Composable
fun rememberLutImporter(st: AppState, luts: LutManager, onDone: (LutManager.ImportReport) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            st.lutLoading = true
            try {
                val report = withContext(Dispatchers.IO) { luts.importAll(uris.map { it to displayName(ctx, it) }) }
                st.lutList = luts.list()
                done(report)
            } catch (e: Exception) {
                done(LutManager.ImportReport(emptyList(), emptyList(), listOf("files" to (e.message ?: "unknown error"))))
            } finally { st.lutLoading = false }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

/** Horizontal strip of LUT tiles, each showing the current clip through that LUT, plus an Import tile. */
@Composable
fun LutPicker(st: AppState, luts: LutManager, v: VideoInfo, g: Geometry, enabled: Boolean, onImport: () -> Unit) {
    // Tiles take the clip's de-squeezed shape: wide for landscape clips, tall for vertical ones.
    val ratio = g.outRatio; val rot = g.extraRotation
    val clipKey = "${v.uri}|${"%.3f".format(java.util.Locale.ROOT, ratio)}|$rot"
    val shape = TileShapeSpec.of(ratio)
    val original = remember(v.thumb, clipKey) { v.thumb?.let { LutThumbs.base(clipKey, it, ratio, rot).asImageBitmap() } }
    val listState = rememberLazyListState()
    // Bring the selected LUT into view when it changes (e.g. right after an import).
    LaunchedEffect(st.lutId, st.lutList.size) {
        val i = st.lutId?.let { id -> st.lutList.indexOfFirst { it.id == id } } ?: -1
        if (i >= 0) runCatching { listState.animateScrollToItem(i + 1) }
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        item(key = "none") {
            LutTile(shape, "No LUT", original, loading = false, selected = st.lutId == null, enabled = enabled) { st.lutId = null }
        }
        items(st.lutList, key = { it.id }) { e ->
            val img by produceState(LutThumbs.cached(e.id, clipKey)?.asImageBitmap(), e.id, clipKey) {
                if (value == null) {
                    val src = v.thumb
                    value = if (src == null) null
                    else withContext(Dispatchers.Default) { LutThumbs.render(luts, e.id, clipKey, src, ratio, rot)?.asImageBitmap() }
                }
            }
            LutTile(shape, e.name, img, loading = img == null && v.thumb != null, selected = st.lutId == e.id, enabled = enabled) { st.lutId = e.id }
        }
        item(key = "import") { ImportTile(shape, enabled = enabled && !st.lutLoading, onImport) }
    }
}

/** Tile width and picture aspect: the clip's shape, kept between 9:16 and 16:9 so tiles stay a sensible size. */
private data class TileShapeSpec(val width: androidx.compose.ui.unit.Dp, val aspect: Float) {
    companion object {
        fun of(ratio: Float) = TileShapeSpec(if (ratio >= 1f) 104.dp else 76.dp, ratio.coerceIn(9f / 16f, 16f / 9f))
    }
}
private val TileShape = RoundedCornerShape(12.dp)

@Composable
private fun LutTile(spec: TileShapeSpec, name: String, img: ImageBitmap?, loading: Boolean, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.width(spec.width).clip(TileShape).clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = name; this.selected = selected }) {
        Box(Modifier.fillMaxWidth().aspectRatio(spec.aspect).clip(TileShape).background(c.surfaceContainerHighest)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outlineVariant, TileShape)) {
            // Always the same children, so tiles never change structure as previews arrive.
            if (img != null) Image(img, null, Modifier.fillMaxSize().clip(TileShape), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(c.primary),
                contentAlignment = Alignment.Center) {
                Icon(AppIcons.Check, null, Modifier.size(14.dp), tint = c.onPrimary)
            }
        }
        Text(name, Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp).heightIn(min = 32.dp),
            style = MaterialTheme.typography.labelSmall, color = if (selected) c.onSurface else c.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ImportTile(spec: TileShapeSpec, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    Column(Modifier.width(spec.width).clip(TileShape).clickable(enabled = enabled, onClick = onClick)
        .semantics { contentDescription = "Import .cube files" }) {
        Box(Modifier.fillMaxWidth().aspectRatio(spec.aspect).clip(TileShape).background(c.surfaceContainer)
            .border(1.dp, c.outlineVariant, TileShape), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Plus, null, Modifier.size(24.dp), tint = c.primary.copy(alpha = alpha))
        }
        Text("Import .cube files", Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 32.dp),
            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant.copy(alpha = alpha),
            maxLines = 2, textAlign = TextAlign.Center)
    }
}
