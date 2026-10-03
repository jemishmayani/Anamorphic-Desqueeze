package com.desqueeze.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

data class VideoInfo(
    val uri: Uri, val name: String,
    val width: Int, val height: Int, val rotation: Int,
    val codec: String, val fps: Float, val bitDepth: Int,
    val colorInfo: String, val hasAudio: Boolean, val durationMs: Long, val sizeBytes: Long,
    val footage: Footage = Footage(),
    val audio: String? = null,
    /** Small display-oriented frame for lists. */
    val thumb: Bitmap? = null,
) {
    /** Width/height as displayed (rotation applied). */
    val displayW get() = if (rotation % 180 == 0) width else height
    val displayH get() = if (rotation % 180 == 0) height else width
    val isHdr get() = footage.hdr != null
    val summary get() = specLine(this)
}

object VideoProbe {
    fun probe(ctx: Context, uri: Uri): VideoInfo {
        var name = "video"; var size = 0L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
            }
        }
        // 1) Header analysis: works for any camera / codec, even ones this phone can't decode.
        var footage = try {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { FootageAnalyzer.analyze(it) }
            } ?: Footage()
        } catch (e: Exception) { Diag.step("Analyzer failed: $e"); Footage() }

        // 2) Android's view of the tracks (frame rate, audio, fallbacks).
        val ex = MediaExtractor()
        var vf: MediaFormat? = null; var audio: String? = null
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i); val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/") && vf == null) vf = f
                if (m.startsWith("audio/") && audio == null) audio = audioLabel(f)
            }
        } catch (_: Exception) { } finally { ex.release() }
        val f = vf
        if (f == null && footage.width == null) throw IllegalArgumentException("No video track found in “$name”.")

        val mime = f?.getString(MediaFormat.KEY_MIME) ?: ""
        val codec = footage.codec ?: when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"; MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
            else -> mime.removePrefix("video/").uppercase()
        }
        val profile = f?.intOrNull(MediaFormat.KEY_PROFILE)
        val transfer = footage.transfer ?: f?.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)?.let {
            when (it) { MediaFormat.COLOR_TRANSFER_HLG -> 18; MediaFormat.COLOR_TRANSFER_ST2084 -> 16; MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> 1; else -> null } }
        val primaries = footage.primaries ?: f?.intOrNull(MediaFormat.KEY_COLOR_STANDARD)?.let {
            when (it) { MediaFormat.COLOR_STANDARD_BT709 -> 1; MediaFormat.COLOR_STANDARD_BT2020 -> 9
                MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> 6; else -> null } }
        val range = footage.fullRange ?: f?.intOrNull(MediaFormat.KEY_COLOR_RANGE)?.let { it == MediaFormat.COLOR_RANGE_FULL }
        val tenFromProfile = profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
            profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
            profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        val bitDepth = footage.bitDepth ?: if (tenFromProfile || transfer == 16 || transfer == 18) 10 else 8
        footage = footage.copy(transfer = transfer, primaries = primaries, fullRange = range, bitDepth = bitDepth, codec = codec)

        val mmr = MediaMetadataRetriever()
        var rot = 0; var fps = f?.floatOrNull(MediaFormat.KEY_FRAME_RATE) ?: 0f; var dur = 0L; var thumb: Bitmap? = null
        try {
            mmr.setDataSource(ctx, uri)
            rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (fps <= 0f) {
                val frames = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                if (frames != null && dur > 0) fps = frames * 1000f / dur
            }
            // 3) One small frame: the list thumbnail, and (if metadata didn't say) the log-look check.
            val bmp = mmr.getScaledFrameAtTime(minOf(dur * 500L, 1_000_000L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
            if (bmp != null) {
                val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                thumb = Frames.orient(sw, f?.getInteger(MediaFormat.KEY_WIDTH) ?: 0, f?.getInteger(MediaFormat.KEY_HEIGHT) ?: 0, rot, 0)
                if (footage.log == null && footage.hdr == null) {
                    val px = IntArray(sw.width * sw.height); sw.getPixels(px, 0, sw.width, 0, 0, sw.width, sw.height)
                    if (FootageAnalyzer.looksLikeLog(px)) footage = footage.copy(log = "Log", logEstimated = true)
                }
                if (sw !== bmp) bmp.recycle()
            }
        } catch (_: Throwable) { } finally { mmr.release() }

        val w = f?.getInteger(MediaFormat.KEY_WIDTH) ?: footage.width!!
        val h = f?.getInteger(MediaFormat.KEY_HEIGHT) ?: footage.height!!
        val color = listOfNotNull(footage.primariesName, footage.hdr ?: if (transfer != null) "SDR" else null,
            range?.let { if (it) "full" else "limited" }).joinToString(" ")
        return VideoInfo(uri, name, w, h, rot, codec, fps, bitDepth, color, audio != null, dur, size, footage, audio, thumb)
    }

    private fun audioLabel(f: MediaFormat): String {
        val m = f.getString(MediaFormat.KEY_MIME) ?: ""
        val codec = when {
            m.contains("mp4a") -> "AAC"; m.contains("raw") -> "PCM"; m.contains("opus") -> "Opus"
            m.contains("ac3") -> "AC-3"; m.contains("eac3") -> "E-AC-3"; m.contains("mpeg") -> "MP3"; m.contains("flac") -> "FLAC"
            else -> m.removePrefix("audio/").uppercase()
        }
        val ch = f.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)
        val hz = f.intOrNull(MediaFormat.KEY_SAMPLE_RATE)
        return listOfNotNull(codec, ch?.let { if (it == 1) "mono" else if (it == 2) "stereo" else "$it ch" },
            hz?.let { "${it / 1000.0} kHz".replace(".0 kHz", " kHz") }).joinToString(", ")
    }

    private fun MediaFormat.intOrNull(k: String) = if (containsKey(k)) try { getInteger(k) } catch (_: Exception) { null } else null
    private fun MediaFormat.floatOrNull(k: String): Float? = if (!containsKey(k)) null else
        try { getFloat(k) } catch (_: Exception) { try { getInteger(k).toFloat() } catch (_: Exception) { null } }
}
