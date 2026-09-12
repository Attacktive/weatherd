package xyz.attacktive.weatherd.domain.repository

import java.nio.file.Files
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.util.DisplayMetrics
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.PhotoBucket
import xyz.attacktive.weatherd.util.AppLogger

class PhotoBackgroundRepositoryTest {
	private val filesDirectory = Files.createTempDirectory("weatherd-photo-test").toFile()
	private val context = mockk<Context> {
		every { filesDir } returns filesDirectory
		every { resources } returns mockk<Resources> {
			every { displayMetrics } returns DisplayMetrics().apply {
				widthPixels = 1080
				heightPixels = 2400
			}
		}
		every { contentResolver } throws IllegalArgumentException("invalid source")
	}
	private val logger = mockk<AppLogger>(relaxed = true)

	@After
	fun deleteTemporaryFiles() {
		filesDirectory.deleteRecursively()
	}

	@Test
	fun `a runtime failure while decoding returns a failed result`() = runTest {
		val repository = PhotoBackgroundRepository(context, logger)
		val outcome = runCatching {
			repository.import(PhotoBucket.DAY, mockk<Uri>())
		}

		assertTrue(outcome.isSuccess)
		assertTrue(outcome.getOrThrow().isFailure)
		assertTrue(outcome.getOrThrow().exceptionOrNull() is IllegalArgumentException)
	}

	@Test
	fun `loading a thumbnail for an unset bucket returns null`() = runTest {
		val repository = PhotoBackgroundRepository(context, logger)

		assertNull(repository.loadThumbnail(PhotoBucket.DAY))
	}
}
