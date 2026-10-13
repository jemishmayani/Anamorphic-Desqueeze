package com.desqueeze.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * 10-bit Re-encode for 10-bit SDR clips (log footage is tagged as standard video, which Media3 processes in 8-bit).
 *
 *   MediaExtractor -> HW decoder (10-bit) -> SurfaceTexture
 *   pass 1: YUV -> RGB at source size, sampling the raw 10-bit YUV (GL_EXT_YUV_target) into a 16-bit float texture
 *   pass 2: rotate, de-squeeze stretch, fit/fill into the output frame, 3D LUT -> EGL RGBA 10:10:10:2 window surface
 *   -> HW HEVC encoder in Main 10 profile -> MediaMuxer (audio copied untouched)
 *
 * Everything runs on one thread that owns the EGL context. Any failure throws [TenBitUnavailable] or an
 * exception, and the caller falls back to the regular (8-bit) export.
 */
class TenBitPipeline(private val ctx: Context) {

    class TenBitUnavailable(msg: String) : Exception(msg)

    data class Spec(
        val video: VideoInfo, val geo: Geometry, val outW: Int, val outH: Int,
        val format: OutFormat, val fill: Boolean, val trim: Pair<Long, Long>?,
        val lut: LutManager.CubeLut?, val lutStrength: Float, val bitrate: Long,
    )

    /** What actually happened, for the diagnostics log and the result note. */
    data class Report(val encoder: String, val yuvSampling: Boolean, val profile: Int?)

    /** Checked once per frame; true stops the export. */
    var isCancelled: () -> Boolean = { false }

