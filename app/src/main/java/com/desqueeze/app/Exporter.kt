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
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.*
import androidx.media3.transformer.ExportResult as ExportResult_
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class ExportJob(val video: VideoInfo, val squeeze: Float, val lutId: String?, val lutStrength: Float)
data class ExportResult(val name: String, val width: Int, val height: Int, val note: String?)

/**
 * Pipeline: MediaCodec HW decode -> OpenGL ES (Presentation stretch = de-squeeze, optional 3D LUT)
 *           -> MediaCodec HW encode. Audio is passed through untouched (no re-encode) when possible.
 * Frames are streamed through GPU surfaces, so RAM use stays flat even for long 4K files.
 */
@OptIn(UnstableApi::class)
class Exporter(private val ctx: Context, private val settings: Settings, private val luts: LutManager) {

    fun targetSize(v: VideoInfo, squeeze: Float, mime: String): Triple<Int, Int, String?> {
        val w0 = even(v.displayW * squeeze); val h0 = even(v.displayH.toFloat())
        return fitToEncoder(w0, h0, mime)
    }

    /** Shrinks (keeping aspect) until a hardware encoder accepts the size. */
    fun fitToEncoder(w0: Int, h0: Int, mime: String): Triple<Int, Int, String?> {
        fun fits(w: Int, h: Int) = (DeviceCaps.maxWidthAt(mime, h) ?: 0) >= w
        if (fits(w0, h0)) return Triple(w0, h0, null)
        var s = 0.99f
        while (s > 0.25f) {
            val w = even(w0 * s); val h = even(h0 * s)
            if (fits(w, h)) return Triple(w, h, "Scaled to ${w}×${h} to fit this phone's encoder (shape kept).")
            s -= 0.01f
        }
        return Triple(w0, h0, null)
    }

    suspend fun export(job: ExportJob, onProgress: (Int) -> Unit): ExportResult {
        val mime = if (settings.codec == Codec.HEVC && hasEncoder(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        var (w, h, note) = targetSize(job.video, job.squeeze, mime)
        if (mime == MimeTypes.VIDEO_H264 && settings.codec == Codec.HEVC) note = listOfNotNull(note, "No HEVC encoder; used H.264.").joinToString(" ")

        val effects = mutableListOf<Effect>(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_STRETCH_TO_FIT))
        if (job.lutId != null && job.lutStrength > 0f)
            effects += SingleColorLut.createFromCube(luts.load(job.lutId).toArgbCube(job.lutStrength))

        val fps = if (job.video.fps > 0) job.video.fps else 30f
        var bitrate = (w.toLong() * h * fps * settings.quality.bitsPerPixel).toLong()
        if (mime == MimeTypes.VIDEO_H264) bitrate = (bitrate * 1.5).toLong()
        bitrate = bitrate.coerceIn(4_000_000, 400_000_000)

        val base = job.video.name.substringBeforeLast('.')
        val outName = "${base}_DESQUEEZED_${"%.2f".format(job.squeeze).trimEnd('0').trimEnd('.')}X.mp4"
        val tmp = File(ctx.cacheDir, "export_${System.nanoTime()}.mp4")

        val isHdr = job.video.colorInfo.contains("HLG") || job.video.colorInfo.contains("PQ")
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
                    .setVideoMimeType(mime)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(c: Composition, r: ExportResult_) { main.removeCallbacks(poll); if (cont.isActive) cont.resume(Unit) }
                        override fun onError(c: Composition, r: ExportResult_, e: ExportException) {
                            main.removeCallbacks(poll); tmp.delete()
                            if (cont.isActive) cont.resumeWithException(Exception(friendly(e), e))
                        }
                    }).build()
                val holder = ProgressHolder()
                poll = Runnable {
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    main.postDelayed(poll, 300)
                }
                cont.invokeOnCancellation { main.post { t.cancel(); main.removeCallbacks(poll); tmp.delete() } }
                t.start(composition, tmp.absolutePath)
                main.post(poll)
            }
        }
        onProgress(100)
        saveToGallery(tmp, outName)
        return ExportResult(outName, w, h, note)
    }

    /** Copies into Movies/<folder> via MediaStore. Original is never touched. */
    private fun saveToGallery(tmp: File, name: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + settings.folder)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw Exception("Could not create output file")
            r.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 20) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); r.update(uri, values, null, null)
        } finally { tmp.delete() }
    }

    private fun friendly(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
            "This phone's encoder can't handle this resolution/format. Try H.264 or a lower quality setting."
        ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This phone can't decode this video (codec or 10-bit not supported in hardware)."
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Source file not found."
        else -> "Export failed: ${e.errorCodeName}" + (e.message?.let { " – $it" } ?: "")
    }

    companion object {
        fun even(f: Float) = (f.roundToInt() / 2) * 2
        fun hasEncoder(mime: String) = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
    }
}
