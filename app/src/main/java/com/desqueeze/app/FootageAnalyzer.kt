package com.desqueeze.app

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Everything we can learn about a clip from its MP4/MOV header, camera-agnostic. */
data class Footage(
    val fourcc: String? = null,
    val codec: String? = null,
    val profile: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitDepth: Int? = null,
    val chroma: String? = null,
    val primaries: Int? = null,
    val transfer: Int? = null,
    val matrix: Int? = null,
    val fullRange: Boolean? = null,
    val dolbyVision: Boolean = false,
    val masteringInfo: Boolean = false,
    val pixelAspect: Pair<Int, Int>? = null,
    val make: String? = null,
    val model: String? = null,
    /** Log profile named in the file's metadata, e.g. "S-Log3". */
    val log: String? = null,
    /** True when [log] comes from a picture-statistics estimate, not metadata. */
    val logEstimated: Boolean = false,
) {
    val hdr: String? get() = when {
        dolbyVision -> "Dolby Vision"
        transfer == 16 -> "HDR10"
        transfer == 18 -> "HLG"
        else -> null
    }
    val camera: String? get() = listOfNotNull(make, model?.takeUnless { m -> make != null && m.contains(make, true) })
        .joinToString(" ").ifBlank { null }
    val primariesName: String? get() = when (primaries) {
        1 -> "BT.709"; 5, 6 -> "BT.601"; 9 -> "BT.2020"; 11 -> "DCI-P3"; 12 -> "Display P3"; null, 2 -> null; else -> "Primaries $primaries"
    }
    val transferName: String? get() = when (transfer) {
        1, 6, 14, 15 -> "BT.709 / SDR"; 13 -> "sRGB"; 16 -> "PQ (ST 2084)"; 18 -> "HLG"; 8 -> "Linear"; null, 2 -> null; else -> "Transfer $transfer"
    }
    val matrixName: String? get() = when (matrix) {
        1 -> "BT.709"; 5, 6 -> "BT.601"; 9 -> "BT.2020 NCL"; 10 -> "BT.2020 CL"; 0 -> "RGB"; null, 2 -> null; else -> "Matrix $matrix"
    }
}

/**
 * Parses only the file header (moov + small metadata boxes), never the media data,
 * so it's instant even for multi-GB files.
 */
object FootageAnalyzer {

    private class B(val type: String, val start: Int, val ps: Int, val pe: Int)

    private val LOG_PATTERNS = listOf(
        "D-Log M" to "d[-_ ]?log[-_ ]?m(?![a-z])",
        "D-Log" to "d[-_ ]?log(?![a-z])",
        "S-Log3" to "s[-_ ]?log ?3",
        "S-Log2" to "s[-_ ]?log ?2",
        "V-Log L" to "v[-_ ]?log[-_ ]?l(?![a-z])",
        "V-Log" to "v[-_ ]?log(?![a-z])",
        "Canon Log 3" to "(c[-_ ]?log ?3|canon ?log ?3)",
        "Canon Log 2" to "(c[-_ ]?log ?2|canon ?log ?2)",
        "Canon Log" to "(c[-_ ]?log(?![a-z0-9])|canon ?log)",
        "F-Log2" to "f[-_ ]?log ?2",
        "F-Log" to "f[-_ ]?log(?![a-z0-9])",
        "N-Log" to "n[-_ ]?log(?![a-z])",
        "Apple Log" to "apple ?log",
        "L-Log" to "l[-_ ]?log(?![a-z])",
        "I-Log" to "i[-_ ]?log(?![a-z])",
        "Samsung Log" to "samsung ?log",
        "GoPro Log" to "gopro ?log",
        "Z-Log2" to "z[-_ ]?log ?2",
        "Blackmagic Film" to "(bmd ?film|blackmagic design film|gen ?5 ?film)",
        "ARRI LogC" to "(arri ?log ?c|log ?c[34](?![0-9]))",
        "RED Log3G10" to "log3g10",
        "Cineon / Log" to "cineon",
    ).map { (name, re) -> name to Regex("(?<![a-z0-9])$re", RegexOption.IGNORE_CASE) }

    private val BRANDS = listOf("DJI", "GoPro", "Insta360", "Sony", "Canon", "Panasonic", "FUJIFILM", "Nikon", "Apple",
        "samsung", "Blackmagic", "Xiaomi", "Google", "OnePlus", "Leica", "Z CAM", "SIGMA", "OLYMPUS", "OM Digital", "Huawei", "vivo", "OPPO")