    /** Runs the whole export into [out]. Blocking; call from a background thread. */
    fun run(spec: Spec, out: File, onProgress: (Int) -> Unit): Report {
        val encoderName = findMain10Encoder() ?: throw TenBitUnavailable("No hardware HEVC encoder with the Main 10 profile")

        val vEx = MediaExtractor().apply { setDataSource(ctx, spec.video.uri, null) }
        val aEx = MediaExtractor().apply { setDataSource(ctx, spec.video.uri, null) }
        val vTrack = (0 until vEx.trackCount).firstOrNull { vEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw TenBitUnavailable("No video track")
        val aTrack = (0 until aEx.trackCount).firstOrNull { aEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        vEx.selectTrack(vTrack)
        val inFormat = vEx.getTrackFormat(vTrack)
        val inMime = inFormat.getString(MediaFormat.KEY_MIME)!!
        val srcW = inFormat.getInteger(MediaFormat.KEY_WIDTH); val srcH = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val startUs = (spec.trim?.first ?: 0L) * 1000; val endUs = spec.trim?.second?.let { it * 1000 } ?: Long.MAX_VALUE
        val durUs = ((if (endUs == Long.MAX_VALUE) spec.video.durationMs * 1000 else endUs) - startUs).coerceAtLeast(1)
        vEx.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        aTrack?.let { aEx.selectTrack(it); aEx.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }

        // Color aspects of the source, for the YUV -> RGB matrix and the output tags.
        val standard = inFormat.intOr(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
        val fullRange = inFormat.intOr(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) == MediaFormat.COLOR_RANGE_FULL

        val egl = Egl10Bit()
        var decoder: MediaCodec? = null; var encoder: MediaCodec? = null; var muxer: MediaMuxer? = null
        var encSurface: Surface? = null; var st: SurfaceTexture? = null; var decSurface: Surface? = null
        val frameThread = HandlerThread("tenbit-frames").apply { start() }
        var muxerStarted = false
        try {
            // Encoder first: if it refuses Main 10 at this size there's nothing to undo.
            val fps = if (spec.video.fps > 0) spec.video.fps else 30f
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, spec.outW, spec.outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, spec.bitrate.toInt())
                setFloat(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                main10Level(encoderName)?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                setInteger(MediaFormat.KEY_COLOR_STANDARD, if (standard == MediaFormat.COLOR_STANDARD_BT2020) standard else MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            val enc = MediaCodec.createByCodecName(encoderName).also { encoder = it }
            try { enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            catch (e: Exception) { throw TenBitUnavailable("Encoder refused Main 10 at ${spec.outW}×${spec.outH}: ${e.message}") }
            encSurface = enc.createInputSurface()
            enc.start()

            egl.setUp(encSurface)
            val gl = GlPrograms(egl.hasExtension("GL_EXT_YUV_target"), standard, fullRange, egl.halfFloatTarget)
            gl.prepare(srcW, srcH, spec.lut, spec.lutStrength)
            val yuv = gl.yuvActive

            // Decoder renders into a SurfaceTexture on our GL context. Rotation is left to us (pass 2).
            val tex = SurfaceTexture(gl.oesTex).also { st = it }
            val frameLock = Object(); var frameReady = false
            tex.setOnFrameAvailableListener({ synchronized(frameLock) { frameReady = true; frameLock.notifyAll() } }, Handler(frameThread.looper))
            decSurface = Surface(tex)
            // The extractor's own format, minus rotation: pass 2 does the rotation, so the texture stays as stored.
            val decFormat = inFormat.apply { setInteger(MediaFormat.KEY_ROTATION, 0) }
            val dec = MediaCodec.createDecoderByType(inMime).also { decoder = it }
            dec.configure(decFormat, decSurface, null, 0)
            dec.start()

            val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            var muxVideo = -1; var muxAudio = -1
            val aFormat = aTrack?.let { aEx.getTrackFormat(it) }
            var profileOut: Int? = null

            val info = MediaCodec.BufferInfo()
            val aBuf = ByteBuffer.allocateDirect(aFormat?.intOr(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 20)?.coerceAtLeast(64 * 1024) ?: 1)
            var audioDone = aFormat == null
            val pendingVideo = ArrayList<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
            var lastVideoUs = 0L

            /** Copies audio samples up to [untilUs] (output time), keeping the two tracks interleaved. */
            fun writeAudio(untilUs: Long) {
                if (audioDone || !muxerStarted) return
                val ab = MediaCodec.BufferInfo()
                while (true) {
                    val t = aEx.sampleTime
                    if (t < 0 || t >= endUs) { audioDone = true; return }
                    if (t - startUs > untilUs) return
                    val n = aEx.readSampleData(aBuf, 0)
                    if (n < 0) { audioDone = true; return }
                    if (t >= startUs) {
                        ab.set(0, n, t - startUs, if (aEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        mux.writeSampleData(muxAudio, aBuf, ab)
                    }
                    aEx.advance()
                }
            }

            val encInfo = MediaCodec.BufferInfo()
            fun drainEncoder(endOfStream: Boolean) {
                var idle = 0
                while (true) {
                    val idx = enc.dequeueOutputBuffer(encInfo, if (endOfStream) 10_000 else 0)
                    val info = encInfo
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (++idle > 1000) throw TenBitUnavailable("Encoder didn't finish")
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = enc.outputFormat
                            profileOut = f.intOr(MediaFormat.KEY_PROFILE, -1).takeIf { it >= 0 }
                            muxVideo = mux.addTrack(f)
                            if (aFormat != null) muxAudio = runCatching { mux.addTrack(aFormat) }.getOrElse { audioDone = true; -1 }
                            mux.setOrientationHint(0)
                            mux.start(); muxerStarted = true
                            for ((b, i) in pendingVideo) mux.writeSampleData(muxVideo, b, i)
                            pendingVideo.clear()
                        }
                        idx >= 0 -> {
                            val buf = enc.getOutputBuffer(idx)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                if (muxerStarted) {
                                    mux.writeSampleData(muxVideo, buf, info)
                                    lastVideoUs = info.presentationTimeUs
                                    writeAudio(lastVideoUs)
                                } else {
                                    val copy = ByteBuffer.allocateDirect(info.size).put(buf).apply { flip() }
                                    pendingVideo += copy to MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
                                }
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            enc.releaseOutputBuffer(idx, false)
                            if (eos) return
                        }
                    }
                }
            }

            var inputDone = false; var decodeDone = false
            while (!decodeDone) {
                if (isCancelled()) throw CancellationException("Export cancelled")
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val n = vEx.readSampleData(buf, 0)
                        val t = vEx.sampleTime
                        if (n < 0 || t < 0 || (endUs != Long.MAX_VALUE && t > endUs + 500_000)) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, t, 0); vEx.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val t = info.presentationTimeUs
                    val keep = info.size > 0 && t >= startUs && t <= endUs
                    dec.releaseOutputBuffer(outIdx, keep)
                    if (keep) {
                        synchronized(frameLock) {
                            val until = System.currentTimeMillis() + 2500
                            while (!frameReady) {
                                val left = until - System.currentTimeMillis()
                                if (left <= 0) throw TenBitUnavailable("Decoder frame didn't arrive")
                                frameLock.wait(left)
                            }
                            frameReady = false
                        }
                        tex.updateTexImage()
                        val m = FloatArray(16); tex.getTransformMatrix(m)
                        gl.drawFrame(m, spec)
                        egl.present((t - startUs) * 1000)
                        drainEncoder(false)
                        onProgress(((t - startUs) * 100 / durUs).toInt().coerceIn(0, 99))
                    }
                    if (eos || t > endUs) decodeDone = true
                }
            }
            enc.signalEndOfInputStream()
            drainEncoder(true)
            if (!muxerStarted) throw TenBitUnavailable("Encoder produced no video")
            writeAudio(Long.MAX_VALUE)
            onProgress(100)
            return Report(encoderName, yuv, profileOut)
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { if (muxerStarted) muxer?.stop() }; runCatching { muxer?.release() }
            runCatching { decSurface?.release() }; runCatching { st?.release() }
            runCatching { egl.release() }; runCatching { encSurface?.release() }
            frameThread.quitSafely()
            vEx.release(); aEx.release()
        }
    }

    companion object {
        /** Hardware HEVC encoder that lists the Main 10 profile, or null. */
        fun findMain10Encoder(): String? = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { ci ->
            ci.isEncoder && ci.isHardwareAccelerated && ci.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                runCatching { ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels
                    .any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 } }.getOrDefault(false)
        }?.name

        /** Highest level the encoder lists for Main 10 (some encoders want a level whenever a profile is set). */
        fun main10Level(name: String): Int? = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == name }
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels
                .filter { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }.maxOfOrNull { it.level }
        }.getOrNull()

        /** True when Re-encode will try 10-bit for this clip (10-bit, not HDR, HEVC chosen, phone has a Main 10 encoder, setting on). */
        fun appliesTo(v: VideoInfo, settings: Settings): Boolean =
            settings.tenBit && v.bitDepth >= 10 && !v.isHdr && settings.codec == Codec.HEVC && main10Available

        val main10Available: Boolean by lazy { runCatching { findMain10Encoder() != null }.getOrDefault(false) }
    }
}

