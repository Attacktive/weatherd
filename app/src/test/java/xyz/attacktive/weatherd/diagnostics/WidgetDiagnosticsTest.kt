package xyz.attacktive.weatherd.diagnostics

import android.content.Context
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetDiagnosticsTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun coldProcessRetainsEventsWhilePersistedCaptureStateLoads() = runTest {
		val journal = WidgetDiagnosticJournal(temporaryFolder.newFolder())
		journal.setEnabled(true)
		val diagnostics = WidgetDiagnostics(mockk<Context>(), { journal }, backgroundScope, StandardTestDispatcher(testScheduler))
		diagnostics.record("cold_process_receiver")
		runCurrent()

		assertTrue(journal.report().contains("cold_process_receiver"))
	}

	@Test
	fun overflowingRecordQueueNeverWritesLossMetadataOnTheCallerThread() = runTest {
		val journal = WidgetDiagnosticJournal(temporaryFolder.newFolder())
		journal.setEnabled(true)
		val diagnostics = WidgetDiagnostics(mockk<Context>(), { journal }, backgroundScope, StandardTestDispatcher(testScheduler))
		runCurrent()
		repeat(1000) { diagnostics.record("queued_event") }

		assertTrue(diagnostics.state.value.droppedEvents > 0L)
		assertEquals(0L, journal.droppedCount())
		runCurrent()
		assertTrue(journal.droppedCount() > 0L)
	}

	@Test
	fun togglingCaptureDoesNotErasePriorStorageFailureEvidence() = runTest {
		val journal = WidgetDiagnosticJournal(temporaryFolder.newFolder())
		journal.recordStorageFailed(true)
		val diagnostics = WidgetDiagnostics(mockk<Context>(), { journal }, backgroundScope, StandardTestDispatcher(testScheduler))
		runCurrent()
		val enabling = async { diagnostics.setEnabled(true) }
		runCurrent()

		assertTrue(enabling.await().isSuccess)
		assertTrue(diagnostics.state.value.storageFailed)
		assertTrue(journal.hasStorageFailed())
		val clearing = async { diagnostics.clear() }
		runCurrent()

		assertTrue(clearing.await().isSuccess)
		assertFalse(diagnostics.state.value.storageFailed)
	}

	@Test
	fun unreadableHealthDoesNotDisableCaptureAndClearingRepairsRecording() = runTest {
		val directory = temporaryFolder.newFolder()
		val journal = WidgetDiagnosticJournal(directory)
		journal.setEnabled(true)
		File(directory, "health").writeText("corrupt")
		val diagnostics = WidgetDiagnostics(mockk<Context>(), { journal }, backgroundScope, StandardTestDispatcher(testScheduler))
		runCurrent()

		assertTrue(diagnostics.state.value.enabled)
		assertTrue(diagnostics.state.value.storageFailed)
		val clearing = async { diagnostics.clear() }
		runCurrent()
		assertTrue(clearing.await().isSuccess)
		diagnostics.record("after_repair")
		runCurrent()

		assertTrue(journal.report().contains("after_repair"))
	}

	@Test
	fun journalRecoveryAndQueueOverflowContributeToTheSameLossCounter() = runTest {
		val directory = temporaryFolder.newFolder()
		val journal = WidgetDiagnosticJournal(directory)
		journal.setEnabled(true)
		journal.append("before_restart")
		File(directory, "journal.log").appendText("interrupted")
		val reopened = WidgetDiagnosticJournal(directory)
		val diagnostics = WidgetDiagnostics(mockk<Context>(), { reopened }, backgroundScope, StandardTestDispatcher(testScheduler))
		runCurrent()
		repeat(1000) { diagnostics.record("queued_after_restart") }
		val queueLosses = diagnostics.state.value.droppedEvents
		runCurrent()

		assertTrue(queueLosses > 0L)
		assertEquals(queueLosses + 1L, diagnostics.state.value.droppedEvents)
		assertEquals(diagnostics.state.value.droppedEvents, reopened.droppedCount())
	}

	@Test
	fun persistedDropRemainsVisibleWhenWritingItsMarkerFails() = runTest {
		val directory = temporaryFolder.newFolder()
		val journal = WidgetDiagnosticJournal(directory)
		journal.setEnabled(true)
		journal.append("before_failure")
		val diagnostics = WidgetDiagnostics(mockk<Context>(), { journal }, backgroundScope, StandardTestDispatcher(testScheduler))
		runCurrent()
		assertTrue(File(directory, "journal.log").delete())
		assertTrue(File(directory, "journal.log").mkdir())
		diagnostics.record("x".repeat(journal.maxBytes))
		runCurrent()

		assertTrue(diagnostics.state.value.storageFailed)
		assertEquals(1L, journal.droppedCount())
		assertEquals(1L, diagnostics.state.value.droppedEvents)
	}
}
