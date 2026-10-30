package xyz.attacktive.weatherd.domain.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import xyz.attacktive.weatherd.domain.model.DEFAULT_LABEL_VERTICAL_POSITION
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration

/**
 * Pure deterministic frame sampler for seasonal night celebrations.
 * Computes positions, progress, and visual geometry from an absolute epoch millisecond timestamp without allocating memory or storing mutable replay state.
 */
internal class SeasonalCelebrationFrame {
	var flybyActive: Boolean = false
		private set
	var flybyProgress: Float = 0f
		private set
	var flybyX: Float = 0f
		private set
	var flybyY: Float = 0f
		private set
	var flybyScale: Float = 1f
		private set
	var flybyTiltDegrees: Float = 0f
		private set
	var flybyFliesRight: Boolean = true
		private set
	var flybyOpacity: Float = 1f
		private set
	var gallopPhase: Float = 0f
		private set

	class FireworkBurst {
		var active: Boolean = false
		var isAscent: Boolean = false
		var ascentOpacity: Float = 1f
		var rocketX: Float = 0f
		var rocketY: Float = 0f
		var rocketPrevX: Float = 0f
		var rocketPrevY: Float = 0f
		var apexX: Float = 0f
		var apexY: Float = 0f
		var glowRadius: Float = 0f
		var glowAlpha: Float = 0f
		var maximumRadius: Float = 0f
		var gravityScale: Float = 0f
		var launchDistance: Float = 0f
		var sparkCount: Int = SPARK_COUNT
		val sparkX = FloatArray(SPARK_COUNT)
		val sparkY = FloatArray(SPARK_COUNT)
		val sparkPrevX = FloatArray(SPARK_COUNT)
		val sparkPrevY = FloatArray(SPARK_COUNT)
		val sparkAlpha = FloatArray(SPARK_COUNT)
		var paletteIndex: Int = 0

		fun clear() {
			active = false
			isAscent = false
			ascentOpacity = 1f
			glowRadius = 0f
			glowAlpha = 0f
		}
	}

	private val burst1 = FireworkBurst()
	private val burst2 = FireworkBurst()
	private val activeBurstsList = ArrayList<FireworkBurst>(2)
	private var safeSkyTop = 0f
	private var safeSkyBottom = 0f
	private var labelExclusionTop = 0f
	private var labelExclusionBottom = 0f

	val activeBurstCount: Int
		get() = activeBurstsList.size

	fun getActiveBurst(index: Int) = activeBurstsList[index]

	fun activeBursts(): List<FireworkBurst> = activeBurstsList

	fun sample(width: Float, height: Float, params: SceneParams, epochMillis: Long): Boolean {
		clear()

		if (params.dayPhase != DayPhase.NIGHT || params.holidayCelebration == HolidayCelebration.NONE || width <= 0f || height <= 0f) {
			return false
		}

		return when (params.holidayCelebration) {
			HolidayCelebration.NONE -> false
			HolidayCelebration.HALLOWEEN -> sampleFlyby(width, height, params, epochMillis, HALLOWEEN_SLOT_MILLIS, HALLOWEEN_DURATION_MILLIS, HALLOWEEN_SALT, isChristmas = false)
			HolidayCelebration.CHRISTMAS -> sampleFlyby(width, height, params, epochMillis, CHRISTMAS_SLOT_MILLIS, CHRISTMAS_DURATION_MILLIS, CHRISTMAS_SALT, isChristmas = true)
			HolidayCelebration.NEW_YEAR -> sampleFireworks(width, height, params, epochMillis)
		}
	}

	fun clear() {
		flybyActive = false
		flybyProgress = 0f
		flybyX = 0f
		flybyY = 0f
		flybyScale = 1f
		flybyTiltDegrees = 0f
		flybyFliesRight = true
		flybyOpacity = 1f
		gallopPhase = 0f
		burst1.clear()
		burst2.clear()
		activeBurstsList.clear()
	}

