package xyz.attacktive.weatherd.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class DiagnosticJournal(
	val directory: File,
	val maxBytes: Int = DEFAULT_MAX_BYTES
) {
	companion object {
		const val DEFAULT_MAX_BYTES = 65536
		private const val ENABLED_FILE_NAME = "enabled"
		private const val ACTIVE_JOURNAL_FILE_NAME = "journal.log"
		private const val PREVIOUS_JOURNAL_FILE_NAME = "journal.old.log"
		private const val HEALTH_FILE_NAME = "health"
		private const val MAX_HEALTH_FILE_BYTES = 512
		private const val MAX_ENABLED_FILE_BYTES = 64
	}

	private val lock = Any()
	private val enabledFile = File(directory, ENABLED_FILE_NAME)
	private val activeFile = File(directory, ACTIVE_JOURNAL_FILE_NAME)
	private val previousFile = File(directory, PREVIOUS_JOURNAL_FILE_NAME)
	private val healthFile = File(directory, HEALTH_FILE_NAME)
	private val segmentBudget = maxOf(1, maxBytes / 2)
	private var tailRecovered = false

	fun isEnabled() = synchronized(lock) {
		if (!enabledFile.exists()) {
			return@synchronized false
		}

		val bytes = readBoundedBytes(enabledFile, MAX_ENABLED_FILE_BYTES)
		val text = String(bytes, Charsets.UTF_8).trim()
		when (text) {
			"true" -> true
			"false" -> false
			else -> throw IOException("Corrupted enabled marker in $enabledFile: $text")
		}
	}

	fun setEnabled(enabled: Boolean) {
		synchronized(lock) {
			ensureDirectory()
			writeAtomic(enabledFile, enabled.toString() + "\n")
		}
	}

	fun append(line: String) = synchronized(lock) {
		if (!isEnabled()) {
			return@synchronized 0L
		}

		ensureDirectory()
		var dropped = 0L
		if (!tailRecovered) {
			dropped = recoverInterruptedTail()
			tailRecovered = true
		}

		val normalized = if (line.endsWith("\n")) {
			line
		} else {
			"$line\n"
		}

		val lineBytes = normalized.toByteArray(Charsets.UTF_8)
		if (lineBytes.size > segmentBudget) {
			recordDroppedEvent()
			val markerBytes = "[oversized event: ${lineBytes.size} bytes omitted]\n".toByteArray(Charsets.UTF_8)
			if (activeFile.length() + markerBytes.size <= segmentBudget) {
				appendBytes(markerBytes)
			}

			return@synchronized dropped + 1L
		}

		if (activeFile.length() + lineBytes.size > segmentBudget) {
			val evictedInPrevious = countLinesBounded(previousFile)
			if (evictedInPrevious > 0) {
				recordEvictedEvents(evictedInPrevious)
			}

			deleteIfPresent(previousFile, "previous journal file")

			if (activeFile.exists()) {
				atomicMove(activeFile, previousFile)
			}
		}

		appendBytes(lineBytes)
		dropped
	}

	private fun appendBytes(bytes: ByteArray) {
		try {
			FileOutputStream(activeFile, true).use { file ->
				file.write(bytes)
				file.fd.sync()
			}
		} catch (exception: IOException) {
			tailRecovered = false
			throw exception
		}
	}

	private fun deleteIfPresent(file: File, description: String) {
		if (file.exists() && !file.delete()) {
			throw IOException("Failed to delete $description: $file")
		}
	}

	fun clear() {
		synchronized(lock) {
			if (!directory.exists()) {
				return
			}

			if (!directory.isDirectory) {
				throw IOException("Directory unavailable: $directory")
			}

			deleteIfPresent(activeFile, "active journal file")
			deleteIfPresent(previousFile, "previous journal file")
			deleteIfPresent(healthFile, "health file")
		}
	}

	fun report() = synchronized(lock) {
		val previousLines = readLinesBounded(previousFile, segmentBudget)
		val activeLines = readLinesBounded(activeFile, segmentBudget)
		val allLines = (previousLines + activeLines).toMutableList()
		if (allLines.isEmpty()) {
			return@synchronized ""
		}

		var totalBytes = allLines.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 }
		while (totalBytes > maxBytes && allLines.isNotEmpty()) {
			val removed = allLines.removeAt(0)
			totalBytes -= (removed.toByteArray(Charsets.UTF_8).size + 1)
		}

		val builder = StringBuilder()
		for (line in allLines) {
			builder.append(line).append('\n')
		}

		builder.toString()
	}

	fun droppedCount() = synchronized(lock) {
		readHealthProperties()["dropped"] ?: 0L
	}

	fun evictedCount() = synchronized(lock) {
		readHealthProperties()["evicted"] ?: 0L
	}

	fun recordDroppedEvent(count: Long = 1L) {
		synchronized(lock) {
			val current = droppedCount()
			updateHealthProperty("dropped", current + count)
		}
	}

	fun recordEvictedEvents(count: Long) {
		synchronized(lock) {
			val current = evictedCount()
			updateHealthProperty("evicted", current + count)
		}
	}

	fun hasStorageFailed() = synchronized(lock) {
		(readHealthProperties()["storageFailed"] ?: 0L) == 1L
	}

	fun recordStorageFailed(failed: Boolean) {
		synchronized(lock) {
			updateHealthProperty("storageFailed", if (failed) 1L else 0L)
		}
	}

	private fun readHealthProperties(): Map<String, Long> {
		if (!healthFile.exists()) {
			return emptyMap()
		}

		val bytes = readBoundedBytes(healthFile, MAX_HEALTH_FILE_BYTES)
		val text = String(bytes, Charsets.UTF_8).trim()
		if (text.isEmpty()) {
			return emptyMap()
		}

		val map = mutableMapOf<String, Long>()
		for (line in text.split('\n')) {
			val trimmed = line.trim()
			if (trimmed.isEmpty()) {
				continue
			}

			val parts = trimmed.split('=')
			if (parts.size != 2) {
				throw IOException("Corrupted health entry in $healthFile: $line")
			}

			val value = parts[1].toLongOrNull() ?: throw IOException("Invalid health number in $healthFile: $line")
			map[parts[0]] = value
		}

		return map
	}

	private fun updateHealthProperty(key: String, value: Long) {
		ensureDirectory()
		val map = readHealthProperties().toMutableMap()
		map[key] = value
		val content = map.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
		writeAtomic(healthFile, content)
	}

	private fun countLinesBounded(file: File): Long {
		if (!file.exists()) {
			return 0L
		}

		val bytes = readBoundedBytes(file, segmentBudget)
		var count = 0L
		for (b in bytes) {
			if (b == '\n'.code.toByte()) {
				count++
			}
		}

		return count
	}

	private fun recoverInterruptedTail(): Long {
		if (!activeFile.exists() || activeFile.length() == 0L) {
			return 0L
		}

		RandomAccessFile(activeFile, "rw").use { file ->
			val length = file.length()
			file.seek(length - 1)
			if (file.read() == '\n'.code) {
				return 0L
			}

			val size = minOf(length, segmentBudget.toLong()).toInt()
			val offset = length - size
			val tail = ByteArray(size)
			file.seek(offset)
			file.readFully(tail)
			val lastNewline = tail.lastIndexOf('\n'.code.toByte())
			file.setLength(if (lastNewline < 0) 0L else offset + lastNewline + 1L)
			file.fd.sync()
		}

		recordDroppedEvent()
		return 1L
	}

	private fun ensureDirectory() {
		if (directory.exists()) {
			if (!directory.isDirectory) {
				throw IOException("Directory unavailable: $directory")
			}

			return
		}

		if (!directory.mkdirs()) {
			throw IOException("Directory unavailable: $directory")
		}
	}

	private fun readBoundedBytes(file: File, maxBytesToRead: Int): ByteArray {
		val length = file.length()
		if (length <= 0L) {
			return ByteArray(0)
		}

		val bytesToRead = minOf(length, maxBytesToRead.toLong()).toInt()
		val offset = length - bytesToRead
		val buffer = ByteArray(bytesToRead)

		RandomAccessFile(file, "r").use { raf ->
			raf.seek(offset)
			raf.readFully(buffer)
		}

		return buffer
	}

	private fun calculateLineStartIndex(bytes: ByteArray, isTruncatedLeading: Boolean): Int {
		if (!isTruncatedLeading) {
			return 0
		}

		val newlineIndex = bytes.indexOf('\n'.code.toByte())
		return if (newlineIndex == -1) {
			bytes.size
		} else {
			newlineIndex + 1
		}
	}

	private fun calculateLineEndIndex(bytes: ByteArray): Int {
		if (bytes.last() == '\n'.code.toByte()) {
			return bytes.size
		}

		val newlineIndex = bytes.lastIndexOf('\n'.code.toByte())
		return if (newlineIndex == -1) {
			0
		} else {
			newlineIndex + 1
		}
	}

	private fun readLinesBounded(file: File, maxBytesToRead: Int): List<String> {
		if (!file.exists()) {
			return emptyList()
		}

		val length = file.length()
		if (length <= 0L) {
			return emptyList()
		}

		val bytes = readBoundedBytes(file, maxBytesToRead)
		if (bytes.isEmpty()) {
			return emptyList()
		}

		val startIndex = calculateLineStartIndex(bytes, length > maxBytesToRead)
		val endIndex = calculateLineEndIndex(bytes)
		if (startIndex >= endIndex) {
			return emptyList()
		}

		val text = String(bytes, startIndex, endIndex - startIndex, Charsets.UTF_8)
		return text.split('\n').filter { it.isNotEmpty() }
	}

	private fun writeAtomic(target: File, text: String) {
		val tempFile = File(directory, "${target.name}.tmp")
		try {
			FileOutputStream(tempFile).use { fos ->
				fos.write(text.toByteArray(Charsets.UTF_8))
				fos.fd.sync()
			}
			atomicMove(tempFile, target)
		} catch (e: Exception) {
			tempFile.delete()
			if (e is IOException) {
				throw e
			}

			throw IOException("Failed to write to ${target.name}", e)
		}
	}

	private fun atomicMove(source: File, target: File) {
		val sourcePath = source.toPath()
		val targetPath = target.toPath()
		try {
			Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (_: IOException) {
			Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
		}
	}
}
