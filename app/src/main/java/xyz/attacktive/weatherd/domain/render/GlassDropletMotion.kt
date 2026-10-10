package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

internal class GlassDropletFrame {
	var centerX = 0f
	var centerY = 0f
	var radius = 0f
	var verticalStretch = 1f
	var opacity = 0f

	internal var anchorX = 0f
		private set
	internal var anchorY = 0f
		private set
	internal var baseRadius = 0f
		private set
	internal var cycleIndex = NO_GLASS_DROPLET_CYCLE
		private set
	internal var cycleStartSeconds = 0f
		private set
	internal var localSeconds = 0f
		private set
	internal var slideProgress = 0f
		private set

	private var laneSlope = 0f
	private var laneCurve = 0f

	fun sample(slot: Int, width: Float, height: Float, timeSeconds: Float) {
		if (slot < 0 || !timeSeconds.isFinite()) {
			clear()

			return
		}

		if (!hasValidGlassDropletViewport(width, height)) {
			clear()

			return
		}

		val phaseOffset = unitFloat(glassDropletHash(slot, 0, GLASS_DROPLET_PHASE_SALT)) * GLASS_DROPLET_CYCLE_SECONDS
		val cyclePosition = timeSeconds + phaseOffset
		cycleIndex = floor(cyclePosition / GLASS_DROPLET_CYCLE_SECONDS).toInt()
		localSeconds = cyclePosition - cycleIndex * GLASS_DROPLET_CYCLE_SECONDS
		cycleStartSeconds = timeSeconds - localSeconds
		seedGeometry(slot, cycleIndex, width, height)
		applyLifecycle(localSeconds, width, height)
	}

	internal fun slideProgressForY(y: Float, height: Float): Float {
		val fallDistance = height - anchorY + baseRadius * GLASS_DROPLET_EXIT_RADIUS_MULTIPLIER
		if (!fallDistance.isFinite() || fallDistance <= 0f) {
			return Float.NaN
		}

		val fallFraction = (y - anchorY) / fallDistance
		if (!isUnitInterval(fallFraction)) {
			return Float.NaN
		}

		return sqrt(fallFraction)
	}

	internal fun slideXAt(progress: Float, width: Float): Float {
		return anchorX + width * (laneSlope * progress + laneCurve * progress * (1f - progress))
	}

	internal fun slideTimeAt(progress: Float): Float {
		return cycleStartSeconds + GLASS_DROPLET_HOLD_END_SECONDS + progress * GLASS_DROPLET_SLIDE_DURATION_SECONDS
	}

	private fun seedGeometry(slot: Int, cycleIndex: Int, width: Float, height: Float) {
		val shortEdge = min(width, height)
		anchorX = width * lerpGlassDroplet(
			GLASS_DROPLET_MIN_X_FRACTION,
			GLASS_DROPLET_MAX_X_FRACTION,
			unitFloat(glassDropletHash(slot, cycleIndex, GLASS_DROPLET_X_SALT))
		)

		anchorY = height * lerpGlassDroplet(
			GLASS_DROPLET_MIN_Y_FRACTION,
			GLASS_DROPLET_MAX_Y_FRACTION,
			unitFloat(glassDropletHash(slot, cycleIndex, GLASS_DROPLET_Y_SALT))
		)

		baseRadius = shortEdge * lerpGlassDroplet(
			GLASS_DROPLET_MIN_RADIUS_FRACTION,
			GLASS_DROPLET_MAX_RADIUS_FRACTION,
			unitFloat(glassDropletHash(slot, cycleIndex, GLASS_DROPLET_RADIUS_SALT))
		)

		laneSlope = lerpGlassDroplet(
			-GLASS_DROPLET_MAX_LANE_SLOPE,
			GLASS_DROPLET_MAX_LANE_SLOPE,
			unitFloat(glassDropletHash(slot, cycleIndex, GLASS_DROPLET_SLOPE_SALT))
		)

		laneCurve = lerpGlassDroplet(
			-GLASS_DROPLET_MAX_LANE_CURVE,
			GLASS_DROPLET_MAX_LANE_CURVE,
			unitFloat(glassDropletHash(slot, cycleIndex, GLASS_DROPLET_CURVE_SALT))
		)

		centerX = anchorX
		centerY = anchorY
		radius = baseRadius
		verticalStretch = 1f
		opacity = 1f
		slideProgress = 0f
	}

