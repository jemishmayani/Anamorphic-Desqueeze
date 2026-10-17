package com.desqueeze.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Lossless de-squeeze for DNG (RAW) photos, like Lossless for video. The RAW data is never touched:
 *  - the RAW image gets the DNG "DefaultScale" tag (non-square pixels), so Lightroom, Adobe Camera Raw and
 *    Photoshop open it de-squeezed;
 *  - an edge crop becomes the DNG default crop (DefaultCropOrigin / DefaultCropSize), also lossless;
 *  - optionally, a new de-squeezed JPEG preview replaces the one galleries show.
 *
 * Directories that change are written anew at the end of the file and the pointer to them is updated;
 * no existing data moves. Works on a [FileChannel], so a file descriptor from the photo picker can be read
 * directly (reopening it by path needs storage permission the app doesn't have).
 */
object DngTagger {
    private const val NEW_SUBFILE_TYPE = 254
    private const val IMAGE_WIDTH = 256
    private const val IMAGE_LENGTH = 257
    private const val ORIENTATION = 274
    private const val SUB_IFDS = 330
    private const val DNG_VERSION = 50706
    private const val DEFAULT_SCALE = 50718
    private const val DEFAULT_CROP_ORIGIN = 50719
    private const val DEFAULT_CROP_SIZE = 50720

    /** Size of the main RAW image (as stored, before Orientation) and the file's Orientation (1 = normal). */
    data class Info(val rawWidth: Int, val rawHeight: Int, val orientation: Int) {
        /** True when Orientation turns the picture by 90° (so display axes are the RAW axes swapped). */
        val turned get() = orientation in 5..8
    }

    private class Entry(val tag: Int, val type: Int, val count: Long, val value: Long, val pos: Long)
    private class Ifd(val offset: Long, val entries: List<Entry>, val next: Long, val pointerPos: Long) {
        fun get(tag: Int) = entries.firstOrNull { it.tag == tag }
    }
    /** A tag to write: TIFF type, count and the value bytes (already in file byte order). */
    private class NewTag(val type: Int, val count: Long, val data: ByteArray)

    private class Tiff(val ch: FileChannel) {
        val little: Boolean
        init {
            val h = bytes(0, 4)
            little = when { h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> true
                h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> false
                else -> throw IllegalArgumentException("Not a DNG/TIFF file.") }
            require(u16(2) == 42) { "Not a DNG/TIFF file." }
        }
        fun bytes(pos: Long, n: Int): ByteArray {
            val b = ByteBuffer.allocate(n); var p = pos
            while (b.hasRemaining()) { val r = ch.read(b, p); require(r > 0) { "Damaged DNG (unexpected end)." }; p += r }
            return b.array()
        }
        fun u16(pos: Long): Int { val b = bytes(pos, 2); val x = b[0].toInt() and 255; val y = b[1].toInt() and 255; return if (little) x or (y shl 8) else (x shl 8) or y }
        fun u32(pos: Long): Long {
            val b = bytes(pos, 4).map { it.toLong() and 255 }
            return if (little) b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24) else (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
        }
        fun e16(v: Int) = if (little) byteArrayOf(v.toByte(), (v shr 8).toByte()) else byteArrayOf((v shr 8).toByte(), v.toByte())
        fun e32(v: Long): ByteArray { val x = v.toInt()
            return if (little) byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte(), (x ushr 24).toByte())
                   else byteArrayOf((x ushr 24).toByte(), (x shr 16).toByte(), (x shr 8).toByte(), x.toByte()) }
        fun write(pos: Long, b: ByteArray) { val buf = ByteBuffer.wrap(b); var p = pos; while (buf.hasRemaining()) p += ch.write(buf, p) }
        /** Appends [b] at the end (word aligned) and returns where it went. */
        fun append(b: ByteArray): Long {
            var end = ch.size(); if (end % 2 != 0L) { write(end, byteArrayOf(0)); end++ }
            write(end, b); return end
        }

        fun ifd(offset: Long, pointerPos: Long): Ifd {
            require(offset in 8 until ch.size()) { "Damaged DNG (bad directory offset)." }
            val n = u16(offset); require(n in 1..2000) { "Damaged DNG (bad directory)." }
            val list = (0 until n).map { i -> val p = offset + 2 + i * 12L; Entry(u16(p), u16(p + 2), u32(p + 4), u32(p + 8), p) }
            return Ifd(offset, list, u32(offset + 2 + n * 12L), pointerPos)
        }
        fun scalar(e: Entry): Long = if (e.type == 3) u16(e.pos + 8).toLong() else e.value

        /** SHORT / LONG / RATIONAL values of an entry, as numbers. */
        fun nums(e: Entry): DoubleArray? {
            val size = when (e.type) { 3 -> 2; 4, 13 -> 4; 5 -> 8; else -> return null }
            val n = e.count.toInt().coerceAtMost(64); val total = size * n
            val at = if (total <= 4) e.pos + 8 else e.value
            return DoubleArray(n) { i -> when (e.type) {
                3 -> u16(at + 2L * i).toDouble()
                5 -> { val d = u32(at + 8L * i + 4); if (d == 0L) 0.0 else u32(at + 8L * i).toDouble() / d }
                else -> u32(at + 4L * i).toDouble()
            } }
        }

        /** IFD0, its SubIFDs (with where each pointer lives), and the RAW image (NewSubfileType 0). */
        fun layout(): Triple<Ifd, List<Ifd>, Ifd> {
            val ifd0 = ifd(u32(4), 4)
            require(ifd0.get(DNG_VERSION) != null) { "Not a DNG file (TIFF without DNGVersion)." }
            val subs = mutableListOf<Ifd>()
            ifd0.get(SUB_IFDS)?.let { s ->
                if (s.count == 1L) subs += ifd(s.value, s.pos + 8)
                else for (i in 0 until s.count.coerceAtMost(16)) { val at = s.value + 4 * i; subs += ifd(u32(at), at) }
            }
            val raw = (listOf(ifd0) + subs).firstOrNull { d -> (d.get(NEW_SUBFILE_TYPE)?.let { scalar(it) } ?: 0L) == 0L }
                ?: throw IllegalArgumentException("No full-size RAW image found in this DNG.")
            return Triple(ifd0, subs, raw)
        }

        /** Writes a copy of [ifd] with [set] tags added/replaced and [drop] tags removed; returns its offset. */
        fun rewrite(entries: List<Entry>, next: Long, set: Map<Int, NewTag>, drop: Set<Int> = emptySet()): Long {
            val kept = entries.filter { it.tag !in set.keys && it.tag !in drop }.associate { it.tag to bytes(it.pos, 12) }
            val built = set.mapValues { (tag, t) ->
                val value = if (t.data.size <= 4) t.data.copyOf(4) else e32(append(t.data))
                e16(tag) + e16(t.type) + e32(t.count) + value
            }
            val all = (kept + built).toSortedMap()   // TIFF wants entries sorted by tag
            val dir = ByteBuffer.allocate(2 + all.size * 12 + 4)
            dir.put(e16(all.size)); all.values.forEach { dir.put(it) }; dir.put(e32(next))
            return append(dir.array())
        }
    }

    private fun shorts(t: Tiff, vararg v: Int) = v.fold(ByteArray(0)) { a, x -> a + t.e16(x) }
    private fun longs(t: Tiff, vararg v: Long) = v.fold(ByteArray(0)) { a, x -> a + t.e32(x) }
    private fun rationals(t: Tiff, vararg v: Pair<Long, Long>) = v.fold(ByteArray(0)) { a, (n, d) -> a + t.e32(n) + t.e32(d) }

    fun info(ch: FileChannel): Info? = runCatching {
        val t = Tiff(ch); val (ifd0, _, raw) = t.layout()
        val w = t.scalar(raw.get(IMAGE_WIDTH)!!).toInt(); val h = t.scalar(raw.get(IMAGE_LENGTH)!!).toInt()
        Info(w, h, ifd0.get(ORIENTATION)?.let { t.scalar(it).toInt() } ?: 1)
    }.getOrNull()

    fun info(file: File): Info? = runCatching { RandomAccessFile(file, "r").use { info(it.channel) } }.getOrNull()

    /** The RAW image's DefaultScale as (horizontal, vertical), or null when it has none. */
    fun defaultScale(file: File): Pair<Float, Float>? = numbers(file, DEFAULT_SCALE)?.let { it[0].toFloat() to it[1].toFloat() }

    /** The RAW image's default crop as (originX, originY, width, height), or null when it has none. */
    fun defaultCrop(file: File): DoubleArray? {
        val o = numbers(file, DEFAULT_CROP_ORIGIN) ?: return null; val s = numbers(file, DEFAULT_CROP_SIZE) ?: return null
        return doubleArrayOf(o[0], o[1], s[0], s[1])
    }

    private fun numbers(file: File, tag: Int): DoubleArray? = runCatching {
        RandomAccessFile(file, "r").use { raf -> val t = Tiff(raf.channel); val (_, _, raw) = t.layout(); raw.get(tag)?.let { t.nums(it) } }
    }.getOrNull()

    /**
     * Tags [file] (in place) so RAW processors stretch it by [squeeze] along the RAW image's width ([rawHorizontal])
     * or height, and crop [crop] (fraction) from every edge via the default crop. Throws if it isn't a DNG it can edit.
     */
    fun tag(file: File, squeeze: Float, rawHorizontal: Boolean, crop: Float = 0f) {
        val (num, den) = PaspWriter.ratio(squeeze)
        RandomAccessFile(file, "rw").use { raf ->
            val t = Tiff(raf.channel); val (_, _, raw) = t.layout()
            val scale = if (rawHorizontal) rationals(t, num.toLong() to den.toLong(), 1L to 1L) else rationals(t, 1L to 1L, num.toLong() to den.toLong())
            val set = mutableMapOf(DEFAULT_SCALE to NewTag(5, 2, scale))
            val c = crop.coerceIn(0f, 0.4f).toDouble()
            if (c > 0) {
                val w = t.scalar(raw.get(IMAGE_WIDTH)!!).toDouble(); val h = t.scalar(raw.get(IMAGE_LENGTH)!!).toDouble()
                val o = raw.get(DEFAULT_CROP_ORIGIN)?.let { t.nums(it) } ?: doubleArrayOf(0.0, 0.0)
                val s = raw.get(DEFAULT_CROP_SIZE)?.let { t.nums(it) } ?: doubleArrayOf(w, h)
                fun r(x: Double) = Math.round(x * 100) to 100L
                set[DEFAULT_CROP_ORIGIN] = NewTag(5, 2, rationals(t, r(o[0] + s[0] * c), r(o[1] + s[1] * c)))
                set[DEFAULT_CROP_SIZE] = NewTag(5, 2, rationals(t, r(s[0] * (1 - 2 * c)), r(s[1] * (1 - 2 * c))))
            }
            val at = t.rewrite(raw.entries, raw.next, set)
            t.write(raw.pointerPos, t.e32(at))
        }
    }

    /**
     * Makes a [width]×[height] JPEG the preview that galleries show: it replaces IFD0's image when IFD0 is a
     * preview (the usual layout), otherwise it's added as a new preview directory. DNG metadata in IFD0 is kept.
     */
    fun setPreview(file: File, jpeg: ByteArray, width: Int, height: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            val t = Tiff(raf.channel); val (ifd0, subs, raw) = t.layout()
            val pos = t.append(jpeg)
            val image = mapOf(
                NEW_SUBFILE_TYPE to NewTag(4, 1, t.e32(1)),
                IMAGE_WIDTH to NewTag(4, 1, t.e32(width.toLong())), IMAGE_LENGTH to NewTag(4, 1, t.e32(height.toLong())),
                258 to NewTag(3, 3, shorts(t, 8, 8, 8)),            // BitsPerSample
                259 to NewTag(3, 1, t.e16(7)),                      // Compression: JPEG
                262 to NewTag(3, 1, t.e16(6)),                      // Photometric: YCbCr
                273 to NewTag(4, 1, t.e32(pos)),                    // StripOffsets
                277 to NewTag(3, 1, t.e16(3)),                      // SamplesPerPixel
                278 to NewTag(4, 1, t.e32(height.toLong())),        // RowsPerStrip
                279 to NewTag(4, 1, t.e32(jpeg.size.toLong())),     // StripByteCounts
                284 to NewTag(3, 1, t.e16(1)),                      // PlanarConfiguration
                50970 to NewTag(4, 1, t.e32(2)),                    // PreviewColorSpace: sRGB
            )
            // Tile layout and YCbCr details of the old preview no longer apply.
            val drop = setOf(322, 323, 324, 325, 530, 531, 532, 513, 514)
            if (raw !== ifd0) {
                val at = t.rewrite(ifd0.entries, ifd0.next, image, drop)
                t.write(4, t.e32(at))
            } else {
                val preview = t.rewrite(emptyList(), 0, image)
                val list = subs.map { it.offset } + preview
                val at = t.rewrite(ifd0.entries, ifd0.next, mapOf(SUB_IFDS to NewTag(4, list.size.toLong(), longs(t, *list.toLongArray()))))
                t.write(4, t.e32(at))
            }
        }
    }

    /** Size of the preview galleries show (IFD0's image when it's a preview), or null. */
    fun previewSize(file: File): Pair<Int, Int>? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val t = Tiff(raf.channel); val (ifd0, _, raw) = t.layout()
            if (raw === ifd0) null else t.scalar(ifd0.get(IMAGE_WIDTH)!!).toInt() to t.scalar(ifd0.get(IMAGE_LENGTH)!!).toInt()
        }
    }.getOrNull()
}
