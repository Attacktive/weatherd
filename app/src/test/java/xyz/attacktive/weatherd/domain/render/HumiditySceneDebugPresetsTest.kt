package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class HumiditySceneDebugPresetsTest {
	@Test
	fun `humidity haze preset stays distinct from reported fog`() {
		val hazeIndex = SCENE_PRESETS.indexOfFirst { it.name == "HUMID HAZE" }
		val haze = debugSceneParams(SCENE_PRESETS[hazeIndex], DayPhase.DAY)
		val fog = debugSceneParams(SCENE_PRESETS.first { it.name == "FOG" }, DayPhase.DAY)

		assertEquals(SCENE_PRESETS.lastIndex, hazeIndex)
		assertEquals(0f, haze.fogDensity, 0.0001f)
		assertEquals(0.25f, haze.humidityHazeDensity, 0.0001f)
		assertEquals(1f, fog.fogDensity, 0.0001f)
		assertEquals(0f, fog.humidityHazeDensity, 0.0001f)
	}
}
