package xyz.attacktive.weatherd.diagnostics

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WidgetDiagnosticJournalTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun disabledCaptureDoesNotRetainEvents() {
		val journal = WidgetDiagnosticJournal(temporaryFolder.newFolder())
		journal.append("receiver_received")

		assertFalse(journal.isEnabled())
		assertFalse(journal.report().contains("receiver_received"))
	}

	@Test
	fun enabledCaptureAndEventsSurviveReopening() {
		val directory = temporaryFolder.newFolder()
		val journal = WidgetDiagnosticJournal(directory)
		journal.setEnabled(true)
		journal.append("receiver_received receipt=7")

		val reopened = WidgetDiagnosticJournal(directory)

		assertTrue(reopened.isEnabled())
		assertTrue(reopened.report().contains("receiver_received receipt=7"))
		reopened.setEnabled(false)
		reopened.append("must_not_be_recorded")

		assertFalse(WidgetDiagnosticJournal(directory).isEnabled())
		assertFalse(reopened.report().contains("must_not_be_recorded"))
		assertTrue(reopened.report().contains("receiver_received receipt=7"))
	}

	@Test
	fun clearingRemovesCapturedEventsWithoutDisablingCapture() {
		val journal = WidgetDiagnosticJournal(temporaryFolder.newFolder())
		journal.setEnabled(true)
		journal.append("before_clear")
		journal.clear()
		journal.append("after_clear")

		assertTrue(journal.isEnabled())
		assertFalse(journal.report().contains("before_clear"))
		assertTrue(journal.report().contains("after_clear"))
	}

	@Test
	fun rotationKeepsRecentCompleteUtf8EventsWithinTheByteBudget() {
		val journal = WidgetDiagnosticJournal(temporaryFolder.newFolder(), maxBytes = 80)
		journal.setEnabled(true)
		for (index in 0..19) {
			journal.append("event=$index 한글")
		}

		val report = journal.report()

		assertTrue(report.toByteArray(Charsets.UTF_8).size <= 80)
		assertTrue(report.endsWith("event=19 한글\n"))
		assertFalse(report.contains("event=0 한글"))
		assertTrue(report.lines().filter { it.isNotEmpty() }.all { it.matches(Regex("event=\\d+ 한글")) })
	}

	@Test
	fun oversizedEventDoesNotDestroyPreviouslyCapturedEvidence() {
		val journal = WidgetDiagnosticJournal(temporaryFolder.newFolder(), maxBytes = 80)
		journal.setEnabled(true)
		journal.append("important_before")
		journal.append("x".repeat(200))
		journal.append("important_after")

		val report = journal.report()

		assertTrue(report.toByteArray(Charsets.UTF_8).size <= 80)
		assertTrue(report.contains("important_before"))
		assertTrue(report.contains("important_after"))
	}

	@Test(expected = IOException::class)
	fun unavailableStorageIsNotReportedAsSuccessfulCapture() {
		val file = temporaryFolder.newFile()
		val journal = WidgetDiagnosticJournal(File(file, "not_a_directory"))
		journal.setEnabled(true)
	}

	@Test
	fun partialTrailingLineFromInterruptedWriteIsDiscardedPreservingPriorCompleteLines() {
		val directory = temporaryFolder.newFolder()
		val journal = WidgetDiagnosticJournal(directory)
		journal.setEnabled(true)
		journal.append("complete_record_1")
		journal.append("complete_record_2")

		val journalFile = File(directory, "journal.log")
		java.io.FileOutputStream(journalFile, true).use { fos ->
			fos.write("interrupted_partial".toByteArray(Charsets.UTF_8))
		}

		val report = journal.report()

		assertTrue(report.contains("complete_record_1"))
		assertTrue(report.contains("complete_record_2"))
		assertFalse(report.contains("interrupted_partial"))
		assertTrue(report.endsWith("complete_record_2\n"))
	}

	@Test
	fun reopenedJournalDoesNotMergeAnInterruptedTailIntoTheNextEvent() {
		val directory = temporaryFolder.newFolder()
		val journal = WidgetDiagnosticJournal(directory)
		journal.setEnabled(true)
		journal.append("before_restart")
		File(directory, "journal.log").appendText("interrupted_tail")

		val reopened = WidgetDiagnosticJournal(directory)
		reopened.append("after_restart")

		assertEquals("before_restart\nafter_restart\n", reopened.report())
		assertTrue(reopened.droppedCount() > 0L)
	}

	@Test
	fun appendFailureForcesTailRecoveryBeforeTheNextEvent() {
		val directory = temporaryFolder.newFolder()
		val journal = WidgetDiagnosticJournal(directory)
		journal.setEnabled(true)
		journal.append("before_failure")
		val active = File(directory, "journal.log")
		val saved = File(directory, "saved.log")
		assertTrue(active.renameTo(saved))
		assertTrue(active.mkdir())

		try {
			journal.append("failed_write")
			org.junit.Assert.fail("Appending to a directory must fail")
		} catch (_: IOException) {
			// Restore a partially written file to model the interrupted bytes left by an unsuccessful append.
		}

		assertTrue(active.delete())
		assertTrue(saved.renameTo(active))
		active.appendText("interrupted_tail")
		journal.append("after_failure")

		assertEquals("before_failure\nafter_failure\n", journal.report())
		assertTrue(journal.droppedCount() > 0L)
	}

	@Test
	fun corruptedLargeFileExceedingBudgetIsBoundedAndRotatedSafely() {
		val directory = temporaryFolder.newFolder()
		val journalFile = File(directory, "journal.log")
		journalFile.writeText("corrupted_line\n".repeat(50))

		val journal = WidgetDiagnosticJournal(directory, maxBytes = 80)
		journal.setEnabled(true)
		journal.append("fresh_event")

		val report = journal.report()

		assertTrue(report.toByteArray(Charsets.UTF_8).size <= 80)
		assertTrue(report.contains("fresh_event"))
	}

	@Test
	fun clearOnNonExistentJournalLeavesEnabledIntact() {
		val directory = temporaryFolder.newFolder()
		val journal = WidgetDiagnosticJournal(directory)
		journal.setEnabled(true)
		journal.clear()

		assertTrue(journal.isEnabled())
		assertEquals("", journal.report())
	}

	@Test
	fun reopeningJournalPreservesEvictedAndDroppedHealthCounts() {
		val directory = temporaryFolder.newFolder()
		val journal = WidgetDiagnosticJournal(directory, maxBytes = 80)
		journal.setEnabled(true)
		journal.append("important_before")
		journal.append("x".repeat(200))

		val reopened = WidgetDiagnosticJournal(directory, maxBytes = 80)

		assertTrue(reopened.droppedCount() >= 1L)
	}

	@Test(expected = IOException::class)
	fun corruptEnabledMarkerThrowsIOException() {
		val directory = temporaryFolder.newFolder()
		val enabledFile = File(directory, "enabled")
		enabledFile.writeText("garbage_data")

		val journal = WidgetDiagnosticJournal(directory)
		journal.isEnabled()
	}

	@Test(expected = IOException::class)
	fun corruptHealthFileThrowsIOException() {
		val directory = temporaryFolder.newFolder()
		val healthFile = File(directory, "health")
		healthFile.writeText("corrupted_health_data")

		val journal = WidgetDiagnosticJournal(directory)
		journal.droppedCount()
	}
}
