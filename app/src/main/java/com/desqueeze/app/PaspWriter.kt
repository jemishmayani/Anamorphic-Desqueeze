package com.desqueeze.app

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Lossless de-squeeze, like setting "pixel aspect ratio" in DaVinci Resolve.
 *
 * The video/audio data is copied byte-for-byte. Only the MP4/MOV header (moov) changes:
 * a 'pasp' (pixel aspect ratio) box is written into the video track, telling players to
 * display each pixel [squeeze]× wider. 10-bit, D-Log, bitrate, fps, audio, timecode and
 * metadata are all untouched, and it takes as long as copying the file.
 */
object PaspWriter {
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")
    private val VISUAL = setOf("hvc1", "hev1", "avc1", "avc3", "dvh1", "dvhe")

    private class Node(
        val type: String,
        var prefix: ByteArray = ByteArray(0),
        var children: MutableList<Node>? = null,
        var raw: ByteArray? = null,
        var trailer: ByteArray = ByteArray(0),
    )

    data class TopBox(val type: String, val offset: Long, val size: Long)

    /** Returns the pixel-aspect ratio as a reduced fraction, e.g. 1.33 -> 133:100. */
    fun ratio(squeeze: Float): Pair<Int, Int> {
        var h = Math.round(squeeze * 1000).toLong(); var v = 1000L
        tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        val g = gcd(h, v); h /= g; v /= g
        return h.toInt() to v.toInt()
    }

