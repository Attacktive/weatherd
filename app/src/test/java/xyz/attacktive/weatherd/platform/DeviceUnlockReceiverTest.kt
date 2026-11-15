package xyz.attacktive.weatherd.platform

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import android.content.BroadcastReceiver
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.util.AppLogger

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceUnlockReceiverTest {
	@Test
	fun `only user present queues an unlock refresh`() = runTest {
		var unlocks = 0
		val receiver = DeviceUnlockReceiver(backgroundScope, mockk(relaxed = true)) { unlocks += 1 }
		for (action in listOf(null, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_UNLOCKED, "wallpaper-visible")) {
			receiver.onReceive(null, intent(action))
		}

		receiver.onReceive(null, null)
		runCurrent()
		assertEquals(0, unlocks)

		receiver.onReceive(null, intent(Intent.ACTION_USER_PRESENT))
		runCurrent()
		assertEquals(1, unlocks)
	}

	@Test
	fun `the pending broadcast finishes after enqueue succeeds`() = runTest {
		val pending = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
		val receiver = spyk(DeviceUnlockReceiver(backgroundScope, mockk(relaxed = true)) {})
		every { receiver.goAsync() } returns pending

		receiver.onReceive(null, intent(Intent.ACTION_USER_PRESENT))
		runCurrent()

		verify(exactly = 1) { pending.finish() }
	}

	@Test
	fun `enqueue failure is logged and still finishes the broadcast`() = runTest {
		val pending = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
		val logger = mockk<AppLogger>(relaxed = true)
		val receiver = spyk(DeviceUnlockReceiver(backgroundScope, logger) { throw IOException("enqueue failed") })
		every { receiver.goAsync() } returns pending

		receiver.onReceive(null, intent(Intent.ACTION_USER_PRESENT))
		runCurrent()

		verify(exactly = 1) { logger.error(any(), any(), any()) }
		verify(exactly = 1) { pending.finish() }
	}

	@Test
	fun `slow enqueue is bounded and releases the pending broadcast`() = runTest {
		val pending = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
		val logger = mockk<AppLogger>(relaxed = true)
		val receiver = spyk(DeviceUnlockReceiver(backgroundScope, logger) { awaitCancellation() })
		every { receiver.goAsync() } returns pending

		receiver.onReceive(null, intent(Intent.ACTION_USER_PRESENT))
		runCurrent()
		advanceTimeBy(8_000L)
		runCurrent()

		verify(exactly = 1) { logger.error(any(), any(), any()) }
		verify(exactly = 1) { pending.finish() }
	}

	private fun intent(action: String?): Intent = mockk { every { this@mockk.action } returns action }
}
