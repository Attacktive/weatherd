package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BirdFlockSize
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SunbeamMode

/**
 * October 18, 2026 Rome comparison from #211.
 *
 * All four controlled cases retain DWD's total (65%) and middle-cloud (10%) values,
 * so the low- and high-cloud changes are independent. The fifth case uses the exact
 * ItaliaMeteo totals and vertical layers from the user's diagnostic.
 *
 * This measures what the renderer changes; it cannot establish which forecast
 * matched the real sky without an independent ground/sky observation.
 */
@RunWith(AndroidJUnit4::class)
class RomeCloudLayerIsolationTest {
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun romeForecastDisagreementSeparatesHighCirrusFromLowCloudEffects() {
        val dwd = romeScene(total = 0.65f, low = 0.58f, mid = 0.10f, high = 0.15f)
        val highOnly = dwd.copy(cloudLayers = dwd.cloudLayers!!.copy(high = 0.65f))
        val lowOnly = dwd.copy(cloudLayers = dwd.cloudLayers!!.copy(low = 0.62f))
        val both = dwd.copy(cloudLayers = dwd.cloudLayers!!.copy(low = 0.62f, high = 0.65f))
        val italia = romeScene(total = 0.90f, low = 0.62f, mid = 0.01f, high = 0.65f)

        val cases = linkedMapOf(
            "dwd" to dwd,
            "highOnly" to highOnly,
            "lowOnly" to lowOnly,
            "both" to both,
            "italia" to italia
        )
        val renderer = SceneRenderer(resources)
        renderer.prewarmCloudTextures()
        val backdrops = cases.mapValues { (_, params) -> render(renderer, params, foreground = false) }
        val frames = cases.mapValues { (_, params) -> render(renderer, params, foreground = true) }

        try {
            val base = frames.getValue("dwd")
            val high = frames.getValue("highOnly")
            val low = frames.getValue("lowOnly")
            val combined = frames.getValue("both")
            val actual = frames.getValue("italia")

            assertEquals(0.58f, effectiveOpaqueCloudiness(dwd), EPSILON)
            assertEquals(0.62f, effectiveOpaqueCloudiness(italia), EPSILON)
            assertFalse(SceneRenderer.shouldDrawOvercastBanks(0.58f, 0f, false))
            assertFalse(SceneRenderer.shouldDrawOvercastBanks(0.62f, 0f, false))

            assertTrue(
                "Raising only high cloud from 15% to 65% must not alter the cached sky gradient",
                backdrops.getValue("dwd").sameAs(backdrops.getValue("highOnly"))
            )
            assertFalse(
                "Raising only low cloud from 58% to 62% changes the gradual gray-sky blend",
                backdrops.getValue("dwd").sameAs(backdrops.getValue("lowOnly"))
            )
            assertFalse("High-cloud-only change must visibly change cirrus", base.sameAs(high))
            assertFalse("Low-cloud-only change must visibly affect the scene", base.sameAs(low))

            val upperBoundary = (HEIGHT * UPPER_SKY_LIMIT).roundToInt()
            val highUpperDelta = meanRgbDelta(base, high, 0, upperBoundary)
            val highLowerDelta = meanRgbDelta(base, high, upperBoundary, HEIGHT)
            val lowUpperDelta = meanRgbDelta(base, low, 0, upperBoundary)
            val lowLowerDelta = meanRgbDelta(base, low, upperBoundary, HEIGHT)
            val combinedUpperDelta = meanRgbDelta(base, combined, 0, upperBoundary)
            val combinedLowerDelta = meanRgbDelta(base, combined, upperBoundary, HEIGHT)

            // The source cirrus sprites are confined to the upper 34% of this portrait.
            // A high-cloud change cannot alter any low/mid geometry or the backdrop.
            assertTrue("Changing high cover must visibly affect the upper sky", highUpperDelta > 0.0)
            assertEquals("High-only cloud changes must not spill below upper sky", 0.0, highLowerDelta, 0.0)

            // DWD's 10% mid cover and ItaliaMeteo's 1% mid cover are both below
            // the scattered-deck floor; total cover is not a separate rendering input
            // when a vertical layer breakdown is available.
            assertTrue(
                "Original ItaliaMeteo values should match the combined low/high control exactly",
                combined.sameAs(actual)
            )
            assertTrue(
                "Source total and sub-threshold middle cover must not alter the backdrop",
                backdrops.getValue("both").sameAs(backdrops.getValue("italia"))
            )

            val result = "Rome cloud isolation (mean RGB channel delta, 0-255): " +
                "high-only upper=$highUpperDelta lower=$highLowerDelta, " +
                "low-only upper=$lowUpperDelta lower=$lowLowerDelta, " +
                "combined upper=$combinedUpperDelta lower=$combinedLowerDelta"
            Log.i(LOG_TAG, result)
            println(result)
        } finally {
            frames.values.forEach(Bitmap::recycle)
            backdrops.values.forEach(Bitmap::recycle)
        }
    }

    private fun romeScene(total: Float, low: Float, mid: Float, high: Float) = SceneParams(
        dayPhase = DayPhase.DAY,
        celestialProgress = 0.10f,
        cloudiness = total,
        cloudLayers = SceneCloudLayers(low = low, mid = mid, high = high),
        fogDensity = 0f,
        precipitation = null,
        thunder = false,
        windFactor = 0.2f,
        cloudCountScale = 1f,
        cloudScale = 1f,
        treesEnabled = false,
        distantAircraftEnabled = false,
        birdFlockSize = BirdFlockSize.OFF,
        sunbeamMode = SunbeamMode.OFF,
        lensFlareEnabled = false
    )

    private fun render(renderer: SceneRenderer, params: SceneParams, foreground: Boolean): Bitmap {
        val image = createBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(image)
        renderer.renderBackdrop(canvas, WIDTH, HEIGHT, params)
        if (foreground) {
            renderer.renderForeground(canvas, WIDTH, HEIGHT, params, TIME_SECONDS, includeOverlayLabels = false)
        }
        return image
    }

    private fun meanRgbDelta(first: Bitmap, second: Bitmap, startY: Int, endY: Int): Double {
        val a = IntArray(WIDTH * HEIGHT)
        val b = IntArray(WIDTH * HEIGHT)
        first.getPixels(a, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        second.getPixels(b, 0, WIDTH, 0, 0, WIDTH, HEIGHT)

        var total = 0L
        for (y in startY until endY) {
            for (x in 0 until WIDTH) {
                val index = y * WIDTH + x
                total += abs(Color.red(a[index]) - Color.red(b[index]))
                total += abs(Color.green(a[index]) - Color.green(b[index]))
                total += abs(Color.blue(a[index]) - Color.blue(b[index]))
            }
        }
        return total.toDouble() / ((endY - startY) * WIDTH * 3)
    }

    private companion object {
        private const val WIDTH = 360
        private const val HEIGHT = 780
        private const val TIME_SECONDS = 17f
        private const val UPPER_SKY_LIMIT = 0.42f
        private const val EPSILON = 0.0001f
        private const val LOG_TAG = "RomeCloudIsolation"
    }
}
