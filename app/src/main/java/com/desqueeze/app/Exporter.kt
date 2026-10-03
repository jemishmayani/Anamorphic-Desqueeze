package com.desqueeze.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.*
import androidx.media3.transformer.ExportResult as ExportResult_
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class ExportJob(val video: VideoInfo, val squeeze: Float, val lutId: String?, val lutStrength: Float,
    val orientation: Orientation = Orientation.AUTO, val direction: Direction = Direction.AUTO) {
    val geo get() = geometry(video, squeeze, orientation, direction)
}
data class ExportResult(val name: String, val width: Int, val height: Int, val note: String?)

/**
 * Pipeline: MediaCodec HW decode -> OpenGL ES (Presentation stretch = de-squeeze, optional 3D LUT)
 *           -> MediaCodec HW encode. Audio is passed through untouched (no re-encode) when possible.
 * Frames are streamed through GPU surfaces, so RAM use stays flat even for long 4K files.
 */
@OptIn(UnstableApi::class)
class Exporter(private val ctx: Context, private val settings: Settings, private val luts: LutManager) {

    fun targetSize(g: Geometry, fps: Float, mime: String): Triple<Int, Int, String?> = fitToEncoder(g.outW, g.outH, mime, fps)

    /** Bitrate Re-encode will request for this size (also used for size estimates). */
    fun bitrateFor(w: Int, h: Int, fps: Float, mime: String): Long {
        val f = if (fps > 0) fps else 30f
        var bitrate = (w.toLong() * h * f * settings.quality.bitsPerPixel).toLong()
        if (mime == MimeTypes.VIDEO_H264) bitrate = (bitrate * 1.5).toLong()
        val cap = maxOf(4_000_000L, minOf(DeviceCaps.maxBitrate(mime).toLong(), 200_000_000L))
        return bitrate.coerceIn(4_000_000L, cap)
    }

    /**
     * Shrinks (keeping shape) until a hardware encoder really accepts the size: alignment,
     * width/height limits AND the clip's frame rate are all checked by the codec itself.
     */
    fun fitToEncoder(w0: Int, h0: Int, mime: String, fps: Float, maxW: Int = Int.MAX_VALUE): Triple<Int, Int, String?> {
        val f = if (fps > 0) fps.toDouble() else 30.0
        if (w0 <= maxW && DeviceCaps.supports(mime, w0, h0, f)) return Triple(w0, h0, null)
        var s = minOf(1f, maxW.toFloat() / w0)
        while (s > 0.2f) {
            val w = align16(w0 * s); val h = align16(h0 * s)
            if (DeviceCaps.supports(mime, w, h, f)) return Triple(w, h, "Scaled to ${w}×${h} to fit this phone's encoder (shape kept).")
            s -= 0.005f
        }
        return Triple(align16(w0 * 0.5f), align16(h0 * 0.5f), "Scaled down to fit this phone's encoder.")
    }

    private data class Attempt(val mime: String, val w: Int, val h: Int, val note: String?)

