package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STORM

@RunWith(AndroidJUnit4::class)
class LakeRainContactsTest {
	private val contacts = LakeRainContacts()
	private val rain = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 1f,
		fogDensity = 0f,
		precipitation = Precipitation(PrecipitationKind.RAIN, SEVERITY_STORM, observed = 1f),
		thunder = false,
		windFactor = 0f
	)

	@Test
	fun drySnowAndDisabledRainDrawNothing() {
		val variants = listOf(
			rain.copy(precipitation = null),
			rain.copy(precipitation = Precipitation(PrecipitationKind.SNOW, SEVERITY_STORM, observed = 1f)),
			rain.copy(precipitationScale = 0f)
		)
		for (params in variants) {
			for (step in 0 until 12) {
				assertEquals(0, changedPixels(render(params, step * 0.5f, true)))
			}
		}
	}

	@Test
	fun ringsAreLocalizedToForegroundWaterAndLeaveApproachDropsUnchanged() {
		var ringPixels = 0
		var dropPixels = 0
		for (step in 0 until 12) {
			val time = step * 0.5f
			val withoutRings = render(rain, time, false)
			val withRings = render(rain, time, true)
			for (y in 0 until HEIGHT) {
				for (x in 0 until WIDTH) {
					val without = withoutRings.getPixel(x, y)
					val with = withRings.getPixel(x, y)
					if (without != Color.TRANSPARENT) {
						dropPixels++
					}

					if (without != Color.TRANSPARENT && y < HEIGHT * 0.70f) {
						assertEquals(without, with)
					}

					if (without != with) {
						ringPixels++
						assertTrue("ring reached shoreline at $x,$y", y > HEIGHT * 0.705f)
					}
				}
			}
		}

		assertTrue("expected visible approach drops", dropPixels > 0)
		assertTrue("expected visible rings", ringPixels > 0)
	}

	@Test
	fun contactsUseSharedTimeAndIgnoreWind() {
		val first = render(rain, 0.3f, true)
		val sameTime = render(rain.copy(windFactor = 1f, windScale = 2f), 0.3f, true)
		val later = render(rain, 0.6f, true)
		var animatedPixels = 0
		for (y in 0 until HEIGHT) {
			for (x in 0 until WIDTH) {
				assertEquals(first.getPixel(x, y), sameTime.getPixel(x, y))
				if (first.getPixel(x, y) != later.getPixel(x, y)) {
					animatedPixels++
				}
			}
		}

		assertTrue("contacts should move with the shared clock", animatedPixels > 0)
	}

	@Test
	fun greaterRainIntensityProducesMoreContacts() {
		var lightPixels = 0
		var heavyPixels = 0
		for (step in 0 until 12) {
			val time = step * 0.5f
			lightPixels += changedPixels(render(rain.copy(precipitationScale = 0.25f), time, true))
			heavyPixels += changedPixels(render(rain.copy(precipitationScale = 2f), time, true))
		}

		assertTrue("heavier rain should produce more contacts", heavyPixels > lightPixels)
	}

	@Test
	fun anApproachingDropReachesThePositionOfANewRing() {
		for (step in 2 until 300) {
			val time = step * 0.02f
			val before = render(rain, time - 0.04f, false)
			val beforeRing = render(rain, time - 0.02f, true)
			val beforeDrops = render(rain, time - 0.02f, false)
			val ring = render(rain, time, true)
			val drops = render(rain, time, false)
			for (y in (HEIGHT * 0.73f).toInt() until HEIGHT - 1) {
				for (x in 1 until WIDTH - 1) {
					if (ring.getPixel(x, y) == drops.getPixel(x, y) || beforeRing.getPixel(x, y) != beforeDrops.getPixel(x, y)) {
						continue
					}

					for (dy in 1..30) {
						for (dx in -4..4) {
							if (x + dx in 0 until WIDTH && before.getPixel(x + dx, y - dy) != Color.TRANSPARENT) {
								return
							}
						}
					}
				}
			}
		}

		throw AssertionError("no new ring followed an approaching drop at the same position")
	}

	private fun render(params: SceneParams, time: Float, ringsEnabled: Boolean): Bitmap {
		val bitmap = createBitmap(WIDTH, HEIGHT)
		contacts.draw(Canvas(bitmap), WIDTH.toFloat(), HEIGHT.toFloat(), params, time, ringsEnabled)

		return bitmap
	}

	private fun changedPixels(bitmap: Bitmap): Int {
		var count = 0
		for (y in 0 until HEIGHT) {
			for (x in 0 until WIDTH) {
				if (bitmap.getPixel(x, y) != Color.TRANSPARENT) {
					count++
				}
			}
		}

		return count
	}

	private companion object {
		const val WIDTH = 360
		const val HEIGHT = 780
	}
}
