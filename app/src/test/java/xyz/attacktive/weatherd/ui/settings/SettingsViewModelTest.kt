package xyz.attacktive.weatherd.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import android.app.Application
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.PhotoBucket
import xyz.attacktive.weatherd.domain.repository.GeocodingRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

/**
 * The photo side of the view model: what the failure flag is allowed to say, and what survives the screen going away.
 * The repositories are mocked because every question here is about scheduling and state rather than about files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
	private val settingsRepository = mockk<SettingsRepository>()
	private val geocodingRepository = mockk<GeocodingRepository>()
	private val photoBackgroundRepository = mockk<PhotoBackgroundRepository>()

	@Before
	fun stubRepositories() {
		every { settingsRepository.settings } returns flowOf(AppSettings())
		every { photoBackgroundRepository.available } returns MutableStateFlow(emptySet())
		every { photoBackgroundRepository.revision } returns MutableStateFlow(0)
		coEvery { photoBackgroundRepository.loadThumbnail(any()) } returns null
	}

	@After
	fun resetMainDispatcher() {
		Dispatchers.resetMain()
	}

	/**
	 * Builds the view model with the test's own scheduler behind both scopes, so that [advanceUntilIdle] drives the application-scoped work too.
	 * The application scope is a scope of the test's making rather than the view model's, which is the whole point of the arrangement under test.
	 */
	private fun TestScope.viewModel(): SettingsViewModel {
		val dispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(dispatcher)

		return SettingsViewModel(
			application = mockk<Application>(relaxed = true),
			settingsRepository = settingsRepository,
			geocodingRepository = geocodingRepository,
			photoBackgroundRepository = photoBackgroundRepository,
			applicationScope = CoroutineScope(dispatcher)
		)
	}

	@Test
	fun `a failure raised while another import is in flight does not outlive that import`() = runTest {
		val viewModel = viewModel()
		val slowNight = CompletableDeferred<Result<Unit>>()
		coEvery { photoBackgroundRepository.import(PhotoBucket.DAY, any()) } returns Result.failure(IllegalStateException())
		coEvery { photoBackgroundRepository.import(PhotoBucket.NIGHT, any()) } coAnswers { slowNight.await() }

		viewModel.importPhoto(PhotoBucket.NIGHT, mockk())
		viewModel.importPhoto(PhotoBucket.DAY, mockk())
		advanceUntilIdle()
		assertTrue(viewModel.photoImportFailed.value)

		slowNight.complete(Result.success(Unit))
		advanceUntilIdle()

		assertFalse(viewModel.photoImportFailed.value)
	}

	@Test
	fun `an import finishes even though the screen is gone`() = runTest {
		val viewModel = viewModel()
		val slowImport = CompletableDeferred<Unit>()
		var copied = false
		coEvery { photoBackgroundRepository.import(PhotoBucket.DAY, any()) } coAnswers {
			slowImport.await()
			copied = true

			Result.success(Unit)
		}

		viewModel.importPhoto(PhotoBucket.DAY, mockk())
		advanceUntilIdle()

		viewModel.viewModelScope.cancel()
		slowImport.complete(Unit)
		advanceUntilIdle()

		assertTrue(copied)
		assertFalse(viewModel.photoImportFailed.value)
	}

	@Test
	fun `a clear finishes even though the screen is gone`() = runTest {
		val viewModel = viewModel()
		val gate = CompletableDeferred<Unit>()
		var cleared = false
		coEvery { photoBackgroundRepository.clear(PhotoBucket.DAY) } coAnswers {
			gate.await()
			cleared = true
		}

		viewModel.clearPhoto(PhotoBucket.DAY)
		advanceUntilIdle()

		viewModel.viewModelScope.cancel()
		gate.complete(Unit)
		advanceUntilIdle()

		assertTrue(cleared)
	}
}
