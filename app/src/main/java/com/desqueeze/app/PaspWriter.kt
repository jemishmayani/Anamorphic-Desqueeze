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
 * display each pixel [squeeze]× wider. 10-bit, log/HDR, bitrate, fps, audio, timecode and
 * metadata are all untouched, and it takes as long as copying the file.
 */
object PaspWriter {
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")
    private val VISUAL = setOf("hvc1", "hev1", "avc1", "avc3", "dvh1", "dvhe", "dva1", "dvav", "av01", "vp09", "mp4v",
        "apch", "apcn", "apcs", "apco", "ap4h", "ap4x", "mjpa", "mjpb", "jpeg")

    private class Node(
        val type: String,
        var prefix: ByteArray = ByteArray(0),
        var children: MutableList<Node>? = null,
        var raw: ByteArray? = null,
        var trailer: ByteArray = ByteArray(0),
    )

    data class TopBox(val type: String, val offset: Long, val size: Long)

    /**
     * Header metadata to write: the recording time (seconds since 1904, as MP4 stores it) into mvhd/tkhd/mdhd,
     * and a location (ISO 6709, e.g. "+12.9716+077.5946/") as a '©xyz' box in moov/udta. Null fields are left alone.
     */
    data class Meta(val mp4Time: Long? = null, val iso6709: String? = null)

    /** Returns the pixel-aspect ratio as a reduced fraction, e.g. 1.33 -> 133:100. */
    fun ratio(squeeze: Float): Pair<Int, Int> {
        var h = Math.round(squeeze * 1000).toLong(); var v = 1000L
        tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
        val g = gcd(h, v); h /= g; v /= g
        return h.toInt() to v.toInt()
    }

