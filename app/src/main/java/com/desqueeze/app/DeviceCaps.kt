package com.desqueeze.app

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel as PL
import android.media.MediaCodecList
import android.media.MediaFormat

data class CodecCap(
    val name: String, val mime: String, val encoder: Boolean, val hardware: Boolean,
    val maxW: Int, val maxH: Int, val maxW1080: Int?, val maxW2160: Int?,
    val fps4k: Int?, val fps1080: Int?, val maxBitrateMbps: Int, val tenBit: Boolean,
) {
    val codecLabel get() = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"
}

/** Reads what this phone's video encoders/decoders actually accept. */
object DeviceCaps {
    private val MIMES = listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)

    fun all(): List<CodecCap> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.flatMap { ci ->
        ci.supportedTypes.filter { t -> MIMES.any { it.equals(t, true) } }.mapNotNull { t -> cap(ci, t.lowercase()) }
    }.sortedWith(compareBy({ !it.encoder }, { !it.hardware }, { it.mime != MediaFormat.MIMETYPE_VIDEO_HEVC }))

    fun encoders(mime: String) = all().filter { it.encoder && it.mime == mime }

    /** Widest frame any encoder of [mime] accepts at height [h], or null if none accept that height. */
    fun maxWidthAt(mime: String, h: Int): Int? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .mapNotNull { ci -> try { ci.getCapabilitiesForType(mime).videoCapabilities?.getSupportedWidthsFor(h)?.upper } catch (_: Exception) { null } }
            .maxOrNull()

    private fun cap(ci: MediaCodecInfo, mime: String): CodecCap? {
        val caps = try { ci.getCapabilitiesForType(mime) } catch (_: Exception) { return null }
        val vc = caps.videoCapabilities ?: return null
        fun widthAt(h: Int) = try { vc.getSupportedWidthsFor(h).upper } catch (_: Exception) { null }
        fun fps(w: Int, h: Int) = try { if (vc.isSizeSupported(w, h)) vc.getSupportedFrameRatesFor(w, h).upper.toInt() else null } catch (_: Exception) { null }
        val ten = caps.profileLevels.any {
            it.profile == PL.HEVCProfileMain10 || it.profile == PL.HEVCProfileMain10HDR10 ||
                it.profile == PL.HEVCProfileMain10HDR10Plus || it.profile == PL.AVCProfileHigh10
        }
        return CodecCap(ci.name, mime, ci.isEncoder, ci.isHardwareAccelerated,
            vc.supportedWidths.upper, vc.supportedHeights.upper, widthAt(1080), widthAt(2160),
            fps(3840, 2160), fps(1920, 1080), vc.bitrateRange.upper / 1_000_000, ten)
    }
}