	private fun sampleFlyby(
		width: Float,
		height: Float,
		params: SceneParams,
		epochMillis: Long,
		slotMillis: Long,
		durationMillis: Long,
		salt: Long,
		isChristmas: Boolean
	): Boolean {
		val slotIndex = Math.floorDiv(epochMillis, slotMillis)
		val startMillis = passStartMillis(slotIndex, slotMillis, durationMillis, salt)
		val elapsed = epochMillis - startMillis
		if (elapsed !in 0L until durationMillis) {
			return false
		}

		if (!selectSkyBand(width, height, params, avoidMoon = true)) {
			return false
		}

		val hash = slotHash(slotIndex, salt)
		flybyActive = true
		flybyProgress = elapsed.toFloat() / durationMillis.toFloat()
		flybyFliesRight = ((hash ushr 16) and 1L) == 0L

		val shortEdge = minOf(width, height)
		val margin = shortEdge * 0.35f
		val startX = if (flybyFliesRight) {
			-margin
		} else {
			width + margin
		}

		val endX = if (flybyFliesRight) {
			width + margin
		} else {
			-margin
		}

		flybyX = lerp(startX, endX, flybyProgress)

		val bandHeight = safeSkyBottom - safeSkyTop
		val bobbingAmplitude = minOf(height * 0.012f, bandHeight * 0.10f)
		val bobbing = sin(flybyProgress * 8f * PI.toFloat()) * bobbingAmplitude
		flybyY = (safeSkyTop + safeSkyBottom) * 0.5f + bobbing
		flybyTiltDegrees = cos(flybyProgress * 8f * PI.toFloat()) * 4f
		val ordinaryScale = if (isChristmas) {
			shortEdge * 0.0016f
		} else {
			shortEdge * 0.0017f
		}

		// The envelope includes the pointed hat, trailing sparkles, four-degree tilt, and vertical bobbing.
		flybyScale = minOf(ordinaryScale, (bandHeight * 0.5f - bobbingAmplitude) / 55f) / 3f

		val nightDimming = params.nightBrightnessScale.coerceIn(0f, 1f)
		flybyOpacity = lerp(0.35f, 1f, nightDimming)

		gallopPhase = if (isChristmas) {
			(elapsed % 400L).toFloat() / 400f
		} else {
			0f
		}

		return true
	}

	private fun updateLabelExclusion(height: Float, params: SceneParams, skyTop: Float, skyBottom: Float) {
		labelExclusionTop = skyBottom
		labelExclusionBottom = skyBottom
		val labels = params.overlayLabels
		if (labels?.weather != null || labels?.location != null) {
			// Conservatively enclose either/both glyph lines and their shadows, retaining the historical compatibility anchor.
			val textTop = height * 0.066f
			val textBottom = height * 0.126f
			val position = params.labelVerticalPosition.coerceIn(0f, 1f)
			val offset = if (position <= DEFAULT_LABEL_VERTICAL_POSITION) {
				lerp(-textTop, 0f, position / DEFAULT_LABEL_VERTICAL_POSITION)
			} else {
				lerp(0f, height - textBottom, (position - DEFAULT_LABEL_VERTICAL_POSITION) / (1f - DEFAULT_LABEL_VERTICAL_POSITION))
			}

			labelExclusionTop = (textTop + offset - height * 0.03f).coerceIn(skyTop, skyBottom)
			labelExclusionBottom = (textBottom + offset + height * 0.03f).coerceIn(skyTop, skyBottom)
		}
	}

	private fun selectSkyBand(width: Float, height: Float, params: SceneParams, avoidMoon: Boolean): Boolean {
		val skyTop = height * 0.10f
		val skyBottom = height * 0.56f
		updateLabelExclusion(height, params, skyTop, skyBottom)
		val labelTop = labelExclusionTop
		val labelBottom = labelExclusionBottom

		var moonTop = skyBottom
		var moonBottom = skyBottom
		if (avoidMoon && params.moonVisible) {
			val radius = minOf(width, height) * (0.10f * params.moonSizeScale.coerceIn(0.5f, 2f) + 0.015f)
			moonTop = (height * 0.24f - radius).coerceIn(skyTop, skyBottom)
			moonBottom = (height * 0.24f + radius).coerceIn(skyTop, skyBottom)
		}

		val labelsFirst = labelTop <= moonTop
		val firstTop = if (labelsFirst) {
			labelTop
		} else {
			moonTop
		}

		val firstBottom = if (labelsFirst) {
			labelBottom
		} else {
			moonBottom
		}

		val secondTop = if (labelsFirst) {
			moonTop
		} else {
			labelTop
		}

		val secondBottom = if (labelsFirst) {
			moonBottom
		} else {
			labelBottom
		}

		safeSkyTop = skyTop
		safeSkyBottom = skyTop
		considerSkyGap(skyTop, firstTop)
		val afterFirst = maxOf(skyTop, firstBottom)
		considerSkyGap(afterFirst, secondTop)
		considerSkyGap(maxOf(afterFirst, secondBottom), skyBottom)

		return safeSkyBottom - safeSkyTop > height * 0.025f
	}

