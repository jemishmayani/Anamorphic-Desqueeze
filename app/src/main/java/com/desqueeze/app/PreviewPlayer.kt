package com.desqueeze.app

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared playback position so the Frame and Look steps continue where you left off. */
class PlayheadMemory { var positionMs = 0L; var playing = true }

/**
 * Cinema-style preview: the frame springs between squeezed and de-squeezed shapes, hold to see
 * the original, live LUT on a lighter proxy, a full-quality before/after still, and a filmstrip timeline.
 * The file is never modified.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewPlayer(
    v: VideoInfo, g: Geometry, desqueezed: Boolean, memory: PlayheadMemory,
    lut: LutManager.CubeLut? = null, lutStrength: Float = 1f, lutOn: Boolean = false,
    guides: Guides = Guides(), compareRequest: Int = 0,
    /** Loop playback inside this range (ms) and shade the rest of the timeline. */
    trim: Pair<Long, Long>? = null,
    /** Exposure tool shown with the video. */
    exposure: Scope = Scope.OFF,
    /** Scope overlay size (tap the overlay to toggle) and close; readings are reported for the Exposure panel. */
    scopeLarge: Boolean = false,
    onScopeLarge: (Boolean) -> Unit = {},
    onScopeClose: () -> Unit = {},
    onStats: (ScopeMath.Stats?) -> Unit = {},
    /** Keeps the whole preview on screen in landscape / two-pane layouts. */
    maxHeight: androidx.compose.ui.unit.Dp? = null,
    onTrimChange: ((Pair<Long, Long>) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf(false) }
    val showWide = desqueezed && !holding
    val target = if (showWide) g.outRatio else g.inRatio
    val ratio by animateFloatAsState(target, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "ratio")

    // Strength changes rebuild the player, so wait until the slider settles.
    var appliedStrength by remember { mutableFloatStateOf(lutStrength) }
    LaunchedEffect(lutStrength) { delay(350); appliedStrength = lutStrength }
    var effectsFailed by remember(v.uri) { mutableStateOf(false) }
    val useLut = lut != null && lutOn && !effectsFailed
    val useRotate = g.extraRotation != 0 && !effectsFailed
    val effectsKey = Triple(if (useRotate) g.extraRotation else 0, useLut, if (useLut) (appliedStrength * 20).toInt() else -1)

    val player = remember(v.uri, effectsKey, lut) {
        ExoPlayer.Builder(ctx).build().apply {
            val fx = mutableListOf<Effect>()
            if (useRotate) fx += ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - g.extraRotation).toFloat()).build()
            if (useLut) {
                // Proxy: render the LUT at ~720p so 4K 10-bit stays smooth on phones.
                val short = minOf(g.dispW, g.dispH); val s = minOf(1f, 720f / short)
                fx += Presentation.createForWidthAndHeight(((g.dispW * s).toInt() / 2) * 2, ((g.dispH * s).toInt() / 2) * 2, Presentation.LAYOUT_SCALE_TO_FIT)
                fx += SingleColorLut.createFromCube(lut!!.toArgbCube(appliedStrength))
            }
            if (fx.isNotEmpty()) setVideoEffects(fx) // must be set before prepare()
            setMediaItem(MediaItem.fromUri(v.uri)); repeatMode = Player.REPEAT_MODE_ALL; volume = 0f
            prepare(); seekTo(memory.positionMs); playWhenReady = memory.playing
        }
    }
    var playing by remember { mutableStateOf(memory.playing) }
    var pos by remember { mutableLongStateOf(memory.positionMs) }
    var dur by remember(v.uri) { mutableLongStateOf(v.durationMs) }
    var muted by remember { mutableStateOf(true) }
    var error by remember(v.uri) { mutableStateOf<String?>(null) }
    var hint by remember(v.uri) { mutableStateOf(true) }
    var compare by remember { mutableStateOf<Pair<ImageBitmap, ImageBitmap>?>(null) }
    var comparing by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val usingFx = useLut || useRotate
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (usingFx) { effectsFailed = true; error = "Live effects aren't supported on this phone. Use Compare to see the LUT." }
                else error = "This phone can't play ${v.codec} for preview. Lossless export still works."
            }
        }
        player.addListener(l)
        onDispose { memory.positionMs = player.currentPosition; memory.playing = player.playWhenReady; player.removeListener(l); player.release() }
    }
    val currentTrim by rememberUpdatedState(trim)
    LaunchedEffect(player) {
        launch { delay(4000); hint = false }
        while (true) {
            pos = player.currentPosition; if (player.duration > 0) dur = player.duration
            val tr = currentTrim
            if (tr != null && (pos >= tr.second || pos < tr.first - 250)) { player.seekTo(tr.first); pos = tr.first }
            playing = player.playWhenReady; memory.positionMs = pos; memory.playing = playing
            delay(120)
        }
    }

    fun startCompare() {
        val l = lut ?: return
        player.playWhenReady = false; comparing = true
        scope.launch {
            val pair = withContext(Dispatchers.Default) {
                val before = Frames.frameAt(ctx, v, player.currentPosition, 1280, g.extraRotation, exact = true) ?: return@withContext null
                val px = IntArray(before.width * before.height); before.getPixels(px, 0, before.width, 0, 0, before.width, before.height)
                val after = Bitmap.createBitmap(l.applyTo(px, lutStrength), before.width, before.height, Bitmap.Config.ARGB_8888)
                before.asImageBitmap() to after.asImageBitmap()
            }
            comparing = false
            if (pair == null) error = "Couldn't grab this frame for comparison." else compare = pair
        }
    }

    // "Before / After" button outside the video asks for the full-quality compare still.
    val seenRequest = remember { mutableIntStateOf(compareRequest) }
    LaunchedEffect(compareRequest) {
        if (compareRequest != seenRequest.intValue) { seenRequest.intValue = compareRequest; if (lut != null) startCompare() }
    }

    val frame = ratio.coerceIn(0.8f, 4f)
    val videoMod = if (ratio >= frame) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true)

    // Exposure tools read small frames from a TextureView (only while a tool is on).
    val useTexture = exposure != Scope.OFF
    var texture by remember { mutableStateOf<android.view.TextureView?>(null) }
    var scopeData by remember { mutableStateOf<ScopeData?>(null) }
    val statsCb by rememberUpdatedState(onStats)
    LaunchedEffect(exposure, texture, player) {
        scopeData = null; statsCb(null)
        val tv = texture ?: return@LaunchedEffect
        if (exposure == Scope.OFF) return@LaunchedEffect
        while (true) {
            val w = if (exposure == Scope.FALSE_COLOR) 320 else 192
            val h = maxOf(2, (w / frame).toInt())
            val bmp = try { if (tv.isAvailable) tv.getBitmap(w, h) else null } catch (_: Throwable) { null }
            if (bmp != null) {
                val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val bw = bmp.width; val bh = bmp.height; bmp.recycle()
                val d = withContext(Dispatchers.Default) {
                    ScopeData(
                        stats = ScopeMath.stats(px),
                        hist = if (exposure == Scope.HISTOGRAM) ScopeMath.rgbHistogram(px) else null,
                        wave = if (exposure == Scope.WAVEFORM) ScopeMath.waveform(px, bw, bh, cols = 96, rows = 64) else null,
                        parade = if (exposure == Scope.PARADE) ScopeMath.parade(px, bw, bh, cols = 48, rows = 64) else null,
                        vector = if (exposure == Scope.VECTORSCOPE) ScopeMath.vectorscope(px, 96) else null,
                        falseColor = if (exposure == Scope.FALSE_COLOR)
                            Bitmap.createBitmap(ScopeMath.falseColor(px), bw, bh, Bitmap.Config.ARGB_8888).asImageBitmap() else null,
                    )
                }
                scopeData = d; statsCb(d.stats)
            }
            delay(if (player.isPlaying) 250 else 600)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // In landscape / two-pane layouts the preview shrinks to stay fully visible.
    val boxWidth = if (maxHeight != null) minOf(this.maxWidth, maxHeight * frame) else this.maxWidth
    Column(Modifier.width(boxWidth).align(Alignment.TopCenter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(frame).clip(RoundedCornerShape(24.dp)).background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                .pointerInput(player) {
                    detectTapGestures(
                        onPress = {
                            val j = scope.launch { delay(230); holding = true; hint = false }
                            tryAwaitRelease(); j.cancel(); holding = false
                        },
                        onTap = { player.playWhenReady = !player.playWhenReady },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            key(player, useTexture) {
                if (useTexture) {
                    // TextureView stretches the picture to its bounds, like the normal view, and lets us read frames.
                    AndroidView(factory = { android.view.TextureView(it).also { tv -> player.setVideoTextureView(tv); texture = tv } },
                        onRelease = { tv -> player.clearVideoTextureView(tv); texture = null }, modifier = videoMod)
                } else {
                    AndroidView(factory = { PlayerView(it).apply {
                        this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                        setShutterBackgroundColor(AColor.BLACK); setKeepContentOnPlayerReset(true)
                    } }, modifier = videoMod)
                }
            }
            if (exposure == Scope.FALSE_COLOR) scopeData?.falseColor?.let { Image(it, "False color", videoMod, contentScale = ContentScale.FillBounds) }
            GuideOverlay(guides, videoMod)

            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Pill(if (showWide) "De-squeezed ${fmtSqueeze(g.outRatio / g.inRatio).let { if (g.vertical) "$it ↕" else it }}" else "Original", accent = showWide)
                if (useLut) { Spacer(Modifier.width(6.dp)); Pill("LUT ${(appliedStrength * 100).toInt()}%", accent = false, warm = true) }
                Spacer(Modifier.weight(1f))
                Pill(g.ratioLabel(target), accent = false)
            }
            if (hint && error == null) Box(Modifier.align(Alignment.Center)) { Pill("Hold to compare with the original", accent = false) }
            if (comparing) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            error?.let { msg -> Text(msg, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Center).padding(28.dp)) }

            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(start = 6.dp, end = 6.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.playWhenReady = !player.playWhenReady }) {
                    Icon(if (playing) AppIcons.Pause else AppIcons.Play, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.weight(1f))
                if (lut != null) TextButton(onClick = { startCompare() }) { Text("Compare", color = Color.White) }
                IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f }) {
                    Icon(if (muted) AppIcons.SoundOff else AppIcons.SoundOn, if (muted) "Unmute" else "Mute", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }

            if (exposure != Scope.OFF) ScopeOverlay(exposure, scopeData, scopeLarge, onScopeLarge, onScopeClose)
            compare?.let { (before, after) -> CompareOverlay(before, after, ratio) { compare = null } }
        }
        FilmstripTimeline(v, g, pos, dur, trim) { ms -> player.seekTo(ms); pos = ms }
    }
    }
}

