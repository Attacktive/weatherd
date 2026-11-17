package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

class SunlitMoteMotionTest {
	private val clear = SceneParams(DayPhase.DAWN, 0f, 0f, null, false, 0.2f, celestialProgress = 0.15f)

	@Test
	fun unavailableSunSuppressesMotes() {
		val suppressed = listOf(
			clear.copy(dayPhase = DayPhase.NIGHT),
			clear.copy(sunVisible = false),
			clear.copy(fogDensity = 0.1f),
			clear.copy(thunder = true),
			clear.copy(precipitation = Precipitation(PrecipitationKind.RAIN, 0.1f, 0.1f)),
			clear.copy(cloudiness = 0.8f, cloudCountScale = 0f),
			clear.copy(dayPhase = DayPhase.DUSK, celestialProgress = 1f)
		)

		for (params in suppressed) {
			assertEquals(0f, sunlitMoteStrength(params), 0f)
		}
	}

	@Test
	fun lowSunIsStrongerAndCloudFadeIsContinuous() {
		assertTrue(sunlitMoteStrength(clear) > sunlitMoteStrength(clear.copy(dayPhase = DayPhase.DAY)))
		val thin = sunlitMoteStrength(clear.copy(cloudiness = 0.3f))
		val thick = sunlitMoteStrength(clear.copy(cloudiness = 0.6f))
		assertTrue(thin > thick)
		assertTrue(thick > 0f)
		assertTrue(abs(thick - sunlitMoteStrength(clear.copy(cloudiness = 0.6001f))) < 0.001f)
		assertEquals(sunlitMoteStrength(clear), sunlitMoteStrength(clear.copy(cloudLayers = SceneCloudLayers(0f, 0f, 1f))), 0f)
	}

	@Test
	fun strengthStaysContinuousAcrossDaylightPhaseBoundaries() {
		for ((before, after) in listOf(DayPhase.DAWN to DayPhase.DAY, DayPhase.DAY to DayPhase.DUSK)) {
			val ending = sunlitMoteStrength(clear.copy(dayPhase = before, celestialProgress = 1f))
			val starting = sunlitMoteStrength(clear.copy(dayPhase = after, celestialProgress = 0f))
			assertEquals("$before to $after must not change mote brightness", ending, starting, 0.0001f)
			assertEquals(ending, sunlitMoteStrength(clear.copy(dayPhase = before, celestialProgress = 0.9999f)), 0.0001f)
			assertEquals(starting, sunlitMoteStrength(clear.copy(dayPhase = after, celestialProgress = 0.0001f)), 0.0001f)
		}
	}

	@Test
	fun geometryStaysBoundedOutsideSolarDiscAcrossSizesAndTime() {
		val field = SunlitMoteField()
		assertTrue(field.x.size in 1..24)
		for (span in listOf(360f, 780f)) {
			for (sunRadius in listOf(span * 0.03f, span * 0.12f)) {
				for (time in listOf(-1f, 0f, 20f, 3600f, 86400f)) {
					field.update(span, 0f, 100f, sunRadius, time)
					for (index in field.x.indices) {
						val distance = hypot(field.x[index], field.y[index] - 100f)
						assertTrue(distance - field.radius[index] * 2f > sunRadius)
						assertTrue(distance < sunRadius + span * 0.35f)
						assertTrue(field.opacity[index] in 0f..1f)
					}
				}
			}
		}
	}

	@Test
	fun absoluteTimeIsRepeatableAfterResizeAndClockReversal() {
		val field = SunlitMoteField()
		field.update(360f, 250f, 100f, 20f, 12f)
		val x = field.x.copyOf()
		val y = field.y.copyOf()
		field.update(780f, 0f, 300f, 50f, 100f)
		field.update(360f, 250f, 100f, 20f, 12f)
		for (index in x.indices) {
			assertEquals(x[index], field.x[index], 0f)
			assertEquals(y[index], field.y[index], 0f)
		}

		field.update(360f, 250f, 100f, 20f, 20f)
		assertTrue(x.indices.any { abs(x[it] - field.x[it]) > 0.1f })
	}

	@Test
	fun geometryFollowsSunAndShorterViewportSide() {
		val field = SunlitMoteField()
		field.update(360f, 0f, 100f, 20f, 12f)
		val x = field.x.copyOf()
		val y = field.y.copyOf()
		field.update(720f, 780f, 200f, 40f, 12f)
		for (index in x.indices) {
			assertEquals(780f + x[index] * 2f, field.x[index], 0.001f)
			assertEquals(y[index] * 2f, field.y[index], 0.001f)
		}
	}
}
