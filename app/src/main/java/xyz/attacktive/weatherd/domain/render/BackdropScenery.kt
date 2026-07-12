package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import kotlin.random.Random
import xyz.attacktive.weatherd.domain.model.BackdropScene

/** A point on a silhouette's upper edge, in unit coordinates: x across the screen, y down from the top. */
data class OutlinePoint(val x: Float, val y: Float)

/** The two silhouette planes of a backdrop scene; the renderer closes each outline down to the bottom edge and fills it. */
data class SceneryOutlines(val far: List<OutlinePoint>, val near: List<OutlinePoint>)

/**
 * The silhouette geometry for [scene], or null for the bare sky.
 * Pure and deterministic: each plane draws from a fixed seed, so every device shows the same skyline and tests can pin its shape.
 * [aspectRatio] (width over height) only scales how many features fit, so buildings and trees keep their proportions instead of stretching on wide screens.
 */
fun sceneryOutlinesFor(scene: BackdropScene, aspectRatio: Float): SceneryOutlines? = when (scene) {
	BackdropScene.NONE -> null
	BackdropScene.METROPOLIS -> metropolis(aspectRatio)
	BackdropScene.WOODS -> woods(aspectRatio)
	BackdropScene.BEACH -> beach(aspectRatio)
	BackdropScene.MOUNTAINS -> mountains(aspectRatio)
}

private fun metropolis(aspectRatio: Float) = SceneryOutlines(
	far = towerRun(Random(11L), count = scaled(9, aspectRatio), topLow = 0.72f, topHigh = 0.80f, baseY = 0.86f),
	near = rooflineRun(Random(23L), count = scaled(7, aspectRatio), topLow = 0.79f, topHigh = 0.88f)
)

private fun woods(aspectRatio: Float) = SceneryOutlines(
	far = treeline(Random(37L), count = scaled(16, aspectRatio), tipLow = 0.795f, tipHigh = 0.83f, dip = 0.03f),
	near = treeline(Random(41L), count = scaled(10, aspectRatio), tipLow = 0.835f, tipHigh = 0.875f, dip = 0.05f)
)

private fun beach(aspectRatio: Float) = SceneryOutlines(
	far = listOf(OutlinePoint(0f, SEA_HORIZON), OutlinePoint(1f, SEA_HORIZON)),
	near = duneSweep(Random(79L), count = scaled(6, aspectRatio))
)

private fun mountains(aspectRatio: Float) = SceneryOutlines(
	far = peakRange(Random(53L), count = scaled(4, aspectRatio), tipLow = 0.705f, tipHigh = 0.78f, valleyLow = 0.84f, valleyHigh = 0.875f),
	near = peakRange(Random(67L), count = scaled(6, aspectRatio), tipLow = 0.825f, tipHigh = 0.865f, valleyLow = 0.895f, valleyHigh = 0.925f)
)

/** Detached towers with street gaps between them, for a distant skyline against the sky. */
private fun towerRun(random: Random, count: Int, topLow: Float, topHigh: Float, baseY: Float): List<OutlinePoint> {
	val points = mutableListOf(OutlinePoint(0f, baseY))
	val edges = segmentEdges(random, count)

	for (index in 0 until count) {
		val left = edges[index]
		val right = edges[index + 1]
		val inset = (right - left) * (0.12f + random.nextFloat() * 0.1f)
		val top = between(random, topLow, topHigh)
		points += OutlinePoint(left + inset, baseY)
		points += OutlinePoint(left + inset, top)
		points += OutlinePoint(right - inset, top)
		points += OutlinePoint(right - inset, baseY)
	}

	points += OutlinePoint(1f, baseY)

	return points
}

/** A continuous wall of building roofs at varying heights, some carrying a thin antenna mast. */
private fun rooflineRun(random: Random, count: Int, topLow: Float, topHigh: Float): List<OutlinePoint> {
	val points = mutableListOf<OutlinePoint>()
	val edges = segmentEdges(random, count)

	for (index in 0 until count) {
		val left = edges[index]
		val right = edges[index + 1]
		val roof = between(random, topLow, topHigh)
		points += OutlinePoint(left, roof)

		if (random.nextFloat() < 0.35f) {
			val mastX = left + (right - left) * (0.25f + random.nextFloat() * 0.3f)
			val mastTop = roof - 0.02f - random.nextFloat() * 0.02f
			points += OutlinePoint(mastX, roof)
			points += OutlinePoint(mastX, mastTop)
			points += OutlinePoint(mastX + MAST_WIDTH, mastTop)
			points += OutlinePoint(mastX + MAST_WIDTH, roof)
		}

		points += OutlinePoint(right, roof)
	}

	return points
}

/** A sawtooth of conifer tips: each tree rises from a shared dip to its tip and falls to the next dip. */
private fun treeline(random: Random, count: Int, tipLow: Float, tipHigh: Float, dip: Float): List<OutlinePoint> {
	val points = mutableListOf(OutlinePoint(0f, tipHigh + dip))
	val edges = segmentEdges(random, count)

	for (index in 0 until count) {
		val center = (edges[index] + edges[index + 1]) / 2f
		points += OutlinePoint(center, between(random, tipLow, tipHigh))
		points += OutlinePoint(edges[index + 1], tipHigh + dip * (0.7f + random.nextFloat() * 0.6f))
	}

	return points
}

/** Triangular peaks with jittered summits and valley floors. */
private fun peakRange(random: Random, count: Int, tipLow: Float, tipHigh: Float, valleyLow: Float, valleyHigh: Float): List<OutlinePoint> {
	val points = mutableListOf(OutlinePoint(0f, between(random, valleyLow, valleyHigh)))
	val edges = segmentEdges(random, count)

	for (index in 0 until count) {
		val summit = edges[index] + (edges[index + 1] - edges[index]) * (0.35f + random.nextFloat() * 0.3f)
		points += OutlinePoint(summit, between(random, tipLow, tipHigh))
		points += OutlinePoint(edges[index + 1], between(random, valleyLow, valleyHigh))
	}

	return points
}

/** The shore rising from the water's edge on the right toward soft dunes on the left, always below [SEA_HORIZON]. */
private fun duneSweep(random: Random, count: Int): List<OutlinePoint> {
	val edges = segmentEdges(random, count)

	return (0..count).map { index ->
		val lift = (1f - edges[index]) * 0.05f
		val y = 0.955f - lift - random.nextFloat() * 0.012f
		OutlinePoint(edges[index], y.coerceIn(0.88f, 0.955f))
	}
}

/** [count] slot boundaries of randomised widths, normalised so the first edge is exactly 0 and the last exactly 1. */
private fun segmentEdges(random: Random, count: Int): FloatArray {
	val widths = FloatArray(count) { 0.6f + random.nextFloat() * 0.9f }
	val total = widths.sum()
	val edges = FloatArray(count + 1)

	for (index in 0 until count) {
		edges[index + 1] = edges[index] + widths[index] / total
	}

	edges[count] = 1f

	return edges
}

/** How many features fit the screen: the counts are tuned for a portrait phone and grow with wider aspects. */
private fun scaled(baseCount: Int, aspectRatio: Float) = (baseCount * aspectRatio / PORTRAIT_ASPECT).roundToInt().coerceAtLeast(2)

private fun between(random: Random, low: Float, high: Float) = low + (high - low) * random.nextFloat()

private const val PORTRAIT_ASPECT = 0.46f

/** Where the beach scene's sea meets the sky, as a fraction of screen height. */
private const val SEA_HORIZON = 0.85f

/** Antenna masts are a hair's width regardless of how many buildings share the skyline. */
private const val MAST_WIDTH = 0.005f
