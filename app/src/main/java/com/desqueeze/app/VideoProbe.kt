package com.desqueeze.app

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

data class VideoInfo(
    val uri: Uri, val name: String,
    val width: Int, val height: Int, val rotation: Int,
    val codec: String, val fps: Float, val bitDepth: Int,
    val colorInfo: String, val hasAudio: Boolean, val durationMs: Long, val sizeBytes: Long,
) {
    /** Width/height as displayed (rotation applied). */
    val displayW get() = if (rotation % 180 == 0) width else height
    val displayH get() = if (rotation % 180 == 0) height else width
    val summary get() = "${displayW}×${displayH} · $codec · ${"%.2f".format(fps)} fps · ${bitDepth}-bit" +
            (if (colorInfo.isNotEmpty()) " · $colorInfo" else "") + (if (hasAudio) " · audio" else " · no audio")
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
        val ex = MediaExtractor()
        var vf: MediaFormat? = null; var audio = false
        try {
            ex.setDataSource(ctx, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i); val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/") && vf == null) vf = f
                if (m.startsWith("audio/")) audio = true
            }
        } finally { ex.release() }
        val f = vf ?: throw IllegalArgumentException("No video track found in this file.")
        val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
        val codec = when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"; MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
            else -> mime.removePrefix("video/").uppercase()
        }
        val profile = f.intOrNull(MediaFormat.KEY_PROFILE)
        val transfer = f.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        val is10 = (mime == MediaFormat.MIMETYPE_VIDEO_HEVC && (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus)) ||
                transfer == MediaFormat.COLOR_TRANSFER_HLG || transfer == MediaFormat.COLOR_TRANSFER_ST2084
        val color = buildList {
            when (f.intOrNull(MediaFormat.KEY_COLOR_STANDARD)) {
                MediaFormat.COLOR_STANDARD_BT709 -> add("BT.709"); MediaFormat.COLOR_STANDARD_BT2020 -> add("BT.2020")
                MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT601_PAL -> add("BT.601")
            }
            when (transfer) {
                MediaFormat.COLOR_TRANSFER_HLG -> add("HLG"); MediaFormat.COLOR_TRANSFER_ST2084 -> add("PQ")
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> add("SDR")
            }
            when (f.intOrNull(MediaFormat.KEY_COLOR_RANGE)) {
                MediaFormat.COLOR_RANGE_FULL -> add("full"); MediaFormat.COLOR_RANGE_LIMITED -> add("limited")
            }
        }.joinToString(" ")
        val mmr = MediaMetadataRetriever()
        var rot = 0; var fps = f.floatOrNull(MediaFormat.KEY_FRAME_RATE) ?: 0f; var dur = 0L
        try {
            mmr.setDataSource(ctx, uri)
            rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (fps <= 0f) {
                val frames = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                if (frames != null && dur > 0) fps = frames * 1000f / dur
            }
        } catch (_: Exception) { } finally { mmr.release() }
        return VideoInfo(uri, name, f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT), rot,
            codec, fps, if (is10) 10 else 8, color, audio, dur, size)
    }
    private fun MediaFormat.intOrNull(k: String) = if (containsKey(k)) try { getInteger(k) } catch (_: Exception) { null } else null
    private fun MediaFormat.floatOrNull(k: String): Float? = if (!containsKey(k)) null else
        try { getFloat(k) } catch (_: Exception) { try { getInteger(k).toFloat() } catch (_: Exception) { null } }
}
