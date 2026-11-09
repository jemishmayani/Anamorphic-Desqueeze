package com.desqueeze.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The RAW developer behind full-size DNG previews. Fixtures: 64×48 RGGB DNGs of four bands (red, green, blue and a
 * grey ramp), black 64 / white 4095, as-shot neutral 0.5 / 1 / 0.7; one uncompressed, one lossless-JPEG.
 */
class RawDeveloperTest {
    private fun fixture(name: String): File {
        val f = File.createTempFile("rawdev", ".dng")
        f.outputStream().use { o -> javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.copyTo(o) } }
        return f
    }
    private fun rgb(im: RawDeveloper.Image, x: Int, y: Int) = im.argb[y * im.width + x].let { intArrayOf(it shr 16 and 255, it shr 8 and 255, it and 255) }

    @Test fun losslessJpegMatchesUncompressed() {
        val a = RawDeveloper.develop(fixture("raw_plain.dng")); val b = RawDeveloper.develop(fixture("raw_lj92.dng"))
        assertEquals(64, a.width); assertEquals(48, a.height)
        assertArrayEquals(a.argb, b.argb)
    }

    @Test fun whiteBalanceAndColours() {
        val im = RawDeveloper.develop(fixture("raw_lj92.dng"))
        val red = rgb(im, 8, 24); val green = rgb(im, 24, 24); val blue = rgb(im, 40, 24); val grey = rgb(im, 56, 24)
        assertTrue("red ${red.toList()}", red[0] > red[1] + 60 && red[0] > red[2] + 60)
        assertTrue("green ${green.toList()}", green[1] > green[0] + 60 && green[1] > green[2] + 60)
        assertTrue("blue ${blue.toList()}", blue[2] > blue[0] + 60 && blue[2] > blue[1] + 60)
        // Grey stays grey after the camera's white balance (channels within a couple of levels).
        assertTrue("grey ${grey.toList()}", grey.max() - grey.min() <= 3)
    }

    @Test fun halfSizeKeepsShapeAndColour() {
        val im = RawDeveloper.develop(fixture("raw_plain.dng"), fullLimit = 0)
        assertTrue(im.halfSize); assertEquals(32, im.width); assertEquals(24, im.height)
        val red = rgb(im, 4, 12); assertTrue(red[0] > red[1] + 60)
    }

    @Test fun packedBitsUnpackMostSignificantFirst() {
        // Two 12-bit samples 0xABC, 0x123 packed big-endian: AB C1 23.
        val s = RawDeveloper.unpack(byteArrayOf(0xAB.toByte(), 0xC1.toByte(), 0x23), 2, 12, little = true)
        assertArrayEquals(intArrayOf(0xABC, 0x123), s)
    }
}