	private fun applyLifecycle(localSeconds: Float, width: Float, height: Float) {
		when {
			localSeconds < GLASS_DROPLET_FORM_END_SECONDS -> {
				val progress = smoothGlassDroplet(localSeconds / GLASS_DROPLET_FORM_END_SECONDS)
				radius = baseRadius * lerpGlassDroplet(GLASS_DROPLET_FORM_START_SCALE, 1f, progress)
				opacity = progress
			}
			localSeconds < GLASS_DROPLET_HOLD_END_SECONDS -> Unit
			localSeconds < GLASS_DROPLET_SLIDE_END_SECONDS -> applySlide(localSeconds, width, height)
			else -> opacity = 0f
		}
	}

	private fun applySlide(localSeconds: Float, width: Float, height: Float) {
		val progress = (localSeconds - GLASS_DROPLET_HOLD_END_SECONDS) / GLASS_DROPLET_SLIDE_DURATION_SECONDS
		val fallProgress = progress * progress
		val fallDistance = height - anchorY + baseRadius * GLASS_DROPLET_EXIT_RADIUS_MULTIPLIER
		val laneOffset = width * (laneSlope * progress + laneCurve * progress * (1f - progress))

		centerX = anchorX + laneOffset
		centerY = anchorY + fallDistance * fallProgress
		verticalStretch = 1f + (GLASS_DROPLET_MAX_VERTICAL_STRETCH - 1f) * smoothGlassDroplet((progress * 2f).coerceAtMost(1f))
		slideProgress = progress
	}

	private fun clear() {
		centerX = 0f
		centerY = 0f
		radius = 0f
		verticalStretch = 1f
		opacity = 0f
		anchorX = 0f
		anchorY = 0f
		baseRadius = 0f
		cycleIndex = NO_GLASS_DROPLET_CYCLE
		cycleStartSeconds = 0f
		localSeconds = 0f
		slideProgress = 0f
		laneSlope = 0f
		laneCurve = 0f
	}
}

internal class GlassDropletField {
	private val frames = Array(GLASS_DROPLET_MAX_COUNT) { GlassDropletFrame() }
	private val cachedTargetCycle = IntArray(GLASS_DROPLET_MAX_COUNT) { NO_GLASS_DROPLET_CYCLE }
	private val captureCollectorSlot = IntArray(GLASS_DROPLET_MAX_COUNT) { NO_GLASS_DROPLET_SLOT }
	private val captureCollectorCycle = IntArray(GLASS_DROPLET_MAX_COUNT) { NO_GLASS_DROPLET_CYCLE }
	private val captureTimeSeconds = FloatArray(GLASS_DROPLET_MAX_COUNT) { Float.POSITIVE_INFINITY }
	private val directCollector = IntArray(GLASS_DROPLET_MAX_COUNT) { NO_GLASS_DROPLET_SLOT }
	private val absorbedArea = FloatArray(GLASS_DROPLET_MAX_COUNT)
	private val collectorProbe = GlassDropletFrame()
	private val secondCollectorProbe = GlassDropletFrame()
	private var cachedWidth = Float.NaN
	private var cachedHeight = Float.NaN
	private var cachedActiveCount = -1

	fun sample(count: Int, width: Float, height: Float, timeSeconds: Float): Int {
		val activeCount = count.coerceIn(0, GLASS_DROPLET_MAX_COUNT)
		for (slot in 0 until activeCount) {
			frames[slot].sample(slot, width, height, timeSeconds)
		}

		if (!canApplyCoalescence(activeCount, width, height, timeSeconds)) {
			return activeCount
		}

		if (captureCacheShapeChanged(activeCount, width, height)) {
			invalidateCaptureCache(activeCount, width, height)
		}

		for (targetSlot in 0 until activeCount) {
			updateCaptureCache(targetSlot, activeCount, width, height)
		}

		applyCoalescence(activeCount, timeSeconds)

		return activeCount
	}

