package xyz.attacktive.weatherd.domain.render

import kotlin.random.Random
import xyz.attacktive.weatherd.domain.model.BirdAppearanceInterval
import xyz.attacktive.weatherd.domain.model.BirdFlockSize

/** Shared-clock flock scheduling with randomized geometry cached for the lifetime of each slot. */
internal class BirdFlockFrame {
	var slot = -1
		private set
	var progress = 0f
		private set
	var direction = 1f
		private set
	var heightFraction = 0f
		private set
	var flockSize = 0
		private set

	private var interval: BirdAppearanceInterval? = null
	private var quiet = false
	private var crossingStart = 0f
	private var sizeFraction = 0f

	fun sample(timeSeconds: Float, size: BirdFlockSize, appearanceInterval: BirdAppearanceInterval): Boolean {
		if (size == BirdFlockSize.OFF) {
			return false
		}

		val slotSeconds = if (appearanceInterval == BirdAppearanceInterval.NATURAL) {
			NATURAL_SLOT_SECONDS
		} else {
			appearanceInterval.seconds.toFloat()
		}

		val nextSlot = (timeSeconds / slotSeconds).toInt()
		if (nextSlot != slot || appearanceInterval != interval) {
			configureSlot(nextSlot, slotSeconds, appearanceInterval)
		}

		val local = timeSeconds - crossingStart
		if (quiet || local !in 0f..CROSSING_SECONDS) {
			return false
		}

		progress = local / CROSSING_SECONDS
		flockSize = size.minBirds + (sizeFraction * (size.maxBirds - size.minBirds + 1)).toInt()

		return true
	}

	private fun configureSlot(nextSlot: Int, slotSeconds: Float, appearanceInterval: BirdAppearanceInterval) {
		slot = nextSlot
		interval = appearanceInterval
		val random = Random(BIRD_SEED + slot)
		val naturalQuiet = random.nextFloat() < 0.45f
		val naturalStart = random.nextFloat(NATURAL_SLOT_SECONDS - CROSSING_SECONDS)
		quiet = appearanceInterval == BirdAppearanceInterval.NATURAL && naturalQuiet
		crossingStart = slot * slotSeconds + if (appearanceInterval == BirdAppearanceInterval.NATURAL) {
			naturalStart
		} else {
			0f
		}

		direction = if (random.nextFloat() < 0.5f) {
			-1f
		} else {
			1f
		}

		sizeFraction = random.nextFloat()
		heightFraction = random.nextFloat(0.14f, 0.26f)
	}

	private companion object {
		const val BIRD_SEED = 11L
		const val NATURAL_SLOT_SECONDS = 217f
		const val CROSSING_SECONDS = 22f
	}
}