    /**
     * @param storedHorizontal stretch along the stored pixel width (normal) or height (vertical anamorphic).
     * @param rotation new clockwise display rotation for the video track, or null to keep the file's own.
     */
    fun write(ctx: Context, src: Uri, out: OutputStream, squeeze: Float?, storedHorizontal: Boolean = true,
              rotation: Int? = null, meta: Meta? = null, onProgress: (Int) -> Unit) {
        val pfd = ctx.contentResolver.openFileDescriptor(src, "r") ?: throw IllegalArgumentException("Can't open the source file.")
        // AutoCloseInputStream owns the descriptor, so it is closed exactly once (Android aborts on double close).
        run {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).channel.use { ch ->
                val boxes = topLevel(ch)
                Diag.step("Boxes: " + boxes.joinToString { "${it.type}@${it.offset}+${it.size}" }.take(400))
                val moov = boxes.firstOrNull { b -> b.type == "moov" } ?: throw IllegalArgumentException("Not an MP4/MOV file (no moov box).")
                require(moov.size < 256L * 1024 * 1024) { "File header is unusually large." }
                val firstMdat = boxes.firstOrNull { b -> b.type == "mdat" }
                val moovBytes = readFully(ch, moov.offset, moov.size.toInt())
                val patched = patchMoov(moovBytes, squeeze, storedHorizontal, rotation, meta, moovBeforeMdat = firstMdat != null && moov.offset < firstMdat.offset)

                Diag.step("Header patched: ${moovBytes.size} -> ${patched.size} bytes; copying")
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

    internal fun patchMoov(moov: ByteArray, squeeze: Float?, storedHorizontal: Boolean, rotation: Int?, meta: Meta?, moovBeforeMdat: Boolean): ByteArray {
        val root = parse(moov, 0, moov.size, "").single()
        if (meta != null) patchMeta(root, meta)
        if (squeeze == null) return finish(root, moov.size, moovBeforeMdat)
        val (a, b) = ratio(squeeze)
        val (hs, vs) = if (storedHorizontal) a to b else b to a
        val pasp = ByteBuffer.allocate(8).putInt(hs).putInt(vs).array()
        var visualFound = 0
        for (trak in root.children!!.filter { it.type == "trak" }) {
            var w = 0; var h = 0; var isVideo = false
            walk(trak) { n, parent ->
                if (parent?.type == "stsd" && n.type in VISUAL) {
                    isVideo = true; visualFound++
                    val pb = ByteBuffer.wrap(n.prefix); w = pb.getShort(24).toInt() and 0xFFFF; h = pb.getShort(26).toInt() and 0xFFFF
                    val kids = n.children!!
                    val existing = kids.firstOrNull { it.type == "pasp" }
                    if (existing != null) existing.raw = pasp else kids += Node("pasp", raw = pasp)
                }
            }
            if (isVideo && w > 0 && h > 0) trak.children!!.firstOrNull { it.type == "tkhd" }?.let { patchTkhd(it, w, h, hs, vs, rotation) }
        }
        require(visualFound > 0) { "No supported video track found to tag." }
        return finish(root, moov.size, moovBeforeMdat)
    }

    /** Serializes; if the header sits before the media data, every sample offset moves by the bytes it grew. */
    private fun finish(root: Node, oldSize: Int, moovBeforeMdat: Boolean): ByteArray {
        val first = ByteArrayOutputStream(oldSize + 128).also { serialize(root, it) }.toByteArray()
        val grow = first.size - oldSize
        if (!moovBeforeMdat || grow == 0) return first
        walk(root) { n, _ -> if (n.type == "stco" || n.type == "co64") shiftOffsets(n, grow) }
        return ByteArrayOutputStream(first.size).also { serialize(root, it) }.toByteArray()
    }

    /** Recording time into mvhd, every tkhd and mdhd; location as moov/udta/©xyz (replacing any old one). */
    private fun patchMeta(root: Node, meta: Meta) {
        meta.mp4Time?.let { t ->
            walk(root) { n, _ ->
                val raw = n.raw
                if ((n.type == "mvhd" || n.type == "tkhd" || n.type == "mdhd") && raw != null && raw.size >= 20) {
                    val b = ByteBuffer.wrap(raw)
                    if (raw[0].toInt() == 1) { b.putLong(4, t); b.putLong(12, t) }
                    else { val v = t.coerceIn(0, 0xFFFFFFFFL).toInt(); b.putInt(4, v); b.putInt(8, v) }
                }
            }
        }
        meta.iso6709?.let { loc ->
            val str = loc.toByteArray(Charsets.US_ASCII)
            val xyz = ByteBuffer.allocate(4 + str.size).putShort(str.size.toShort()).putShort(0x15c7).put(str).array()
            val kids = root.children!!
            val udta = kids.firstOrNull { it.type == "udta" }
            if (udta == null) { kids += Node("udta", children = mutableListOf(Node("\u00a9xyz", raw = xyz))); return@let }
            // udta is kept raw elsewhere; open it here only if it parses cleanly as boxes.
            val inner = udta.raw?.let { r -> runCatching { parse(r, 0, r.size, "udta") }.getOrNull() } ?: udta.children ?: return@let
            inner.removeAll { it.type == "\u00a9xyz" }
            inner += Node("\u00a9xyz", raw = xyz)
            udta.raw = null; udta.children = inner
        }
    }

    /**
     * Track header: display size (what QuickTime/Photos use instead of pasp) and,
     * if requested, the rotation matrix. Same byte length, so no offsets move.
     */
    private fun patchTkhd(tkhd: Node, w: Int, h: Int, hs: Int, vs: Int, rotation: Int?) {
        val raw = tkhd.raw ?: return
        val b = ByteBuffer.wrap(raw)
        val m = if (raw[0].toInt() == 1) 52 else 40
        if (raw.size < m + 44) return
        if (rotation != null) {
            val one = 0x10000; val w30 = 0x40000000
            val (ma, mb, mc, md, tx, ty) = when (((rotation % 360) + 360) % 360) {
                90 -> M6(0, one, -one, 0, h shl 16, 0)
                180 -> M6(-one, 0, 0, -one, w shl 16, h shl 16)
                270 -> M6(0, -one, one, 0, 0, w shl 16)
                else -> M6(one, 0, 0, one, 0, 0)
            }
            b.putInt(m, ma); b.putInt(m + 4, mb); b.putInt(m + 8, 0)
            b.putInt(m + 12, mc); b.putInt(m + 16, md); b.putInt(m + 20, 0)
            b.putInt(m + 24, tx); b.putInt(m + 28, ty); b.putInt(m + 32, w30)
        }
        val dw = w.toDouble() * (if (hs > vs) hs.toDouble() / vs else 1.0)
        val dh = h.toDouble() * (if (vs > hs) vs.toDouble() / hs else 1.0)
        b.putInt(m + 36, (dw * 65536).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        b.putInt(m + 40, (dh * 65536).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
    private data class M6(val a: Int, val b: Int, val c: Int, val d: Int, val tx: Int, val ty: Int)

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
