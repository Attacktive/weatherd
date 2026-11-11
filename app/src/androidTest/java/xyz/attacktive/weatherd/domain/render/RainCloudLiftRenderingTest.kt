package xyz.attacktive.weatherd.domain.render

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class RainCloudLiftRenderingTest {
	@Test
	fun rainClippingStartsAtTheRaisedCloudBaseInRainAndThunderstorms() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		val sources = mutableListOf<Bitmap>()
		val prototype = StormCloudPrototype(resources, enabled = true) { _, _ ->
			createBitmap(100, 100).apply {
				eraseColor(Color.WHITE)
				sources.add(this)
			}
		}

		try {
			prototype.prewarm()
			for ((width, height) in listOf(240 to 528, 528 to 240, 360 to 360)) {
				val target = createBitmap(width, height)
				try {
					for (phase in DayPhase.entries) {
						for (thunder in listOf(false, true)) {
							val params = SceneParams(phase, 1f, 0f, Precipitation(PrecipitationKind.RAIN, 0.5f, 1f), thunder, 0.5f)
							val clip = RainCloudClip()
							clip.boundary.reset(width.toFloat(), height.toFloat())
							assertTrue(prototype.draw(Canvas(), width.toFloat(), height.toFloat(), params, 0f, Color.WHITE, CloudColorGrade.IDENTITY, clip.boundary))
							val depth = min(height * 0.62f, max(height * 0.48f, width * 0.70f))
							val expectedBase = depth * 1.03f - height * 0.15f
							assertTrue(clip.boundary.hasVisibleAreaBelowClouds())
							for (edge in clip.boundary.lowerEdges) {
								assertEquals(expectedBase, edge, 0.001f)
							}

							target.eraseColor(Color.TRANSPARENT)
							val canvas = Canvas(target)
							val checkpoint = canvas.save()
							try {
								clip.clip(canvas)
								canvas.drawColor(Color.RED)
							} finally {
								canvas.restoreToCount(checkpoint)
							}

							assertEquals(Color.TRANSPARENT, target.getPixel(width / 2, floor(expectedBase).toInt() - 2))
							assertEquals(Color.RED, target.getPixel(width / 2, ceil(expectedBase).toInt() + 2))
						}
					}
				} finally {
					target.recycle()
				}
			}
		} finally {
			sources.forEach { it.recycle() }
		}
	}
}
