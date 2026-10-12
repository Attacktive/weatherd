package xyz.attacktive.weatherd.domain.render

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

internal class DistantAircraftFrame {
	var passStartSeconds = Float.NaN
		private set
	var planeX = 0f
		private set
	var planeY = 0f
		private set
	var directionX = 0f
		private set
	var directionY = 0f
		private set
	var bodyLength = 0f
		private set
	var planeOpacity = 0f
		private set
	var trailStartX = 0f
		private set
	var trailStartY = 0f
		private set
	var trailEndX = 0f
		private set
	var trailEndY = 0f
		private set
	var trailStartAgeSeconds = 0f
		private set
	var trailEndAgeSeconds = 0f
		private set
	var hasTrail = false
		private set

	private var cachedSlot = Int.MIN_VALUE
	private var startsAtFraction = 0f
	private var startYFraction = 0f
	private var endYFraction = 0f
	private var fliesRight = true

	fun sample(width: Float, height: Float, timeSeconds: Float): Boolean {
		if (!validSampleInput(width, height, timeSeconds)) {
			passStartSeconds = Float.NaN
			clearDrawable()

			return false
		}

		val slot = floor(timeSeconds / SLOT_SECONDS).toInt()
		if (slot != cachedSlot) {
			cacheSlot(slot)
		}

		passStartSeconds = slot * SLOT_SECONDS + START_MIN_SECONDS + startsAtFraction * (START_MAX_SECONDS - START_MIN_SECONDS)
		val elapsed = timeSeconds - passStartSeconds
		if (elapsed < 0f || elapsed >= FLIGHT_SECONDS + TRAIL_LIFETIME_SECONDS) {
			clearDrawable()

			return false
		}

		configureDrawable(width, height, elapsed)

		return planeOpacity > 0f || hasTrail
	}

	private fun validSampleInput(width: Float, height: Float, timeSeconds: Float) =
		width.isFinite() && height.isFinite() && timeSeconds.isFinite() && width > 0f && height > 0f && timeSeconds >= 0f && timeSeconds < SCENE_CLOCK_SECONDS

	private fun configureDrawable(width: Float, height: Float, elapsed: Float) {
		val startX = if (fliesRight) {
			-width * HORIZONTAL_MARGIN_FRACTION
		} else {
			width * (1f + HORIZONTAL_MARGIN_FRACTION)
		}
		val endX = if (fliesRight) {
			width * (1f + HORIZONTAL_MARGIN_FRACTION)
		} else {
			-width * HORIZONTAL_MARGIN_FRACTION
		}
		val startY = height * startYFraction
		val endY = height * endYFraction

		configurePlane(width, height, elapsed, startX, startY, endX, endY)
		configureTrail(elapsed, startX, startY, endX, endY)
	}

	private fun configurePlane(width: Float, height: Float, elapsed: Float, startX: Float, startY: Float, endX: Float, endY: Float) {
		val deltaX = endX - startX
		val deltaY = endY - startY
		val directionLength = hypot(deltaX, deltaY)
		directionX = deltaX / directionLength
		directionY = deltaY / directionLength
		bodyLength = BODY_LENGTH_FRACTION * min(width, height)

		val planeProgress = (elapsed / FLIGHT_SECONDS).coerceIn(0f, 1f)
		planeX = lerp(startX, endX, planeProgress)
		planeY = lerp(startY, endY, planeProgress)
		planeOpacity = planeOpacity(elapsed)
	}

	private fun configureTrail(elapsed: Float, startX: Float, startY: Float, endX: Float, endY: Float) {
		val oldestEmissionSeconds = maxOf(0f, elapsed - TRAIL_LIFETIME_SECONDS)
		val newestEmissionSeconds = minOf(elapsed, FLIGHT_SECONDS)
		hasTrail = newestEmissionSeconds > oldestEmissionSeconds
		if (!hasTrail) {
			clearTrail()

			return
		}

		val oldestProgress = oldestEmissionSeconds / FLIGHT_SECONDS
		val newestProgress = newestEmissionSeconds / FLIGHT_SECONDS
		trailStartX = lerp(startX, endX, oldestProgress)
		trailStartY = lerp(startY, endY, oldestProgress)
		trailEndX = lerp(startX, endX, newestProgress)
		trailEndY = lerp(startY, endY, newestProgress)
		trailStartAgeSeconds = elapsed - oldestEmissionSeconds
		trailEndAgeSeconds = elapsed - newestEmissionSeconds
	}

	private fun cacheSlot(slot: Int) {
		cachedSlot = slot
		startsAtFraction = aircraftFraction(slot, START_SALT)
		fliesRight = aircraftFraction(slot, DIRECTION_SALT) >= 0.5f
		startYFraction = lerp(START_Y_MIN_FRACTION, START_Y_MAX_FRACTION, aircraftFraction(slot, START_Y_SALT))
		endYFraction = startYFraction + lerp(END_Y_DELTA_MIN_FRACTION, END_Y_DELTA_MAX_FRACTION, aircraftFraction(slot, END_Y_SALT))
	}

	private fun clearDrawable() {
		planeX = 0f
		planeY = 0f
		directionX = 0f
		directionY = 0f
		bodyLength = 0f
		planeOpacity = 0f
		clearTrail()
	}

	private fun clearTrail() {
		hasTrail = false
		trailStartX = 0f
		trailStartY = 0f
		trailEndX = 0f
		trailEndY = 0f
		trailStartAgeSeconds = 0f
		trailEndAgeSeconds = 0f
	}

	private fun planeOpacity(elapsedSeconds: Float): Float {
		if (elapsedSeconds < 0f || elapsedSeconds >= FLIGHT_SECONDS) {
			return 0f
		}

		val fadeIn = smoothstep((elapsedSeconds / PLANE_FADE_SECONDS).coerceIn(0f, 1f))
		val fadeOut = smoothstep(((FLIGHT_SECONDS - elapsedSeconds) / PLANE_FADE_SECONDS).coerceIn(0f, 1f))

		return min(fadeIn, fadeOut)
	}
}

private fun aircraftFraction(slot: Int, salt: Int): Float {
	var mixed = (slot + AIRCRAFT_HASH_SEED) * 374_761_393 + salt * 668_265_263
	mixed = (mixed xor (mixed ushr 13)) * 1_274_126_177
	mixed = mixed xor (mixed ushr 16)

	return (mixed and 0x7fff_ffff) / 2_147_483_647f
}

private fun smoothstep(value: Float) = value * value * (3f - 2f * value)

private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

private const val SCENE_CLOCK_SECONDS = 21_600f
private const val SLOT_SECONDS = 180f
private const val START_MIN_SECONDS = 15f
private const val START_MAX_SECONDS = 80f
private const val FLIGHT_SECONDS = 60f
private const val TRAIL_LIFETIME_SECONDS = 20f
private const val PLANE_FADE_SECONDS = 4f
private const val HORIZONTAL_MARGIN_FRACTION = 0.12f
private const val START_Y_MIN_FRACTION = 0.18f
private const val START_Y_MAX_FRACTION = 0.32f
private const val END_Y_DELTA_MIN_FRACTION = -0.04f
private const val END_Y_DELTA_MAX_FRACTION = 0.04f
private const val BODY_LENGTH_FRACTION = 0.016f
private const val AIRCRAFT_HASH_SEED = 141
private const val START_SALT = 23
private const val DIRECTION_SALT = 37
private const val START_Y_SALT = 53
private const val END_Y_SALT = 71
