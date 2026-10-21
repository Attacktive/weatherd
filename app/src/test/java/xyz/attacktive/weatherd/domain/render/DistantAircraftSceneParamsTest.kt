package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

class DistantAircraftSceneParamsTest {
	@Test
	fun `aircraft eligibility respects weather and rendered cloud layers`() {
		val clear = clearParams()

		assertTrue(showsDistantAircraft(clear.copy(dayPhase = DayPhase.DAY)))
		assertTrue(showsDistantAircraft(clear.copy(dayPhase = DayPhase.DAWN)))
		assertTrue(showsDistantAircraft(clear.copy(dayPhase = DayPhase.DUSK)))
		assertTrue(showsDistantAircraft(clear.copy(dayPhase = DayPhase.NIGHT)))
		assertFalse(showsDistantAircraft(clear.copy(distantAircraftEnabled = false)))
		assertFalse(showsDistantAircraft(clear.copy(fogDensity = 1f)))
		assertFalse(showsDistantAircraft(clear.copy(thunder = true)))
		assertFalse(
			showsDistantAircraft(
				clear.copy(precipitation = Precipitation(PrecipitationKind.RAIN, severity = 0.5f, observed = 0.5f))
			)
		)
		assertFalse(showsDistantAircraft(clear.copy(cloudiness = 0.55f)))
		assertTrue(showsDistantAircraft(clear.copy(cloudiness = 0.549f)))
		assertTrue(showsDistantAircraft(clear.copy(humidityHazeDensity = 0.25f)))
		assertFalse(showsDistantAircraft(clear.copy(cloudiness = 0.8f, cloudScale = 0f)))

		assertTrue(
			showsDistantAircraft(
				clear.copy(
					cloudiness = 0.9f,
					cloudLayers = SceneCloudLayers(low = 0.1f, mid = 0.1f, high = 0.9f)
				)
			)
		)
		assertFalse(
			showsDistantAircraft(
				clear.copy(
					cloudiness = 0.2f,
					cloudLayers = SceneCloudLayers(low = 0.6f, mid = 0.1f, high = 0f)
				)
			)
		)
		assertFalse(showsDistantAircraft(clear.copy(cloudiness = 0.3f, cloudCountScale = 2f)))
	}

	@Test
	fun `distant aircraft preference is foreground only`() {
		val enabled = clearParams(distantAircraftEnabled = true)
		val disabled = enabled.copy(distantAircraftEnabled = false)

		assertEquals(backdropSignature(enabled), backdropSignature(disabled))
	}

	@Test
	fun `debug scene params carry distant aircraft preference`() {
		val preset = SCENE_PRESETS.first()

		assertTrue(debugSceneParams(preset, DayPhase.DAY, distantAircraftEnabled = true).distantAircraftEnabled)
		assertFalse(debugSceneParams(preset, DayPhase.DAY, distantAircraftEnabled = false).distantAircraftEnabled)
	}

	private fun clearParams(distantAircraftEnabled: Boolean = true) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0.05f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0.2f,
		distantAircraftEnabled = distantAircraftEnabled
	)
}
