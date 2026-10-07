package com.desqueeze.app

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared playback position so the Frame and Look steps continue where you left off. */
/** Playhead shared by Frame and Look; it belongs to one clip, so switching clips starts the new one from its beginning. */
class PlayheadMemory { var positionMs = 0L; var playing = true; var clip: android.net.Uri? = null }

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
    scopePos: Offset = Offset(1f, 0f),
    onScopePos: (Offset) -> Unit = {},
    onScopeClose: () -> Unit = {},
    onStats: (ScopeMath.Stats?) -> Unit = {},
    /** Keeps the whole preview on screen in landscape / two-pane layouts. */
    maxHeight: androidx.compose.ui.unit.Dp? = null,
    onTrimChange: ((Pair<Long, Long>) -> Unit)? = null,
    /** Reports press-and-hold, so the Squeezed / De-squeezed toggle can show what's on screen. */
    onHold: (Boolean) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // A different clip than the remembered playhead belongs to: start it from its beginning (or its trim start).
    if (memory.clip != v.uri) { memory.clip = v.uri; memory.positionMs = trim?.first ?: 0L }
    var holding by remember { mutableStateOf(false) }
    val holdCb by rememberUpdatedState(onHold)
    LaunchedEffect(holding) { holdCb(holding) }
    val showWide = desqueezed && !holding
    val target = if (showWide) g.outRatio else g.inRatio
    val ratio by animateFloatAsState(target, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "ratio")

    // Strength changes rebuild the player, so wait until the slider settles.
    var appliedStrength by remember { mutableFloatStateOf(lutStrength) }
    LaunchedEffect(lutStrength) { delay(350); appliedStrength = lutStrength }
    var effectsFailed by remember(v.uri) { mutableStateOf(false) }
    val useLut = lut != null && lutOn && !effectsFailed
    // Orientation overrides rotate the video view itself (instant), instead of rebuilding the player with a GPU effect.
    val useRotate = false
    val effectsKey = Triple(if (useRotate) g.extraRotation else 0, useLut, if (useLut) (appliedStrength * 20).toInt() else -1)

    // Bumped to rebuild the player after a transient decoder failure (or when the user taps Retry).
    var attempt by remember(v.uri) { mutableIntStateOf(0) }
    var autoRetries by remember(v.uri) { mutableIntStateOf(0) }
    var canRetry by remember(v.uri) { mutableStateOf(false) }
    val player = remember(v.uri, effectsKey, lut, attempt) {
        ExoPlayer.Builder(ctx).build().apply {
            val fx = mutableListOf<Effect>()
            if (useRotate) fx += ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - g.extraRotation).toFloat()).build()
            if (useLut) {
                // Proxy: render the LUT at ~720p so 4K 10-bit stays smooth on phones.
                // Keep the decoded frame's own shape (the view below has that shape, so nothing is letterboxed).
                val short = minOf(g.dispW, g.dispH); val s = minOf(1f, 720f / short)
                val fileLandscape = (g.dispW >= g.dispH) == (g.extraRotation % 180 == 0)
                val h = if (fileLandscape) short * s else maxOf(g.dispW, g.dispH) * s
                fx += Presentation.createForHeight((h.toInt() / 2) * 2)
                fx += SingleColorLut.createFromCube(lut!!.toArgbCube(appliedStrength))
            }
            if (fx.isNotEmpty()) setVideoEffects(fx) // must be set before prepare()
            setMediaItem(MediaItem.fromUri(v.uri)); repeatMode = Player.REPEAT_MODE_ALL; volume = 0f
            seekTo(memory.positionMs)
            // No prepare() here: preparing claims a hardware decoder, so it waits for DecoderGate below.
        }
    }
    LaunchedEffect(player) {
        DecoderGate.acquire(player)
        player.prepare(); player.playWhenReady = memory.playing
    }
    var playing by remember { mutableStateOf(memory.playing) }
    var pos by remember { mutableLongStateOf(memory.positionMs) }
    var dur by remember(v.uri) { mutableLongStateOf(v.durationMs) }
    var muted by remember { mutableStateOf(true) }
    var error by remember(v.uri) { mutableStateOf<String?>(null) }
    var hint by remember(v.uri) { mutableStateOf(true) }
    var compare by remember { mutableStateOf<Pair<ImageBitmap, ImageBitmap>?>(null) }
    // False until the first frame can play (waiting for a decoder, preparing, or rebuffering).
    var ready by remember(player) { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val usingFx = useLut || useRotate
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                Diag.step("Preview error ${e.errorCodeName} (attempt ${attempt + 1}, fx=$usingFx): ${e.cause?.javaClass?.simpleName}")
                val unsupported = !DeviceCaps.canDecode(v.codec, v.bitDepth) ||
                    (e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED && autoRetries >= 1)
                when {
                    // A decoder that's merely busy (another preview releasing it, another app, a running export)
                    // fails the same way as a missing one, so only blame the phone when it truly has no decoder.
                    unsupported && !usingFx -> error = "This phone has no decoder for ${v.bitDepth}-bit ${v.codec}, so it can't be previewed. Lossless export still works."
                    autoRetries < 3 -> {
                        error = null; canRetry = false
                        val wait = 400L shl autoRetries
                        autoRetries++
                        scope.launch { delay(wait); attempt++ }
                    }
                    usingFx -> { effectsFailed = true; error = "Live effects aren't supported on this phone. Use Compare to see the LUT." }
                    else -> { error = "Preview couldn't start: the phone's video decoder is busy (another app or an export may be using it)."; canRetry = true }
                }
            }
        }
        player.addListener(l)
        onDispose {
            memory.positionMs = player.currentPosition; memory.playing = player.playWhenReady
            player.removeListener(l); player.release(); DecoderGate.release(player)
        }
    }
    val currentTrim by rememberUpdatedState(trim)
    LaunchedEffect(player) {
        launch { delay(4000); hint = false }
        while (true) {
            pos = player.currentPosition; if (player.duration > 0) dur = player.duration
            val tr = currentTrim
            if (tr != null && (pos >= tr.second || pos < tr.first - 250)) { player.seekTo(tr.first); pos = tr.first }
            playing = player.playWhenReady; memory.positionMs = pos; memory.playing = playing
            if (player.playbackState == Player.STATE_READY && autoRetries > 0) { autoRetries = 0 }
            ready = player.playbackState == Player.STATE_READY
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

    // Portrait results (e.g. vertical desqueeze) get a tall frame that hugs the picture, not a wide black box.
    val frame = ratio.coerceIn(0.56f, 4f)
    val videoMod = if (ratio >= frame) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true)

    // The video view (a TextureView; exposure tools read small frames from it) and the player attached to it.
    var texture by remember { mutableStateOf<android.view.TextureView?>(null) }
    val attached = remember { arrayOfNulls<ExoPlayer>(1) }
    var scopeData by remember { mutableStateOf<ScopeData?>(null) }
    val statsCb by rememberUpdatedState(onStats)
    LaunchedEffect(exposure, texture, player) {
        scopeData = null; statsCb(null)
        val tv = texture ?: return@LaunchedEffect
        if (exposure == Scope.OFF) return@LaunchedEffect
        while (true) {
            // 384 px wide (bins divide it evenly, so no striping); false color at 360 for the overlay.
            val w = if (exposure == Scope.FALSE_COLOR) 360 else 384
            val h = maxOf(2, (w / frame).toInt())
            val bmp = try { if (tv.isAvailable) tv.getBitmap(w, h) else null } catch (_: Throwable) { null }
            if (bmp != null) {
                val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                val bw = bmp.width; val bh = bmp.height; bmp.recycle()
                val d = withContext(Dispatchers.Default) {
                    ScopeData(
                        stats = ScopeMath.stats(px),
                        hist = if (exposure == Scope.HISTOGRAM) ScopeMath.rgbHistogramFine(px) else null,
                        waveImg = if (exposure == Scope.WAVEFORM)
                            ScopeRender.density(ScopeMath.waveformCounts(px, bw, bh, bw / 2, 128), bw / 2, 128, 0xFFDDE6F0.toInt()) else null,
                        paradeImg = if (exposure == Scope.PARADE) listOf(0xFFFF6A6A.toInt(), 0xFF62E592.toInt(), 0xFF6AB4FF.toInt()).mapIndexed { ch, col ->
                            ScopeRender.density(ScopeMath.waveformCounts(px, bw, bh, bw / 4, 128, ch), bw / 4, 128, col) } else null,
                        vectorImg = if (exposure == Scope.VECTORSCOPE) ScopeRender.vector(ScopeMath.vectorscope(px, 192), 192) else null,
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
    // Tall pictures may use more of the screen height so the stretch is actually visible.
    val mh = maxHeight?.let { if (frame < 1f) it * 1.3f else it }
    val boxWidth = if (mh != null) minOf(this.maxWidth, mh * frame) else this.maxWidth
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
            // One video view for the whole life of this preview. When the player is rebuilt (LUT, strength, retry)
            // only the player attached to the view changes, so the layout never removes and inserts a view in
            // the same frame as other changes (that combination crashed Compose when a LUT finished loading).
            // A TextureView stretches the picture to its bounds and lets the exposure tools read frames.
            Box(videoMod) {
                AndroidView(
                    factory = { c -> android.view.TextureView(c).also { tv -> texture = tv } },
                    update = { tv ->
                        if (attached[0] !== player) {
                            attached[0]?.let { old -> runCatching { old.clearVideoTextureView(tv) } }
                            player.setVideoTextureView(tv); attached[0] = player
                        }
                    },
                    onRelease = { tv ->
                        attached[0]?.let { p -> runCatching { p.clearVideoTextureView(tv) } }
                        attached[0] = null; texture = null
                    },
                    // Laid out at the squeezed shape and stretched on screen. With a live LUT, Media3 fits each
                    // frame into the view keeping its shape, so a view already stretched would show black bars
                    // and lose the de-squeeze.
                    modifier = Modifier.fillMaxSize().squeezedThenStretched(g.inRatio, ratio).rotatedContent(g.extraRotation),
                )
            }
            if (exposure == Scope.FALSE_COLOR) scopeData?.falseColor?.let {
                Box(videoMod) { Image(it, "False color", Modifier.fillMaxSize().rotatedContent(g.extraRotation), contentScale = ContentScale.FillBounds) }
            }
            // Shows how much the picture was stretched: the original frame's shape as a dashed outline.
            StretchOutline(g, showWide, videoMod)
            GuideOverlay(guides, videoMod)

            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent))).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Pill(if (showWide) "De-squeezed ${fmtSqueeze(stretchFactor(g))} ${if (g.vertical) "↕" else "↔"}"
                     else if (holding) "Squeezed (holding)" else "Squeezed", accent = showWide)
                if (useLut) { Spacer(Modifier.width(6.dp)); Pill("LUT ${(appliedStrength * 100).toInt()}%", accent = false, warm = true) }
                Spacer(Modifier.weight(1f))
                Pill(g.ratioLabel(target), accent = false)
            }
            if (hint && error == null) Box(Modifier.align(Alignment.Center)) { Pill("Hold to compare with the original", accent = false) }
            if (comparing) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            else if (!ready && error == null && compare == null)
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.8f), strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            error?.let { msg ->
                Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(msg, color = Color.White, style = MaterialTheme.typography.bodySmall)
                    if (canRetry) TextButton(onClick = { error = null; canRetry = false; autoRetries = 0; attempt++ }) { Text("Retry", color = Color.White) }
                }
            }

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

            if (exposure != Scope.OFF) ScopeOverlay(exposure, scopeData, scopeLarge, onScopeLarge, scopePos, onScopePos, onScopeClose)
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
    val cacheKey = "${v.uri}|${g.extraRotation}"
    val frames = remember(cacheKey) {
        mutableStateListOf<ImageBitmap?>().apply { addAll(FilmstripCache.get(cacheKey) ?: List(n) { null }) }
    }
    LaunchedEffect(cacheKey) {
        if (frames.all { it != null }) return@LaunchedEffect          // already extracted for this clip
        // Let the preview claim its decoder first; then read all thumbnails with ONE frame reader,
        // instead of eight readers each opening a decoder alongside the player.
        delay(900)
        val times = List(n) { i -> if (v.durationMs > 0) v.durationMs * (2 * i + 1) / (2 * n) else 0L }
        withContext(Dispatchers.IO) {
            Frames.framesAt(ctx, v, times, 200, g.extraRotation) { i, b -> withContext(Dispatchers.Main) { frames[i] = b.asImageBitmap() } }
        }
        if (frames.all { it != null }) FilmstripCache.put(cacheKey, frames.toList())
    }
    val stripLoading = frames.any { it == null }
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
            // Thumbnails still loading: a thin indeterminate bar along the bottom of the strip.
            if (stripLoading) LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                color = c.primary.copy(alpha = 0.8f), trackColor = Color.Transparent)
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
    /** Pre-rendered, phosphor-toned scope images (drawn scaled with filtering, graticule on top). */
    val waveImg: ImageBitmap? = null,
    val paradeImg: List<ImageBitmap>? = null,
    val vectorImg: ImageBitmap? = null,
    val falseColor: ImageBitmap? = null,
)

