package com.desqueeze.app

import android.graphics.Color as AColor
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cinema-style preview: the frame springs between squeezed and de-squeezed widths,
 * press-and-hold shows the original, with custom transport controls.
 * Hardware playback only; the file is never modified.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewPlayer(v: VideoInfo, squeeze: Float, desqueezed: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf(false) }
    val showWide = desqueezed && !holding
    val target = v.displayW * (if (showWide) squeeze else 1f) / v.displayH
    val ratio by animateFloatAsState(target, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "ratio")

    val player = remember(v.uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(v.uri)); repeatMode = Player.REPEAT_MODE_ALL; volume = 0f; prepare(); playWhenReady = true
        }
    }
    var playing by remember(v.uri) { mutableStateOf(true) }
    var pos by remember(v.uri) { mutableLongStateOf(0L) }
    var dur by remember(v.uri) { mutableLongStateOf(v.durationMs) }
    var muted by remember(v.uri) { mutableStateOf(true) }
    var error by remember(v.uri) { mutableStateOf<String?>(null) }
    var hint by remember(v.uri) { mutableStateOf(true) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "This phone can't play ${v.codec} for preview. Lossless export still works."
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(player) {
        launch { delay(4000); hint = false }
        while (true) {
            pos = player.currentPosition; if (player.duration > 0) dur = player.duration; playing = player.playWhenReady
            delay(120)
        }
    }

    val c = MaterialTheme.colorScheme
    val frame = maxOf(ratio, 16f / 9f)
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
        AndroidView(
            factory = { PlayerView(it).apply {
                this.player = player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                setShutterBackgroundColor(AColor.BLACK); setKeepContentOnPlayerReset(true)
            } },
            modifier = if (ratio >= 16f / 9f) Modifier.fillMaxWidth().aspectRatio(ratio)
                       else Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true),
        )

        // top scrim + labels
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(if (showWide) "De-squeezed ${fmtSqueeze(squeeze)}" else "Original", accent = showWide)
            Spacer(Modifier.weight(1f))
            Pill("%.2f : 1".format(target), accent = false)
        }

        AnimatedVisibility(hint && error == null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            Pill("Hold to compare with the original", accent = false)
        }
        error?.let { msg ->
            Text(msg, color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(24.dp))
        }

        // bottom scrim + transport
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(start = 6.dp, end = 6.dp, top = 18.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.playWhenReady = !player.playWhenReady }) {
                    Icon(if (playing) AppIcons.Pause else AppIcons.Play, if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text("${fmtDuration(pos)} / ${fmtDuration(dur)}", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, style = Mono)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { muted = !muted; player.volume = if (muted) 0f else 1f }) {
                    Icon(if (muted) AppIcons.SoundOff else AppIcons.SoundOn, if (muted) "Unmute" else "Mute", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            SeekBar(if (dur > 0) pos.toFloat() / dur else 0f, c.primary) { frac -> player.seekTo((frac * dur).toLong()); pos = (frac * dur).toLong() }
        }
    }
}

@Composable
private fun Pill(text: String, accent: Boolean) {
    Box(
        Modifier.clip(CircleShape)
            .background(if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 12.sp, color = if (accent) MaterialTheme.colorScheme.onPrimary else Color.White, style = Mono)
    }
}

@Composable
private fun SeekBar(progress: Float, accent: Color, onSeek: (Float) -> Unit) {
    val seek by rememberUpdatedState(onSeek)
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = (drag ?: progress).coerceIn(0f, 1f)
    Canvas(
        Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 10.dp)
            .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(seek); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
    ) {
        val y = size.height / 2; val w = size.width
        drawLine(Color.White.copy(alpha = 0.28f), Offset(0f, y), Offset(w, y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(accent, Offset(0f, y), Offset(w * shown, y), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(Color.White, (if (drag != null) 7.dp else 5.dp).toPx(), Offset(w * shown, y))
    }
}
