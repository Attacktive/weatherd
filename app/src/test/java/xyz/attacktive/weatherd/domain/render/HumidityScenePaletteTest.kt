package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.SkyColorPreset

class HumidityScenePaletteTest {
	@Test
	fun `humidity haze keeps the underlying phase palette`() {
		for (phase in DayPhase.entries) {
			for (preset in SkyColorPreset.entries) {
				val clear = SceneParams(
					dayPhase = phase,
					cloudiness = 0.05f,
					fogDensity = 0f,
					precipitation = null,
					thunder = false,
					windFactor = 0.3f,
					skyColorPreset = preset
				)
				val humid = clear.copy(humidityHazeDensity = 0.25f)

				assertEquals("$phase $preset", skyGradientFor(clear), skyGradientFor(humid))
			}
		}
	}
}