/** Turns scope densities into images: colour × phosphor brightness, alpha = brightness. */
private object ScopeRender {
    fun density(counts: IntArray, cols: Int, rows: Int, argb: Int, columnMajor: Boolean = true): ImageBitmap {
        val ph = ScopeMath.phosphor(counts)
        val r = argb shr 16 and 255; val g = argb shr 8 and 255; val b = argb and 255
        val out = IntArray(cols * rows)
        for (x in 0 until cols) for (y in 0 until rows) {
            val v = ph[if (columnMajor) x * rows + y else y * cols + x]
            if (v > 0f) out[y * cols + x] = ((v * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(out, cols, rows, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    fun vector(v: FloatArray, n: Int): ImageBitmap {
        val out = IntArray(n * n) { i -> val a = v[i]; if (a <= 0f) 0 else (((0.2f + 0.8f * a) * 255).toInt() shl 24) or 0xBFE6C9 }
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

private val ScopeBg = Color(0xD90B0D10)
private val Grat = Color.White.copy(alpha = 0.16f)
private val ChannelColors = listOf(Color(0xFFFF5A5A), Color(0xFF5BE08A), Color(0xFF5AA9FF))

/**
 * A compact scope floating over the video's corner, like on a camera monitor, so the preview never
 * grows and the tools below stay reachable. Tap to enlarge or shrink; × turns it off.
 * False color covers the picture itself, so it only shows a small label with ×.
 */
@Composable
fun BoxScope.ScopeOverlay(scope: Scope, data: ScopeData?, large: Boolean, onLarge: (Boolean) -> Unit,
                          pos: Offset, onPos: (Offset) -> Unit, onClose: () -> Unit) {
    if (scope == Scope.FALSE_COLOR) {
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).clip(CircleShape).background(ScopeBg)
            .padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("False color", fontSize = 12.sp, color = Color.White, style = Mono)
            CloseDot(onClose)
        }
    } else BoxWithConstraints(Modifier.matchParentSize().padding(start = 10.dp, end = 10.dp, top = 46.dp, bottom = 46.dp)) {
        val square = scope == Scope.VECTORSCOPE
        val w = if (large) maxWidth * 0.66f else maxWidth * 0.40f
        val h = if (square) minOf(w, maxHeight) else minOf(maxHeight, if (large) maxHeight else w * 0.56f)
        val boxW = if (square) h else w
        // Movable: drag anywhere inside the video; position is stored as a fraction of the free space,
        // so it stays put when the scope changes size or the preview changes shape.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val freeX = with(density) { (maxWidth - boxW).toPx() }.coerceAtLeast(1f)
        val freeY = with(density) { (maxHeight - h).toPx() }.coerceAtLeast(1f)
        val posNow by rememberUpdatedState(pos)
        var dragging by remember { mutableStateOf(false) }
        Box(Modifier
            .offset { androidx.compose.ui.unit.IntOffset((pos.x.coerceIn(0f, 1f) * freeX).toInt(), (pos.y.coerceIn(0f, 1f) * freeY).toInt()) }
            .size(boxW, h).clip(RoundedCornerShape(12.dp)).background(ScopeBg)
            .border(if (dragging) 1.5.dp else 1.dp, Color.White.copy(alpha = if (dragging) 0.45f else 0.10f), RoundedCornerShape(12.dp))
            .pointerInput(freeX, freeY) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                ) { change, d ->
                    change.consume()
                    onPos(Offset((posNow.x + d.x / freeX).coerceIn(0f, 1f), (posNow.y + d.y / freeY).coerceIn(0f, 1f)))
                }
            }
            .clickable { onLarge(!large) }) {
            // Grip: a hint that the scope can be dragged.
            Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(22.dp, 3.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = if (dragging) 0.8f else 0.35f)))
            val d = data
            if (d == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp)
            else Canvas(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
                when (scope) {
                    Scope.HISTOGRAM -> d.hist?.let { drawRgbHistogram(it) }
                    Scope.WAVEFORM -> d.waveImg?.let { drawScopeImage(it, 0f, size.width); drawIreGrid(labels = true) }
                    Scope.PARADE -> d.paradeImg?.let { p ->
                        val third = size.width / 3f
                        p.forEachIndexed { i, img -> drawScopeImage(img, i * third + 2f, third - 4f) }
                        drawIreGrid(labels = true)
                    }
                    Scope.VECTORSCOPE -> d.vectorImg?.let { drawVectorscope(it) }
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

private val gratLabel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(150, 255, 255, 255); textSize = 18f; typeface = android.graphics.Typeface.MONOSPACE
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIreGrid(labels: Boolean = false) {
    for (ire in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
        val y = size.height * (1 - ire)
        drawLine(if (ire == 0f || ire == 1f) Grat.copy(alpha = 0.3f) else Grat, Offset(0f, y), Offset(size.width, y), 1f)
        if (labels && ire > 0f) drawContext.canvas.nativeCanvas.drawText("${(ire * 100).toInt()}", 2f, y + 16f, gratLabel)
    }
}

/** Draws a pre-rendered scope image stretched into [left, left+width] with smooth (bilinear) filtering. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScopeImage(img: ImageBitmap, left: Float, width: Float) {
    drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), 0),
        dstSize = androidx.compose.ui.unit.IntSize(width.toInt(), size.height.toInt()),
        blendMode = androidx.compose.ui.graphics.BlendMode.Plus, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
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
    for (ch in 0..2) drawPath(path(h[ch]), ChannelColors[ch].copy(alpha = 0.32f), blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
    drawPath(path(h[3]), Color.White.copy(alpha = 0.92f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** Cb right, Cr up; 75% colour-bar targets, the skin-tone line, and the picture's colour cloud. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVectorscope(img: ImageBitmap) {
    val r = minOf(size.width, size.height) / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero, srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
        dstOffset = androidx.compose.ui.unit.IntOffset((c.x - r).toInt(), (c.y - r).toInt()),
        dstSize = androidx.compose.ui.unit.IntSize((2 * r).toInt(), (2 * r).toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
    drawCircle(Grat, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    drawCircle(Grat.copy(alpha = 0.08f), r * 0.5f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    drawLine(Grat, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f); drawLine(Grat, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
    val a = Math.toRadians(ScopeMath.SKIN_LINE_DEG.toDouble())
    drawLine(Color(0xFFF4C7A1).copy(alpha = 0.55f), c, Offset(c.x + r * Math.cos(a).toFloat(), c.y - r * Math.sin(a).toFloat()), 1.2f)
    ScopeMath.TARGETS.forEachIndexed { i, (_, cbcr) ->
        val p = Offset(c.x + cbcr.first * 2 * r, c.y - cbcr.second * 2 * r)
        val col = listOf(ChannelColors[0], Color(0xFFE070E0), ChannelColors[2], Color(0xFF60D8E0), ChannelColors[1], Color(0xFFF2D35B))[i]
        drawRect(col, topLeft = Offset(p.x - 3f, p.y - 3f), size = androidx.compose.ui.geometry.Size(6f, 6f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
    }
}

/** Filmstrip thumbnails per clip (and rotation), so moving between Frame and Look doesn't re-read them. */
object FilmstripCache {
    private val map = object : LinkedHashMap<String, List<ImageBitmap?>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<ImageBitmap?>>) = size > 12
    }
    @Synchronized fun get(k: String) = map[k]
    @Synchronized fun put(k: String, v: List<ImageBitmap?>) { map[k] = v }
}

/** How much the picture is stretched along its stretch axis (e.g. 1.20), whichever way it goes. */
fun stretchFactor(g: Geometry): Float =
    if (g.vertical) g.outH.toFloat() / g.dispH.coerceAtLeast(1) else g.outW.toFloat() / g.dispW.coerceAtLeast(1)

/**
 * Lays the child out at [squeezed] aspect (centered in this box, whose aspect is [shown]) and scales it up
 * on screen to fill the box, so the child's own size always has the picture's squeezed shape.
 */
fun Modifier.squeezedThenStretched(squeezed: Float, shown: Float): Modifier {
    if (squeezed <= 0f || shown <= 0f) return this
    val sx = if (shown > squeezed) shown / squeezed else 1f
    val sy = if (shown < squeezed) squeezed / shown else 1f
    if (sx == 1f && sy == 1f) return this
    return this.graphicsLayer { scaleX = sx; scaleY = sy }.layout { m, c ->
        val w = c.maxWidth; val h = c.maxHeight
        val cw = (w / sx).toInt().coerceAtLeast(1); val ch = (h / sy).toInt().coerceAtLeast(1)
        val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(cw, ch))
        layout(w, h) { p.place((w - cw) / 2, (h - ch) / 2) }
    }
}

/**
 * Lays the child out with width and height swapped for 90°/270° and rotates it, so a view showing the
 * file's own orientation fills this box at the overridden orientation, without rebuilding the player.
 */
fun Modifier.rotatedContent(deg: Int): Modifier {
    val d = ((deg % 360) + 360) % 360
    if (d == 0) return this
    return this.layout { m, c ->
        val w = c.maxWidth; val h = c.maxHeight
        val swap = d % 180 != 0
        val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(if (swap) h else w, if (swap) w else h))
        layout(w, h) { p.place((w - p.width) / 2, (h - p.height) / 2) }
    }.graphicsLayer { rotationZ = d.toFloat() }
}

/** Dashed outline of the original (squeezed) frame with arrows on the stretch axis; fades with the de-squeeze toggle. */
@Composable
fun StretchOutline(g: Geometry, show: Boolean, modifier: Modifier) {
    val f = stretchFactor(g)
    val a by animateFloatAsState(if (show && f > 1.005f) 1f else 0f, tween(300), label = "outline")
    // Always one Canvas (drawing nothing when hidden), so the preview's layout never gains or loses a child here.
    Canvas(modifier) {
        if (a <= 0f) return@Canvas
        val w = size.width; val h = size.height
        val iw = if (g.vertical) w else w / f; val ih = if (g.vertical) h / f else h
        val l = (w - iw) / 2; val t = (h - ih) / 2
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        drawRect(Color.White.copy(alpha = 0.75f * a), Offset(l, t), androidx.compose.ui.geometry.Size(iw, ih),
            style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f, pathEffect = dash))
        // Arrows from the original edge out to the stretched edge.
        val arrow = Color.White.copy(alpha = 0.85f * a); val head = 10f
        if (g.vertical) {
            for ((from, to) in listOf(t to 0f, t + ih to h)) if (kotlin.math.abs(from - to) > 12f) {
                val x = w / 2; drawLine(arrow, Offset(x, from), Offset(x, to + if (to > from) -2f else 2f), 3f)
                val dir = if (to > from) 1f else -1f
                drawLine(arrow, Offset(x, to - dir * 2f), Offset(x - head, to - dir * (head + 2f)), 3f)
                drawLine(arrow, Offset(x, to - dir * 2f), Offset(x + head, to - dir * (head + 2f)), 3f)
            }
        } else {
            for ((from, to) in listOf(l to 0f, l + iw to w)) if (kotlin.math.abs(from - to) > 12f) {
                val y = h / 2; drawLine(arrow, Offset(from, y), Offset(to + if (to > from) -2f else 2f, y), 3f)
                val dir = if (to > from) 1f else -1f
                drawLine(arrow, Offset(to - dir * 2f, y), Offset(to - dir * (head + 2f), y - head), 3f)
                drawLine(arrow, Offset(to - dir * 2f, y), Offset(to - dir * (head + 2f), y + head), 3f)
            }
        }
    }
}
