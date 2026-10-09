package xyz.attacktive.weatherd.domain.repository

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.PhotoBucket
import xyz.attacktive.weatherd.util.LogcatLogger

@RunWith(AndroidJUnit4::class)
class PhotoBackgroundStateTest {
	@Test
	fun clearingPhotoPublishesAvailabilityBeforeRenderInvalidation() = runBlocking {
		val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
		val directory = File(targetContext.cacheDir, "photo-state-${System.nanoTime()}")
		val backgrounds = File(directory, "backgrounds")
		assertTrue(backgrounds.mkdirs())
		val bitmap = createBitmap(8, 8)
		File(backgrounds, "day.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

		bitmap.recycle()
		val context = object: ContextWrapper(targetContext) {
			override fun getFilesDir() = directory
		}

		try {
			val repository = PhotoBackgroundRepository(context, LogcatLogger())
			assertTrue(repository.hasFor(BackdropScene.PHOTO, DayPhase.DAY))
			val availableOnInvalidation = async(Dispatchers.Unconfined) {
				repository.state.drop(1).first()
				repository.hasFor(BackdropScene.PHOTO, DayPhase.DAY)
			}

			repository.clear(PhotoBucket.DAY)
			assertFalse(withTimeout(5_000L) { availableOnInvalidation.await() })
			assertFalse(repository.hasFor(BackdropScene.PHOTO, DayPhase.DAY))
		} finally {
			directory.deleteRecursively()
		}
	}
}