    fun analyze(ch: FileChannel): Footage {
        val size = ch.size()
        val tops = mutableListOf<Triple<String, Long, Long>>()
        var pos = 0L; val h = ByteBuffer.allocate(16)
        while (pos + 8 <= size) {
            h.clear(); ch.read(h, pos); h.flip()
            var len = h.int.toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { h.get(it) }, Charsets.ISO_8859_1)
            if (len == 1L) len = h.long else if (len == 0L) len = size - pos
            if (len < 8 || pos + len > size) break
            tops += Triple(type, pos, len); pos += len
        }
        val moov = tops.firstOrNull { it.first == "moov" } ?: return Footage()
        if (moov.third > 128L * 1024 * 1024) return Footage()
        val d = read(ch, moov.second, moov.third.toInt())

        var f = Footage()
        val meta = HashMap<String, String>()
        // mask = header bytes with sample tables blanked (they're binary noise for text search)
        val mask = d.copyOf()

        fun walk(start: Int, end: Int, path: String) {
            for (b in boxes(d, start, end)) {
                when (b.type) {
                    "moov", "trak", "mdia", "minf", "udta", "edts", "dinf" -> walk(b.ps, b.pe, path + "/" + b.type)
                    "stbl" -> {
                        for (c in boxes(d, b.ps, b.pe)) if (c.type != "stsd") mask.fill(0, c.start, c.pe)
                    }
                    "meta" -> {
                        val qt = b.pe - b.ps >= 8 && str(d, b.ps + 4, 4) == "hdlr"
                        parseMeta(d, if (qt) b.ps else b.ps + 4, b.pe, meta)
                    }
                    else -> if (path.endsWith("udta") && b.type.isNotEmpty() && b.type[0] == '\u00A9') {
                        qtString(d, b)?.let { meta.putIfAbsent(b.type, it) }
                    }
                }
            }
        }
        walk(0, d.size, "")

        // video track sample entry
        for (trak in boxes(d, 8, d.size).filter { it.type == "trak" }) {
            val mdia = child(d, trak, "mdia") ?: continue
            val hdlr = child(d, mdia, "hdlr") ?: continue
            if (str(d, hdlr.ps + 8, 4) != "vide") continue
            val stsd = child(d, child(d, child(d, mdia, "minf") ?: continue, "stbl") ?: continue, "stsd") ?: continue
            val e = boxes(d, stsd.ps + 8, stsd.pe).firstOrNull() ?: continue
            f = sampleEntry(d, e, f)
            break
        }

        // extra top-level metadata boxes (e.g. Sony XML, XMP in uuid)
        val extra = StringBuilder()
        for ((type, off, len) in tops) {
            if (type in setOf("moov", "mdat", "free", "skip", "wide") || len > 4L * 1024 * 1024) continue
            extra.append(String(read(ch, off, len.toInt()), Charsets.ISO_8859_1)).append(' ')
        }

        val text = String(mask, Charsets.ISO_8859_1) + " " + meta.values.joinToString(" ") + " " + extra
        val log = LOG_PATTERNS.firstOrNull { (_, re) -> re.containsMatchIn(text) }?.first

        var make = meta["com.apple.quicktime.make"] ?: meta["\u00A9mak"]
        var model = meta["com.apple.quicktime.model"] ?: meta["\u00A9mod"] ?: meta["\u00A9mdl"]
        Regex("manufacturer=\"([^\"]+)\"[^>]*?modelName=\"([^\"]+)\"").find(extra)?.let { m ->
            make = make ?: m.groupValues[1]; model = model ?: m.groupValues[2]
        }
        if (make == null) make = BRANDS.firstOrNull { b -> Regex("(?<![A-Za-z])${Regex.escape(b)}(?![A-Za-z])").containsMatchIn(text) }
            ?.let { if (it == "samsung") "Samsung" else it }

