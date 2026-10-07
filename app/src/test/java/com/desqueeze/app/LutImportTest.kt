package com.desqueeze.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch LUT import: duplicate detection, validation and the summary shown to the user. */
class LutImportTest {
    /** A 2×2×2 .cube; [title] and [comment] vary without changing the LUT data. */
    private fun cube(title: String = "Test", comment: String = "# made for a test", warm: Boolean = false): String {
        val rows = (0 until 8).map { i ->
            val r = (i and 1).toFloat(); val g = (i shr 1 and 1).toFloat(); val b = (i shr 2 and 1).toFloat()
            if (warm) "${minOf(1f, r + 0.1f)} $g ${maxOf(0f, b - 0.1f)}" else "$r $g $b"
        }
        return (listOf(comment, "TITLE \"$title\"", "LUT_3D_SIZE 2") + rows).joinToString("\n")
    }

    @Test fun sameDataIsADuplicateDespiteTitleCommentsAndSpacing() {
        val a = cube(title = "Rec709 v1", comment = "# exported by camera app")
        val b = cube(title = "Copy of Rec709", comment = "# another comment")
            .replace(" ", "   ").replace("\n", "\r\n") + "\n\n"
        assertEquals(lutHash(a), lutHash(b))
    }

    @Test fun differentDataIsNotADuplicate() {
        assertNotEquals(lutHash(cube()), lutHash(cube(warm = true)))
    }

    @Test fun parseAcceptsAValidCubeAndRejectsBrokenOnes() {
        assertEquals(2, LutManager.parse(cube()).size)
        val missingRows = cube().lines().dropLast(1).joinToString("\n")
        val notALut = "hello, this is a text file"
        for (bad in listOf(missingRows, notALut)) {
            var failed = false
            try { LutManager.parse(bad) } catch (_: IllegalArgumentException) { failed = true }
            assertTrue("should reject: ${bad.take(30)}", failed)
        }
    }

    @Test fun summaryReadsNaturally() {
        assertEquals("Imported 1 LUT.", lutImportSummary(1, emptyList(), emptyList()))
        assertEquals("Imported 3 LUTs. Skipped “Rec709”: already in your library.",
            lutImportSummary(3, listOf("Rec709"), emptyList()))
        assertEquals("Imported 2 LUTs. Skipped 2 already in your library.",
            lutImportSummary(2, listOf("a", "b"), emptyList()))
        assertEquals("Couldn't import 1: “notes” (Missing or invalid LUT_3D_SIZE.).",
            lutImportSummary(0, emptyList(), listOf("notes" to "Missing or invalid LUT_3D_SIZE.")))
        assertEquals("No LUTs imported.", lutImportSummary(0, emptyList(), emptyList()))
        val many = (1..5).map { "f$it" to "bad" }
        assertTrue(lutImportSummary(0, emptyList(), many).endsWith("; and 2 more."))
    }
}