	private fun considerSkyGap(top: Float, bottom: Float) {
		if (bottom - top > safeSkyBottom - safeSkyTop) {
			safeSkyTop = top
			safeSkyBottom = bottom
		}
	}

	private fun sampleFireworks(width: Float, height: Float, params: SceneParams, epochMillis: Long): Boolean {
		if (!selectSkyBand(width, height, params, avoidMoon = false)) {
			return false
		}

		val currentSlot = Math.floorDiv(epochMillis, FIREWORK_SLOT_MILLIS)
		val nightDimming = params.nightBrightnessScale.coerceIn(0f, 1f)

		configureFireworkSlot(burst1, currentSlot, epochMillis, width, height, params, nightDimming)
		configureFireworkSlot(burst2, currentSlot - 1L, epochMillis, width, height, params, nightDimming)

		if (burst1.active) {
			activeBurstsList.add(burst1)
		}

		if (burst2.active) {
			activeBurstsList.add(burst2)
		}

		return activeBurstsList.isNotEmpty()
	}

	private fun configureFireworkSlot(
		burst: FireworkBurst,
		slotIndex: Long,
		epochMillis: Long,
		width: Float,
		height: Float,
		params: SceneParams,
		nightDimming: Float
	) {
		val startMillis = fireworkStartMillis(slotIndex)
		val elapsed = epochMillis - startMillis
		if (elapsed !in 0L until FIREWORK_BURST_DURATION_MILLIS) {
			burst.clear()
			return
		}

		val hash = slotHash(slotIndex, FIREWORK_SALT)
		val positiveHash = hash and Long.MAX_VALUE
		burst.active = true
		burst.paletteIndex = ((positiveHash ushr 4).toInt() and 0x7FFFFFFF) % 6

		val shortEdge = minOf(width, height)
		val bandHeight = safeSkyBottom - safeSkyTop
		burst.maximumRadius = minOf(shortEdge * 0.08f, bandHeight * 0.22f)
		burst.gravityScale = minOf(shortEdge * 0.035f, bandHeight * 0.10f)
		burst.apexY = safeSkyTop + bandHeight * (0.40f + 0.10f * (((positiveHash ushr 20) % 1000L).toFloat() / 1000f))
		burst.launchDistance = minOf(height * 0.22f, safeSkyBottom - burst.apexY - bandHeight * 0.08f)
		val horizontalEnvelope = maxOf(burst.maximumRadius * 1.25f + 4f, width * 0.04f + 4f)
		val left = width * 0.12f + horizontalEnvelope
		val right = if (params.moonVisible) {
			width * 0.72f - shortEdge * 0.10f * params.moonSizeScale.coerceIn(0.5f, 2f) - horizontalEnvelope
		} else {
			width * 0.88f - horizontalEnvelope
		}

		if (right < left) {
			burst.clear()
			return
		}

		burst.apexX = lerp(left, right, ((positiveHash ushr 12) % 1000L).toFloat() / 1000f)

		if (elapsed < FIREWORK_ASCENT_MILLIS) {
			configureAscent(burst, elapsed, width, positiveHash, nightDimming)
		} else {
			configureBurst(burst, elapsed, positiveHash, nightDimming)
		}
	}

	private fun configureAscent(
		burst: FireworkBurst,
		elapsed: Long,
		width: Float,
		positiveHash: Long,
		nightDimming: Float
	) {
		burst.isAscent = true
		burst.ascentOpacity = nightDimming
		val p = elapsed.toFloat() / FIREWORK_ASCENT_MILLIS.toFloat()
		val launchDrift = ((((positiveHash ushr 28) % 1000L).toFloat() / 1000f) - 0.5f) * width * 0.08f
		val launchX = burst.apexX + launchDrift
		val launchY = burst.apexY + burst.launchDistance

		burst.rocketX = lerp(launchX, burst.apexX, p)
		burst.rocketY = lerp(launchY, burst.apexY, 1f - (1f - p) * (1f - p))

		val prevP = maxOf(0f, p - 0.08f)
		burst.rocketPrevX = lerp(launchX, burst.apexX, prevP)
		burst.rocketPrevY = lerp(launchY, burst.apexY, 1f - (1f - prevP) * (1f - prevP))
		burst.glowRadius = 0f
		burst.glowAlpha = 0f
	}

