package xyz.attacktive.weatherd.domain.render

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.DayPhase

class RainbowTest {

	@Test
	fun `rainbow tints warmly at dawn and dusk, stays neutral in daytime, and turns dark at night`() {
		assertEquals(0xFFFCECD8.toInt(), RainbowLayer.rainbowTint(DayPhase.DAWN))
		assertEquals(0xFFFFDECC.toInt(), RainbowLayer.rainbowTint(DayPhase.DUSK))
		assertEquals(Color.WHITE, RainbowLayer.rainbowTint(DayPhase.DAY))
		assertEquals(Color.BLACK, RainbowLayer.rainbowTint(DayPhase.NIGHT))
	}

}
