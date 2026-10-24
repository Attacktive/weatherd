package xyz.attacktive.weatherd.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.platform.currentHomeLauncher

data class DiagnosticState(
	val ready: Boolean = false,
	val enabled: Boolean = false,
	val storageFailed: Boolean = false,
	val droppedEvents: Long = 0L
)

class Diagnostics internal constructor(
	private val context: Context,
	journalFactory: () -> DiagnosticJournal,
	private val coroutineScope: CoroutineScope,
	private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
	companion object {
		private const val QUEUE_CAPACITY = 256
		private val WHITESPACE_REGEX = Regex("\\s+")

		@Volatile
		private var instance: Diagnostics? = null

		fun get(context: Context) = instance ?: synchronized(this) {
			instance ?: run {
				val appContext = context.applicationContext
				Diagnostics(
					context = appContext,
					journalFactory = {
						val directory = File(checkNotNull(appContext.noBackupFilesDir), "diagnostics")
						DiagnosticJournal(directory)
					},
					coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
					dispatcher = Dispatchers.IO
				).also { instance = it }
			}
		}
	}

	private sealed interface Command {
		data class Record(val line: String) : Command
		data class SetEnabled(val enabled: Boolean, val deferred: CompletableDeferred<Result<Unit>>) : Command
		data class Clear(val deferred: CompletableDeferred<Result<Unit>>) : Command
		data class Report(val deferred: CompletableDeferred<Result<String>>) : Command
	}

	private val journal by lazy(journalFactory)
	private val sessionId = UUID.randomUUID().toString().take(8)
	private val sequenceNumber = AtomicLong(0L)
	private val droppedEventsCount = AtomicLong(0L)
	private val pendingDroppedEvents = AtomicLong(0L)
	private val channel = Channel<Command>(capacity = QUEUE_CAPACITY)

	private val _state = MutableStateFlow(DiagnosticState())
	val state: StateFlow<DiagnosticState> = _state.asStateFlow()

	init {
		coroutineScope.launch(dispatcher) {
			try {
				val initialEnabled = journal.isEnabled()
				_state.update { it.copy(enabled = initialEnabled) }
				val initialDropped = journal.droppedCount()
				val initialStorageFailed = journal.hasStorageFailed()
				droppedEventsCount.addAndGet(initialDropped)
				_state.update {
					it.copy(
						ready = true,
						storageFailed = it.storageFailed || initialStorageFailed,
						droppedEvents = droppedEventsCount.get()
					)
				}
			} catch (e: Exception) {
				if (e is CancellationException) {
					throw e
				}

				_state.update { it.copy(ready = true, storageFailed = true) }
			}

			for (command in channel) {
				persistDroppedEvents()
				when (command) {
					is Command.Record -> handleRecord(command.line)
					is Command.SetEnabled -> handleSetEnabled(command.enabled, command.deferred)
					is Command.Clear -> handleClear(command.deferred)
					is Command.Report -> handleReport(command.deferred)
				}
			}
		}
	}

	suspend fun setEnabled(enabled: Boolean): Result<Unit> {
		val deferred = CompletableDeferred<Result<Unit>>()
		channel.send(Command.SetEnabled(enabled, deferred))
		return deferred.await()
	}

	suspend fun clear(): Result<Unit> {
		val deferred = CompletableDeferred<Result<Unit>>()
		channel.send(Command.Clear(deferred))
		return deferred.await()
	}

	suspend fun report(): Result<String> {
		val deferred = CompletableDeferred<Result<String>>()
		channel.send(Command.Report(deferred))
		return deferred.await()
	}

	fun record(event: String, vararg fields: Pair<String, String>) {
		try {
			val capture = _state.value
			if (capture.ready && !capture.enabled) {
				return
			}

			val wallTime = System.currentTimeMillis()
			val elapsedRealtime = SystemClock.elapsedRealtime()
			val seq = sequenceNumber.incrementAndGet()
			val line = formatEvent(wallTime, elapsedRealtime, seq, event, fields)
			val sendResult = channel.trySend(Command.Record(line))
			if (!sendResult.isSuccess) {
				val dropped = droppedEventsCount.incrementAndGet()
				pendingDroppedEvents.incrementAndGet()
				_state.update { it.copy(droppedEvents = dropped) }
			}
		} catch (_: Exception) {
			_state.update { it.copy(storageFailed = true) }
		}
	}

	private fun persistDroppedEvents() {
		val count = pendingDroppedEvents.getAndSet(0L)
		if (count == 0L) {
			return
		}

		try {
			journal.recordDroppedEvent(count)
		} catch (_: Exception) {
			pendingDroppedEvents.addAndGet(count)
			_state.update { it.copy(storageFailed = true) }
		}
	}

	private fun handleRecord(line: String) {
		try {
			val journalDrops = journal.append(line)
			if (journalDrops > 0L) {
				droppedEventsCount.addAndGet(journalDrops)
				_state.update { it.copy(droppedEvents = droppedEventsCount.get()) }
			}
		} catch (e: Exception) {
			if (e is CancellationException) {
				throw e
			}

			try {
				val knownLosses = journal.droppedCount() + pendingDroppedEvents.get()
				droppedEventsCount.updateAndGet { maxOf(it, knownLosses) }
			} catch (_: Exception) {
				// Unreadable health leaves the last known count; the storage-failure warning marks it incomplete.
			}

			_state.update { it.copy(storageFailed = true, droppedEvents = droppedEventsCount.get()) }
			try {
				journal.recordStorageFailed(true)
			} catch (storageEx: Exception) {
				if (storageEx is CancellationException) {
					throw storageEx
				}
			}
		}
	}

	private fun handleSetEnabled(enabled: Boolean, deferred: CompletableDeferred<Result<Unit>>) {
		try {
			journal.setEnabled(enabled)
			_state.update { it.copy(enabled = enabled) }
			deferred.complete(Result.success(Unit))
		} catch (e: Exception) {
			if (e is CancellationException) {
				throw e
			}

			_state.update { it.copy(storageFailed = true) }
			try {
				journal.recordStorageFailed(true)
			} catch (storageEx: Exception) {
				if (storageEx is CancellationException) {
					throw storageEx
				}
			}

			deferred.complete(Result.failure(e))
		}
	}

	private fun handleClear(deferred: CompletableDeferred<Result<Unit>>) {
		try {
			journal.clear()
			droppedEventsCount.set(0L)
			_state.update { it.copy(storageFailed = false, droppedEvents = 0L) }
			deferred.complete(Result.success(Unit))
		} catch (e: Exception) {
			if (e is CancellationException) {
				throw e
			}

			_state.update { it.copy(storageFailed = true) }
			try {
				journal.recordStorageFailed(true)
			} catch (storageEx: Exception) {
				if (storageEx is CancellationException) {
					throw storageEx
				}
			}

			deferred.complete(Result.failure(e))
		}
	}

	private fun handleReport(deferred: CompletableDeferred<Result<String>>) {
		try {
			val journalReport = journal.report()
			val fullReport = buildReport(journalReport)
			deferred.complete(Result.success(fullReport))
		} catch (e: Exception) {
			if (e is CancellationException) {
				throw e
			}

			_state.update { it.copy(storageFailed = true) }
			try {
				journal.recordStorageFailed(true)
			} catch (storageEx: Exception) {
				if (storageEx is CancellationException) {
					throw storageEx
				}
			}

			deferred.complete(Result.failure(e))
		}
	}

	private fun formatFields(fields: Array<out Pair<String, String>>): String {
		if (fields.isEmpty()) {
			return ""
		}

		return fields.joinToString(separator = " ", prefix = " ") { (key, value) ->
			val sanitizedKey = key.replace(WHITESPACE_REGEX, "_")
			val sanitizedValue = value.replace('\n', ' ').replace('\r', ' ')
			"$sanitizedKey=$sanitizedValue"
		}
	}

	private fun formatEvent(
		wallTime: Long,
		elapsedRealtime: Long,
		seq: Long,
		event: String,
		fields: Array<out Pair<String, String>>
	): String {
		val wallIso = runCatching {
			Instant.ofEpochMilli(wallTime).toString()
		}.getOrElse { wallTime.toString() }

		val sanitizedEvent = event.replace(WHITESPACE_REGEX, "_")
		val buildCode = BuildConfig.VERSION_CODE
		val formattedFields = formatFields(fields)

		return "time=$wallIso elapsed_ms=$elapsedRealtime session=$sessionId seq=$seq build=$buildCode event=$sanitizedEvent$formattedFields"
	}

	private fun describeLauncherMetadata(): String {
		val launcher = currentHomeLauncher(context)
		if (launcher == null) {
			return "unavailable"
		}

		val launcherVersion = runCatching {
			context.packageManager.getPackageInfo(launcher.packageName, 0).versionName
		}.getOrNull() ?: "unknown"

		return "${launcher.label} (${launcher.packageName}, v$launcherVersion)"
	}

	private fun readEvictedEventsText(): String {
		val evictedCount = runCatching { journal.evictedCount() }.getOrNull()
		return if (evictedCount != null) {
			evictedCount.toString()
		} else {
			"unknown (read failed)"
		}
	}

	private fun describeRetainedWindow(lines: List<String>): String {
		if (lines.isEmpty()) {
			return "none"
		}

		val firstLine = lines.first()
		val lastLine = lines.last()
		if (firstLine.contains("time=") && lastLine.contains("time=")) {
			val firstTime = firstLine.substringAfter("time=").substringBefore(" ")
			val firstSeq = firstLine.substringAfter("seq=").substringBefore(" ")
			val lastTime = lastLine.substringAfter("time=").substringBefore(" ")
			val lastSeq = lastLine.substringAfter("seq=").substringBefore(" ")
			return "from $firstTime (seq $firstSeq) to $lastTime (seq $lastSeq) (${lines.size} records retained)"
		}

		return "${lines.size} records retained"
	}

	private fun buildReport(journalReport: String): String {
		val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
		val device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
		val launcherInfo = describeLauncherMetadata()
		val currentState = _state.value
		val evictedText = readEvictedEventsText()
		val lines = journalReport.lines().filter { it.isNotBlank() }
		val retainedWindow = describeRetainedWindow(lines)

		return buildString {
			appendLine("=== Weatherd Diagnostics ===")
			appendLine("App version: $appVersion")
			appendLine("Device: $device")
			appendLine("Default launcher: $launcherInfo")
			appendLine("Session ID: $sessionId")
			appendLine("Health: ready=${currentState.ready}, enabled=${currentState.enabled}, storageFailed=${currentState.storageFailed}, droppedEvents=${currentState.droppedEvents}, evictedEvents=$evictedText")
			appendLine("Retained window: $retainedWindow")
			if (currentState.storageFailed) {
				appendLine("Warning: Storage failures occurred; persisted loss counters may be incomplete or unknown due to storage unavailability.")
			}

			appendLine("Notice: Diagnostic capture is best-effort and bounded (retained max 64 KiB). Capture is opt-in; retained records can span process sessions and app updates. Abrupt process termination can lose queued events before disk persistence, so even zero reported drops is not a completeness guarantee. System broadcast restrictions, power management, or process death by the OS may prevent event execution; absence of an event is not proof that the system did not dispatch a task.")
			appendLine("=== Events ===")
			if (lines.isEmpty()) {
				appendLine("(no events recorded)")
			} else {
				append(journalReport)
				if (!journalReport.endsWith("\n")) {
					appendLine()
				}
			}

			appendLine("=== End Diagnostics ===")
		}
	}
}
