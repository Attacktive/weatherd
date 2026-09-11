package xyz.attacktive.weatherd.domain.model

/**
 * A slot the user can fill with one of their own photos, one per stretch of the day.
 * [DAY] and [NIGHT] stand alone; [DAWN] and [DUSK] are optional refinements that borrow from the phase they border in light when left empty — see [parent].
 */
enum class PhotoBucket {
	DAY,
	NIGHT,
	DAWN,
	DUSK;

	/**
	 * The bucket to borrow from when this one has no photo, or null when nothing may stand in for it.
	 * Twilight borrows from the side it resembles: dawn from [DAY], dusk from [NIGHT].
	 * [DAY] and [NIGHT] have no parent on purpose — a photo shot at noon must never turn up at midnight.
	 */
	val parent
		get() = when (this) {
			DAY, NIGHT -> null
			DAWN -> DAY
			DUSK -> NIGHT
		}

	companion object {
		/**
		 * The bucket stored under [name], matched against the entry's own uppercase spelling ("DAY", "NIGHT", "DAWN", "DUSK") exactly.
		 * Null means the value was absent or unrecognized — a lowercase or otherwise differently spelled name yields null rather than a match, and callers must not read that null as "use the default bucket".
		 */
		fun fromName(name: String?) = entries.firstOrNull { it.name == name }
	}
}

/**
 * Which of the user's photos to draw during [dayPhase], given the buckets they have actually filled, or null when none fits and the caller must paint the procedural sky instead.
 * Each phase asks for its own bucket first and falls back to that bucket's [PhotoBucket.parent] only.
 */
fun photoBucketFor(dayPhase: DayPhase, available: Set<PhotoBucket>): PhotoBucket? {
	val bucket = when (dayPhase) {
		DayPhase.DAY -> PhotoBucket.DAY
		DayPhase.NIGHT -> PhotoBucket.NIGHT
		DayPhase.DAWN -> PhotoBucket.DAWN
		DayPhase.DUSK -> PhotoBucket.DUSK
	}

	if (bucket in available) {
		return bucket
	}

	return bucket.parent?.takeIf { it in available }
}
