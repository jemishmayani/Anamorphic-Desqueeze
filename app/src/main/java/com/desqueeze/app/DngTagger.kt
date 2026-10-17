package com.desqueeze.app

import java.io.File
import java.io.RandomAccessFile

/**
 * Lossless de-squeeze for DNG (RAW) photos, like Lossless for video: the file is kept byte for byte and the RAW
 * image gets the DNG "DefaultScale" tag (non-square pixels). Lightroom, Adobe Camera Raw and Photoshop apply it,
 * so the photo opens de-squeezed with all RAW data intact. The embedded preview (what galleries show) is unchanged.
 *
 * Works on a file in place: an existing DefaultScale is overwritten; otherwise the RAW image's tag directory is
 * rewritten at the end of the file with the tag added and the pointer to it updated. No existing data moves.
 */
object DngTagger {
    private const val NEW_SUBFILE_TYPE = 254
    private const val IMAGE_WIDTH = 256
    private const val IMAGE_LENGTH = 257
    private const val ORIENTATION = 274
    private const val SUB_IFDS = 330
    private const val DNG_VERSION = 50706
    private const val DEFAULT_SCALE = 50718

    /** Size of the main RAW image (as stored, before Orientation) and the file's Orientation (1 = normal). */
    data class Info(val rawWidth: Int, val rawHeight: Int, val orientation: Int) {
        /** True when Orientation turns the picture by 90° (so display axes are the RAW axes swapped). */
        val turned get() = orientation in 5..8
    }

    private class Entry(val tag: Int, val type: Int, val count: Long, val value: Long, val pos: Long)
    private class Ifd(val offset: Long, val entries: List<Entry>, val next: Long, val pointerPos: Long)

    private class Tiff(val f: RandomAccessFile) {
        val little: Boolean
        init {
            f.seek(0); val a = f.read(); val b = f.read()
            little = when { a == 'I'.code && b == 'I'.code -> true; a == 'M'.code && b == 'M'.code -> false
                else -> throw IllegalArgumentException("Not a DNG/TIFF file.") }
            require(u16(2) == 42) { "Not a DNG/TIFF file." }
        }
        fun u16(pos: Long): Int { f.seek(pos); val a = f.read(); val b = f.read(); return if (little) a or (b shl 8) else (a shl 8) or b }
        fun u32(pos: Long): Long { f.seek(pos); val b = ByteArray(4); f.readFully(b)
            val v = if (little) (b[0].toLong() and 255) or ((b[1].toLong() and 255) shl 8) or ((b[2].toLong() and 255) shl 16) or ((b[3].toLong() and 255) shl 24)
                    else ((b[0].toLong() and 255) shl 24) or ((b[1].toLong() and 255) shl 16) or ((b[2].toLong() and 255) shl 8) or (b[3].toLong() and 255)
            return v }
        fun put16(v: Int) { if (little) { f.write(v and 255); f.write(v shr 8 and 255) } else { f.write(v shr 8 and 255); f.write(v and 255) } }
        fun put32(v: Long) { val x = v.toInt()
            if (little) { f.write(x and 255); f.write(x shr 8 and 255); f.write(x shr 16 and 255); f.write(x ushr 24) }
            else { f.write(x ushr 24); f.write(x shr 16 and 255); f.write(x shr 8 and 255); f.write(x and 255) } }

        fun ifd(offset: Long, pointerPos: Long): Ifd {
            require(offset in 8 until f.length()) { "Damaged DNG (bad directory offset)." }
            val n = u16(offset); require(n in 1..2000) { "Damaged DNG (bad directory)." }
            val list = (0 until n).map { i ->
                val p = offset + 2 + i * 12L
                Entry(u16(p), u16(p + 2), u32(p + 4), u32(p + 8), p)
            }
            return Ifd(offset, list, u32(offset + 2 + n * 12L), pointerPos)
        }
        /** Inline SHORT/LONG value of an entry. */
        fun scalar(e: Entry): Long = if (e.type == 3) u16(e.pos + 8).toLong() else e.value

