package com.desqueeze.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A small RAW developer for DNG files, used to make a full-size preview to embed in exported DNGs. Phones only
 * decode the small preview a DNG already carries (often 720 px), so a de-squeezed gallery preview has to come
 * from the sensor data itself.
 *
 * Reads Bayer (CFA) DNGs, uncompressed or lossless-JPEG compressed, in strips or tiles; applies black/white
 * levels, the camera's as-shot white balance and colour matrix, a gentle tone curve and sRGB gamma. The result is
 * the DNG's default crop, in sensor orientation (the DNG's Orientation tag applies to previews too), not yet
 * stretched: [Image.scaleX]/[Image.scaleY] carry its DefaultScale.
 *
 * Pure Kotlin (no Android types) so it's unit-tested on a desktop JVM.
 */
object RawDeveloper {
    class Image(val width: Int, val height: Int, val argb: IntArray, val scaleX: Double, val scaleY: Double, val halfSize: Boolean)

    class Unsupported(msg: String) : Exception(msg)

    /**
     * Develops [file]. Sensors over [fullLimit] pixels are developed at half size (one RGB pixel per 2×2 block,
     * sharper than it sounds and far lighter on memory).
     */
    fun develop(file: File, fullLimit: Long = 14_000_000L): Image = RandomAccessFile(file, "r").use { raf ->
        val t = Tiff(raf.channel)
        val ifds = t.allIfds()
        val raw = ifds.filter { (it.num(254)?.firstOrNull() ?: 0.0).toInt() == 0 && it.num(262)?.firstOrNull()?.toInt() == 32803 }
            .maxByOrNull { (it.num(256)?.get(0) ?: 0.0) * (it.num(257)?.get(0) ?: 0.0) }
            ?: throw Unsupported("No Bayer RAW image in this DNG")
        val main = ifds.first()
        fun tag(id: Int) = raw.num(id) ?: main.num(id)

        val w = raw.num(256)!![0].toInt(); val h = raw.num(257)!![0].toInt()
        val bps = raw.num(258)?.get(0)?.toInt() ?: 16
        val comp = raw.num(259)?.get(0)?.toInt() ?: 1
        if ((raw.num(277)?.get(0)?.toInt() ?: 1) != 1) throw Unsupported("RAW with ${raw.num(277)!![0].toInt()} samples per pixel")
        val data = ShortArray(w * h)
        readPixels(t, raw, w, h, bps, comp, data)

        // Linearization table, black and white levels.
        val lin = tag(50712)?.map { it.toInt() }?.toIntArray()
        val whiteLevel = tag(50717)?.get(0) ?: ((1 shl bps) - 1).toDouble()
        val blackDim = tag(50713)?.map { it.toInt() } ?: listOf(1, 1)
        val black = tag(50714) ?: doubleArrayOf(0.0)
        val bRows = max(1, blackDim.getOrElse(0) { 1 }); val bCols = max(1, blackDim.getOrElse(1) { 1 })

        // Active area, then the default crop inside it.
        val aa = tag(50829)?.map { it.toInt() } ?: listOf(0, 0, h, w)
        val aTop = aa[0]; val aLeft = aa[1]; val aH = aa[2] - aa[0]; val aW = aa[3] - aa[1]
        val co = raw.num(50719) ?: main.num(50719); val cs = raw.num(50720) ?: main.num(50720)
        var cx = co?.get(0)?.toInt() ?: 0; var cy = co?.get(1)?.toInt() ?: 0
        var cw = cs?.get(0)?.toInt() ?: aW; var ch = cs?.get(1)?.toInt() ?: aH
        cx = cx.coerceIn(0, aW - 1); cy = cy.coerceIn(0, aH - 1); cw = cw.coerceIn(1, aW - cx); ch = ch.coerceIn(1, aH - cy)
        val scale = raw.num(50718) ?: main.num(50718)
        val sx = scale?.getOrNull(0) ?: 1.0; val sy = scale?.getOrNull(1) ?: 1.0

        // CFA layout (colour index 0 = R, 1 = G, 2 = B), relative to the image origin.
        val dim = (raw.num(33421) ?: doubleArrayOf(2.0, 2.0)).map { it.toInt() }
        val pat = (raw.num(33422) ?: throw Unsupported("No CFA pattern")).map { it.toInt() }
        val pr = dim[0]; val pc = dim[1]
        if (pr != 2 || pc != 2 || pat.size < 4 || pat.any { it !in 0..2 }) throw Unsupported("Only 2×2 RGB Bayer RAW is supported")
        fun cfa(y: Int, x: Int) = pat[(y % 2) * 2 + (x % 2)]

        // Colour: white balance multipliers and camera → sRGB.
        val neutral = main.num(50728) ?: raw.num(50728) ?: doubleArrayOf(1.0, 1.0, 1.0)
        val mul = DoubleArray(3) { 1.0 / neutral[it].coerceAtLeast(1e-6) }.let { m -> val lo = m.min(); DoubleArray(3) { m[it] / lo } }
        val cm = (main.num(50722) ?: main.num(50721))?.takeIf { it.size >= 9 }   // prefer the D65 matrix when there are two
        val rgbCam = if (cm != null) camToSrgb(cm) else arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val ev = 2.0.pow(main.num(50730)?.get(0) ?: 0.0)
        val curve = toneCurve()

        // Normalised linear sensor value at (y, x), black-subtracted and white-balanced, clipped to 1.
        val span = DoubleArray(bRows * bCols) { i -> (whiteLevel - black.getOrElse(i % black.size) { 0.0 }).coerceAtLeast(1.0) }
        fun v(y: Int, x: Int): Float {
            var s = data[y * w + x].toInt() and 0xFFFF
            if (lin != null && s < lin.size) s = lin[s]
            val bi = (y % bRows) * bCols + (x % bCols)
            val n = (s - black.getOrElse(bi % black.size) { 0.0 }) / span[bi]
            return (n * mul[cfa(y, x)]).coerceIn(0.0, 1.0).toFloat()
        }

        val half = cw.toLong() * ch > fullLimit
        val ow = if (half) cw / 2 else cw; val oh = if (half) ch / 2 else ch
        val out = IntArray(ow * oh)
        val x0 = aLeft + cx; val y0 = aTop + cy
        val rgb = FloatArray(3); val cnt = IntArray(3)
        for (oy in 0 until oh) {
            for (ox in 0 until ow) {
                rgb[0] = 0f; rgb[1] = 0f; rgb[2] = 0f
                if (half) {
                    // One pixel per 2×2 block: each colour averaged over its sites in the block.
                    cnt[0] = 0; cnt[1] = 0; cnt[2] = 0
                    val by = y0 + oy * 2; val bx = x0 + ox * 2
                    for (dy in 0..1) for (dx in 0..1) { val c = cfa(by + dy, bx + dx); rgb[c] += v(by + dy, bx + dx); cnt[c]++ }
                    for (c in 0..2) if (cnt[c] > 0) rgb[c] /= cnt[c]
                } else {
                    // Bilinear: own colour from the site, the other two averaged from the 3×3 neighbours.
                    val y = y0 + oy; val x = x0 + ox
                    val own = cfa(y, x); rgb[own] = v(y, x)
                    cnt[0] = 0; cnt[1] = 0; cnt[2] = 0
                    for (dy in -1..1) {
                        val yy = (y + dy).coerceIn(0, h - 1)
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val xx = (x + dx).coerceIn(0, w - 1)
                            val c = cfa(yy, xx); if (c == own) continue
                            rgb[c] += v(yy, xx); cnt[c]++
                        }
                    }
                    for (c in 0..2) if (c != own && cnt[c] > 0) rgb[c] /= cnt[c]
                }
                var pix = 0xFF shl 24
                for (o in 0..2) {
                    val l = ((rgbCam[o][0] * rgb[0] + rgbCam[o][1] * rgb[1] + rgbCam[o][2] * rgb[2]) * ev).coerceIn(0.0, 1.0)
                    pix = pix or ((curve[(l * 4095).toInt()].toInt() and 255) shl (16 - 8 * o))
                }
                out[oy * ow + ox] = pix
            }
        }
        Image(ow, oh, out, sx, sy, half)
    }

    /** Camera RGB → linear sRGB from ColorMatrix (XYZ → camera), the dcraw way: rows normalised so white stays white. */
    internal fun camToSrgb(cm: DoubleArray): Array<DoubleArray> {
        val xyzRgb = arrayOf(doubleArrayOf(0.412453, 0.357580, 0.180423), doubleArrayOf(0.212671, 0.715160, 0.072169), doubleArrayOf(0.019334, 0.119193, 0.950227))
        val camRgb = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { k -> cm[i * 3 + k] * xyzRgb[k][j] } } }
        for (i in 0..2) { val s = camRgb[i].sum(); if (s != 0.0) for (j in 0..2) camRgb[i][j] /= s }
        return invert3(camRgb)
    }

    private fun invert3(m: Array<DoubleArray>): Array<DoubleArray> {
        val a = m[0][0]; val b = m[0][1]; val c = m[0][2]; val d = m[1][0]; val e = m[1][1]; val f = m[1][2]; val g = m[2][0]; val h = m[2][1]; val i = m[2][2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (kotlin.math.abs(det) < 1e-12) return arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        return arrayOf(
            doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
            doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
            doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det))
    }

    /** Linear 0..1 (4096 steps) → 8-bit sRGB with a soft highlight shoulder and a touch of contrast. */
    private fun toneCurve(): ByteArray = ByteArray(4096) { i ->
        val x = i / 4095.0
        val shoulder = if (x < 0.75) x else 0.75 + 0.25 * (1 - exp(-(x - 0.75) / 0.25))
        val s = if (shoulder <= 0.0031308) 12.92 * shoulder else 1.055 * shoulder.pow(1 / 2.4) - 0.055
        val c = s + 0.08 * (s - 0.5) * (1 - kotlin.math.abs(2 * s - 1))   // gentle S around mid-grey
        (c.coerceIn(0.0, 1.0) * 255 + 0.5).toInt().toByte()
    }

    // ---------------------------------------------------------------- pixel data

    private fun readPixels(t: Tiff, ifd: Tiff.Ifd, w: Int, h: Int, bps: Int, comp: Int, out: ShortArray) {
        val tiled = ifd.num(322) != null
        val tw = if (tiled) ifd.num(322)!![0].toInt() else w
        val th = if (tiled) ifd.num(323)!![0].toInt() else (ifd.num(278)?.get(0)?.toInt() ?: h).coerceAtMost(h)
        val offs = (if (tiled) ifd.num(324) else ifd.num(273)) ?: throw Unsupported("No image data")
        val lens = (if (tiled) ifd.num(325) else ifd.num(279)) ?: throw Unsupported("No image data")
        val across = if (tiled) (w + tw - 1) / tw else 1
        for (k in offs.indices) {
            val tx = (k % across) * tw; val ty = (k / across) * th
            if (ty >= h) break
            val bytes = t.bytes(offs[k].toLong(), lens[k].toInt())
            val samples: IntArray; val rowLen: Int
            when (comp) {
                1 -> { samples = unpack(bytes, tw * th, bps, t.little); rowLen = tw }
                7 -> { val (s, _) = Lj92.decode(bytes); samples = s; rowLen = tw }
                else -> throw Unsupported("RAW compression $comp isn't supported")
            }
            val rows = min(th, h - ty); val cols = min(tw, w - tx)
            for (r in 0 until rows) {
                val base = r * rowLen
                if (base >= samples.size) break
                for (c in 0 until cols) { val i = base + c; if (i < samples.size) out[(ty + r) * w + tx + c] = samples[i].toShort() }
            }
        }
    }

    /** Uncompressed samples: 8/16-bit in file byte order, anything else packed most-significant bit first. */
    internal fun unpack(b: ByteArray, n: Int, bps: Int, little: Boolean): IntArray {
        val out = IntArray(n)
        when (bps) {
            8 -> for (i in 0 until min(n, b.size)) out[i] = b[i].toInt() and 255
            16 -> for (i in 0 until min(n, b.size / 2)) {
                val lo = b[2 * i].toInt() and 255; val hi = b[2 * i + 1].toInt() and 255
                out[i] = if (little) lo or (hi shl 8) else (lo shl 8) or hi
            }
            else -> {
                var acc = 0L; var bits = 0; var p = 0
                for (i in 0 until n) {
                    while (bits < bps) { if (p >= b.size) return out; acc = (acc shl 8) or (b[p++].toLong() and 255); bits += 8 }
                    out[i] = ((acc shr (bits - bps)) and ((1L shl bps) - 1)).toInt(); bits -= bps
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- lossless JPEG (ITU T.81 process 14)

    internal object Lj92 {
        /** Decodes one lossless JPEG; returns the samples row by row (components interleaved) and the frame width × components. */
        fun decode(b: ByteArray): Pair<IntArray, Int> {
            var p = 0
            fun u8() = b[p++].toInt() and 255
            fun u16() = (u8() shl 8) or u8()
            if (u16() != 0xFFD8) throw Unsupported("Not a JPEG")
            var prec = 16; var fh = 0; var fw = 0; var nc = 0
            val compIds = IntArray(4)
            val tables = arrayOfNulls<Huff>(4)
            var restart = 0
            while (p < b.size) {
                var m = u8(); if (m != 0xFF) continue
                m = u8(); while (m == 0xFF) m = u8()
                when (m) {
                    0xC3 -> { val len = u16(); val end = p + len - 2
                        prec = u8(); fh = u16(); fw = u16(); nc = u8()
                        for (i in 0 until nc) { compIds[i] = u8(); u8(); u8() }
                        p = end }
                    0xC4 -> { val len = u16(); val end = p + len - 2
                        while (p < end) { val id = u8() and 15; val counts = IntArray(16) { u8() }
                            val syms = IntArray(counts.sum()) { u8() }; tables[id] = Huff(counts, syms) }
                        p = end }
                    0xDD -> { u16(); restart = u16() }
                    0xDA -> {
                        val len = u16(); val end = p + len - 2
                        val ns = u8(); val tsel = IntArray(ns)
                        for (i in 0 until ns) { val id = u8(); val t = u8() shr 4; val ci = (0 until nc).first { compIds[it] == id }; tsel[ci] = t }
                        val pred = u8(); u8(); val pt = u8() and 15
                        p = end
                        val out = IntArray(fw * fh * nc)
                        val br = Bits(b, p)
                        val rowW = fw * nc
                        var mcu = 0
                        var firstRow = true   // the first row of the image, or of a restart interval: predicted from the left only
                        for (y in 0 until fh) {
                            if (y > 0) firstRow = false
                            for (x in 0 until fw) {
                                if (restart > 0 && mcu > 0 && mcu % restart == 0) {
                                    br.restart()
                                    if (x == 0) firstRow = true
                                }
                                for (c in 0 until nc) {
                                    val h = tables[tsel[c]] ?: throw Unsupported("Missing Huffman table")
                                    val t = h.decode(br)
                                    val diff = when (t) { 0 -> 0; 16 -> 32768; else -> { val v = br.get(t); if (v < (1 shl (t - 1))) v - (1 shl t) + 1 else v } }
                                    val i = y * rowW + x * nc + c
                                    val px = when {
                                        firstRow && x == 0 -> 1 shl (prec - pt - 1)
                                        firstRow -> out[i - nc]
                                        x == 0 -> out[i - rowW]
                                        else -> {
                                            val ra = out[i - nc]; val rb = out[i - rowW]; val rc = out[i - rowW - nc]
                                            when (pred) { 1 -> ra; 2 -> rb; 3 -> rc; 4 -> ra + rb - rc; 5 -> ra + ((rb - rc) shr 1)
                                                6 -> rb + ((ra - rc) shr 1); 7 -> (ra + rb) shr 1; else -> ra }
                                        }
                                    }
                                    out[i] = (px + diff) and 0xFFFF
                                }
                                mcu++
                            }
                        }
                        if (pt > 0) for (i in out.indices) out[i] = out[i] shl pt
                        return out to rowW
                    }
                    0xD9 -> break
                    else -> { if (m in 0xD0..0xD7 || m == 0x01) continue; val len = u16(); p += len - 2 }
                }
            }
            throw Unsupported("Lossless JPEG without image data")
        }

        private class Huff(counts: IntArray, private val syms: IntArray) {
            private val maxCode = IntArray(18) { -1 }; private val valPtr = IntArray(17); private val minCode = IntArray(17)
            init {
                var code = 0; var k = 0
                for (l in 1..16) {
                    valPtr[l] = k; minCode[l] = code
                    code += counts[l - 1]; k += counts[l - 1]
                    maxCode[l] = if (counts[l - 1] > 0) code - 1 else -1
                    code = code shl 1
                }
                maxCode[17] = Int.MAX_VALUE
            }
            fun decode(br: Bits): Int {
                var code = 0
                for (l in 1..16) {
                    code = (code shl 1) or br.bit()
                    if (maxCode[l] >= 0 && code <= maxCode[l]) return syms[valPtr[l] + code - minCode[l]]
                }
                throw Unsupported("Corrupt Huffman data")
            }
        }

        private class Bits(private val b: ByteArray, private var p: Int) {
            private var acc = 0L; private var n = 0
            private fun fill() {
                while (n <= 56) {
                    var v = 0
                    if (p < b.size) {
                        v = b[p].toInt() and 255
                        if (v == 0xFF) {
                            val nx = if (p + 1 < b.size) b[p + 1].toInt() and 255 else 0
                            if (nx == 0x00) p += 2 else { v = 0 }   // a marker: feed zeros, don't advance past it
                        } else p++
                    }
                    acc = (acc shl 8) or v.toLong(); n += 8
                }
            }
            fun bit(): Int { if (n == 0) fill(); n--; return ((acc ushr n) and 1L).toInt() }
            fun get(k: Int): Int { if (n < k) fill(); n -= k; return ((acc ushr n) and ((1L shl k) - 1)).toInt() }
            /** Skips to the next RSTn marker and starts a fresh bit stream after it. */
            fun restart() {
                acc = 0; n = 0
                while (p + 1 < b.size && !((b[p].toInt() and 255) == 0xFF && (b[p + 1].toInt() and 255) in 0xD0..0xD7)) p++
                p += 2
            }
        }
    }

    // ---------------------------------------------------------------- TIFF reading

    internal class Tiff(private val ch: FileChannel) {
        val little: Boolean = bytes(0, 2).let { it[0] == 'I'.code.toByte() }
        private val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

        fun bytes(pos: Long, n: Int): ByteArray {
            val buf = ByteBuffer.allocate(n); var p = pos
            while (buf.hasRemaining()) { val r = ch.read(buf, p); if (r <= 0) break; p += r }
            return buf.array()
        }
        private fun buf(pos: Long, n: Int) = ByteBuffer.wrap(bytes(pos, n)).order(order)

        inner class Ifd(val tags: Map<Int, DoubleArray>) { fun num(t: Int) = tags[t] }

        private fun readIfd(off: Long, seen: MutableSet<Long>, out: MutableList<Ifd>) {
            if (off <= 0 || off >= ch.size() || !seen.add(off)) return
            val n = buf(off, 2).short.toInt() and 0xFFFF
            val e = buf(off + 2, n * 12)
            val tags = HashMap<Int, DoubleArray>()
            for (i in 0 until n) {
                e.position(i * 12)
                val tag = e.short.toInt() and 0xFFFF; val type = e.short.toInt() and 0xFFFF; val count = e.int.toLong() and 0xFFFFFFFFL
                val size = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> 0 }
                if (size == 0 || count == 0L || count > 1_000_000) continue
                val total = size * count
                val data = if (total <= 4) { val b = ByteArray(4); e.get(b); ByteBuffer.wrap(b).order(order) }
                           else buf(e.int.toLong() and 0xFFFFFFFFL, total.toInt())
                tags[tag] = DoubleArray(count.toInt()) { k ->
                    when (type) {
                        1, 7, 2 -> (data.get(k).toInt() and 255).toDouble()
                        6 -> data.get(k).toDouble()
                        3 -> (data.getShort(k * 2).toInt() and 0xFFFF).toDouble()
                        8 -> data.getShort(k * 2).toDouble()
                        4 -> (data.getInt(k * 4).toLong() and 0xFFFFFFFFL).toDouble()
                        9 -> data.getInt(k * 4).toDouble()
                        11 -> data.getFloat(k * 4).toDouble()
                        5 -> (data.getInt(k * 8).toLong() and 0xFFFFFFFFL).toDouble() / ((data.getInt(k * 8 + 4).toLong() and 0xFFFFFFFFL).takeIf { it != 0L } ?: 1L)
                        10 -> data.getInt(k * 8).toDouble() / (data.getInt(k * 8 + 4).takeIf { it != 0 } ?: 1)
                        else -> data.getDouble(k * 8)
                    }
                }
            }
            out += Ifd(tags)
            tags[330]?.forEach { readIfd(it.toLong(), seen, out) }          // SubIFDs (where DNGs keep the RAW)
            readIfd(buf(off + 2 + n * 12L, 4).int.toLong() and 0xFFFFFFFFL, seen, out)
        }

        fun allIfds(): List<Ifd> {
            val first = buf(4, 4).int.toLong() and 0xFFFFFFFFL
            return mutableListOf<Ifd>().also { readIfd(first, HashSet(), it) }.ifEmpty { throw Unsupported("Not a TIFF/DNG") }
        }
    }
}