private fun MediaFormat.intOr(key: String, def: Int) = if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(def) else def

/** EGL with an RGBA 10:10:10:2 window surface on the encoder's input, so 10-bit values reach the encoder. */
private class Egl10Bit {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    /** Pass 1 can render to RGBA16F (EXT_color_buffer_half_float / float); otherwise RGB10_A2. */
    var halfFloatTarget = false

    fun setUp(window: Surface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw TenBitPipeline.TenBitUnavailable("EGL init failed")
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10, EGL14.EGL_BLUE_SIZE, 10, EGL14.EGL_ALPHA_SIZE, 2,
            EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(8); val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, configs.size, n, 0) || n[0] == 0)
            throw TenBitPipeline.TenBitUnavailable("No 10-bit EGL config")
        // eglChooseConfig may also return deeper configs; insist on exactly 10 bits for red.
        val config = configs.take(n[0]).filterNotNull().firstOrNull { c ->
            val r = IntArray(1); EGL14.eglGetConfigAttrib(display, c, EGL14.EGL_RED_SIZE, r, 0); r[0] == 10
        } ?: throw TenBitPipeline.TenBitUnavailable("No 10-bit EGL config")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw TenBitPipeline.TenBitUnavailable("No GLES 3 context")
        surface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) throw TenBitPipeline.TenBitUnavailable("Encoder surface refused 10-bit (EGL error ${EGL14.eglGetError()})")
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw TenBitPipeline.TenBitUnavailable("eglMakeCurrent failed")
        halfFloatTarget = hasExtension("GL_EXT_color_buffer_half_float") || hasExtension("GL_EXT_color_buffer_float")
    }

    fun hasExtension(name: String) = (GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: "").split(' ').contains(name)

    fun present(ptsNs: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, ptsNs)
        if (!EGL14.eglSwapBuffers(display, surface)) throw TenBitPipeline.TenBitUnavailable("eglSwapBuffers failed (${EGL14.eglGetError()})")
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
    }
}

