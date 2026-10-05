package xyz.attacktive.weatherd.domain.render

import kotlin.math.sqrt

/** A broad closed surface envelope with a fixed physical normal, independent of lighting and material noise. */
internal data class MountainSurfacePatch(val outline: List<OutlinePoint>, val normalX: Float, val normalY: Float, val normalZ: Float)

/** One layer's shared relief field; snow samples its parent rock instead of preparing another field. */
internal data class MountainSurface(val layerIndex: Int, val patches: List<MountainSurfacePatch>)

/** Partitions existing contours at prominent saddles without consuming their random streams or changing any vertex. */
internal fun mountainSurfacesFor(outlines: SceneryOutlines, aspectRatio: Float): List<MountainSurface> = outlines.layers.mapIndexed { layerIndex, layer ->
	val edge = layer.outline
	val prominence = when (layer.material) {
		SceneryMaterial.ROCK -> 0.028f
		SceneryMaterial.FOREST -> 0.012f
		else -> 0.003f
	}

	val saddles = mutableListOf(0)
	for (index in 1 until edge.lastIndex) {
		val point = edge[index]
		if (point.y < edge[index - 1].y || point.y < edge[index + 1].y) {
			continue
		}

		var left = index - 1
		while (left > 0 && edge[left - 1].y <= edge[left].y) {
			left--
		}

		var right = index + 1
		while (right < edge.lastIndex && edge[right + 1].y <= edge[right].y) {
			right++
		}

		if (point.y - maxOf(edge[left].y, edge[right].y) >= prominence) {
			saddles += index
		}
	}

	saddles += edge.lastIndex
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

	MountainSurface(layerIndex, patches)
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
