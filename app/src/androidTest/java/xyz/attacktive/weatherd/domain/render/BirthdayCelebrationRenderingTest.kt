package xyz.attacktive.weatherd.domain.render

import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration

@RunWith(AndroidJUnit4::class)
class BirthdayCelebrationRenderingTest {
	@Test
	fun bannerFitsBothOrientationsAndDisappearsOutsideItsWindow() {
		val start = SeasonalCelebrationFrame.passStartMillis(200L, SeasonalCelebrationFrame.BIRTHDAY_SLOT_MILLIS, SeasonalCelebrationFrame.BIRTHDAY_DURATION_MILLIS, SeasonalCelebrationFrame.BIRTHDAY_SALT)
		val params = debugSceneParams(SCENE_PRESETS.first(), DayPhase.NIGHT, holidayCelebration = HolidayCelebration.BIRTHDAY, birthdayGreeting = "Buon compleanno!", moonSizeScale = 2f)
			.copy(
				overlayLabels = OverlayLabels("Clear", "Rome")
			)

		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			for (position in listOf(0f, 0.09f, 0.35f, 0.75f, 1f)) {
				val configured = params.copy(labelVerticalPosition = position)
				assertTrue(renderedPixels(width, height, configured, start + 1000) > 0)

				val frame = SeasonalCelebrationFrame()
				assertTrue(frame.sample(width.toFloat(), height.toFloat(), configured, start + 1000))
				assertTrue("Greeting needs fireworks even with a large Moon", frame.activeBurstCount > 0)
				assertEquals(0, renderedPixels(width, height, configured, start - 1))
				assertEquals(0, renderedPixels(width, height, configured, start + SeasonalCelebrationFrame.BIRTHDAY_DURATION_MILLIS))
				assertEquals(0, renderedPixels(width, height, configured.copy(nightBrightnessScale = 0f), start + 1000))
				assertEquals(0, renderedPixels(width, height, configured.copy(dayPhase = DayPhase.DAY), start + 1000))
			}
		}
	}

	@Test
	fun translatedBannerRemainsInsideItsReservedBounds() {
		val layer = BirthdayBannerLayer()
		val frame = SeasonalCelebrationFrame()
		val params = debugSceneParams(SCENE_PRESETS.first(), DayPhase.NIGHT, holidayCelebration = HolidayCelebration.BIRTHDAY, moonSizeScale = 2f)
		val start = SeasonalCelebrationFrame.passStartMillis(200L, SeasonalCelebrationFrame.BIRTHDAY_SLOT_MILLIS, SeasonalCelebrationFrame.BIRTHDAY_DURATION_MILLIS, SeasonalCelebrationFrame.BIRTHDAY_SALT)

		for ((width, height) in listOf(360 to 780, 780 to 360)) {
			for (text in listOf("Happy birthday!", "Buon compleanno!", "Alles Gute zum Geburtstag!", "Feliz cumpleaños!", "Joyeux anniversaire !", "생일 축하해요!", "お誕生日おめでとう！", "生日快乐！")) {
				val bitmap = createBitmap(width, height)

				try {
					assertTrue(frame.sample(width.toFloat(), height.toFloat(), params, start + 1000))

					layer.draw(Canvas(bitmap), width.toFloat(), text, frame)
					val top = frame.birthdayGreetingY - frame.birthdayGreetingHeight * 0.5f
					val bottom = frame.birthdayGreetingY + frame.birthdayGreetingHeight * 0.5f
					var count = 0
					for (y in 0 until height) {
						for (x in 0 until width) {
							if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
								assertTrue("Banner escaped vertically: $text", y >= top - 1 && y <= bottom + 1)
								assertTrue("Banner escaped horizontally: $text", x > width * 0.10f && x < width * 0.90f)

								count++
							}
						}
					}

					assertTrue(count > 0)
				} finally {
					bitmap.recycle()
				}
			}
		}
	}

	private fun renderedPixels(width: Int, height: Int, params: SceneParams, epochMillis: Long): Int {
		val bitmap = createBitmap(width, height)
		try {
			SeasonalCelebrationLayer().draw(Canvas(bitmap), width.toFloat(), height.toFloat(), params, epochMillis)

			val pixels = IntArray(width * height)
			bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

			return pixels.count { Color.alpha(it) > 0 }
		} finally {
			bitmap.recycle()
		}
	}
}
