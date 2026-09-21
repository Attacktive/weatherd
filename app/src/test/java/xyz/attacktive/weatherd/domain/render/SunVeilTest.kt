package xyz.attacktive.weatherd.domain.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class SunVeilTest {
	@Test
	fun `sun veil follows sun visibility`() {
		val params = sceneParams(DayPhase.DAY)

		assertTrue(showsSunVeil(params))
		assertFalse(showsSunVeil(params.copy(sunVisible = false)))
	}

	@Test
	fun `sun veil stays hidden at night`() {
		assertFalse(showsSunVeil(sceneParams(DayPhase.NIGHT)))
	}

	private fun sceneParams(dayPhase: DayPhase) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = 0.5f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0f
	)
}
