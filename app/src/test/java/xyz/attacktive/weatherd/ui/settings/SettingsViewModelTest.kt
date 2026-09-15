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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import android.app.Application
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.GeoPlace
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
		).also {
			testScheduler.runCurrent()
		}
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

	@Test
	fun `fewer than two trimmed characters produce Idle and never call search`() = runTest {
		val viewModel = viewModel()

		viewModel.onCityQueryChange("  ")
		runCurrent()
		assertEquals(CitySearchState.Idle, viewModel.citySearch.value)

		viewModel.onCityQueryChange("a")
		runCurrent()
		assertEquals(CitySearchState.Idle, viewModel.citySearch.value)

		viewModel.onCityQueryChange(" a ")
		runCurrent()
		assertEquals(CitySearchState.Idle, viewModel.citySearch.value)

		coVerify(exactly = 0) { geocodingRepository.search(any()) }
	}

	@Test
	fun `an eligible query waits 400 ms, then yields Loading and Results`() = runTest {
		val viewModel = viewModel()
		val places = listOf(GeoPlace(name = "Tokyo", latitude = 35.6895, longitude = 139.69171))
		val deferred = CompletableDeferred<Result<List<GeoPlace>>>()
		coEvery { geocodingRepository.search("Tokyo") } coAnswers { deferred.await() }

		viewModel.onCityQueryChange("Tokyo")

		advanceTimeBy(399)
		runCurrent()
		assertEquals(CitySearchState.Idle, viewModel.citySearch.value)

		advanceTimeBy(1)
		runCurrent()
		assertEquals(CitySearchState.Loading, viewModel.citySearch.value)

		deferred.complete(Result.success(places))
		runCurrent()
		assertEquals(CitySearchState.Results(places), viewModel.citySearch.value)
	}

	@Test
	fun `an eligible query waits 400 ms, then yields Empty and Error`() = runTest {
		val viewModel = viewModel()
		coEvery { geocodingRepository.search("EmptyCity") } returns Result.success(emptyList())
		coEvery { geocodingRepository.search("ErrorCity") } returns Result.failure(IllegalStateException("boom"))

		viewModel.onCityQueryChange("EmptyCity")
		advanceTimeBy(400)
		runCurrent()
		assertEquals(CitySearchState.Empty, viewModel.citySearch.value)

		viewModel.onCityQueryChange("ErrorCity")
		advanceTimeBy(400)
		runCurrent()
		assertEquals(CitySearchState.Error("boom"), viewModel.citySearch.value)
	}

	@Test
	fun `immediate search executes without advancing virtual time and is not repeated when the debounce window elapses`() = runTest {
		val viewModel = viewModel()
		val places = listOf(GeoPlace(name = "Rome", latitude = 41.89, longitude = 12.51))
		coEvery { geocodingRepository.search("Rome") } returns Result.success(places)

		viewModel.onCityQueryChange("Rome")
		viewModel.searchCityImmediately("Rome")
		runCurrent()

		assertEquals(CitySearchState.Results(places), viewModel.citySearch.value)
		coVerify(exactly = 1) { geocodingRepository.search("Rome") }

		advanceTimeBy(400)
		runCurrent()
		coVerify(exactly = 1) { geocodingRepository.search("Rome") }
	}

	@Test
	fun `immediate search retries an unchanged query`() = runTest {
		val viewModel = viewModel()
		val places = listOf(GeoPlace(name = "Rome", latitude = 41.89, longitude = 12.51))
		coEvery { geocodingRepository.search("Rome") } returns Result.failure(IllegalStateException("Network failure")) andThen Result.success(places)

		viewModel.onCityQueryChange("Rome")
		advanceTimeBy(400)
		runCurrent()
		assertEquals(CitySearchState.Error("Network failure"), viewModel.citySearch.value)
		coVerify(exactly = 1) { geocodingRepository.search("Rome") }

		viewModel.searchCityImmediately("Rome")
		runCurrent()
		assertEquals(CitySearchState.Results(places), viewModel.citySearch.value)
		coVerify(exactly = 2) { geocodingRepository.search("Rome") }
	}

	@Test
	fun `a later query cancels a blocked earlier query and only the later result reaches citySearch`() = runTest {
		val viewModel = viewModel()
		val slowDeferred = CompletableDeferred<Result<List<GeoPlace>>>()
		val tokyoPlaces = listOf(GeoPlace(name = "Tokyo", latitude = 35.6895, longitude = 139.69171))
		val osakaPlaces = listOf(GeoPlace(name = "Osaka", latitude = 34.6937, longitude = 135.5023))

		coEvery { geocodingRepository.search("Tokyo") } coAnswers { slowDeferred.await() }
		coEvery { geocodingRepository.search("Osaka") } returns Result.success(osakaPlaces)

		viewModel.onCityQueryChange("Tokyo")
		advanceTimeBy(400)
		runCurrent()
		assertEquals(CitySearchState.Loading, viewModel.citySearch.value)

		viewModel.onCityQueryChange("Osaka")
		advanceTimeBy(400)
		runCurrent()

		assertEquals(CitySearchState.Results(osakaPlaces), viewModel.citySearch.value)

		slowDeferred.complete(Result.success(tokyoPlaces))
		advanceUntilIdle()

		assertEquals(CitySearchState.Results(osakaPlaces), viewModel.citySearch.value)
	}

	@Test
	fun `clearing during loading returns to Idle and the canceled request never becomes Error`() = runTest {
		val viewModel = viewModel()
		val deferred = CompletableDeferred<Result<List<GeoPlace>>>()
		coEvery { geocodingRepository.search("Tokyo") } coAnswers { deferred.await() }

		viewModel.onCityQueryChange("Tokyo")
		advanceTimeBy(400)
		runCurrent()
		assertEquals(CitySearchState.Loading, viewModel.citySearch.value)

		viewModel.clearCityQuery()
		runCurrent()
		assertEquals(CitySearchState.Idle, viewModel.citySearch.value)

		deferred.complete(Result.failure(IllegalStateException("Canceled request failure")))
		advanceUntilIdle()

		assertEquals(CitySearchState.Idle, viewModel.citySearch.value)
	}

	@Test
	fun `an eligible query edit cancels an active in-flight request during debounce`() = runTest {
		val viewModel = viewModel()
		val tokyoDeferred = CompletableDeferred<Result<List<GeoPlace>>>()
		val tokyoPlaces = listOf(GeoPlace(name = "Tokyo", latitude = 35.6895, longitude = 139.69171))
		val tokyooPlaces = listOf(GeoPlace(name = "Tokyoo", latitude = 35.0, longitude = 139.0))
		coEvery { geocodingRepository.search("Tokyo") } coAnswers { tokyoDeferred.await() }
		coEvery { geocodingRepository.search("Tokyoo") } returns Result.success(tokyooPlaces)

		viewModel.searchCityImmediately("Tokyo")
		runCurrent()
		assertEquals(CitySearchState.Loading, viewModel.citySearch.value)

		viewModel.onCityQueryChange("Tokyoo")
		runCurrent()

		tokyoDeferred.complete(Result.success(tokyoPlaces))
		runCurrent()

		assertNotEquals(CitySearchState.Results(tokyoPlaces), viewModel.citySearch.value)

		advanceTimeBy(400)
		runCurrent()
		assertEquals(CitySearchState.Results(tokyooPlaces), viewModel.citySearch.value)
	}
}
