/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

package org.mozilla.geckoview.test

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.lessThan
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.Setting
import org.mozilla.geckoview.test.util.UiThreadUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ship-path oracle for bug 1549943: proves GeckoView renders its (transparent)
 * content into a caller-owned Surface and that we can consume the result as a
 * GL_TEXTURE_EXTERNAL_OES texture with real per-pixel alpha -- the exact access
 * WebView/Blink denies (WebView draws itself into the view tree; you never get
 * the buffer). This is what makes the Connect3 overlay possible on GeckoView.
 *
 * Unlike TransparencyTest (which uses capturePixels -> the WebRender framebuffer,
 * bypassing the window surface), this drives the *real producer*: the EGLSurface
 * GeckoView creates on our SurfaceTexture's ANativeWindow -- the same path a
 * SurfaceView-backed or SurfaceTexture-backed display uses in production.
 *
 * The integration code is the SurfaceTexture(texId) -> Surface -> surfaceChanged
 * setup plus the samplerExternalOES draw; only the final glReadPixels is
 * test-only (production samples the external texture straight into its scene).
 *
 * Requires the transparent session setting (Fix 2) + useMaxScreenDepth(true)
 * (Fix 1, set in RuntimeCreator) + setClearColor(0).
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class TransparencyTextureTest : BaseSessionTest() {

    companion object {
        // Match transparent_page.html's geometry: opaque red box at 350..450 in
        // an 800x800 layout, so the center pixel is red and the corner is clear.
        private const val W = 800
        private const val H = 800
        private const val TIMEOUT_MS = 20000L

        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            varying vec2 vTex;
            void main() {
                vTex = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
        """
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    @Setting.List(Setting(key = Setting.Key.TRANSPARENT, value = "true"))
    @Test
    fun transparentFrameReachesExternalTexture() {
        // 1. A GL context on this (UI) thread so we can create an external texture,
        //    consume the SurfaceTexture, and read back for the assertion.
        initEgl()

        // 2. The external texture + SurfaceTexture WE own. This is the consumer
        //    end of the buffer queue; GeckoView will be the producer.
        //    The onFrameAvailable callback runs on its own HandlerThread so it
        //    fires regardless of which looper the test body is pumping.
        val texId = genExternalTexture()
        val surfaceTexture = SurfaceTexture(texId)
        surfaceTexture.setDefaultBufferSize(W, H)
        val frameReady = AtomicBoolean(false)
        val callbackThread = HandlerThread("st-frame").apply { start() }
        surfaceTexture.setOnFrameAvailableListener(
            { frameReady.set(true) },
            Handler(callbackThread.looper),
        )
        val surface = Surface(surfaceTexture)

        // 3. Hand our Surface to GeckoView -- the access WebView never gives us.
        val display = mainSession.acquireDisplay()
        display.surfaceChanged(
            GeckoDisplay.SurfaceInfo.Builder(surface).size(W, H).build(),
        )

        // 4. Transparent clear BEFORE load: the page is static, so setClearColor
        //    after load schedules no new composite (TransparencyTest only gets a
        //    post-clear frame because capturePixels forces one). Set it up front
        //    so the very first content composite already uses a transparent clear.
        mainSession.compositorController.setClearColor(Color.TRANSPARENT)
        mainSession.loadTestPath(TRANSPARENT_PAGE_HTML_PATH)
        mainSession.waitForPageStop()

        // 5. Latch the produced frame into our external texture (pure GPU, no CPU
        //    copy). Take the first frame, then best-effort grab a later one in
        //    case content painted just after -- static page, so it converges.
        UiThreadUtils.waitForCondition({ frameReady.get() }, TIMEOUT_MS)
        surfaceTexture.updateTexImage()
        try {
            frameReady.set(false)
            UiThreadUtils.waitForCondition({ frameReady.get() }, 2000L)
            surfaceTexture.updateTexImage()
        } catch (e: UiThreadUtils.TimeoutException) {
            // No newer frame; the first one stands.
        }
        callbackThread.quitSafely()

        // 6. Sample the external texture into an RGBA FBO and read it back. The
        //    readback is test-only; production would sample straight into its scene.
        val pixels = drawExternalTextureToFbo(texId)
        val corner = getPixel(pixels, 10, 10)
        val center = getPixel(pixels, W / 2, H / 2)
        val diag = "corner=#%08X center=#%08X".format(corner, center)

        // THE PROOF: Gecko's transparent region reaches OUR GL texture with alpha.
        assertThat(
            "gecko surface data reaches our external texture with alpha [$diag]",
            Color.alpha(corner),
            lessThan(255),
        )
        // Content opacity survives the round-trip through the texture.
        assertThat("opaque content stays opaque [$diag]", Color.alpha(center), equalTo(255))
        assertThat("opaque content keeps its red [$diag]", Color.red(center), greaterThan(200))

        display.surfaceDestroyed()
        mainSession.releaseDisplay(display)
        surface.release()
        surfaceTexture.release()
        destroyEgl()
    }

    // ---- GL/EGL plumbing (this is also the shape of the production consumer) ----

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfig, 0) &&
                numConfig[0] > 0,
        ) { "eglChooseConfig failed" }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0,
        )
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "eglMakeCurrent failed"
        }
    }

    private fun destroyEgl() {
        EGL14.eglMakeCurrent(
            eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
        )
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }

    private fun genExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val id = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        return id
    }

    /** Draw the external texture over a WxH RGBA FBO and read the pixels back. */
    private fun drawExternalTextureToFbo(texId: Int): ByteBuffer {
        // Offscreen RGBA color target.
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val fboTex = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, W, H, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTex, 0,
        )
        check(
            GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
                GLES20.GL_FRAMEBUFFER_COMPLETE,
        ) { "FBO incomplete" }

        GLES20.glViewport(0, 0, W, H)
        // Preserve alpha exactly: no blending, write straight from the texture.
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val program = buildProgram()
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val sTex = GLES20.glGetUniformLocation(program, "sTex")

        // Full-screen triangle strip.
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val vb: FloatBuffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(quad).position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vb)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(sTex, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val buffer = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        GLES20.glDeleteFramebuffers(1, fbos, 0)
        GLES20.glDeleteTextures(1, texIds, 0)
        return buffer
    }

    private fun buildProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    /** glReadPixels is bottom-up; flip to Android top-left coordinates. */
    private fun getPixel(buffer: ByteBuffer, x: Int, y: Int): Int {
        val i = ((H - 1 - y) * W + x) * 4
        val r = buffer.get(i).toInt() and 0xff
        val g = buffer.get(i + 1).toInt() and 0xff
        val b = buffer.get(i + 2).toInt() and 0xff
        val a = buffer.get(i + 3).toInt() and 0xff
        return Color.argb(a, r, g, b)
    }
}