	fun frame(slot: Int): GlassDropletFrame = frames[slot]

	private fun captureCacheShapeChanged(activeCount: Int, width: Float, height: Float): Boolean {
		if (activeCount != cachedActiveCount) {
			return true
		}

		if (width != cachedWidth) {
			return true
		}

		return height != cachedHeight
	}

	private fun invalidateCaptureCache(activeCount: Int, width: Float, height: Float) {
		cachedActiveCount = activeCount
		cachedWidth = width
		cachedHeight = height
		for (slot in 0 until GLASS_DROPLET_MAX_COUNT) {
			cachedTargetCycle[slot] = NO_GLASS_DROPLET_CYCLE
		}
	}

	private fun updateCaptureCache(targetSlot: Int, activeCount: Int, width: Float, height: Float) {
		val target = frames[targetSlot]
		if (cachedTargetCycle[targetSlot] == target.cycleIndex) {
			return
		}

		resetCaptureCacheEntry(targetSlot, target.cycleIndex)
		val holdStart = target.cycleStartSeconds + GLASS_DROPLET_FORM_END_SECONDS
		val holdEnd = target.cycleStartSeconds + GLASS_DROPLET_HOLD_END_SECONDS

		for (collectorSlot in 0 until activeCount) {
			if (collectorSlot == targetSlot) {
				continue
			}

			updateCaptureFromCollectorCycles(targetSlot, collectorSlot, target, width, height, holdStart, holdEnd)
		}
	}

	private fun resetCaptureCacheEntry(targetSlot: Int, targetCycle: Int) {
		cachedTargetCycle[targetSlot] = targetCycle
		captureCollectorSlot[targetSlot] = NO_GLASS_DROPLET_SLOT
		captureCollectorCycle[targetSlot] = NO_GLASS_DROPLET_CYCLE
		captureTimeSeconds[targetSlot] = Float.POSITIVE_INFINITY
	}

	private fun updateCaptureFromCollectorCycles(targetSlot: Int, collectorSlot: Int, target: GlassDropletFrame, width: Float, height: Float, holdStart: Float, holdEnd: Float) {
		collectorProbe.sample(collectorSlot, width, height, holdStart)
		val firstCycle = collectorProbe.cycleIndex
		considerCaptureCandidate(targetSlot, collectorSlot, collectorProbe, target, width, height, holdStart, holdEnd)

		secondCollectorProbe.sample(collectorSlot, width, height, holdEnd - GLASS_DROPLET_CAPTURE_TIME_EPSILON)
		if (secondCollectorProbe.cycleIndex != firstCycle) {
			considerCaptureCandidate(targetSlot, collectorSlot, secondCollectorProbe, target, width, height, holdStart, holdEnd)
		}
	}

	private fun considerCaptureCandidate(targetSlot: Int, collectorSlot: Int, collector: GlassDropletFrame, target: GlassDropletFrame, width: Float, height: Float, holdStart: Float, holdEnd: Float) {
		val captureTime = captureTimeForCandidate(collector, target, width, height, holdStart, holdEnd)
		if (captureTime >= captureTimeSeconds[targetSlot]) {
			return
		}

		captureCollectorSlot[targetSlot] = collectorSlot
		captureCollectorCycle[targetSlot] = collector.cycleIndex
		captureTimeSeconds[targetSlot] = captureTime
	}

	private fun captureTimeForCandidate(collector: GlassDropletFrame, target: GlassDropletFrame, width: Float, height: Float, holdStart: Float, holdEnd: Float): Float {
		val progress = collector.slideProgressForY(target.anchorY, height)
		if (!progress.isFinite()) {
			return Float.POSITIVE_INFINITY
		}

		val captureTime = collector.slideTimeAt(progress)
		if (!isWithinHoldWindow(captureTime, holdStart, holdEnd)) {
			return Float.POSITIVE_INFINITY
		}

		val collectorX = collector.slideXAt(progress, width)
		val captureReach = (collector.baseRadius + target.baseRadius) * GLASS_DROPLET_CAPTURE_REACH_MULTIPLIER
		if (abs(collectorX - target.anchorX) > captureReach) {
			return Float.POSITIVE_INFINITY
		}

		return captureTime
	}

