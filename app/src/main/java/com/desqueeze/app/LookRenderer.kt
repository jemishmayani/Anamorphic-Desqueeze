package com.desqueeze.app

import android.graphics.SurfaceTexture
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
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Live preview of the look, drawn by our own GPU shader between the video player and the on-screen view.
 *
 * The player keeps decoding into [input] the whole time; a new LUT or strength is just a new texture or number for
 * the shader, so the look changes instantly (even while paused) instead of rebuilding the player for each change,
 * which took a second or more, flashed black and could fail on some phones.
 *
 * Everything GL runs on one private thread. [onInput] and [onError] are called on the main thread.
 */
class LookRenderer(
    private val out: SurfaceTexture, w: Int, h: Int,
    private val onInput: (Surface) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val thread = HandlerThread("look-preview").apply { start() }
    private val gl = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var outW = w; private var outH = h
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTex = 0; private var lutTex = 0; private var prog = 0
    private var st: SurfaceTexture? = null
    var input: Surface? = null; private set
    private val stMatrix = FloatArray(16)
    private var hasFrame = false
    private var released = false

    // Look state (GL thread).
    private var lutN = 0f; private var strength = 1f; private var lutOn = false
    private var uploaded: LutManager.CubeLut? = null

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }

    init { gl.post { try { setUp() } catch (e: Throwable) { fail(e) } } }

    private fun fail(e: Throwable) {
        Diag.step("Look preview: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
        main.post { if (!released) onError(e) }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
        val cfg = cfgs[0] ?: error("No GLES 3 config")
        context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "No GLES 3 context" }
        surface = EGL14.eglCreateWindowSurface(display, cfg, out, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "Window surface refused (EGL ${EGL14.eglGetError()})" }
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }

        val t = IntArray(2); GLES30.glGenTextures(2, t, 0); oesTex = t[0]; lutTex = t[1]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        for ((k, p) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
                GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE))
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, k, p)
        prog = program(VS, FS)

        val s = SurfaceTexture(oesTex)
        s.setOnFrameAvailableListener({ frame() }, gl)
        st = s
        val inp = Surface(s); input = inp
        main.post { if (!released) onInput(inp) }
    }

    private fun frame() {
        val s = st ?: return
        try { s.updateTexImage(); s.getTransformMatrix(stMatrix); hasFrame = true; draw() } catch (e: Throwable) { fail(e) }
    }

    private fun draw() {
        if (!hasFrame || surface == EGL14.EGL_NO_SURFACE) return
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, "uLut"), 1)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uLutOn"), if (lutOn && lutN >= 2f) 1f else 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uLutN"), maxOf(lutN, 2f))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uStrength"), strength)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uSt"), 1, false, stMatrix, 0)
        val aPos = GLES30.glGetAttribLocation(prog, "aPos"); val aTex = GLES30.glGetAttribLocation(prog, "aTex")
        quad.position(0); GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aPos)
        quad.position(2); GLES30.glVertexAttribPointer(aTex, 2, GLES30.GL_FLOAT, false, 16, quad); GLES30.glEnableVertexAttribArray(aTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        EGL14.eglSwapBuffers(display, surface)
    }

    /** Shows [lut] at [strength] (null or off: the plain picture). Cheap to call on every slider move. */
    fun setLook(lut: LutManager.CubeLut?, strength: Float, on: Boolean) {
        gl.post {
            if (released || prog == 0) return@post
            try {
                if (lut != null && lut !== uploaded) { upload(lut); uploaded = lut }
                this.strength = strength.coerceIn(0f, 1f); lutOn = on && lut != null
                draw()
            } catch (e: Throwable) { fail(e) }
        }
    }

    fun resize(w: Int, h: Int) { gl.post { outW = w; outH = h; if (!released) draw() } }

    private fun upload(l: LutManager.CubeLut) {
        val n = l.size
        val data = ByteBuffer.allocateDirect(n * n * n * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val span = FloatArray(3) { c -> (l.domainMax[c] - l.domainMin[c]).takeIf { it != 0f } ?: 1f }
        for (i in 0 until n * n * n) for (c in 0..2) data.put(((l.data[i * 3 + c] - l.domainMin[c]) / span[c]).coerceIn(0f, 1f))
        data.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, n, n, n, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, data)
        for ((k, p) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
                GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE,
                GLES30.GL_TEXTURE_WRAP_R to GLES30.GL_CLAMP_TO_EDGE))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, k, p)
        val e = GLES30.glGetError(); check(e == GLES30.GL_NO_ERROR) { "LUT upload GL error 0x${Integer.toHexString(e)}" }
        lutN = n.toFloat()
    }

    fun release() {
        if (released) return
        released = true
        gl.post {
            runCatching { input?.release(); st?.release() }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY; surface = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT
            thread.quitSafely()
        }
    }

    private fun program(v: String, f: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader: " + GLES30.glGetShaderInfoLog(s).take(200) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, v)); GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, f))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program: " + GLES30.glGetProgramInfoLog(p).take(200) }
        return p
    }

    private companion object {
        const val VS = """#version 300 es
            in vec2 aPos; in vec2 aTex; uniform mat4 uSt; out vec2 vTex;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = (uSt * vec4(aTex, 0.0, 1.0)).xy; }"""
        const val FS = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float; precision highp sampler3D;
            uniform samplerExternalOES uTex; uniform sampler3D uLut;
            uniform float uLutOn; uniform float uStrength; uniform float uLutN;
            in vec2 vTex; out vec4 o;
            void main() {
                vec3 c = texture(uTex, vTex).rgb;
                if (uLutOn > 0.5) {
                    vec3 p = clamp(c, 0.0, 1.0) * ((uLutN - 1.0) / uLutN) + 0.5 / uLutN;
                    c = mix(c, texture(uLut, p).rgb, uStrength);
                }
                o = vec4(clamp(c, 0.0, 1.0), 1.0);
            }"""
    }
}
