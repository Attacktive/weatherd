package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class SceneDebugPresetsTest {
	@Test
	fun `defaults preserve declared wind factor and default scales across every preset`() {
		for (preset in SCENE_PRESETS) {
			val params = debugSceneParams(preset, DayPhase.DAY)

			assertEquals(preset.windFactor, params.windFactor, 0.0001f)
			assertEquals(1f, params.precipitationScale, 0.0001f)
			assertEquals(1f, params.windScale, 0.0001f)
			assertEquals(1f, params.cloudScale, 0.0001f)
		}
	}

	@Test
	fun `non-default precipitation scale reaches scene params`() {
		val preset = SCENE_PRESETS.first { it.name == "RAIN" }
		val params = debugSceneParams(preset, DayPhase.DAY, precipitationScale = 1.8f)

		assertEquals(1.8f, params.precipitationScale, 0.0001f)
	}

	@Test
	fun `non-default wind scale reaches scene params without mutating wind factor`() {
		val preset = SCENE_PRESETS.first { it.name == "CLEAR" }
		val params = debugSceneParams(preset, DayPhase.DAY, windScale = 1.5f)

		assertEquals(preset.windFactor, params.windFactor, 0.0001f)
		assertEquals(1.5f, params.windScale, 0.0001f)
	}

	@Test
	fun `non-default cloud scale reaches scene params without mutating cloudiness`() {
		val preset = SCENE_PRESETS.first { it.name == "PARTLY CLOUDY" }
		val params = debugSceneParams(preset, DayPhase.DAY, cloudScale = 0.5f)

		assertEquals(preset.cloudiness, params.cloudiness, 0.0001f)
		assertEquals(0.5f, params.cloudScale, 0.0001f)
	}

	@Test
	fun `high wind scale leaves preset wind factor unchanged`() {
		val thunderstorm = SCENE_PRESETS.first { it.name == "THUNDERSTORM" }
		val params = debugSceneParams(thunderstorm, DayPhase.DAY, windScale = 2f)

		assertEquals(thunderstorm.windFactor, params.windFactor, 0.0001f)
		assertEquals(2f, params.windScale, 0.0001f)
	}

	@Test
	fun `precipitation, wind, and cloud scales are not interchangeable`() {
		val preset = SCENE_PRESETS.first { it.name == "CLEAR" }
		// Passing distinct non-default positional arguments ensures they cannot be transposed without failing.
		val params = debugSceneParams(preset, DayPhase.DAY, 0.5f, 1.5f, 0.8f)

		assertEquals(0.5f, params.precipitationScale, 0.0001f)
		assertEquals(1.5f, params.windScale, 0.0001f)
		assertEquals(0.8f, params.cloudScale, 0.0001f)
		assertEquals(preset.windFactor, params.windFactor, 0.0001f)
	}
}
