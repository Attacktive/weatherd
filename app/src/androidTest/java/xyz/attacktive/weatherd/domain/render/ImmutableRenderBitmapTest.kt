package xyz.attacktive.weatherd.domain.render

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmutableRenderBitmapTest {
	@Test
	fun rasterizedBitmapIsImmutableWithoutChangingPixels() {
		val bitmap = renderImmutableBitmap(4, 4) { canvas ->
			canvas.drawColor(Color.MAGENTA)
		}

		try {
			assertFalse(bitmap.isMutable)
			assertEquals(Color.MAGENTA, bitmap.getPixel(2, 2))
		} finally {
			bitmap.recycle()
		}
	}
}