	private fun applyCoalescence(activeCount: Int, timeSeconds: Float) {
		resetCoalescenceState(activeCount)
		applyCaptureEvents(activeCount, timeSeconds)
		accumulateAbsorbedArea(activeCount)
		growCollectors(activeCount)
	}

	private fun resetCoalescenceState(activeCount: Int) {
		for (slot in 0 until activeCount) {
			directCollector[slot] = NO_GLASS_DROPLET_SLOT
			absorbedArea[slot] = 0f
		}
	}

	private fun applyCaptureEvents(activeCount: Int, timeSeconds: Float) {
		for (targetSlot in 0 until activeCount) {
			applyCaptureEvent(targetSlot, activeCount, timeSeconds)
		}
	}

	private fun applyCaptureEvent(targetSlot: Int, activeCount: Int, timeSeconds: Float) {
		val captureTime = captureTimeSeconds[targetSlot]
		if (!captureTime.isFinite() || timeSeconds < captureTime) {
			return
		}

		val collectorSlot = captureCollectorSlot[targetSlot]
		if (collectorSlot !in 0 until activeCount) {
			return
		}

		val collector = frames[collectorSlot]
		val collectorMatchesCurrentCycle = collector.cycleIndex == captureCollectorCycle[targetSlot]
		if (collectorMatchesCurrentCycle && collectorWasCapturedBefore(collectorSlot, captureTime)) {
			return
		}

		frames[targetSlot].opacity = 0f
		if (collectorMatchesCurrentCycle) {
			directCollector[targetSlot] = collectorSlot
		}
	}

	private fun accumulateAbsorbedArea(activeCount: Int) {
		for (targetSlot in 0 until activeCount) {
			val collectorSlot = directCollector[targetSlot]
			if (collectorSlot == NO_GLASS_DROPLET_SLOT) {
				continue
			}

			val rootSlot = findRootCollector(collectorSlot, activeCount)
			if (rootSlot == targetSlot) {
				continue
			}

			val radius = frames[targetSlot].baseRadius
			absorbedArea[rootSlot] += radius * radius
		}
	}

	private fun growCollectors(activeCount: Int) {
		for (collectorSlot in 0 until activeCount) {
			growCollector(collectorSlot)
		}
	}

	private fun growCollector(collectorSlot: Int) {
		val area = absorbedArea[collectorSlot]
		val collector = frames[collectorSlot]
		if (area <= 0f || collector.opacity <= 0f) {
			return
		}

		val originalRadius = collector.radius
		val mergedRadius = sqrt(originalRadius * originalRadius + area)
		collector.radius = mergedRadius
		collector.centerY += (mergedRadius - originalRadius) * GLASS_DROPLET_COALESCENCE_SPEED_NUDGE * collector.slideProgress
	}

	private fun collectorWasCapturedBefore(collectorSlot: Int, beforeTime: Float): Boolean {
		val collectorCaptureTime = captureTimeSeconds[collectorSlot]

		return collectorCaptureTime.isFinite() && collectorCaptureTime < beforeTime - GLASS_DROPLET_CAPTURE_TIME_EPSILON
	}

	private fun findRootCollector(startSlot: Int, activeCount: Int): Int {
		var current = startSlot
		var hops = 0
		while (hops < activeCount) {
			val next = directCollector[current]
			if (next == NO_GLASS_DROPLET_SLOT || next == current) {
				return current
			}

			current = next
			hops += 1
		}

		return startSlot
	}
}

internal fun glassDropletCount(params: SceneParams, width: Float, height: Float): Int {
	val precipitation = params.precipitation

	return if (!hasValidGlassDropletViewport(width, height) || !isPositiveFinite(params.precipitationScale)) {
		0
	} else if (precipitation == null || precipitation.kind != PrecipitationKind.RAIN) {
		0
	} else {
		val normalization = GLASS_DROPLET_REFERENCE_EDGE / min(width, height)
		val normalizedWidth = width * normalization
		val normalizedHeight = height * normalization
		val precipitationCount = precipitationDropCount(precipitation, params.precipitationScale, normalizedWidth, normalizedHeight)

		(precipitationCount / GLASS_DROPLET_DENSITY_DIVISOR)
			.roundToInt()
			.coerceIn(1, GLASS_DROPLET_MAX_COUNT)
	}
}

