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
    /** Built once; creating MediaCodecList is slow and was being done hundreds of times. */
    val infos: Array<MediaCodecInfo> by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
    val supportCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val MIMES = listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)

    fun all(): List<CodecCap> = infos.flatMap { ci ->
        ci.supportedTypes.filter { t -> MIMES.any { it.equals(t, true) } }.mapNotNull { t -> cap(ci, t.lowercase()) }
    }.sortedWith(compareBy({ !it.encoder }, { !it.hardware }, { it.mime != MediaFormat.MIMETYPE_VIDEO_HEVC }))

    fun encoders(mime: String) = all().filter { it.encoder && it.mime == mime }

    /** Widest frame any encoder of [mime] accepts at height [h], or null if none accept that height. */
    fun maxWidthAt(mime: String, h: Int): Int? =
        infos.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
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

/** Asks the hardware encoders themselves whether w×h at fps is accepted (checks alignment + rate too). */
fun DeviceCaps.supports(mime: String, w: Int, h: Int, fps: Double): Boolean = supportCache.getOrPut("$mime $w $h $fps") {
    val list = infos
        .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
    val pool = list.filter { it.isHardwareAccelerated }.ifEmpty { list }
    pool.any { ci ->
        try {
            val vc = ci.getCapabilitiesForType(mime).videoCapabilities ?: return@any false
            vc.isSizeSupported(w, h) && vc.areSizeAndRateSupported(w, h, fps)
        } catch (_: Exception) { false }
    }
}

fun DeviceCaps.maxBitrate(mime: String): Int =
    infos
        .filter { it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { t -> t.equals(mime, true) } }
        .mapNotNull { try { it.getCapabilitiesForType(mime).videoCapabilities?.bitrateRange?.upper } catch (_: Exception) { null } }
        .maxOrNull() ?: 100_000_000

/** Hardware (or any) decoder for this codec, and for 10-bit when needed. */
fun DeviceCaps.canDecode(codec: String, bitDepth: Int): Boolean {
    val mime = when (codec) {
        "HEVC" -> "video/hevc"; "H.264" -> "video/avc"; "AV1" -> "video/av01"; "VP9" -> "video/x-vnd.on2.vp9"
        "MPEG-4" -> "video/mp4v-es"; else -> return false
    }
    return infos.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }.any { ci ->
        if (bitDepth <= 8) true else try {
            val pl = ci.getCapabilitiesForType(mime).profileLevels
            when (codec) {
                "HEVC" -> pl.any { it.profile == PL.HEVCProfileMain10 || it.profile == PL.HEVCProfileMain10HDR10 || it.profile == PL.HEVCProfileMain10HDR10Plus }
                "H.264" -> pl.any { it.profile == PL.AVCProfileHigh10 || it.profile == PL.AVCProfileHigh422 }
                else -> true
            }
        } catch (_: Exception) { false }
    }
}
