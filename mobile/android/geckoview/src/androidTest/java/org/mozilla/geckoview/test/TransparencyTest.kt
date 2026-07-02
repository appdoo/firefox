/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * Any copyright is dedicated to the Public Domain.
   http://creativecommons.org/publicdomain/zero/1.0/ */

package org.mozilla.geckoview.test

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.lessThan
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.Setting
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.WithDisplay

private const val SCREEN_WIDTH = 800
private const val SCREEN_HEIGHT = 800

/**
 * Transparency oracle for bug 1549943 ("GeckoView cannot be transparent once
 * HTML has loaded").
 *
 * This is written red-first: it asserts the *fixed* behavior, so it FAILS on the
 * unpatched tree (that failure is the bug, reproduced by GeckoView's own
 * pixel-capture harness) and PASSES once the fix lands.
 *
 * Why capturePixels() is a valid oracle: it reads back the WebRender framebuffer
 * as SurfaceFormat::B8G8R8A8 (see widget/android/nsWindow.cpp RecvScreenPixels)
 * with no opaque-forcing, so real per-pixel alpha survives into the ARGB_8888
 * Bitmap. This therefore isolates *content* opacity (Fix 2): with a transparent
 * compositor clear color and a non-opaque root document, a transparent region of
 * the page must have alpha < 255.
 *
 * Root cause it exercises: the root document's CanvasBackgroundColor display item
 * is opaque white by default (layout/generic/nsCanvasFrame.cpp; Nicol, bug
 * comment 5), and the compositor clears to Color.WHITE by default
 * (CompositorController). Unpatched => every captured pixel is opaque white.
 *
 * NOT covered here: the Android EGL *window-surface* alpha channel (the other
 * half of the bug), which only affects on-screen display and is verified on a
 * real device — capturePixels bypasses the window surface entirely.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class TransparencyTest : BaseSessionTest() {

    private fun capturePixels(): Bitmap {
        return sessionRule.waitForResult(sessionRule.display!!.capturePixels())
    }

    private fun requestTransparentClearColor() {
        // GeckoSessionTestRule runs the test body ON the UI thread (it pumps the
        // main looper via UiThreadUtils.waitForCondition), so setClearColor's
        // UI-thread assertion is already satisfied — call it directly. Wrapping it
        // in Instrumentation.runOnMainSync() would throw "can not be called from
        // the main application thread".
        mainSession.compositorController.setClearColor(Color.TRANSPARENT)
    }

    @Setting.List(Setting(key = Setting.Key.TRANSPARENT, value = "true"))
    @WithDisplay(width = SCREEN_WIDTH, height = SCREEN_HEIGHT)
    @Test
    fun transparentPageShowsAlphaThrough() {
        mainSession.loadTestPath(TRANSPARENT_PAGE_HTML_PATH)
        mainSession.waitForPageStop()

        requestTransparentClearColor()

        val bitmap = capturePixels()
        // Corner is a transparent region of the page; center is an opaque red box.
        val corner = bitmap.getPixel(10, 10)
        val center = bitmap.getPixel(SCREEN_WIDTH / 2, SCREEN_HEIGHT / 2)

        // Carry the real ARGB into the failure message so a failure distinguishes
        // the opaque-white canvas (unpatched: corner=#FFFFFFFF) from a missing
        // alpha plane or a timing artifact.
        val diag = "corner=#%08X center=#%08X".format(corner, center)

        // A transparent region must show alpha through. The unpatched tree fails
        // here — the opaque-white canvas makes alpha == 255.
        assertThat(
            "transparent region shows alpha through [$diag]",
            Color.alpha(corner),
            lessThan(255),
        )

        // Content opacity must be preserved regardless of the transparency fix:
        // the red box stays fully opaque red.
        assertThat("opaque content stays opaque", Color.alpha(center), equalTo(255))
        assertThat("opaque content keeps its color (red)", Color.red(center), greaterThan(200))
    }
}
