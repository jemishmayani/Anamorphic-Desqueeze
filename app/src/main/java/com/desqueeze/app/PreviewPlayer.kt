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
    scope: Scope = Scope.OFF,
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
    val useTexture = scope != Scope.OFF
    var texture by remember { mutableStateOf<android.view.TextureView?>(null) }
    var stats by remember { mutableStateOf<ScopeMath.Stats?>(null) }
    var wave by remember { mutableStateOf<Array<FloatArray>?>(null) }
    var falseImg by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(scope, texture, player) {
        stats = null; wave = null; falseImg = null
        val tv = texture ?: return@LaunchedEffect
        if (scope == Scope.OFF) return@LaunchedEffect
        while (true) {
            val w = if (scope == Scope.FALSE_COLOR) 320 else 192
            val h = maxOf(2, (w / frame).toInt())
            val bmp = try { if (tv.isAvailable) tv.getBitmap(w, h) else null } catch (_: Throwable) { null }
            if (bmp != null) {
                val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val bw = bmp.width; val bh = bmp.height; bmp.recycle()
                withContext(Dispatchers.Default) {
                    val s = ScopeMath.stats(px)
                    val wf = if (scope == Scope.WAVEFORM) ScopeMath.waveform(px, bw, bh) else null
                    val fc = if (scope == Scope.FALSE_COLOR) Bitmap.createBitmap(ScopeMath.falseColor(px), bw, bh, Bitmap.Config.ARGB_8888).asImageBitmap() else null
                    Triple(s, wf, fc)
                }.let { (s, wf, fc) -> stats = s; wave = wf; falseImg = fc }
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
            if (scope == Scope.FALSE_COLOR) falseImg?.let { Image(it, "False color", videoMod, contentScale = ContentScale.FillBounds) }
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

            compare?.let { (before, after) -> CompareOverlay(before, after, ratio) { compare = null } }
        }
        if (scope != Scope.OFF) ScopePanel(scope, stats, wave)
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

/** Histogram / waveform with clipping readouts, or the false-color legend. Readings are of the picture as shown. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScopePanel(scope: Scope, stats: ScopeMath.Stats?, wave: Array<FloatArray>?) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF0E1218)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (scope) {
            Scope.HISTOGRAM -> Canvas(Modifier.fillMaxWidth().height(84.dp)) {
                val h = stats?.histogram ?: return@Canvas
                val bw = size.width / h.size
                for (i in h.indices) {
                    val bh = h[i] * size.height
                    val col = when { i <= 1 -> Color(0xFF9C6ADE); i >= h.size - 2 -> Color(0xFFE57373); else -> Color(0xFFB8C4D4) }
                    drawRect(col, topLeft = Offset(i * bw, size.height - bh), size = androidx.compose.ui.geometry.Size(bw * 0.9f, bh))
                }
                for (ire in listOf(0.1f, 0.5f, 0.9f)) drawLine(Color.White.copy(alpha = 0.15f), Offset(size.width * ire, 0f), Offset(size.width * ire, size.height), 1f)
            }
            Scope.WAVEFORM -> Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                for (ire in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
                    val y = size.height * (1 - ire)
                    drawLine(Color.White.copy(alpha = if (ire == 0f || ire == 1f) 0.3f else 0.12f), Offset(0f, y), Offset(size.width, y), 1f)
                }
                val g = wave ?: return@Canvas
                val cw = size.width / g.size; val rh = size.height / g[0].size
                for (x in g.indices) for (y in g[x].indices) {
                    val a = g[x][y]; if (a <= 0.02f) continue
                    drawRect(Color(0xFF7CFFB0).copy(alpha = a.coerceIn(0.08f, 1f)), topLeft = Offset(x * cw, y * rh),
                        size = androidx.compose.ui.geometry.Size(cw + 0.5f, rh + 0.5f))
                }
            }
            Scope.FALSE_COLOR -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScopeMath.ZONES.filter { it.argb != 0 }.forEach { z ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(z.argb)))
                            Spacer(Modifier.width(5.dp))
                            Text("${z.label} ${z.from.toInt()}–${minOf(100, z.to.toInt())}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f), style = Mono)
                        }
                    }
                }
            }
            Scope.OFF -> {}
        }
        val s = stats
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s == null) Text("Reading the picture…", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
            else {
                Text("Clipped ${"%.1f".format(s.clippedPct)}%", fontSize = 12.sp, style = Mono,
                    color = if (s.clippedPct > 1f) Color(0xFFE57373) else Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.width(12.dp))
                Text("Crushed ${"%.1f".format(s.crushedPct)}%", fontSize = 12.sp, style = Mono,
                    color = if (s.crushedPct > 1f) Color(0xFF9C6ADE) else Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.weight(1f))
                Text("Median ${s.p50.toInt()} IRE", fontSize = 12.sp, style = Mono, color = Color.White.copy(alpha = 0.8f))
            }
        }
        Text("Readings are of the picture as shown: log stays flat, so its highlights clip later than they look. Turn the LUT on to judge the final look.",
            fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
    }
}
