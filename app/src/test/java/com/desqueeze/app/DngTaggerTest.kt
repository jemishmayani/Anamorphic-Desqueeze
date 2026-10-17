package com.desqueeze.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lossless DNG de-squeeze: DefaultScale lands on the RAW image, nothing else changes. */
class DngTaggerTest {
    /** Minimal little-endian DNG: IFD0 = preview (NewSubfileType 1, Orientation 6) with one SubIFD = RAW 4000×3000. */
    private fun dng(withScale: Boolean = false): File {
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        fun entry(tag: Int, type: Int, count: Int, value: Int) { b.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(value) }
        // IFD0 at 8: 6 entries
        b.position(8); b.putShort(6)
        entry(254, 4, 1, 1); entry(256, 3, 1, 160); entry(257, 3, 1, 120); entry(274, 3, 1, 6); entry(330, 4, 1, 200); entry(50706, 1, 4, 0x00000401)
        b.putInt(0)
        // RAW SubIFD at 200
        b.position(200); b.putShort(if (withScale) 4 else 3)
        entry(254, 4, 1, 0); entry(256, 4, 1, 4000); entry(257, 4, 1, 3000)
        if (withScale) entry(50718, 5, 2, 300)
        b.putInt(0)
        if (withScale) { b.position(300); b.putInt(1).putInt(1).putInt(1).putInt(1) }
        // Some "image data" after, which must stay untouched.
        b.position(400); repeat(16) { b.put(0x5A) }
        return File.createTempFile("dngtest", ".dng").apply { writeBytes(b.array()); deleteOnExit() }
    }

    @Test fun readsRawSizeAndOrientation() {
        val i = DngTagger.info(dng())!!
        assertEquals(4000, i.rawWidth); assertEquals(3000, i.rawHeight); assertEquals(6, i.orientation); assertTrue(i.turned)
    }

    @Test fun addsDefaultScaleToTheRawImageAndKeepsTheRest() {
        val f = dng(); val before = f.readBytes()
        DngTagger.tag(f, 1.33f, rawHorizontal = true)
        val (h, v) = DngTagger.defaultScale(f)!!
        assertEquals(1.33f, h, 0.001f); assertEquals(1f, v, 0.001f)
        val after = f.readBytes()
        // Only the SubIFD pointer (IFD0's entry 5 value, bytes 66..69) may change in the original part of the file.
        assertTrue("original bytes unchanged apart from the pointer", (0 until before.size).all { it in 66..69 || before[it] == after[it] })
        assertTrue("pointer moved to the new directory", (66..69).any { before[it] != after[it] })
        assertTrue("image data untouched", (400 until 416).all { after[it] == 0x5A.toByte() })
        assertEquals(4000, DngTagger.info(f)!!.rawWidth)
    }

    @Test fun replacesAnExistingScale() {
        val f = dng(withScale = true)
        DngTagger.tag(f, 1.5f, rawHorizontal = false)
        val (h, v) = DngTagger.defaultScale(f)!!
        assertEquals(1f, h, 0.001f); assertEquals(1.5f, v, 0.001f)
    }

    @Test fun edgeCropBecomesTheDefaultCrop() {
        val f = dng()
        DngTagger.tag(f, 1.2f, rawHorizontal = true, crop = 0.05f)
        val c = DngTagger.defaultCrop(f)!!
        assertEquals(200f, c[0].toFloat(), 0.01f); assertEquals(150f, c[1].toFloat(), 0.01f)
        assertEquals(3600f, c[2].toFloat(), 0.01f); assertEquals(2700f, c[3].toFloat(), 0.01f)
    }

    @Test fun newPreviewReplacesTheSmallOneAndKeepsTheRaw() {
        val f = dng()
        DngTagger.tag(f, 1.2f, rawHorizontal = true)
        val jpeg = ByteArray(5000) { (it % 251).toByte() }
        DngTagger.setPreview(f, jpeg, 4800, 3000)
        assertEquals(4800 to 3000, DngTagger.previewSize(f))
        val i = DngTagger.info(f)!!
        assertEquals(4000, i.rawWidth); assertEquals(6, i.orientation)
        assertEquals(1.2f, DngTagger.defaultScale(f)!!.first, 0.001f)
        val bytes = f.readBytes()
        assertTrue("JPEG data appended", String(bytes, Charsets.ISO_8859_1).contains(String(jpeg, Charsets.ISO_8859_1)))
    }

    @Test fun refusesPlainTiffAndJpeg() {
        val jpeg = File.createTempFile("dngtest", ".jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32, 0, 16)); deleteOnExit() }
        assertEquals(null, DngTagger.info(jpeg))
        var failed = false
        try { DngTagger.tag(jpeg, 1.2f, true) } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