    /** Tries the best settings first, then progressively safer ones if the encoder refuses. */
    suspend fun export(job: ExportJob, onProgress: (Int) -> Unit): ExportResult {
        val hevcOk = hasEncoder(MimeTypes.VIDEO_H265)
        val first = if (settings.codec == Codec.HEVC && hevcOk) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val other = if (first == MimeTypes.VIDEO_H265) MimeTypes.VIDEO_H264 else if (hevcOk) MimeTypes.VIDEO_H265 else null
        val g = job.geo
        val w0 = g.outW; val h0 = g.outH; val fps = job.video.fps
        val started = System.nanoTime()

        val attempts = mutableListOf<Attempt>()
        suspend fun add(mime: String, maxW: Int, extra: String?) {
            val (w, h, n) = withContext(Dispatchers.Default) { fitToEncoder(w0, h0, mime, fps, maxW) }
            if (attempts.none { it.mime == mime && it.w == w && it.h == h })
                attempts += Attempt(mime, w, h, listOfNotNull(n, extra).joinToString(" ").ifEmpty { null })
        }
        add(first, Int.MAX_VALUE, if (first == MimeTypes.VIDEO_H264 && settings.codec == Codec.HEVC) "No HEVC encoder; used H.264." else null)
        add(first, 3840, null)
        if (other != null) add(other, 3840, "Saved as ${if (other == MimeTypes.VIDEO_H265) "HEVC" else "H.264"} because the first encoder refused.")
        add(MimeTypes.VIDEO_H264, 1920, "Saved at reduced size because larger sizes were refused by this phone.")

        var lastError: ExportFailure? = null
        Diag.step("Re-encode plan: " + attempts.joinToString { "${it.mime.removePrefix("video/")} ${it.w}x${it.h}" })
        for ((i, a) in attempts.withIndex()) {
            try {
                Diag.step("Attempt ${i + 1}: ${a.mime} ${a.w}x${a.h}")
                runOnce(job, a, onProgress)
                Speed.recordEncode(ctx, a.w.toLong() * a.h * (job.video.durationMs / 1000.0 * (if (fps > 0) fps else 30f)), (System.nanoTime() - started) / 1e9)
                return ExportResult(outName(job), a.w, a.h,
                    listOfNotNull(a.note, if (i > 0) "First choice failed (${lastError?.code}); retried automatically." else null)
                        .joinToString(" ").ifEmpty { null })
            } catch (e: ExportFailure) {
                Diag.step("Attempt ${i + 1} failed: ${e.message?.replace("\n", " ")}")
                lastError = e
                if (!e.retriable) throw e
                onProgress(0)
            }
        }
        throw lastError ?: Exception("Export failed")
    }

    /** Lossless: copy the file and tag it with the pixel aspect ratio. No re-encoding at all. */
    suspend fun exportLossless(job: ExportJob, onProgress: (Int) -> Unit): ExportResult = withContext(Dispatchers.IO) {
        val ext = job.video.name.substringAfterLast('.', "mp4").lowercase().let { if (it == "mov") "mov" else "mp4" }
        val name = outName(job).removeSuffix(".mp4") + ".$ext"
        Diag.step("Lossless: start, output $name")
        val g = job.geo; val t0 = System.nanoTime()
        saveStream(name, if (ext == "mov") "video/quicktime" else "video/mp4") { out ->
            PaspWriter.write(ctx, job.video.uri, out, job.squeeze, g.storedHorizontal,
                if (g.extraRotation != 0) g.rotation else null) { p -> onProgress(p) }
        }
        Speed.recordCopy(ctx, job.video.sizeBytes, (System.nanoTime() - t0) / 1e9)
        Diag.step("Lossless: done")
        val (a0, b0) = PaspWriter.ratio(job.squeeze)
        val (hs, vs) = if (g.storedHorizontal) a0 to b0 else b0 to a0
        ExportResult(name, g.dispW, g.dispH,
            "Lossless: original pixels kept, tagged $hs:$vs pixel aspect" + (if (g.extraRotation != 0) ", shown rotated ${g.rotation}°" else "") +
                ". Displays as ${g.outW}×${g.outH}.")
    }

    private fun outName(job: ExportJob) =
        "${job.video.name.substringBeforeLast('.')}_DESQUEEZED_${"%.2f".format(job.squeeze).trimEnd('0').trimEnd('.')}X.mp4"