/** Full-quality still: drag the divider to wipe between the original (left) and the LUT (right). */
@Composable
private fun CompareOverlay(before: ImageBitmap, after: ImageBitmap, ratio: Float, onClose: () -> Unit) {
    var split by remember { mutableFloatStateOf(0.5f) }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val frame = ratio.coerceIn(0.8f, 4f)
        val mod = if (ratio >= frame) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true)
        Box(mod.pointerInput(Unit) {
            detectTapGestures { split = (it.x / size.width).coerceIn(0f, 1f) }
        }.pointerInput(Unit) {
            detectHorizontalDragGestures { change, _ -> split = (change.position.x / size.width).coerceIn(0f, 1f) }
        }) {
            Image(after, "With LUT", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Image(before, "Original", Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * split) { this@drawWithContent.drawContent() } },
                contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * split
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                drawCircle(Color.White, 12.dp.toPx(), Offset(x, size.height / 2))
                drawCircle(Color.Black.copy(alpha = 0.5f), 4.dp.toPx(), Offset(x, size.height / 2))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
                Pill("Before", accent = false); Spacer(Modifier.weight(1f)); Pill("After", accent = false, warm = true)
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Done", color = Color.White, fontSize = 13.sp)
        }
    }
}

/** Thumbnail strip with a playhead: tap or drag to seek. */
@Composable
fun FilmstripTimeline(v: VideoInfo, g: Geometry, pos: Long, dur: Long, trim: Pair<Long, Long>? = null, onSeek: (Long) -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val n = 8
    val frames = remember(v.uri, g.extraRotation) { mutableStateListOf<ImageBitmap?>().apply { repeat(n) { add(null) } } }
    LaunchedEffect(v.uri, g.extraRotation) {
        withContext(Dispatchers.IO) {
            for (i in 0 until n) {
                val t = if (v.durationMs > 0) v.durationMs * (2 * i + 1) / (2 * n) else 0L
                Frames.frameAt(ctx, v, t, 200, g.extraRotation, exact = false)?.let { b -> withContext(Dispatchers.Main) { frames[i] = b.asImageBitmap() } }
            }
        }
    }
    val seek by rememberUpdatedState(onSeek)
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = (drag ?: if (dur > 0) pos.toFloat() / dur else 0f).coerceIn(0f, 1f)
    Column {
        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceContainerHigh)
            .pointerInput(dur) { detectTapGestures { seek((it.x / size.width * dur).toLong()) } }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { f -> seek((f * dur).toLong()) }; drag = null },
                    onDragCancel = { drag = null },
                ) { ch, _ -> drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
            }) {
            Row(Modifier.fillMaxSize()) {
                frames.forEach { f ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (f != null) Image(f, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width * frac
                if (trim != null && dur > 0) {
                    // Trimmed-away parts are dimmed; the kept range gets warm handles.
                    val a = size.width * trim.first / dur; val b = size.width * trim.second / dur
                    drawRect(Color.Black.copy(alpha = 0.65f), size = androidx.compose.ui.geometry.Size(a, size.height))
                    drawRect(Color.Black.copy(alpha = 0.65f), topLeft = Offset(b, 0f), size = androidx.compose.ui.geometry.Size(size.width - b, size.height))
                    drawRect(Warm, topLeft = Offset(a, 0f), size = androidx.compose.ui.geometry.Size(b - a, size.height),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                } else drawRect(Color.Black.copy(alpha = 0.35f), topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(size.width - x, size.height))
                drawLine(c.primary, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx(), StrokeCap.Round)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp)) {
            Text(fmtDuration((frac * dur).toLong()), style = MaterialTheme.typography.labelSmall.merge(Mono), color = c.onSurface)
            Spacer(Modifier.weight(1f))
            Text(fmtDuration(dur), style = MaterialTheme.typography.labelSmall.merge(Mono), color = c.onSurfaceVariant)
        }
    }
}

@Composable
fun Pill(text: String, accent: Boolean, warm: Boolean = false) {
    Box(Modifier.clip(CircleShape)
        .background(when { accent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.9f); warm -> Warm.copy(alpha = 0.85f); else -> Color.Black.copy(alpha = 0.45f) })
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, fontSize = 12.sp, color = if (accent) MaterialTheme.colorScheme.onPrimary else if (warm) Color(0xFF2A1A00) else Color.White, style = Mono)
    }
}