/** The two shader passes. Pure GLES 3.0 plus GL_EXT_YUV_target when available. */
private class GlPrograms(yuvAvailable: Boolean, standard: Int, private val fullRange: Boolean, private val halfFloat: Boolean) {
    var oesTex = 0; private set
    /** Sampling the raw YUV (full 10-bit precision). False when the driver lacks GL_EXT_YUV_target or rejects it. */
    var yuvActive = yuvAvailable; private set
    private var midTex = 0; private var fbo = 0; private var lutTex = 0; private var lutSize = 0
    private var p1 = 0; private var p2 = 0
    private var srcW = 0; private var srcH = 0
    private var strength = 1f

    // YUV -> RGB coefficients (Kr, Kb) for the source's matrix.
    private val kr: Float; private val kb: Float
    init {
        when (standard) {
            MediaFormat.COLOR_STANDARD_BT2020 -> { kr = 0.2627f; kb = 0.0593f }
            MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> { kr = 0.299f; kb = 0.114f }
            else -> { kr = 0.2126f; kb = 0.0722f }
        }
    }

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val vs = """#version 300 es
        in vec2 aPos; in vec2 aTex; uniform mat4 uTexM; out vec2 vTex;
        void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = (uTexM * vec4(aTex, 0.0, 1.0)).xy; }"""

    private fun pass1Fs(yuv: Boolean): String {
        val kg = 1f - kr - kb
        // Limited range 10-bit: Y 64..940, C 64..960 (as normalized samples).
        val yOff = if (fullRange) 0f else 64f / 1023f; val yScale = if (fullRange) 1f else 1023f / 876f
        val cScale = if (fullRange) 1f else 1023f / 896f
        val toRgb = """
            float y = (yuv.x - $yOff) * $yScale; float cb = (yuv.y - 0.5) * $cScale; float cr = (yuv.z - 0.5) * $cScale;
            vec3 rgb = vec3(y + ${2f * (1f - kr)} * cr,
                            y - ${2f * kb * (1f - kb) / kg} * cb - ${2f * kr * (1f - kr) / kg} * cr,
                            y + ${2f * (1f - kb)} * cb);
            o = vec4(clamp(rgb, 0.0, 1.0), 1.0);"""
        return if (yuv) """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            #extension GL_EXT_YUV_target : require
            precision highp float;
            uniform highp __samplerExternal2DY2YEXT uTex; in vec2 vTex; out vec4 o;
            void main() { vec3 yuv = texture(uTex, vTex).xyz; $toRgb }"""
        else """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform highp samplerExternalOES uTex; in vec2 vTex; out vec4 o;
            void main() { o = vec4(texture(uTex, vTex).rgb, 1.0); }"""
    }

    private val fs2 = """#version 300 es
        precision highp float; precision highp sampler3D;
        uniform sampler2D uTex; uniform sampler3D uLut; uniform float uLutOn; uniform float uStrength; uniform float uLutN;
        in vec2 vTex; out vec4 o;
        void main() {
            vec3 c = texture(uTex, vTex).rgb;
            if (uLutOn > 0.5) {
                vec3 p = clamp(c, 0.0, 1.0) * ((uLutN - 1.0) / uLutN) + 0.5 / uLutN;
                c = mix(c, texture(uLut, p).rgb, uStrength);
            }
            o = vec4(clamp(c, 0.0, 1.0), 1.0);
        }"""

