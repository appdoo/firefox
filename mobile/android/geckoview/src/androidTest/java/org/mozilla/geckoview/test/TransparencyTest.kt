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
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.AssertCalled
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.Setting
import org.mozilla.geckoview.test.rule.GeckoSessionTestRule.WithDisplay

private const val SCREEN_WIDTH = 800
private const val SCREEN_HEIGHT = 800

/**
 * Checks that GeckoSessionSettings.Builder.transparent() gives a content background that is actually transparent.
 *
 * capturePixels() reads the WebRender output back as B8G8R8A8 (see nsWindow::RecvScreenPixels) without forcing it
 * opaque, so per-pixel alpha reaches the Bitmap. With a transparent compositor clear color and the transparent setting,
 * a transparent region of the page must have alpha < 255. Without the setting, the root document's canvas background is
 * painted opaque white and every pixel has alpha 255.
 *
 * The test runtime enables useMaxScreenDepth (see RuntimeCreator): with the default 24-bit EGL config the surface has
 * no alpha channel and the readback is opaque even when content is transparent.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class TransparencyTest : BaseSessionTest() {

    private fun capturePixels(): Bitmap {
        return sessionRule.waitForResult(sessionRule.display!!.capturePixels())
    }

    private fun requestTransparentClearColor() {
        // The test body already runs on the UI thread, which setClearColor
        // requires.
        mainSession.compositorController.setClearColor(Color.TRANSPARENT)
    }

    @Setting.List(Setting(key = Setting.Key.TRANSPARENT, value = "true"))
    @WithDisplay(width = SCREEN_WIDTH, height = SCREEN_HEIGHT)
    @Test
    fun transparentPageShowsAlphaThrough() {
        requestTransparentClearColor()

        mainSession.loadTestPath(TRANSPARENT_PAGE_HTML_PATH)
        sessionRule.waitUntilCalled(
            object : ContentDelegate {
                @AssertCalled(count = 1) override fun onFirstContentfulPaint(session: GeckoSession) {}
            }
        )

        val bitmap = capturePixels()
        // Corner is a transparent region of the page; center is an opaque red box.
        val corner = bitmap.getPixel(10, 10)
        val center = bitmap.getPixel(SCREEN_WIDTH / 2, SCREEN_HEIGHT / 2)

        // Opaque white (#FFFFFFFF) in the corner means the canvas background was
        // still painted.
        val diag = "corner=#%08X center=#%08X".format(corner, center)

        assertThat(
            "transparent region shows alpha through [$diag]",
            Color.alpha(corner),
            lessThan(255),
        )

        // Opaque content stays opaque.
        assertThat("opaque content stays opaque", Color.alpha(center), equalTo(255))
        assertThat("opaque content keeps its color (red)", Color.red(center), greaterThan(200))
    }
}
