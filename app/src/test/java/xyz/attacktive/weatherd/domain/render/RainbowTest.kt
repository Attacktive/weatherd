package xyz.attacktive.weatherd.domain.render

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class RainbowTest {
	@Test
	fun `rainbow displays during daytime when enabled, but never at night or when disabled`() {
		for (phase in DayPhase.entries) {
			val enabledParams = baseParams(dayPhase = phase, showRainbow = true)
			val disabledParams = baseParams(dayPhase = phase, showRainbow = false)

			assertFalse(showsRainbow(disabledParams))

			if (phase == DayPhase.NIGHT) {
				assertFalse(showsRainbow(enabledParams))
			} else {
				assertTrue(showsRainbow(enabledParams))
			}
		}
	}

	@Test
	fun `rainbow tints warmly at dawn and dusk, stays neutral in daytime, and turns dark at night`() {
		assertEquals(0xFFFCECD8.toInt(), RainbowLayer.rainbowTint(DayPhase.DAWN))
		assertEquals(0xFFFFDECC.toInt(), RainbowLayer.rainbowTint(DayPhase.DUSK))
		assertEquals(Color.WHITE, RainbowLayer.rainbowTint(DayPhase.DAY))
		assertEquals(Color.BLACK, RainbowLayer.rainbowTint(DayPhase.NIGHT))
	}

	private fun baseParams(dayPhase: DayPhase, showRainbow: Boolean) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = 0.1f,
		fogDensity = 0f,
		precipitation = null,
		thunder = false,
		windFactor = 0.2f,
		showRainbow = showRainbow
	)
}