    fun prepare(w: Int, h: Int, lut: LutManager.CubeLut?, lutStrength: Float) {
        srcW = w; srcH = h; strength = lutStrength.coerceIn(0f, 1f)
        val t = IntArray(3); GLES30.glGenTextures(3, t, 0)
        oesTex = t[0]; midTex = t[1]; lutTex = t[2]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        val filter = GLES30.GL_NEAREST   // pass 1 maps texels 1:1
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        // Intermediate full-size RGB texture (16-bit float when renderable, else 10-bit), linear filtering for pass 2.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, midTex)
        if (halfFloat) GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        else GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null)
        for ((k, v) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
                GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, k, v)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, midTex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw TenBitPipeline.TenBitUnavailable("Intermediate frame buffer not supported")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        if (lut != null && strength > 0f) {
            val n = lut.size; lutSize = n
            val data = ByteBuffer.allocateDirect(n * n * n * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until n * n * n) for (c in 0..2)
                data.put(((lut.data[i * 3 + c] - lut.domainMin[c]) / (lut.domainMax[c] - lut.domainMin[c])).coerceIn(0f, 1f))
            data.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, data)
            for ((k, v) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
                    GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE,
                    GLES30.GL_TEXTURE_WRAP_R to GLES30.GL_CLAMP_TO_EDGE))
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, k, v)
        }
        p1 = if (yuvActive) runCatching { program(vs, pass1Fs(true)) }.getOrElse { yuvActive = false; program(vs, pass1Fs(false)) }
             else program(vs, pass1Fs(false))
        p2 = program(vs, fs2)
        checkGl("prepare")
    }

    /**
     * Pass 1 converts the frame as stored; pass 2 places it in the output: rotated by the display rotation,
     * stretched to the de-squeezed shape, then fitted (bars) or cropped (fill) into the output frame.
     */
    fun drawFrame(stMatrix: FloatArray, s: TenBitPipeline.Spec) {
        // Pass 1: full-size YUV -> RGB.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, srcW, srcH)
        GLES30.glUseProgram(p1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p1, "uTex"), 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p1, "uTexM"), 1, false, stMatrix, 0)
        drawQuad(p1, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        // Pass 2: into the encoder surface.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, s.outW, s.outH)
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(p2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, midTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p2, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p2, "uLut"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p2, "uLutOn"), if (lutSize > 0) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p2, "uLutN"), lutSize.coerceAtLeast(2).toFloat())
        GLES30.glUniform1f(GLES30.glGetUniformLocation(p2, "uStrength"), strength)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(p2, "uTexM"), 1, false, IDENTITY, 0)

        // Picture rectangle in the output (pixels): whole frame for "Original", else fitted / cropped by the de-squeezed shape.
        val pic = s.geo.outW.toFloat() / s.geo.outH
        val frame = s.outW.toFloat() / s.outH
        var rw = s.outW.toFloat(); var rh = s.outH.toFloat()
        if (s.format != OutFormat.ORIGINAL) {
            val wider = pic > frame
            if (wider != s.fill) rh = rw / pic else rw = rh * pic
        }
        val x0 = (s.outW - rw) / 2f; val y0 = (s.outH - rh) / 2f
        fun nx(x: Float) = x / s.outW * 2f - 1f
        fun ny(y: Float) = 1f - y / s.outH * 2f   // y measured from the top
        // Corners in display space (top-left, top-right, bottom-left, bottom-right) -> stored-frame texture coords.
        val corners = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)
        val pos = FloatArray(8); val tex = FloatArray(8)
        val rot = ((s.geo.rotation % 360) + 360) % 360
        corners.forEachIndexed { i, (x, y) ->
            pos[i * 2] = nx(x0 + x * rw); pos[i * 2 + 1] = ny(y0 + y * rh)
            // Display = stored turned clockwise by rot; invert to find the stored point (top-left origin).
            val (sx, sy) = when (rot) { 90 -> y to 1f - x; 180 -> 1f - x to 1f - y; 270 -> 1f - y to x; else -> x to y }
            tex[i * 2] = sx; tex[i * 2 + 1] = 1f - sy      // pass 1 wrote the image upright: v = 0 at the bottom
        }
        drawQuad(p2, pos, tex)
        checkGl("draw")
    }

    private fun drawQuad(p: Int, pos: FloatArray, tex: FloatArray) {
        quad.position(0); for (i in 0 until 4) { quad.put(pos[i * 2]); quad.put(pos[i * 2 + 1]); quad.put(tex[i * 2]); quad.put(tex[i * 2 + 1]) }
        val aPos = GLES30.glGetAttribLocation(p, "aPos"); val aTex = GLES30.glGetAttribLocation(p, "aTex")
        quad.position(0); GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aPos)
        quad.position(2); GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun program(v: String, f: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw TenBitPipeline.TenBitUnavailable("Shader: " + GLES30.glGetShaderInfoLog(s).take(200))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, v)); GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, f))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw TenBitPipeline.TenBitUnavailable("Program: " + GLES30.glGetProgramInfoLog(p).take(200))
        return p
    }

    private fun checkGl(where: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) throw TenBitPipeline.TenBitUnavailable("GL error 0x${Integer.toHexString(e)} in $where")
    }

    companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
}