/** Everything the scope overlay draws, computed off the main thread a few times per second. */
class ScopeData(
    val stats: ScopeMath.Stats,
    val hist: Array<FloatArray>? = null,
    val wave: Array<FloatArray>? = null,
    val parade: Array<Array<FloatArray>>? = null,
    val vector: FloatArray? = null,
    val falseColor: ImageBitmap? = null,
)

private val ScopeBg = Color(0xD90B0D10)
private val Grat = Color.White.copy(alpha = 0.16f)
private val ChannelColors = listOf(Color(0xFFFF5A5A), Color(0xFF5BE08A), Color(0xFF5AA9FF))

/**
 * A compact scope floating over the video's corner, like on a camera monitor, so the preview never
 * grows and the tools below stay reachable. Tap to enlarge or shrink; × turns it off.
 * False color covers the picture itself, so it only shows a small label with ×.
 */
@Composable
fun BoxScope.ScopeOverlay(scope: Scope, data: ScopeData?, large: Boolean, onLarge: (Boolean) -> Unit, onClose: () -> Unit) {
    if (scope == Scope.FALSE_COLOR) {
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(CircleShape).background(ScopeBg)
            .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("False color", fontSize = 12.sp, color = Color.White, style = Mono)
            CloseDot(onClose)
        }
        return
    }
    BoxWithConstraints(Modifier.matchParentSize().padding(start = 10.dp, end = 10.dp, top = 46.dp, bottom = 46.dp)) {
        val square = scope == Scope.VECTORSCOPE
        val w = if (large) maxWidth * 0.66f else maxWidth * 0.40f
        val h = if (square) minOf(w, maxHeight) else minOf(maxHeight, if (large) maxHeight else w * 0.56f)
        val boxW = if (square) h else w
        Box(Modifier.align(Alignment.TopEnd).size(boxW, h).clip(RoundedCornerShape(12.dp)).background(ScopeBg)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .clickable { onLarge(!large) }) {
            val d = data
            if (d == null) Text("Reading…", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.Center))
            else Canvas(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
                when (scope) {
                    Scope.HISTOGRAM -> d.hist?.let { drawRgbHistogram(it) }
                    Scope.WAVEFORM -> d.wave?.let { drawWaveform(it, Color(0xFFDDE6F0), 0f, size.width) }
                    Scope.PARADE -> d.parade?.let { p ->
                        val third = size.width / 3f
                        drawIreGrid()
                        p.forEachIndexed { i, g -> drawWaveform(g, ChannelColors[i], i * third + 2f, third - 4f, grid = false) }
                    }
                    Scope.VECTORSCOPE -> d.vector?.let { drawVectorscope(it, 96) }
                    else -> {}
                }
            }
            if (d != null && scope != Scope.VECTORSCOPE) Text(
                "${scope.short}   clip ${"%.1f".format(d.stats.clippedPct)}%   ${d.stats.p50.toInt()} IRE",
                fontSize = 9.sp, style = Mono, color = Color.White.copy(alpha = 0.75f), maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 2.dp))
            Box(Modifier.align(Alignment.TopEnd)) { CloseDot(onClose) }
        }
    }
}

