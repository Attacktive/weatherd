package xyz.attacktive.weatherd.domain.render

import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class WeatherNoticeRenderingTest {
	@Test
	fun warningRemainsVisibleWithoutLabelsAndDisappearsAfterRecovery() {
		val renderer = SceneRenderer(InstrumentationRegistry.getInstrumentation().targetContext.resources)
		val effects = SceneScreenEffects(renderer)
		val base = debugSceneParams(SCENE_PRESETS.first(), DayPhase.DAY)
		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			val empty = createBitmap(width, height)
			val warning = createBitmap(width, height)
			val recovered = createBitmap(width, height)
			try {
				effects.render(Canvas(empty), width, height, base, 0f)
				effects.render(Canvas(warning), width, height, base.copy(weatherNotice = WeatherNotice(1_000_000L, "Berlin")), 0f)
				effects.render(Canvas(recovered), width, height, base, 0f)
				assertFalse("Stale weather must visibly warn even with both label toggles off", empty.sameAs(warning))
				assertTrue("Recovery must remove all warning pixels", empty.sameAs(recovered))
			} finally {
				empty.recycle()
				warning.recycle()
				recovered.recycle()
			}
		}
	}

	@Test
	fun warningAgeAdvancesWhileSceneAndAnimationClockStayUnchanged() {
		val renderer = SceneRenderer(InstrumentationRegistry.getInstrumentation().targetContext.resources)
		val effects = SceneScreenEffects(renderer)
		val params = debugSceneParams(SCENE_PRESETS.first(), DayPhase.NIGHT).copy(weatherNotice = WeatherNotice(1_000_000L, "Berlin"))
		val first = createBitmap(360, 780)
		val later = createBitmap(360, 780)
		try {
			effects.render(Canvas(first), 360, 780, params, 0f, noticeEpochSeconds = 1_000_120L)
			effects.render(Canvas(later), 360, 780, params, 0f, noticeEpochSeconds = 1_000_180L)
			assertFalse("A settled scene must update the displayed age without restarting motion", first.sameAs(later))
		} finally {
			first.recycle()
			later.recycle()
		}
	}
}
