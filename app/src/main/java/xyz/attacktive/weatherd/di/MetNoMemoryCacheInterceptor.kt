package xyz.attacktive.weatherd.di

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Short-lived RFC 7234-style memory cache for explicit MET comparisons.
 * It honors response freshness and conditional revalidation without writing coordinate-bearing request URLs or bodies to disk.
 */
internal class MetNoMemoryCacheInterceptor : Interceptor {
	private data class Entry(
		val bytes: ByteArray,
		val contentType: MediaType?,
		val headers: Headers,
		val protocol: Protocol,
		val status: Int,
		val message: String,
		val expiresAtMillis: Long,
		val lastModified: String?
	) {
		fun responseFor(request: Request) = Response.Builder()
			.request(request)
			.protocol(protocol)
			.code(status)
			.message(message)
			.headers(headers)
			.body(bytes.toResponseBody(contentType))
			.build()
	}

	private val lock = Any()
	private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.method != "GET") {
			return chain.proceed(request)
		}

		val key = request.url.toString()
		val now = System.currentTimeMillis()
		val previous = synchronized(lock) { entries[key] }
		if (previous != null && previous.expiresAtMillis > now) {
			return previous.responseFor(request)
		}

		val network = chain.proceed(revalidationRequest(request, previous))
		if (network.code == 304 && previous != null) {
			return revalidatedResponse(request, key, previous, network, now)
		}

		synchronized(lock) { entries.remove(key) }
		return cacheResponse(key, network, now)
	}

	private fun revalidationRequest(request: Request, previous: Entry?): Request {
		val lastModified = previous?.lastModified ?: return request

		return request.newBuilder().header("If-Modified-Since", lastModified).build()
	}

	private fun revalidatedResponse(request: Request, key: String, previous: Entry, network: Response, now: Long): Response {
		val updated = previous.copy(
			expiresAtMillis = expiryFor(network, now) ?: (now + REVALIDATION_RETRY_MILLIS),
			lastModified = network.header("Last-Modified") ?: previous.lastModified
		)
		network.body?.close()
		store(key, updated)
		return updated.responseFor(request)
	}

	private fun cacheResponse(key: String, network: Response, now: Long): Response {
		if (!network.isSuccessful || network.cacheControl.noStore) {
			return network
		}

		val expiry = expiryFor(network, now) ?: return network
		val body = network.body ?: return network
		val contentType = body.contentType()
		val bytes = body.bytes()
		val replayable = network.newBuilder().body(bytes.toResponseBody(contentType)).build()
		if (bytes.size <= MAX_ENTRY_BYTES) {
			val cached = Entry(
				bytes = bytes,
				contentType = contentType,
				headers = network.headers,
				protocol = network.protocol,
				status = network.code,
				message = network.message,
				expiresAtMillis = expiry,
				lastModified = network.header("Last-Modified")
			)
			store(key, cached)
		}

		return replayable
	}

	private fun store(key: String, value: Entry) {
		synchronized(lock) {
			entries[key] = value
			if (entries.size > MAX_ENTRIES) {
				entries.remove(entries.keys.first())
			}
		}
	}

	private fun expiryFor(response: Response, now: Long): Long? {
		val directives = response.cacheControl
		if (directives.noCache) {
			return now
		}

		if (directives.maxAgeSeconds >= 0) {
			val age = response.header("Age")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
			val remaining = (directives.maxAgeSeconds.toLong() - age).coerceAtLeast(0L)

			return now + remaining * 1000L
		}

		return response.header("Expires")?.let { header ->
			runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
		}
	}

	private companion object {
		const val MAX_ENTRIES = 4
		const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
		const val REVALIDATION_RETRY_MILLIS = 60_000L
	}
}