@Composable
private fun CloseDot(onClose: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Cross, "Turn off scope", Modifier.size(12.dp), tint = Color.White.copy(alpha = 0.85f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIreGrid() {
    for (ire in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
        val y = size.height * (1 - ire)
        drawLine(if (ire == 0f || ire == 1f) Grat.copy(alpha = 0.3f) else Grat, Offset(0f, y), Offset(size.width, y), 1f)
    }
}

/** RGB channels as soft translucent fills, luma as a white outline; grid at 0/25/50/75/100 IRE. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRgbHistogram(h: Array<FloatArray>) {
    for (ire in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) drawLine(Grat, Offset(size.width * ire, 0f), Offset(size.width * ire, size.height), 1f)
    val n = h[0].size; val bw = size.width / (n - 1)
    fun path(v: FloatArray) = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, size.height)
        for (i in 0 until n) lineTo(i * bw, size.height * (1 - v[i].coerceIn(0f, 1f)))
        lineTo(size.width, size.height); close()
    }
    for (ch in 0..2) drawPath(path(h[ch]), ChannelColors[ch].copy(alpha = 0.35f), blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
    drawPath(path(h[3]), Color.White.copy(alpha = 0.9f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveform(
    g: Array<FloatArray>, color: Color, left: Float, width: Float, grid: Boolean = true,
) {
    if (grid) drawIreGrid()
    val cw = width / g.size; val rh = size.height / g[0].size
    for (x in g.indices) for (y in g[x].indices) {
        val a = g[x][y]; if (a <= 0.02f) continue
        drawRect(color.copy(alpha = a.coerceIn(0.1f, 1f)), topLeft = Offset(left + x * cw, y * rh),
            size = androidx.compose.ui.geometry.Size(cw + 0.5f, rh + 0.5f))
    }
}

/** Cb right, Cr up; 75% colour-bar targets, the skin-tone line, and the picture's colour cloud. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVectorscope(v: FloatArray, n: Int) {
    val r = minOf(size.width, size.height) / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(Grat, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    drawLine(Grat, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f); drawLine(Grat, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
    val a = Math.toRadians(ScopeMath.SKIN_LINE_DEG.toDouble())
    drawLine(Color(0xFFF4C7A1).copy(alpha = 0.55f), c, Offset(c.x + r * Math.cos(a).toFloat(), c.y - r * Math.sin(a).toFloat()), 1.2f)
    val cell = 2 * r / n
    for (i in v.indices) {
        val a2 = v[i]; if (a2 <= 0.03f) continue
        drawRect(Color(0xFFBFE6C9).copy(alpha = a2.coerceIn(0.12f, 1f)),
            topLeft = Offset(c.x - r + (i % n) * cell, c.y - r + (i / n) * cell), size = androidx.compose.ui.geometry.Size(cell + 0.4f, cell + 0.4f))
    }
    ScopeMath.TARGETS.forEachIndexed { i, (_, cbcr) ->
        val p = Offset(c.x + cbcr.first * 2 * r, c.y - cbcr.second * 2 * r)
        val col = listOf(ChannelColors[0], Color(0xFFE070E0), ChannelColors[2], Color(0xFF60D8E0), ChannelColors[1], Color(0xFFF2D35B))[i]
        drawRect(col, topLeft = Offset(p.x - 3f, p.y - 3f), size = androidx.compose.ui.geometry.Size(6f, 6f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
    }
}
