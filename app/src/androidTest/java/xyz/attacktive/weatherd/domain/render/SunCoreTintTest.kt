package xyz.attacktive.weatherd.domain.render

import kotlin.math.ceil
import kotlin.math.floor
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.R

@RunWith(AndroidJUnit4::class)
class SunCoreTintTest {
	@Test
	fun coreCorrectionPreservesOpacityAndAllPixelsOutsideTheCoreAtDifferentScales() {
		val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
		val source = checkNotNull(BitmapFactory.decodeResource(resources, R.drawable.sun_starburst, BitmapFactory.Options().apply { inScaled = false }))
		val tint = Color.rgb(255, 231, 202)
		val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = LightingColorFilter(tint, 0) }
		val core = SunCoreTint(source, source.width / 2f, source.height / 2f, source.width * 0.05f, tint)
		try {
			for (size in listOf(80f, 230f, 511f)) {
				for (alpha in listOf(255, 128, 8)) {
					val destination = RectF(7.25f, 11.75f, 7.25f + size, 11.75f + size)
					val baseline = createBitmap(540, 540)
					val corrected = createBitmap(540, 540)
					paint.alpha = alpha
					Canvas(baseline).drawBitmap(source, null, destination, paint)
					core.draw(Canvas(corrected), destination, alpha)
					val reach = size * 0.05f + size / source.width
					val left = floor(destination.centerX() - reach).toInt()
					val top = floor(destination.centerY() - reach).toInt()
					val right = ceil(destination.centerX() + reach).toInt()
					val bottom = ceil(destination.centerY() + reach).toInt()
					val coreBounds = Rect(left, top, right, bottom)
					try {
						for (y in 0 until baseline.height) {
							for (x in 0 until baseline.width) {
								val before = baseline.getPixel(x, y)
								val after = corrected.getPixel(x, y)
								assertEquals("Core tint must preserve alpha at $x,$y, size $size, opacity $alpha", Color.alpha(before), Color.alpha(after))
								if (!coreBounds.contains(x, y)) {
									assertEquals("Core tint must not alter surrounding artwork at $x,$y, size $size", before, after)
								}
							}
						}
					} finally {
						baseline.recycle()
						corrected.recycle()
					}
				}
			}
		} finally {
			source.recycle()
		}
	}
}