    private suspend fun runOnce(job: ExportJob, a: Attempt, onProgress: (Int) -> Unit) {
        val effects = mutableListOf<Effect>()
        val extra = job.geo.extraRotation
        // Media3 rotates counter-clockwise; metadata rotation is clockwise.
        if (extra != 0) effects += ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - extra).toFloat()).build()
        effects += Presentation.createForWidthAndHeight(a.w, a.h, Presentation.LAYOUT_STRETCH_TO_FIT)
        if (job.lutId != null && job.lutStrength > 0f)
            effects += SingleColorLut.createFromCube(luts.load(job.lutId).toArgbCube(job.lutStrength))

        // Never more than the encoder says it can do (some refuse to start otherwise).
        val bitrate = bitrateFor(a.w, a.h, job.video.fps, a.mime)

        val tmp = File(ctx.cacheDir, "export_${System.nanoTime()}.mp4")
        val isHdr = job.video.isHdr
        val item = EditedMediaItem.Builder(MediaItem.fromUri(job.video.uri)).setEffects(Effects(listOf(), effects)).build()
        val composition = Composition.Builder(EditedMediaItemSequence(item))
            .setHdrMode(if (isHdr && settings.keepHdr) Composition.HDR_MODE_KEEP_HDR
                        else Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        suspendCancellableCoroutine<Unit> { cont ->
            val main = Handler(Looper.getMainLooper())
            main.post {
                val encoderFactory = DefaultEncoderFactory.Builder(ctx)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate.toInt()).build())
                    .setEnableFallback(true).build()
                lateinit var poll: Runnable
                val t = Transformer.Builder(ctx)
                    .setVideoMimeType(a.mime)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(c: Composition, r: ExportResult_) { main.removeCallbacks(poll); if (cont.isActive) cont.resume(Unit) }
                        override fun onError(c: Composition, r: ExportResult_, e: ExportException) {
                            main.removeCallbacks(poll); tmp.delete()
                            if (cont.isActive) cont.resumeWithException(ExportFailure(e, a, bitrate))
                        }
                    }).build()
                val holder = ProgressHolder()
                poll = Runnable {
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    main.postDelayed(poll, 300)
                }
                cont.invokeOnCancellation { main.post { t.cancel(); main.removeCallbacks(poll); tmp.delete() } }
                Diag.step("Transformer start: ${bitrate / 1_000_000} Mbps, hdr=$isHdr")
                t.start(composition, tmp.absolutePath)
                main.post(poll)
            }
        }
        onProgress(100)
        Diag.step("Encoded; saving to gallery")
        withContext(Dispatchers.IO) { saveToGallery(tmp, outName(job)) }
        Diag.step("Saved")
    }

    /** Carries the real cause so the user (and we) can see exactly what the phone rejected. */
    private class ExportFailure(e: ExportException, a: Attempt, bitrate: Long) : Exception(
        friendly(e) + "\nDetails: ${e.errorCodeName} at ${a.w}×${a.h} " +
            "${if (a.mime == MimeTypes.VIDEO_H265) "HEVC" else "H.264"}, ${bitrate / 1_000_000} Mbps" +
            rootCause(e)?.let { " – $it" }.orEmpty(), e,
    ) {
        val code: String = e.errorCodeName
        val retriable = e.errorCode in setOf(
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
        )
    }

    /** Copies into Movies/<folder> via MediaStore. Original is never touched. */
    private fun saveToGallery(tmp: File, name: String) {
        try { saveStream(name, "video/mp4") { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 20) } } }
        finally { tmp.delete() }
    }

    private fun saveStream(name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val r = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + settings.folder)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw Exception("Couldn't create the output file. Is storage full?")
        try {
            r.openOutputStream(uri)!!.use { write(it) }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); r.update(uri, values, null, null)
        } catch (e: Throwable) { r.delete(uri, null, null); throw e } // no half-written files left behind
    }

    companion object {
        fun even(f: Float) = (f.roundToInt() / 2) * 2
        fun align16(f: Float) = maxOf(16, (f.toInt() / 16) * 16)
        fun hasEncoder(mime: String) = DeviceCaps.infos
            .any { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
    }
}

private fun friendly(e: ExportException): String = when (e.errorCode) {
    ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
        "This phone's encoder refused the output size or format, even after automatic retries."
    ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        "This phone can't decode this video (codec or 10-bit not supported in hardware)."
    ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Source file not found."
    else -> "Export failed: ${e.errorCodeName}" + (e.message?.let { " – $it" } ?: "")
}

private fun rootCause(e: Throwable): String? {
    var c: Throwable? = e.cause; var last: Throwable? = null
    while (c != null && c !== last) { last = c; c = c.cause }
    return last?.let { (it.javaClass.simpleName + ": " + (it.message ?: "")).take(220) }
}