        /** IFD0 and its SubIFDs; the RAW image is the one with NewSubfileType 0 (full-resolution image). */
        fun rawIfd(): Pair<Ifd, Ifd> {
            val ifd0 = ifd(u32(4), 4)
            require(ifd0.entries.any { it.tag == DNG_VERSION }) { "Not a DNG file (TIFF without DNGVersion)." }
            val candidates = mutableListOf(ifd0)
            ifd0.entries.firstOrNull { it.tag == SUB_IFDS }?.let { s ->
                if (s.count == 1L) candidates += ifd(s.value, s.pos + 8)
                else for (i in 0 until s.count.coerceAtMost(16)) { val at = s.value + 4 * i; candidates += ifd(u32(at), at) }
            }
            val raw = candidates.firstOrNull { d -> (d.entries.firstOrNull { it.tag == NEW_SUBFILE_TYPE }?.let { scalar(it) } ?: 0L) == 0L }
                ?: throw IllegalArgumentException("No full-size RAW image found in this DNG.")
            return ifd0 to raw
        }
    }

    fun info(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val t = Tiff(raf); val (ifd0, raw) = t.rawIfd()
            val w = raw.entries.first { it.tag == IMAGE_WIDTH }.let { t.scalar(it) }.toInt()
            val h = raw.entries.first { it.tag == IMAGE_LENGTH }.let { t.scalar(it) }.toInt()
            val o = ifd0.entries.firstOrNull { it.tag == ORIENTATION }?.let { t.scalar(it).toInt() } ?: 1
            Info(w, h, o)
        }
    }.getOrNull()

    /** The RAW image's DefaultScale as (horizontal, vertical), or null when it has none. */
    fun defaultScale(file: File): Pair<Float, Float>? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val t = Tiff(raf); val (_, raw) = t.rawIfd()
            val e = raw.entries.firstOrNull { it.tag == DEFAULT_SCALE && it.type == 5 && it.count == 2L } ?: return@use null
            (t.u32(e.value).toFloat() / t.u32(e.value + 4)) to (t.u32(e.value + 8).toFloat() / t.u32(e.value + 12))
        }
    }.getOrNull()

    /**
     * Tags [file] (in place) so RAW processors stretch it by [squeeze] along the RAW image's width
     * ([rawHorizontal]) or height. Throws if the file isn't a DNG it can safely edit.
     */
    fun tag(file: File, squeeze: Float, rawHorizontal: Boolean) {
        val (num, den) = PaspWriter.ratio(squeeze)
        val (hn, hd, vn, vd) = if (rawHorizontal) listOf(num, den, 1, 1) else listOf(1, 1, num, den)
        RandomAccessFile(file, "rw").use { raf ->
            val t = Tiff(raf); val (_, raw) = t.rawIfd()
            fun writeScale(at: Long) { raf.seek(at); t.put32(hn.toLong()); t.put32(hd.toLong()); t.put32(vn.toLong()); t.put32(vd.toLong()) }
            val existing = raw.entries.firstOrNull { it.tag == DEFAULT_SCALE }
            if (existing != null && existing.type == 5 && existing.count == 2L) { writeScale(existing.value); return }

            // Append: the two rationals, then the directory with the tag added (entries sorted by tag, as TIFF requires).
            var end = raf.length(); if (end % 2 != 0L) { raf.seek(end); raf.write(0); end++ }
            val dataPos = end
            writeScale(dataPos)
            val ifdPos = dataPos + 16
            val kept = raw.entries.filter { it.tag != DEFAULT_SCALE }
            val all = (kept.map { it.tag to it } + (DEFAULT_SCALE to null)).sortedBy { it.first }
            val raws = kept.associate { e -> e.tag to ByteArray(12).also { b -> raf.seek(e.pos); raf.readFully(b) } }
            raf.seek(ifdPos); t.put16(all.size)
            for ((tag, e) in all) {
                if (e == null) { t.put16(DEFAULT_SCALE); t.put16(5); t.put32(2); t.put32(dataPos) }
                else raf.write(raws.getValue(tag))
            }
            t.put32(raw.next)
            // Point the file (or the SubIFDs list) at the new directory.
            raf.seek(raw.pointerPos); t.put32(ifdPos)
        }
    }
}
