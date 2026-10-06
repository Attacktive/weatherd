package xyz.attacktive.weatherd.domain.render

import kotlin.math.sqrt

/** A broad closed surface envelope with a fixed physical normal, independent of lighting and material noise. */
internal data class MountainSurfacePatch(val outline: List<OutlinePoint>, val normalX: Float, val normalY: Float, val normalZ: Float)

/** One layer's shared relief field; snow samples its parent rock instead of preparing another field. */
internal data class MountainSurface(val layerIndex: Int, val patches: List<MountainSurfacePatch>)

/** Partitions existing contours at prominent saddles without consuming their random streams or changing any vertex. */
internal fun mountainSurfacesFor(outlines: SceneryOutlines, aspectRatio: Float): List<MountainSurface> = outlines.layers.mapIndexed { layerIndex, layer ->
	MountainSurface(layerIndex, preparedPatches(layer, aspectRatio))
}

private fun preparedPatches(layer: SceneryLayer, aspectRatio: Float): List<MountainSurfacePatch> {
	val edge = layer.outline
	val saddles = prominentSaddles(edge, prominenceFor(layer.material))

	val patches = mutableListOf<MountainSurfacePatch>()
	for (basin in 0 until saddles.lastIndex) {
		val start = saddles[basin]
		val end = saddles[basin + 1]
		var summit = start
		for (index in start + 1..end) {
			if (edge[index].y < edge[summit].y) {
				summit = index
			}
		}

		val foot = OutlinePoint(edge[summit].x * 0.75f + (edge[start].x + edge[end].x) * 0.125f, 1f)
		if (summit > start) {
			val envelope = edge.subList(start, summit + 1) + listOf(foot, OutlinePoint(edge[start].x, 1f))
			patches += orientedPatch(envelope, edge[start], edge[summit], layer.material, aspectRatio)
		}

		if (summit < end) {
			val envelope = edge.subList(summit, end + 1) + listOf(OutlinePoint(edge[end].x, 1f), foot)
			patches += orientedPatch(envelope, edge[summit], edge[end], layer.material, aspectRatio)
		}
	}

	return patches
}

private fun prominenceFor(material: SceneryMaterial) = when (material) {
	SceneryMaterial.ROCK -> 0.028f
	SceneryMaterial.FOREST -> 0.012f
	else -> 0.003f
}

private fun prominentSaddles(edge: List<OutlinePoint>, prominence: Float): List<Int> {
	val saddles = mutableListOf(0)
	for (index in 1 until edge.lastIndex) {
		val point = edge[index]
		if (point.y < edge[index - 1].y || point.y < edge[index + 1].y) {
			continue
		}

		val left = crestIndex(edge, index, -1)
		val right = crestIndex(edge, index, 1)
		if (point.y - maxOf(edge[left].y, edge[right].y) >= prominence) {
			saddles += index
		}
	}

	saddles += edge.lastIndex

	return saddles
}

private fun crestIndex(edge: List<OutlinePoint>, saddle: Int, direction: Int): Int {
	var index = saddle + direction
	while (true) {
		val next = edge.getOrNull(index + direction) ?: break
		if (next.y > edge[index].y) {
			break
		}

		index += direction
	}

	return index
}

private fun orientedPatch(envelope: List<OutlinePoint>, from: OutlinePoint, to: OutlinePoint, material: SceneryMaterial, aspectRatio: Float): MountainSurfacePatch {
	val relief = when (material) {
		SceneryMaterial.ROCK -> 1.5f
		SceneryMaterial.FOREST -> 0.65f
		else -> 0.3f
	}

	val physicalWidth = ((to.x - from.x) * aspectRatio).coerceAtLeast(0.0001f)
	val normalX = ((to.y - from.y) / physicalWidth * relief).coerceIn(-1.5f, 1.5f)
	val normalY = 0.42f
	val normalZ = 0.75f
	val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)

	return MountainSurfacePatch(envelope, normalX / length, normalY / length, normalZ / length)
}
