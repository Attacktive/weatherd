package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class SceneDebugPresetsTest {
	@Test
	fun `defaults preserve declared wind factor and default precipitation scale across every preset`() {
		for (preset in SCENE_PRESETS) {
			val params = debugSceneParams(preset, DayPhase.DAY)

			assertEquals(preset.windFactor, params.windFactor, 0.0001f)
			assertEquals(1f, params.precipitationScale, 0.0001f)
		}
	}

	@Test
	fun `non-default precipitation scale reaches scene params`() {
		val preset = SCENE_PRESETS.first { it.name == "RAIN" }
		val params = debugSceneParams(preset, DayPhase.DAY, precipitationScale = 1.8f)

		assertEquals(1.8f, params.precipitationScale, 0.0001f)
	}

	@Test
	fun `non-default wind scale scales the preset wind factor`() {
		val preset = SCENE_PRESETS.first { it.name == "CLEAR" }
		val params = debugSceneParams(preset, DayPhase.DAY, windScale = 1.5f)

		assertEquals(0.45f, params.windFactor, 0.0001f)
	}

	@Test
	fun `wind scale clamps at one rather than overflowing`() {
		val thunderstorm = SCENE_PRESETS.first { it.name == "THUNDERSTORM" }
		val params = debugSceneParams(thunderstorm, DayPhase.DAY, windScale = 2f)

		assertEquals(1f, params.windFactor, 0.0001f)
	}

	@Test
	fun `precipitation and wind scales are not interchangeable`() {
		val preset = SCENE_PRESETS.first { it.name == "CLEAR" }
		// Passing distinct non-default positional arguments ensures they cannot be transposed without failing.
		val params = debugSceneParams(preset, DayPhase.DAY, 0.5f, 1.5f)

		assertEquals(0.5f, params.precipitationScale, 0.0001f)
		assertEquals(0.45f, params.windFactor, 0.0001f)
	}
}
