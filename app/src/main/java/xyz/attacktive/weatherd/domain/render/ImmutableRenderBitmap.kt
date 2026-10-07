package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap

/**
 * Rasterizes drawing commands into a mutable staging bitmap, then returns an immutable copy suitable for hardware-recorded canvases.
 * The staging bitmap never escapes this function and is always recycled after the copy is made.
 */
internal inline fun renderImmutableBitmap(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap = renderImmutableBitmap(width, height, Bitmap.Config.ARGB_8888, draw)

internal inline fun renderImmutableBitmap(width: Int, height: Int, config: Bitmap.Config, draw: (Canvas) -> Unit): Bitmap {
	val mutable = createBitmap(width, height)

	return try {
		draw(Canvas(mutable))
		checkNotNull(mutable.copy(config, false)) {
			"Failed to create immutable render bitmap"
		}
	} finally {
		mutable.recycle()
	}
}