private fun canApplyCoalescence(activeCount: Int, width: Float, height: Float, timeSeconds: Float): Boolean {
	return activeCount > 0 && hasValidGlassDropletViewport(width, height) && timeSeconds.isFinite()
}

private fun isWithinHoldWindow(timeSeconds: Float, holdStart: Float, holdEnd: Float) = timeSeconds >= holdStart && timeSeconds < holdEnd

private fun isUnitInterval(value: Float) = value.isFinite() && value in 0f..1f

private fun hasValidGlassDropletViewport(width: Float, height: Float) = isPositiveFinite(width) && isPositiveFinite(height)

private fun isPositiveFinite(value: Float) = value.isFinite() && value > 0f

private fun glassDropletHash(slot: Int, cycle: Int, salt: Int): Int {
	var value = slot * 0x045d9f3b + cycle * 0x27d4eb2d + salt
	value = value xor (value ushr 16)
	value *= 0x045d9f3b
	value = value xor (value ushr 16)

	return value
}

private fun unitFloat(value: Int) = ((value ushr 8) and 0x00ff_ffff) / 16_777_216f

private fun smoothGlassDroplet(value: Float): Float {
	val clamped = value.coerceIn(0f, 1f)

	return clamped * clamped * (3f - 2f * clamped)
}

private fun lerpGlassDroplet(start: Float, end: Float, fraction: Float) = start + (end - start) * fraction

private const val NO_GLASS_DROPLET_SLOT = -1
private const val NO_GLASS_DROPLET_CYCLE = Int.MIN_VALUE
private const val GLASS_DROPLET_REFERENCE_EDGE = 1080f
private const val GLASS_DROPLET_DENSITY_DIVISOR = 20f
internal const val GLASS_DROPLET_MAX_COUNT = 48
private const val GLASS_DROPLET_CYCLE_SECONDS = 24f
private const val GLASS_DROPLET_FORM_END_SECONDS = 2f
private const val GLASS_DROPLET_HOLD_END_SECONDS = 8f
private const val GLASS_DROPLET_SLIDE_END_SECONDS = 22f
private const val GLASS_DROPLET_SLIDE_DURATION_SECONDS = GLASS_DROPLET_SLIDE_END_SECONDS - GLASS_DROPLET_HOLD_END_SECONDS
private const val GLASS_DROPLET_FORM_START_SCALE = 0.4f
private const val GLASS_DROPLET_MIN_X_FRACTION = 0.08f
private const val GLASS_DROPLET_MAX_X_FRACTION = 0.92f
private const val GLASS_DROPLET_MIN_Y_FRACTION = 0.05f
private const val GLASS_DROPLET_MAX_Y_FRACTION = 0.65f
private const val GLASS_DROPLET_MIN_RADIUS_FRACTION = 0.004f
private const val GLASS_DROPLET_MAX_RADIUS_FRACTION = 0.012f
private const val GLASS_DROPLET_MAX_VERTICAL_STRETCH = 1.8f
private const val GLASS_DROPLET_EXIT_RADIUS_MULTIPLIER = 3f
private const val GLASS_DROPLET_MAX_LANE_SLOPE = 0.025f
private const val GLASS_DROPLET_MAX_LANE_CURVE = 0.015f
private const val GLASS_DROPLET_CAPTURE_REACH_MULTIPLIER = 1.25f
private const val GLASS_DROPLET_COALESCENCE_SPEED_NUDGE = 0.45f
private const val GLASS_DROPLET_CAPTURE_TIME_EPSILON = 0.001f
private const val GLASS_DROPLET_PHASE_SALT = 0x13a5ba1d
private const val GLASS_DROPLET_X_SALT = 0x2156d72b
private const val GLASS_DROPLET_Y_SALT = 0x32c4f1a7
private const val GLASS_DROPLET_RADIUS_SALT = 0x417bc943
private const val GLASS_DROPLET_SLOPE_SALT = 0x51d7348f
private const val GLASS_DROPLET_CURVE_SALT = 0x61a9e657