	private fun configureBurst(
		burst: FireworkBurst,
		elapsed: Long,
		positiveHash: Long,
		nightDimming: Float
	) {
		burst.isAscent = false
		val burstElapsed = elapsed - FIREWORK_ASCENT_MILLIS
		val t = burstElapsed.toFloat() / (FIREWORK_BURST_DURATION_MILLIS - FIREWORK_ASCENT_MILLIS).toFloat()

		if (t < 0.22f) {
			val glowFraction = t / 0.22f
			burst.glowRadius = burst.maximumRadius * 0.625f * glowFraction
			burst.glowAlpha = (1f - glowFraction) * 0.35f * nightDimming
		} else {
			burst.glowRadius = 0f
			burst.glowAlpha = 0f
		}

		burst.sparkCount = SPARK_COUNT
		val maxRadius = burst.maximumRadius

		// Hoist particle-invariant computation out of loop:
		val expansion = 1f - exp(-3.2f * t)
		val baseExpansionRadius = maxRadius * expansion
		val gravity = burst.gravityScale * t * t

		val prevT = maxOf(0f, t - 0.04f)
		val prevExpansion = 1f - exp(-3.2f * prevT)
		val basePrevRadius = maxRadius * prevExpansion
		val prevGravity = burst.gravityScale * prevT * prevT

		val fade = (1f - t) * (1f - t)
		val baseFadeAlpha = fade * nightDimming

		for (i in 0 until SPARK_COUNT) {
			val angleBase = (2f * PI.toFloat() * i.toFloat()) / SPARK_COUNT.toFloat()
			val iHash = (positiveHash xor (i.toLong() * 37L)) and Long.MAX_VALUE
			val angleJitter = (((iHash ushr 8) % 1000L).toFloat() / 1000f - 0.5f) * 0.35f
			val angle = angleBase + angleJitter

			val sHash = (positiveHash xor (i.toLong() * 101L)) and Long.MAX_VALUE
			val speedMult = 0.75f + 0.50f * (((sHash ushr 8) % 1000L).toFloat() / 1000f)

			val radius = baseExpansionRadius * speedMult
			val x = burst.apexX + radius * cos(angle)
			val y = burst.apexY + radius * sin(angle) + gravity

			val prevRadius = basePrevRadius * speedMult
			val prevX = burst.apexX + prevRadius * cos(angle)
			val prevY = burst.apexY + prevRadius * sin(angle) + prevGravity

			val twinkle = 0.8f + 0.2f * sin(t * 24f + i.toFloat() * 1.5f)
			val alpha = (baseFadeAlpha * twinkle).coerceIn(0f, 1f)

			burst.sparkX[i] = x
			burst.sparkY[i] = y
			burst.sparkPrevX[i] = prevX
			burst.sparkPrevY[i] = prevY
			burst.sparkAlpha[i] = alpha
		}
	}

	companion object {
		const val HALLOWEEN_SLOT_MILLIS = 90_000L
		const val HALLOWEEN_DURATION_MILLIS = 16_000L
		const val HALLOWEEN_SALT = 0x57495443484CL

		const val CHRISTMAS_SLOT_MILLIS = 100_000L
		const val CHRISTMAS_DURATION_MILLIS = 18_000L
		const val CHRISTMAS_SALT = 0x53414E5441534CL

		const val FIREWORK_SLOT_MILLIS = 6_000L
		const val FIREWORK_BURST_DURATION_MILLIS = 3_400L
		const val FIREWORK_ASCENT_MILLIS = 800L
		const val FIREWORK_SALT = 0x4E4557594541524CL
		const val SPARK_COUNT = 14

		fun passStartMillis(slotIndex: Long, slotMillis: Long, durationMillis: Long, salt: Long): Long {
			val hash = slotHash(slotIndex, salt) and Long.MAX_VALUE
			val maxOffset = slotMillis - durationMillis
			val startOffset = hash % maxOffset

			return slotIndex * slotMillis + startOffset
		}

		fun fireworkStartMillis(slotIndex: Long): Long {
			val hash = slotHash(slotIndex, FIREWORK_SALT) and Long.MAX_VALUE
			val startOffset = hash % 2_000L

			return slotIndex * FIREWORK_SLOT_MILLIS + startOffset
		}

		fun slotHash(slotIndex: Long, salt: Long): Long {
			var x = slotIndex xor salt
			x = x xor (x ushr 30)
			x *= -4658895280553007687L
			x = x xor (x ushr 27)
			x *= -7723592293117605677L
			x = x xor (x ushr 31)

			return x
		}

		private fun lerp(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction
	}
}