        return f.copy(log = log, make = make?.trim()?.take(40), model = model?.trim()?.take(40))
    }

    private fun sampleEntry(d: ByteArray, e: B, base: Footage): Footage {
        var f = base.copy(fourcc = e.type)
        if (e.pe - e.ps >= 78) f = f.copy(width = u16(d, e.ps + 24), height = u16(d, e.ps + 26))
        f = when (e.type) {
            "hvc1", "hev1" -> f.copy(codec = "HEVC")
            "dvh1", "dvhe" -> f.copy(codec = "HEVC", dolbyVision = true)
            "avc1", "avc3" -> f.copy(codec = "H.264")
            "dva1", "dvav" -> f.copy(codec = "H.264", dolbyVision = true)
            "av01" -> f.copy(codec = "AV1")
            "vp09" -> f.copy(codec = "VP9")
            "apch" -> f.copy(codec = "ProRes", profile = "422 HQ", bitDepth = 10, chroma = "4:2:2")
            "apcn" -> f.copy(codec = "ProRes", profile = "422", bitDepth = 10, chroma = "4:2:2")
            "apcs" -> f.copy(codec = "ProRes", profile = "422 LT", bitDepth = 10, chroma = "4:2:2")
            "apco" -> f.copy(codec = "ProRes", profile = "422 Proxy", bitDepth = 10, chroma = "4:2:2")
            "ap4h" -> f.copy(codec = "ProRes", profile = "4444", bitDepth = 12, chroma = "4:4:4")
            "ap4x" -> f.copy(codec = "ProRes", profile = "4444 XQ", bitDepth = 12, chroma = "4:4:4")
            "mp4v" -> f.copy(codec = "MPEG-4")
            else -> f.copy(codec = e.type.uppercase())
        }
        if (e.pe - e.ps < 78) return f
        for (c in boxes(d, e.ps + 78, e.pe)) {
            try {
                when (c.type) {
                    "hvcC" -> if (c.pe - c.ps >= 19) {
                        val prof = d[c.ps + 1].toInt() and 0x1F
                        f = f.copy(bitDepth = (d[c.ps + 17].toInt() and 7) + 8, chroma = chroma(d[c.ps + 16].toInt() and 3),
                            profile = when (prof) { 1 -> "Main"; 2 -> "Main 10"; 3 -> "Main Still"; 4 -> "Range Extensions"; else -> null })
                    }
                    "avcC" -> {
                        val prof = d[c.ps + 1].toInt() and 0xFF
                        var bd = 8; var cf = 1
                        if (prof in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                            var p = c.ps + 5
                            val nSps = d[p].toInt() and 0x1F; p++
                            repeat(nSps) { p += 2 + u16(d, p) }
                            val nPps = d[p].toInt() and 0xFF; p++
                            repeat(nPps) { p += 2 + u16(d, p) }
                            if (p + 2 <= c.pe) { cf = d[p].toInt() and 3; bd = (d[p + 1].toInt() and 7) + 8 }
                        }
                        f = f.copy(bitDepth = bd, chroma = chroma(cf), profile = when (prof) {
                            66 -> "Baseline"; 77 -> "Main"; 100 -> "High"; 110 -> "High 10"; 122 -> "High 4:2:2"; 244 -> "High 4:4:4"; else -> null })
                    }
                    "av1C" -> {
                        val b = d[c.ps + 2].toInt()
                        val hi = b and 0x40 != 0; val twelve = b and 0x20 != 0
                        val sx = b and 0x08 != 0; val sy = b and 0x04 != 0; val mono = b and 0x10 != 0
                        f = f.copy(bitDepth = if (hi) (if (twelve) 12 else 10) else 8,
                            chroma = if (mono) "4:0:0" else if (sx && sy) "4:2:0" else if (sx) "4:2:2" else "4:4:4")
                    }
                    "vpcC" -> {
                        val b = d[c.ps + 6].toInt() and 0xFF
                        f = f.copy(bitDepth = b shr 4, chroma = when ((b shr 1) and 7) { 0, 1 -> "4:2:0"; 2 -> "4:2:2"; 3 -> "4:4:4"; else -> null },
                            fullRange = b and 1 == 1, primaries = d[c.ps + 7].toInt() and 0xFF,
                            transfer = d[c.ps + 8].toInt() and 0xFF, matrix = d[c.ps + 9].toInt() and 0xFF)
                    }
                    "colr" -> {
                        val kind = str(d, c.ps, 4)
                        if (kind == "nclx" || kind == "nclc") {
                            f = f.copy(primaries = u16(d, c.ps + 4), transfer = u16(d, c.ps + 6), matrix = u16(d, c.ps + 8),
                                fullRange = if (kind == "nclx" && c.ps + 10 < c.pe) (d[c.ps + 10].toInt() and 0x80) != 0 else f.fullRange)
                        }
                    }
                    "pasp" -> {
                        val hs = u32(d, c.ps).toInt(); val vs = u32(d, c.ps + 4).toInt()
                        if (hs > 0 && vs > 0) f = f.copy(pixelAspect = hs to vs)
                    }
                    "dvcC", "dvvC", "dvwC" -> f = f.copy(dolbyVision = true)
                    "mdcv", "clli", "SmDm", "CoLL" -> f = f.copy(masteringInfo = true)
                }
            } catch (_: IndexOutOfBoundsException) { /* truncated box: ignore */ }
        }
        return f
    }

    private fun chroma(cf: Int) = when (cf) { 0 -> "4:0:0"; 1 -> "4:2:0"; 2 -> "4:2:2"; 3 -> "4:4:4"; else -> null }

    /** QuickTime 'meta': 'keys' names + 'ilst' values (iPhone make/model etc.) and iTunes-style ©xxx items. */
    private fun parseMeta(d: ByteArray, start: Int, end: Int, out: MutableMap<String, String>) {
        val kids = boxes(d, start, end)
        val names = ArrayList<String>()
        kids.firstOrNull { it.type == "keys" }?.let { k ->
            var p = k.ps + 8; val n = u32(d, k.ps + 4).toInt()
            repeat(n) { if (p + 8 > k.pe) return@repeat; val sz = u32(d, p).toInt(); names += str(d, p + 8, sz - 8); p += sz }
        }
        kids.firstOrNull { it.type == "ilst" }?.let { il ->
            for (item in boxes(d, il.ps, il.pe)) {
                val idx = u32(d, item.start + 4).toInt()
                val key = if (idx in 1..names.size) names[idx - 1] else item.type
                val data = boxes(d, item.ps, item.pe).firstOrNull { it.type == "data" } ?: continue
                if (data.pe - data.ps > 8 && u32(d, data.ps).toInt() and 0xFFFFFF in setOf(1, 0))
                    out.putIfAbsent(key, String(d, data.ps + 8, data.pe - data.ps - 8, Charsets.UTF_8).trim('\u0000', ' '))
            }
        }
    }

    private fun qtString(d: ByteArray, b: B): String? {
        if (b.pe - b.ps < 4) return null
        if (b.pe - b.ps > 16 && str(d, b.ps + 4, 4) == "data") return String(d, b.ps + 16, b.pe - b.ps - 16, Charsets.UTF_8).trim('\u0000', ' ')
        val len = u16(d, b.ps)
        if (len <= 0 || b.ps + 4 + len > b.pe) return null
        return String(d, b.ps + 4, len, Charsets.UTF_8).trim('\u0000', ' ')
    }

    private fun boxes(d: ByteArray, start: Int, end: Int): List<B> {
        val out = ArrayList<B>(); var p = start
        while (p + 8 <= end) {
            var size = u32(d, p); var hdr = 8
            if (size == 1L) { if (p + 16 > end) break; size = ByteBuffer.wrap(d, p + 8, 8).long; hdr = 16 }
            else if (size == 0L) size = (end - p).toLong()
            if (size < hdr || p + size > end) break
            out += B(str(d, p + 4, 4), p, p + hdr, (p + size).toInt()); p += size.toInt()
        }
        return out
    }

    private fun child(d: ByteArray, b: B, type: String) = boxes(d, b.ps, b.pe).firstOrNull { it.type == type }
    private fun u16(d: ByteArray, p: Int) = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
    private fun u32(d: ByteArray, p: Int): Long = ByteBuffer.wrap(d, p, 4).int.toLong() and 0xFFFFFFFFL
    private fun str(d: ByteArray, p: Int, n: Int) = if (p + n <= d.size && n >= 0) String(d, p, n, Charsets.ISO_8859_1) else ""
    private fun read(ch: FileChannel, off: Long, len: Int): ByteArray {
        val b = ByteBuffer.allocate(len); var p = off
        while (b.hasRemaining()) { val n = ch.read(b, p); if (n <= 0) break; p += n }
        return b.array()
    }

    /**
     * Fallback estimate for clips whose camera doesn't record the log profile:
     * log footage has lifted blacks, rolled-off highlights and low saturation.
     * [argb] is a small downscaled frame. Returns true when it looks flat like log.
     */
    fun looksLikeLog(argb: IntArray): Boolean {
        if (argb.isEmpty()) return false
        val lum = FloatArray(argb.size); var satSum = 0f
        for (i in argb.indices) {
            val c = argb[i]
            val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
            lum[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            satSum += if (mx > 0f) (mx - mn) / mx else 0f
        }
        lum.sort()
        val p1 = lum[(lum.size * 0.01).toInt()]; val p99 = lum[(lum.size * 0.99).toInt().coerceAtMost(lum.size - 1)]
        val sat = satSum / argb.size
        // Log curves never reach true black (S-Log3 ≈ 0.09, V-Log ≈ 0.12, D-Log M ≈ 0.08 in full range),
        // so lifted blacks are required; dark night scenes with real blacks don't qualify.
        val lifted = p1 > 0.075f
        return lifted && (p99 - p1 < 0.72f || sat < 0.20f)
    }
}
