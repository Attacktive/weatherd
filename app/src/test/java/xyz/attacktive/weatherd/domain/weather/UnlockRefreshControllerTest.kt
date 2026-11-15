package xyz.attacktive.weatherd.domain.weather

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockRefreshControllerTest {
	@Test
	fun `disabled unlocks do not fetch or consume the first attempt`() = runBlocking {
		val fixture = Fixture(enabled = false)

		assertEquals(UnlockRefreshResult.SKIPPED, fixture.controller.refresh())
		assertEquals(0, fixture.requests)

		fixture.enabled = true
		assertEquals(UnlockRefreshResult.COMPLETED, fixture.controller.refresh())
		assertEquals(1, fixture.requests)
	}

	@Test
	fun `first unlock at zero uptime is accepted`() = runBlocking {
		val fixture = Fixture()

		assertEquals(UnlockRefreshResult.COMPLETED, fixture.controller.refresh())
		assertEquals(1, fixture.requests)
	}

	@Test
	fun `duplicate unlocks issue only one refresh`() = runBlocking {
		val fixture = Fixture()

		repeat(20) { fixture.controller.refresh() }

		assertEquals(1, fixture.requests)
	}

	@Test
	fun `cooldown has an inclusive five minute boundary`() = runBlocking {
		val fixture = Fixture()
		fixture.controller.refresh()
		fixture.now = UNLOCK_REFRESH_COOLDOWN_MILLIS - 1

		assertEquals(UnlockRefreshResult.SKIPPED, fixture.controller.refresh())
		fixture.now += 1

		assertEquals(UnlockRefreshResult.COMPLETED, fixture.controller.refresh())
		assertEquals(2, fixture.requests)
	}

	@Test
	fun `rejected unlocks do not extend the cooldown`() = runBlocking {
		val fixture = Fixture()
		fixture.controller.refresh()
		fixture.now = UNLOCK_REFRESH_COOLDOWN_MILLIS / 2
		fixture.controller.refresh()
		fixture.now = UNLOCK_REFRESH_COOLDOWN_MILLIS
		fixture.controller.refresh()

		assertEquals(2, fixture.requests)
	}

	@Test
	fun `concurrent unlocks do not queue another request even after the cooldown`() = runBlocking {
		val fixture = Fixture()
		val release = CompletableDeferred<Unit>()
		fixture.action = { release.await() }
		val first = async(start = CoroutineStart.UNDISPATCHED) { fixture.controller.refresh() }
		fixture.now = 2 * UNLOCK_REFRESH_COOLDOWN_MILLIS

		assertEquals(UnlockRefreshResult.SKIPPED, fixture.controller.refresh())
		assertEquals(1, fixture.requests)

		release.complete(Unit)
		assertEquals(UnlockRefreshResult.COMPLETED, first.await())
	}

	@Test
	fun `failed refresh is logged and consumes cooldown before recovery`() = runBlocking {
		val fixture = Fixture()
		fixture.action = { throw IOException("offline") }

		assertEquals(UnlockRefreshResult.FAILED, fixture.controller.refresh())
		assertEquals(UnlockRefreshResult.SKIPPED, fixture.controller.refresh())
		assertEquals(1, fixture.failures.size)
		assertEquals(1, fixture.requests)

		fixture.now = UNLOCK_REFRESH_COOLDOWN_MILLIS
		fixture.action = {}

		assertEquals(UnlockRefreshResult.COMPLETED, fixture.controller.refresh())
		assertEquals(2, fixture.requests)
	}

	@Test
	fun `cancellation propagates releases the guard and retains cooldown`() = runBlocking {
		val fixture = Fixture()
		fixture.action = { awaitCancellation() }
		val first = launch(start = CoroutineStart.UNDISPATCHED) { fixture.controller.refresh() }
		first.cancel()
		first.join()

		assertTrue(first.isCancelled)
		assertTrue(fixture.failures.isEmpty())
		assertEquals(UnlockRefreshResult.SKIPPED, fixture.controller.refresh())

		fixture.now = UNLOCK_REFRESH_COOLDOWN_MILLIS
		fixture.action = {}
		assertEquals(UnlockRefreshResult.COMPLETED, fixture.controller.refresh())
	}

	@Test
	fun `direct cancellation exceptions are not converted to failure`() = runBlocking {
		val fixture = Fixture()
		fixture.action = { throw CancellationException("canceled") }
		var canceled = false

		try {
			fixture.controller.refresh()
		} catch (_: CancellationException) {
			canceled = true
		}

		assertTrue(canceled)
		assertTrue(fixture.failures.isEmpty())
	}

	@Test
	fun `disabling and reenabling cannot reset an existing cooldown`() = runBlocking {
		val fixture = Fixture()
		fixture.controller.refresh()
		fixture.enabled = false
		fixture.controller.refresh()
		fixture.enabled = true

		assertEquals(UnlockRefreshResult.SKIPPED, fixture.controller.refresh())
		assertEquals(1, fixture.requests)
	}

	@Test
	fun `unavailable preferences do not start a refresh`() = runBlocking {
		var requests = 0
		val failures = mutableListOf<Exception>()
		val controller = UnlockRefreshController(
			isEnabled = { throw IOException("preferences unavailable") },
			requestRefresh = { requests += 1 },
			elapsedRealtimeMillis = { 0L },
			onFailure = failures::add
		)

		assertEquals(UnlockRefreshResult.FAILED, controller.refresh())
		assertEquals(0, requests)
		assertEquals(1, failures.size)
	}

	@Test
	fun `a reversed clock does not strand the refresh gate`() = runBlocking {
		val fixture = Fixture()
		fixture.now = 100_000L
		fixture.controller.refresh()
		fixture.now = 0L

		assertEquals(UnlockRefreshResult.COMPLETED, fixture.controller.refresh())
		assertEquals(2, fixture.requests)
	}

	private class Fixture(var enabled: Boolean = true) {
		var now = 0L
		var requests = 0
		var action: suspend () -> Unit = {}
		val failures = mutableListOf<Exception>()
		val controller = UnlockRefreshController(
			isEnabled = { enabled },
			requestRefresh = {
				requests += 1
				action()
			},
			elapsedRealtimeMillis = { now },
			onFailure = failures::add
		)
	}
}
