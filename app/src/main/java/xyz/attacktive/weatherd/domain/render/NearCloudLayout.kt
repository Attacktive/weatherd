package xyz.attacktive.weatherd.domain.render

/** An owned daily placement snapshot; prefixes and samplers cannot mutate its collection. */
internal class NearCloudLayout(val epochDay: Long, placements: List<CumulusPlacement>) {
	private val placements = placements.toTypedArray()
	val size: Int
		get() = placements.size

	operator fun get(index: Int) = placements[index]
}

/** Renderer-only clock boundary; layers never resolve another day within a frame. */
internal fun interface CloudEpochDaySource {
	fun currentEpochDay(): Long
}

internal val SYSTEM_CLOUD_EPOCH_DAY_SOURCE = CloudEpochDaySource { System.currentTimeMillis() / 86_400_000L }