    fun write(ctx: Context, src: Uri, out: OutputStream, squeeze: Float, onProgress: (Int) -> Unit) {
        val pfd = ctx.contentResolver.openFileDescriptor(src, "r") ?: throw IllegalArgumentException("Can't open the source file.")
        pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                val boxes = topLevel(ch)
                val moov = boxes.firstOrNull { b -> b.type == "moov" } ?: throw IllegalArgumentException("Not an MP4/MOV file (no moov box).")
                require(moov.size < 256L * 1024 * 1024) { "File header is unusually large." }
                val firstMdat = boxes.firstOrNull { b -> b.type == "mdat" }
                val moovBytes = readFully(ch, moov.offset, moov.size.toInt())
                val patched = patchMoov(moovBytes, squeeze, moovBeforeMdat = firstMdat != null && moov.offset < firstMdat.offset)

                val total = boxes.sumOf { b -> b.size }.coerceAtLeast(1)
                var done = 0L
                val buf = ByteBuffer.allocate(4 shl 20)
                for (b in boxes) {
                    if (b === moov) { out.write(patched); done += b.size; continue }
                    var pos = b.offset; val end = b.offset + b.size
                    while (pos < end) {
                        buf.clear(); if (end - pos < buf.capacity()) buf.limit((end - pos).toInt())
                        val n = ch.read(buf, pos); if (n <= 0) throw IllegalStateException("Unexpected end of file.")
                        out.write(buf.array(), 0, n); pos += n; done += n
                        onProgress((done * 100 / total).toInt().coerceAtMost(99))
                    }
                }
                out.flush()
            }
        }
        onProgress(100)
    }

    fun topLevel(ch: FileChannel): List<TopBox> {
        val size = ch.size(); val list = mutableListOf<TopBox>(); var pos = 0L
        val h = ByteBuffer.allocate(16)
        while (pos + 8 <= size) {
            h.clear(); ch.read(h, pos); h.flip()
            var len = h.int.toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { a -> h.get(a) }, Charsets.ISO_8859_1)
            if (len == 1L) len = h.long else if (len == 0L) len = size - pos
            if (len < 8 || pos + len > size) { // trailing junk: keep it as-is
                list += TopBox("????", pos, size - pos); break
            }
            list += TopBox(type, pos, len); pos += len
        }
        return list
    }

    private fun readFully(ch: FileChannel, off: Long, len: Int): ByteArray {
        val b = ByteBuffer.allocate(len); var p = off
        while (b.hasRemaining()) { val n = ch.read(b, p); if (n <= 0) break; p += n }
        return b.array()
    }

    /* ---------------- moov patching ---------------- */

    private fun patchMoov(moov: ByteArray, squeeze: Float, moovBeforeMdat: Boolean): ByteArray {
        val root = parse(moov, 0, moov.size, "").single()
        val (hs, vs) = ratio(squeeze)
        val pasp = ByteBuffer.allocate(8).putInt(hs).putInt(vs).array()
        var grow = 0
        var visualFound = 0
        walk(root) { n, parent ->
            if (parent?.type == "stsd" && n.type in VISUAL) {
                visualFound++
                val kids = n.children!!
                val existing = kids.firstOrNull { it.type == "pasp" }
                if (existing != null) { grow += 8 - (existing.raw?.size ?: 0); existing.raw = pasp }
                else { kids += Node("pasp", raw = pasp); grow += 16 }
            }
        }
        require(visualFound > 0) { "No HEVC/H.264 video track found that can be tagged." }
        // If the header sits before the media data, every sample offset moves by the bytes we added.
        if (moovBeforeMdat && grow != 0) walk(root) { n, _ -> if (n.type == "stco" || n.type == "co64") shiftOffsets(n, grow) }
        return ByteArrayOutputStream(moov.size + 64).also { serialize(root, it) }.toByteArray()
    }

    private fun walk(n: Node, parent: Node? = null, f: (Node, Node?) -> Unit) {
        f(n, parent); n.children?.toList()?.forEach { walk(it, n, f) }
    }

    private fun shiftOffsets(n: Node, delta: Int) {
        val b = ByteBuffer.wrap(n.raw!!)
        val count = b.getInt(4)
        if (n.type == "stco") for (i in 0 until count) {
            val at = 8 + i * 4; val v = (b.getInt(at).toLong() and 0xFFFFFFFFL) + delta
            require(v <= 0xFFFFFFFFL) { "File too large for lossless mode; use Re-encode." }
            b.putInt(at, v.toInt())
        } else for (i in 0 until count) { val at = 8 + i * 8; b.putLong(at, b.getLong(at) + delta) }
    }

    private fun parse(d: ByteArray, start: Int, end: Int, parentType: String): MutableList<Node> {
        val out = mutableListOf<Node>(); var p = start
        val bb = ByteBuffer.wrap(d)
        while (p < end) {
            if (end - p < 8) { // e.g. QuickTime's 4-byte terminator
                out.lastOrNull()?.let { it.trailer = it.trailer + d.copyOfRange(p, end) } ?: run {
                    out += Node("    ", raw = d.copyOfRange(p, end)) }
                break
            }
            var size = bb.getInt(p).toLong() and 0xFFFFFFFFL
            val type = String(d, p + 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L) { size = bb.getLong(p + 8); hdr = 16 } else if (size == 0L) size = (end - p).toLong()
            require(size >= hdr && p + size <= end) { "Damaged file header ($type)." }
            val ps = p + hdr; val pe = (p + size).toInt()
            out += when {
                type in CONTAINERS -> Node(type, children = parse(d, ps, pe, type))
                type == "stsd" -> Node(type, prefix = d.copyOfRange(ps, ps + 8), children = parse(d, ps + 8, pe, type))
                parentType == "stsd" && type in VISUAL && pe - ps >= 78 ->
                    Node(type, prefix = d.copyOfRange(ps, ps + 78), children = parse(d, ps + 78, pe, type))
                else -> Node(type, raw = d.copyOfRange(ps, pe))
            }
            p = pe
        }
        return out
    }

    private fun serialize(n: Node, out: ByteArrayOutputStream) {
        if (n.type == "    ") { out.write(n.raw!!); return }
        val body = ByteArrayOutputStream()
        body.write(n.prefix)
        n.children?.forEach { serialize(it, body) } ?: body.write(n.raw!!)
        val payload = body.toByteArray()
        val size = 8 + payload.size
        out.write(ByteBuffer.allocate(8).putInt(size).put(n.type.toByteArray(Charsets.ISO_8859_1)).array())
        out.write(payload)
        out.write(n.trailer) // trailer bytes belong to the parent's payload, after this box
    }
}
