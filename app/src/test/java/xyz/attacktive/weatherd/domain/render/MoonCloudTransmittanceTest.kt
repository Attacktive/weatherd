package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class MoonCloudTransmittanceTest {
	@Test
	fun clearOpeningLeavesMoonFullyVisibleEvenInPartlyCloudyWeather() {
		val params = debugSceneParams(SCENE_PRESETS.first { it.name == "PARTLY CLOUDY" }, DayPhase.NIGHT)
		assertEquals(0.7f, effectiveOpaqueCloudiness(params), 0f)
		assertEquals(1f, moonCloudTransmittance(0f), 0f)
	}

	@Test
	fun onlyLocalCloudOverlapDimsTheMoon() {
		val thinCloud = moonCloudTransmittance(0.25f)
		val mediumCloud = moonCloudTransmittance(0.5f)
		val thickCloud = moonCloudTransmittance(0.85f)

		assertTrue(thinCloud in 0f..1f)
		assertTrue(mediumCloud < thinCloud)
		assertTrue(thickCloud < mediumCloud)
		assertEquals(0f, moonCloudTransmittance(1f), 0f)
	}

	@Test
	fun invalidLocalOpacityCannotProduceAnInvalidAlpha() {
		assertEquals(1f, moonCloudTransmittance(Float.NaN), 0f)
		assertEquals(1f, moonCloudTransmittance(Float.POSITIVE_INFINITY), 0f)
		assertEquals(1f, moonCloudTransmittance(-1f), 0f)
		assertEquals(0f, moonCloudTransmittance(2f), 0f)
	}
}
