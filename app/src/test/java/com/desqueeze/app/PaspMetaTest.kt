package com.desqueeze.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Header edits for "Keep recording date & location": times, the ©xyz box, and sample offsets. */
class PaspMetaTest {
    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(payload).array()
    private fun cat(vararg parts: ByteArray) = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    /** mvhd/tkhd/mdhd version 0 with zero times, and one stco entry pointing at [offset]. */
    private fun moov(offset: Int, udta: ByteArray? = null): ByteArray {
        val mvhd = box("mvhd", ByteArray(100))
        val tkhd = box("tkhd", ByteArray(84))
        val mdhd = box("mdhd", ByteArray(24))
        val stco = box("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(offset).array())
        val trak = box("trak", cat(tkhd, box("mdia", cat(mdhd, box("minf", box("stbl", stco))))))
        return box("moov", cat(mvhd, trak, udta ?: ByteArray(0)))
    }

    private fun find(d: ByteArray, type: String): Int {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        for (i in 4..d.size - 4) if ((0..3).all { d[i + it] == t[it] }) return i - 4
        return -1
    }

    @Test fun writesTimesAndLocationAndShiftsOffsets() {
        val src = moov(offset = 5000)
        val t = 3_843_000_000L   // seconds since 1904 (2025)
        val out = PaspWriter.patchMoov(src, null, true, null, PaspWriter.Meta(t, "+12.9716+077.5946/"), moovBeforeMdat = true)
        val b = ByteBuffer.wrap(out)
        for (type in listOf("mvhd", "tkhd", "mdhd")) {
            val at = find(out, type)
            assertEquals(type, t, b.getInt(at + 12).toLong() and 0xFFFFFFFFL)   // creation
            assertEquals(type, t, b.getInt(at + 16).toLong() and 0xFFFFFFFFL)   // modification
        }
        val xyz = find(out, "©xyz")
        assertTrue("©xyz written", xyz > 0)
        assertEquals("+12.9716+077.5946/", String(out, xyz + 12, b.getShort(xyz + 8).toInt(), Charsets.US_ASCII))
        val grow = out.size - src.size
        assertTrue(grow > 0)
        assertEquals("stco shifted by the header growth", 5000 + grow, b.getInt(find(out, "stco") + 16))
    }

    @Test fun replacesAnExistingLocationAndKeepsOtherUdtaBoxes() {
        val oldXyz = box("©xyz", cat(ByteBuffer.allocate(4).putShort(5).putShort(0x15c7).array(), "+1+2/".toByteArray()))
        val other = box("©too", "keep".toByteArray())
        val src = moov(offset = 100, udta = box("udta", cat(other, oldXyz)))
        val out = PaspWriter.patchMoov(src, null, true, null, PaspWriter.Meta(null, "-33.8688+151.2093/"), moovBeforeMdat = false)
        val s = String(out, Charsets.ISO_8859_1)
        assertTrue(s.contains("-33.8688+151.2093/")); assertTrue(!s.contains("+1+2/")); assertTrue(s.contains("©tookeep"))
        assertEquals("offsets untouched when media comes first", 100, ByteBuffer.wrap(out).getInt(find(out, "stco") + 16))
    }

    @Test fun squeezeStillTagsVideoWithMetaOff() {
        // No visual sample entry in this minimal header: tagging must refuse rather than write a broken file.
        var failed = false
        try { PaspWriter.patchMoov(moov(1), 1.33f, true, null, null, false) } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}

class Mp4DateTest {
    @Test fun parsesRetrieverDates() {
        assertEquals(1791342048000L, Exporter.parseMp4Date("20261007T030048.000Z"))
        assertEquals(1791342048000L, Exporter.parseMp4Date("20261007T030048Z"))
        assertEquals(null, Exporter.parseMp4Date("19040101T000000.000Z"))   // "no date" placeholder
        assertEquals(null, Exporter.parseMp4Date(null))
        assertEquals(null, Exporter.parseMp4Date("garbage"))
    }
}
