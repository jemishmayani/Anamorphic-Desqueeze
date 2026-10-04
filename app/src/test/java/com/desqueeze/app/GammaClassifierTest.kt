package com.desqueeze.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gamma / log classification. Metadata cases use tiny fixture files in src/test/resources/gamma;
 * picture cases use synthetic 160×90 frames that model each scenario.
 */
class GammaClassifierTest {

    /* ---------------- synthetic frames ---------------- */

    private val w = 160; private val h = 90
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    private fun rnd(i: Int, seed: Int) = ((i * 1103515245 + seed * 12345) ushr 8 and 0xFF) - 128

    /** Natural-looking Rec.709 picture: full range with real blacks and whites, varied colour, grain. */
    private fun natural(seed: Int = 0) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        val l = 128 + 150 * sin(x / 9.0 + seed) * cos(y / 6.0 - seed)
        argb((l + 40 * sin(x / 15.0) + rnd(i, seed) / 10).toInt(), (l + rnd(i, seed + 1) / 10).toInt(), (l + 40 * cos(y / 11.0) + rnd(i, seed + 2) / 10).toInt())
    }

    /** Log-like picture: the same scene with lifted blacks, compressed range and low saturation. */
    private fun logLook(seed: Int = 0) = natural(seed).map { c ->
        fun ch(v: Int) = (0.09f * 255 + (v / 255f) * 0.55f * 255).toInt()
        val r = ch(c shr 16 and 255); val g = ch(c shr 8 and 255); val b = ch(c and 255); val m = (r + g + b) / 3
        argb(m + (r - m) / 3, m + (g - m) / 3, m + (b - m) / 3)
    }.toIntArray()

    private fun uniform(v: Int) = IntArray(w * h) { argb(v, v, v) }

    /** Title card: black with white text blocks. */
    private fun titleCard() = IntArray(w * h) { i -> val x = i % w; val y = i / w
        if ((y in 38..44 && x in 40..120) || (y in 50..53 && x in 55..105)) argb(255, 255, 255) else argb(0, 0, 0) }

    /** Screen recording: light UI background, flat coloured panels. */
    private fun screen() = IntArray(w * h) { i -> val x = i % w; val y = i / w
        when { y < 14 -> argb(63, 81, 181); x in 10..75 && y in 25..75 -> argb(255, 193, 7); x in 85..150 && y in 25..45 -> argb(158, 158, 158); else -> argb(242, 242, 242) } }

    /** Cartoon / animation: flat colours on a mid-grey background (lifted blacks, low contrast). */
    private fun cartoon() = IntArray(w * h) { i -> val x = i % w; val y = i / w
        when { x in 20..60 && y in 20..60 -> argb(76, 175, 80); x in 100..140 && y in 15..75 -> argb(176, 176, 176); else -> argb(154, 154, 154) } }

    private fun classify(transfer: Int? = 1, frames: List<IntArray>? = null, hints: List<LogHint> = emptyList(),
                         make: String? = null, bitDepth: Int = 8, dv: Boolean = false, mastering: Boolean = false) =
        GammaClassifier.classify(transfer, 1, dv, mastering, bitDepth, make, hints, frames)

    /* ---------------- sampling ---------------- */

    @Test fun samplingNeverUsesTheIntroOrTheFirstFrame() {
        val t = GammaClassifier.sampleTimes(60_000)
        assertEquals(5, t.size)
        assertTrue("first sample after 3 s / 10%", t.first() >= 6_000)
        assertTrue("last sample before the final 5%", t.last() < 57_000)
        assertTrue(GammaClassifier.sampleTimes(10_000).first() >= 3_000)
        assertTrue(GammaClassifier.sampleTimes(3_000).none { it == 0L })
    }

    /* ---------------- required scenarios ---------------- */

    @Test fun normalRec709() {
        val g = classify(frames = List(5) { natural(it) })
        assertEquals(GammaKind.SDR, g.kind); assertEquals(Confidence.LIKELY, g.confidence)
    }

    @Test fun rec709WithGrayIntro_neverLog() {
        // Old bug: a flat gray first frame was read as log. Intro frames are skipped by sampling,
        // and even if a gray frame is sampled it's excluded as uninformative.
        assertEquals(GammaClassifier.FrameVerdict.UNIFORM, GammaClassifier.judge(uniform(128)))
        val g = classify(frames = listOf(uniform(128)) + List(4) { natural(it) })
        assertEquals(GammaKind.SDR, g.kind)
        assertFalse(g.isLog)
    }

    @Test fun blackAndTitleCardIntro_excluded() {
        assertEquals(GammaClassifier.FrameVerdict.DARK, GammaClassifier.judge(uniform(0)))
        val tc = GammaClassifier.judge(titleCard())
        assertTrue(tc == GammaClassifier.FrameVerdict.DARK || tc == GammaClassifier.FrameVerdict.GRAPHIC)
        val g = classify(frames = listOf(uniform(0), titleCard()) + List(3) { natural(it) })
        assertEquals(GammaKind.SDR, g.kind)
    }

    @Test fun djiDLogWithoutProfileMetadata_isLikelyNeverConfirmed() {
        val g = classify(frames = List(5) { logLook(it) }, make = "DJI", bitDepth = 10)
        assertEquals(GammaKind.LOG, g.kind); assertEquals(Confidence.LIKELY, g.confidence)
        assertFalse("pixels alone must never confirm log", g.isConfirmedLog)
        assertNull(g.profile)
    }

    @Test fun flatPictureWithoutAnyHints_isOnlyPossible() {
        val g = classify(frames = List(5) { logLook(it) })
        assertEquals(GammaKind.LOG, g.kind); assertEquals(Confidence.POSSIBLE, g.confidence)
        assertTrue(g.reasons.first().contains("Heuristic"))
    }

    @Test fun djiDLog_confirmedByGammaKey() {
        val f = fixture("dlog_gamma_key.mp4")
        assertEquals("DJI", f.make)
        val g = classify(hints = f.logHints, transfer = f.transfer, bitDepth = 10)
        assertEquals(GammaKind.LOG, g.kind); assertEquals(Confidence.CONFIRMED, g.confidence); assertEquals("D-Log", g.profile)
        assertTrue(g.diagnosis.startsWith("D-Log: Confirmed"))
    }

    @Test fun djiDLogM_confirmedByCameraXml() {
        val f = fixture("dlogm_camera_xml.mp4")
        val g = classify(hints = f.logHints, transfer = f.transfer, bitDepth = 10)
        assertTrue(g.isConfirmedLog); assertEquals("D-Log M", g.profile)
        assertTrue(g.reasons.first().contains("ColorProfile"))
    }

    @Test fun djiFileWithoutProfile_hasNoHints() {
        val f = fixture("dji_10bit_no_profile.mp4")
        assertTrue(f.logHints.isEmpty()); assertEquals(10, f.bitDepth); assertEquals(1, f.transfer)
    }

    @Test fun hlg_confirmedByTransfer() {
        val f = fixture("hlg.mp4")
        val g = classify(transfer = f.transfer, bitDepth = 10, frames = List(5) { logLook(it) }) // even flat pixels can't override
        assertEquals(GammaKind.HLG, g.kind); assertEquals(Confidence.CONFIRMED, g.confidence)
    }

    @Test fun pqHdr10_confirmedByTransfer() {
        val f = fixture("pq_hdr10.mp4")
        assertEquals(16, f.transfer)
        val g = classify(transfer = f.transfer, bitDepth = 10, mastering = f.masteringInfo)
        assertEquals(GammaKind.PQ, g.kind); assertEquals("HDR10", g.profile); assertTrue(g.isHdr)
    }

    @Test fun dolbyVision_confirmed() {
        val g = classify(transfer = 16, dv = true)
        assertEquals(GammaKind.PQ, g.kind); assertEquals("Dolby Vision", g.profile)
    }

    @Test fun youtubeDownloadTitledVlog_isNotVLog() {
        val f = fixture("youtube_vlog_title.mp4")
        assertTrue("\"vlog\" in a title must not be read as Panasonic V-Log", f.logHints.isEmpty())
        val g = classify(transfer = f.transfer, frames = List(5) { natural(it) })
        assertEquals(GammaKind.SDR, g.kind)
    }

    @Test fun gradedUploadMentioningSLog3_pixelsWin() {
        val f = fixture("slog3_in_title.mp4")
        assertEquals(1, f.logHints.size); assertFalse(f.logHints.first().structured)
        val g = classify(transfer = f.transfer, hints = f.logHints, frames = List(5) { natural(it) })
        assertEquals(GammaKind.SDR, g.kind)
        assertTrue(g.reasons.any { it.contains("S-Log3") })
    }

    @Test fun freeTextAndFlatPicture_isLikelyNotConfirmed() {
        val g = classify(hints = listOf(LogHint("S-Log3", "title", false)), frames = List(5) { logLook(it) })
        assertEquals(Confidence.LIKELY, g.confidence); assertFalse(g.isConfirmedLog)
    }

    @Test fun animationAndScreenRecording_neverLog() {
        assertEquals(GammaClassifier.FrameVerdict.GRAPHIC, GammaClassifier.judge(screen()))
        assertTrue(GammaClassifier.judge(cartoon()) != GammaClassifier.FrameVerdict.LOG_LIKE)
        assertFalse(classify(transfer = null, frames = List(5) { screen() }).isLog)
        assertFalse(classify(transfer = null, frames = List(5) { cartoon() }).isLog)
        assertEquals(GammaKind.UNKNOWN, classify(transfer = null, frames = List(5) { screen() }).kind)
    }

    @Test fun darkNightSceneWithRealBlacks_notLog() {
        val night = IntArray(w * h) { i -> val x = i % w; if (x in 60..90) argb(255, 210, 160) else argb(6 + rnd(i, 3) / 40, 6, 8) }
        assertTrue(GammaClassifier.judge(night) != GammaClassifier.FrameVerdict.LOG_LIKE)
    }

    @Test fun lutAdviceOnlyForConfirmedLog() {
        assertTrue(classify(hints = listOf(LogHint("D-Log M", "camera XML ColorProfile", true))).isConfirmedLog)
        assertFalse(classify(frames = List(5) { logLook(it) }, make = "DJI", bitDepth = 10).isConfirmedLog)
    }

    @Test fun structuredValuesAcceptCompactSpellingsButFreeTextDoesNot() {
        assertEquals("V-Log", FootageAnalyzer.logName("vlog", structured = true))
        assertNull(FootageAnalyzer.logName("my vlog", structured = false))
        assertEquals("D-Log M", FootageAnalyzer.logName("dlogm", structured = true))
        assertEquals("S-Log3", FootageAnalyzer.logName("s-log3-cine", structured = true))
    }

    /* ---------------- helpers ---------------- */

    private fun fixture(name: String): Footage {
        val url = javaClass.classLoader!!.getResource("gamma/$name") ?: error("missing fixture $name")
        return FileInputStream(File(url.toURI())).channel.use { FootageAnalyzer.analyze(it) }
    }
}
