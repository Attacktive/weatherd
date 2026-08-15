package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import xyz.attacktive.weatherd.domain.model.BackdropScene

/** A point on a silhouette's upper edge, in unit coordinates: x across the screen, y down from the top. */
data class OutlinePoint(val x: Float, val y: Float)

/**
 * What a painted layer is made of; [ScenePalette][sceneryLayerColor] maps it to a color under the current weather and day phase.
 * [SILHOUETTE] is the backward-compatible material: it takes the sky-derived plane tone exactly, reproducing the pre-paint look.
 */
enum class SceneryMaterial {
	SILHOUETTE,
	WATER,
	SAND,
	HULL,
	SAIL,
	PARASOL,
	ROCK,
	FOREST,
	MEADOW,
	SNOW,
	PASTURE,
	WHEAT,
	BARN,
	STEEL,
	MASONRY
}

/** Which depth plane a layer belongs to — drives crest bookkeeping, atmospheric strength, and the glow/haze bands between planes. */
enum class SceneryPlane {
	FAR,
	NEAR
}

/**
 * One filled region of a scene: an upper edge the renderer closes down to the frame bottom.
 * Layers draw in list order, back to front.
 */
data class SceneryLayer(
	val outline: List<OutlinePoint>,
	val material: SceneryMaterial,
	val plane: SceneryPlane
)

/** What kind of cheap animated critter [SceneryFauna] describes. */
enum class SceneryFaunaKind {
	GULL,
	SHARK,
	WHALE
}

/**
 * A cheap animated accent: gulls and marine life on the coast.
 * Positions are unit-frame anchors; the renderer animates them from [phase] / [speed] and wall-clock time.
 */
data class SceneryFauna(
	val kind: SceneryFaunaKind,
	val baseX: Float,
	val baseY: Float,
	val scale: Float,
	val phase: Float,
	val speed: Float
)

/**
 * A countryside windmill: hub and ground-contact in unit coords.
 * [groundY] is the hill surface the tower must reach — without it the sails float.
 */
data class SceneryWindmill(val hubX: Float, val hubY: Float, val groundY: Float, val scale: Float)

/** A closed painted shape drawn over its [plane] — a part of the beach sloop, a mountain snowcap, the countryside farmhouse. */
data class SceneryGlyph(
	val outline: List<OutlinePoint>,
	val material: SceneryMaterial,
	val plane: SceneryPlane = SceneryPlane.FAR
)

/**
 * The painted layers of a backdrop scene, back to front, plus optional accent polylines stroked in the near tone (fence posts).
 * [windows] are warm night-light centers for the metropolis; [beacons] are red tips on the tallest towers only.
 * [glyphs] are closed painted shapes over their plane (the beach sloop and snowcaps on the far, the farmhouse on the near); [parasols] on the left beach; [windmill] on the countryside rise.
 */
data class SceneryOutlines(
	val layers: List<SceneryLayer>,
	val accents: List<List<OutlinePoint>> = emptyList(),
	val windows: List<OutlinePoint> = emptyList(),
	val beacons: List<OutlinePoint> = emptyList(),
	val glyphs: List<SceneryGlyph> = emptyList(),
	val parasols: List<OutlinePoint> = emptyList(),
	val reflectionY: Float? = null,
	val gulls: List<SceneryFauna> = emptyList(),
	val marine: List<SceneryFauna> = emptyList(),
	val windmill: SceneryWindmill? = null
)

/**
 * The silhouette geometry for [scene], or null for the bare sky.
 * Pure and deterministic: each plane draws from a fixed seed, so every device shows the same skyline and tests can pin its shape.
 * [aspectRatio] (width over height) only scales how many features fit, so buildings and trees keep their proportions instead of stretching on wide screens.
 */
fun sceneryOutlinesFor(scene: BackdropScene, aspectRatio: Float): SceneryOutlines? = when (scene) {
	BackdropScene.NONE -> null
	BackdropScene.METROPOLIS -> metropolis(aspectRatio)
	BackdropScene.BEACH -> beach(aspectRatio)
	BackdropScene.MOUNTAINS -> mountains(aspectRatio)
	BackdropScene.COUNTRYSIDE -> countryside(aspectRatio)
}

/** Hazy steel towers behind warm masonry blocks with real street gaps, fronted by a city park with a few tree crowns. */
private fun metropolis(aspectRatio: Float): SceneryOutlines {
	val windowRandom = Random(31L)
	val farWindows = mutableListOf<OutlinePoint>()
	val nearWindows = mutableListOf<OutlinePoint>()
	val roofCandidates = mutableListOf<OutlinePoint>()

	val far = towerRun(
		random = Random(11L),
		spec = TowerRunSpec(
			count = scaledSparse(6, aspectRatio),
			topLow = 0.70f,
			topHigh = 0.79f,
			baseY = 0.86f,
			setbacks = true,
			windowChance = 0.35f
		),
		windows = farWindows,
		windowRandom = windowRandom,
		roofCandidates = roofCandidates
	)

	// Detached blocks instead of the old continuous roofline — the solid parapet read as a wall, not a city.
	val nearCount = scaledSparse(5, aspectRatio)
	val construction = mutableListOf<OutlinePoint>()
	val near = towerRun(
		random = Random(23L),
		spec = TowerRunSpec(
			count = nearCount,
			topLow = 0.78f,
			topHigh = 0.88f,
			baseY = 0.94f,
			setbacks = true,
			windowChance = 0.55f,
			constructionIndex = nearCount - 2
		),
		windows = nearWindows,
		windowRandom = windowRandom,
		construction = construction
	)

	val park = rollingHills(Random(37L), count = scaled(5, aspectRatio), topLow = 0.925f, topHigh = 0.94f)
	val crane = constructionCrane(construction, aspectRatio)

	// Cap the lit-window budget so dusk/night stays a handful of rect draws, not a skyline disco.
	val litWindows = (farWindows + nearWindows).take(MAX_WINDOWS)

	// Aviation lights belong on the tallest far towers — on the roof, not floating above it — plus one on the crane apex.
	val beacons = roofCandidates.sortedBy { it.y }.take(BEACON_COUNT) + crane.light

	return SceneryOutlines(
		layers = listOf(
			SceneryLayer(far, SceneryMaterial.STEEL, SceneryPlane.FAR),
			SceneryLayer(near, SceneryMaterial.MASONRY, SceneryPlane.NEAR),
			SceneryLayer(park, SceneryMaterial.MEADOW, SceneryPlane.NEAR)
		),
		windows = litWindows,
		beacons = beacons,
		glyphs = parkTrees(Random(41L), park, aspectRatio) + crane.glyphs
	)
}

/** A tower crane's painted parts and the anti-collision light riding its apex. */
private data class SiteCrane(val glyphs: List<SceneryGlyph>, val light: OutlinePoint)

/**
 * A tower crane standing over the construction site: mast on the slab, jib and counter-jib, apex, and a hook line dropping toward the unfinished floor.
 * Every dimension derives from the jib length, so the crane keeps its shape at every aspect.
 */
private fun constructionCrane(site: List<OutlinePoint>, aspectRatio: Float): SiteCrane {
	val left = site.first().x
	val right = site.last().x
	val top = site.first().y
	val slabY = site.last().y
	val span = right - left
	val jibLen = span * 1.15f
	val rise = jibLen * aspectRatio
	val mastX = left + span * 0.35f
	val mastHalf = jibLen * 0.03f
	val jibY = top - rise * 0.5f
	val jibHalf = rise * 0.025f

	// The mast ends on the slab line itself (a hair past, to bury the anti-aliasing) — overshooting drew a dark seam down the facade.
	val mast = listOf(
		OutlinePoint(mastX - mastHalf, jibY),
		OutlinePoint(mastX + mastHalf, jibY),
		OutlinePoint(mastX + mastHalf, slabY + 0.002f),
		OutlinePoint(mastX - mastHalf, slabY + 0.002f)
	)
	val jib = listOf(
		OutlinePoint(mastX - jibLen * 0.3f, jibY - jibHalf),
		OutlinePoint(mastX + jibLen * 0.7f, jibY - jibHalf),
		OutlinePoint(mastX + jibLen * 0.7f, jibY + jibHalf),
		OutlinePoint(mastX - jibLen * 0.3f, jibY + jibHalf)
	)
	val apex = listOf(
		OutlinePoint(mastX - mastHalf, jibY - jibHalf),
		OutlinePoint(mastX, jibY - rise * 0.22f),
		OutlinePoint(mastX + mastHalf, jibY - jibHalf)
	)

	val hookX = mastX + jibLen * 0.45f
	val hookHalf = jibLen * 0.008f
	val hook = listOf(
		OutlinePoint(hookX - hookHalf, jibY),
		OutlinePoint(hookX + hookHalf, jibY),
		OutlinePoint(hookX + hookHalf, top - rise * 0.12f),
		OutlinePoint(hookX - hookHalf, top - rise * 0.12f)
	)

	return SiteCrane(
		glyphs = listOf(mast, jib, apex, hook).map { SceneryGlyph(it, SceneryMaterial.HULL, SceneryPlane.NEAR) },
		light = OutlinePoint(mastX, jibY - rise * 0.22f)
	)
}

/**
 * Rounded tree crowns standing over the park band, drawn as near-plane glyphs so they front the masonry blocks.
 * Heights derive from each crown's on-screen width, so the trees keep their shape at every aspect.
 */
private fun parkTrees(random: Random, park: List<OutlinePoint>, aspectRatio: Float): List<SceneryGlyph> {
	val featureScale = (PORTRAIT_ASPECT / aspectRatio).coerceAtLeast(0.55f)
	val count = scaledSparse(4, aspectRatio)

	return List(count) { index ->
		val x = (index + random.nextFloat(0.25f, 0.75f)) / count
		val width = random.nextFloat(0.035f, 0.05f) * featureScale
		val rise = width * aspectRatio
		val groundY = sampleOutlineY(park, x) + rise * 0.1f
		val half = width * 0.5f

		val crown = listOf(
			OutlinePoint(x - half * 0.55f, groundY),
			OutlinePoint(x - half, groundY - rise * 0.4f),
			OutlinePoint(x - half * 0.8f, groundY - rise * 0.85f),
			OutlinePoint(x, groundY - rise * 1.05f),
			OutlinePoint(x + half * 0.8f, groundY - rise * 0.85f),
			OutlinePoint(x + half, groundY - rise * 0.4f),
			OutlinePoint(x + half * 0.55f, groundY)
		)

		SceneryGlyph(crown, SceneryMaterial.FOREST, SceneryPlane.NEAR)
	}
}

/** A shoreline with a bluff, beach parasols on the left, a sloop on the horizon, and a little life in the water. */
private fun beach(aspectRatio: Float): SceneryOutlines {
	// The boat and umbrellas keep a readable size on wide screens instead of shrinking to hairlines.
	val featureScale = (PORTRAIT_ASPECT / aspectRatio).coerceAtLeast(MIN_BEACH_FEATURE_SCALE)
	val boatRandom = Random(89L)
	val bluff = coastalBluff(Random(79L))

	return SceneryOutlines(
		layers = listOf(
			SceneryLayer(flatSea(), SceneryMaterial.WATER, SceneryPlane.FAR),
			SceneryLayer(bluff, SceneryMaterial.SAND, SceneryPlane.NEAR)
		),
		// The boat stays over open water on the right — under the bluff it gets swallowed by the near fill.
		glyphs = sailboat(start = boatRandom.nextFloat(0.60f, 0.70f), length = 0.10f * featureScale, aspectRatio = aspectRatio),
		parasols = beachParasols(Random(83L), bluff, featureScale),
		reflectionY = SEA_HORIZON,
		gulls = beachGulls(Random(97L), featureScale),
		marine = beachMarine(Random(101L), featureScale)
	)
}

/** Hazy rock ridges behind a forested near range, with snow hugging the tallest far summits. */
private fun mountains(aspectRatio: Float): SceneryOutlines {
	val far = mountainRange(
		random = Random(53L),
		count = scaled(3, aspectRatio),
		crestLow = 0.70f,
		crestHigh = 0.78f,
		saddleLow = 0.80f,
		saddleHigh = 0.85f,
		broadCrests = true
	)
	val near = mountainRange(
		random = Random(67L),
		count = scaled(4, aspectRatio),
		crestLow = 0.83f,
		crestHigh = 0.875f,
		saddleLow = 0.89f,
		saddleHigh = 0.925f,
		broadCrests = false
	)

	// A sunlit meadow floor in front of the pines — the third color band that keeps the scene from being rock-on-green alone.
	val meadow = rollingHills(Random(73L), count = scaled(6, aspectRatio), topLow = 0.92f, topHigh = 0.94f)

	return SceneryOutlines(
		layers = listOf(
			SceneryLayer(far, SceneryMaterial.ROCK, SceneryPlane.FAR),
			SceneryLayer(near, SceneryMaterial.FOREST, SceneryPlane.NEAR),
			SceneryLayer(meadow, SceneryMaterial.MEADOW, SceneryPlane.NEAR)
		),
		glyphs = snowcaps(far)
	)
}

/**
 * Snow on the high far summits: each cap's top edge is the ridge outline itself, so snow hugs rock exactly, and the underside sits at a snowline just below the peak with a small central dip.
 * Only summits rising above [SNOW_SUMMIT_MAX] get a cap, leaving the lower peaks bare for variety.
 */
private fun snowcaps(range: List<OutlinePoint>): List<SceneryGlyph> {
	// The tallest summit always earns snow, however low the seeded ridge came out; the fixed threshold only adds caps on genuinely high peaks.
	val summitMax = maxOf(SNOW_SUMMIT_MAX, range.minOf { it.y } + 0.01f)
	val caps = mutableListOf<SceneryGlyph>()
	var index = 0

	while (index < range.size) {
		if (range[index].y >= summitMax) {
			index++
			continue
		}

		// The cap spans every consecutive vertex above the summit threshold, so a broad crest gets one cap instead of two.
		var end = index
		while (end + 1 < range.size && range[end + 1].y < summitMax) {
			end++
		}

		val run = range.subList(index, end + 1)
		val summitY = run.minOf { it.y }
		val snowline = maxOf(summitY + SNOWCAP_DEPTH, run.maxOf { it.y } + 0.008f)

		// The cap's bottom corners slide down the actual slopes to the snowline instead of dropping vertically off the run's edge vertices.
		val left = slopeCrossing(range, index, -1, snowline)
		val right = slopeCrossing(range, end, 1, snowline)
		val dip = OutlinePoint((left.x + right.x) * 0.5f, snowline + 0.01f)

		caps += SceneryGlyph(listOf(left) + run + listOf(right, dip), SceneryMaterial.SNOW)
		index = end + 1
	}

	return caps
}

/** Where the slope from vertex [index] toward its [direction]-side neighbor crosses [snowline]; the vertex itself when there is no neighbor below it. */
private fun slopeCrossing(range: List<OutlinePoint>, index: Int, direction: Int, snowline: Float): OutlinePoint {
	val vertex = range[index]
	val neighbor = range.getOrNull(index + direction) ?: return OutlinePoint(vertex.x, snowline)
	if (neighbor.y <= snowline) {
		return OutlinePoint(vertex.x, snowline)
	}

	val t = (snowline - vertex.y) / (neighbor.y - vertex.y)

	return OutlinePoint(lerp(vertex.x, neighbor.x, t), snowline)
}

/** Hazy pasture hills behind a sunlit meadow rise carrying the farmhouse and windmill, with a ripe wheat strip along the bottom. */
private fun countryside(aspectRatio: Float): SceneryOutlines {
	val featureScale = (PORTRAIT_ASPECT / aspectRatio).coerceAtLeast(0.55f)
	val farHills = rollingHills(Random(57L), count = scaled(4, aspectRatio), topLow = 0.78f, topHigh = 0.85f)
	val nearHills = rollingHills(Random(59L), count = scaled(5, aspectRatio), topLow = 0.86f, topHigh = 0.91f)
	val mill = pastureWindmill(Random(71L), nearHills, featureScale)

	// Tower is welded into the near silhouette so it can't float; only the sails are drawn each frame.
	val withMill = attachWindmillTower(nearHills, mill)

	// The warm band that keeps the farmland from being green-on-green; the fence line straddles its crest.
	val wheat = rollingHills(Random(77L), count = scaled(6, aspectRatio), topLow = 0.92f, topHigh = 0.94f)

	return SceneryOutlines(
		layers = listOf(
			SceneryLayer(farHills, SceneryMaterial.PASTURE, SceneryPlane.FAR),
			SceneryLayer(withMill, SceneryMaterial.MEADOW, SceneryPlane.NEAR),
			SceneryLayer(wheat, SceneryMaterial.WHEAT, SceneryPlane.NEAR)
		),
		accents = pastureFence(Random(63L), wheat, featureScale),
		glyphs = farmhouseGlyphs(withMill, Random(61L), featureScale, aspectRatio),
		windmill = mill
	)
}

/** Knobs for a distant tower run — keeps [towerRun] under the parameter-count lint. */
private data class TowerRunSpec(
	val count: Int,
	val topLow: Float,
	val topHigh: Float,
	val baseY: Float,
	val setbacks: Boolean = false,
	val windowChance: Float = 0f,
	val constructionIndex: Int? = null
)

/** Detached towers with street gaps between them, for a distant skyline against the sky. */
private fun towerRun(
	random: Random,
	spec: TowerRunSpec,
	windows: MutableList<OutlinePoint>? = null,
	windowRandom: Random? = null,
	roofCandidates: MutableList<OutlinePoint>? = null,
	construction: MutableList<OutlinePoint>? = null
): List<OutlinePoint> {
	val points = mutableListOf(OutlinePoint(0f, spec.baseY))
	val edges = segmentEdges(random, spec.count)

	for (index in 0 until spec.count) {
		val left = edges[index]
		val right = edges[index + 1]
		val inset = (right - left) * random.nextFloat(0.10f, 0.22f)
		val towerLeft = left + inset
		val towerRight = right - inset
		val top = random.nextFloat(spec.topLow, spec.topHigh)
		points += OutlinePoint(towerLeft, spec.baseY)

		val isConstruction = index == spec.constructionIndex
		val usedSetback = !isConstruction &&
			spec.setbacks &&
			random.nextFloat() < 0.4f &&
			towerRight - towerLeft > 0.04f

		when {
			isConstruction -> {
				val slabY = appendConstructionTower(points, random, towerLeft, towerRight, top, spec.baseY)

				// The site's two corners: left at the core top, right at the slab line — the crane builder reads both.
				construction?.add(OutlinePoint(towerLeft, top))
				construction?.add(OutlinePoint(towerRight, slabY))
			}
			usedSetback -> appendSetbackTower(points, random, towerLeft, towerRight, top, spec.baseY)
			else -> appendFlatTower(points, towerLeft, towerRight, top, spec.baseY)
		}

		// The unfinished tower stays dark: nobody lives on a bare slab.
		val chance = when {
			isConstruction -> 0f
			usedSetback -> spec.windowChance * 0.7f
			else -> spec.windowChance
		}

		maybePlantWindows(windows, windowRandom, towerLeft, towerRight, top, spec.baseY, chance)

		// Beacon candidate sits on the roof line itself (same y as the parapet), never hovering above it.
		roofCandidates?.add(OutlinePoint((towerLeft + towerRight) * 0.5f, top))
	}

	points += OutlinePoint(1f, spec.baseY)

	return points
}

/** An unfinished tower for the construction site: a bare slab line with the lift core poking above it. Returns the slab height for the crane to stand on. */
private fun appendConstructionTower(
	points: MutableList<OutlinePoint>,
	random: Random,
	towerLeft: Float,
	towerRight: Float,
	top: Float,
	baseY: Float
): Float {
	val span = towerRight - towerLeft
	val slabY = top + random.nextFloat(0.014f, 0.022f)
	val coreLeft = towerLeft + span * random.nextFloat(0.55f, 0.62f)
	val coreRight = coreLeft + span * random.nextFloat(0.12f, 0.16f)

	points += OutlinePoint(towerLeft, slabY)
	points += OutlinePoint(coreLeft, slabY)
	points += OutlinePoint(coreLeft, top)
	points += OutlinePoint(coreRight, top)
	points += OutlinePoint(coreRight, slabY)
	points += OutlinePoint(towerRight, slabY)
	points += OutlinePoint(towerRight, baseY)

	return slabY
}

private fun appendSetbackTower(
	points: MutableList<OutlinePoint>,
	random: Random,
	towerLeft: Float,
	towerRight: Float,
	top: Float,
	baseY: Float
) {
	val mid = towerLeft + (towerRight - towerLeft) * random.nextFloat(0.35f, 0.65f)
	val shoulder = top + random.nextFloat(0.012f, 0.022f)
	points += OutlinePoint(towerLeft, shoulder)
	points += OutlinePoint(mid, shoulder)
	points += OutlinePoint(mid, top)
	points += OutlinePoint(towerRight, top)
	points += OutlinePoint(towerRight, baseY)
}

private fun appendFlatTower(
	points: MutableList<OutlinePoint>,
	towerLeft: Float,
	towerRight: Float,
	top: Float,
	baseY: Float
) {
	points += OutlinePoint(towerLeft, top)
	points += OutlinePoint(towerRight, top)
	points += OutlinePoint(towerRight, baseY)
}

private fun maybePlantWindows(
	windows: MutableList<OutlinePoint>?,
	windowRandom: Random?,
	left: Float,
	right: Float,
	top: Float,
	baseY: Float,
	chance: Float
) {
	if (windows == null || windowRandom == null) {
		return
	}

	plantWindows(windows, windowRandom, left, right, top, baseY, chance)
}

/** Seeded warm-window centers inside a building slab; denser near the roof, sparse enough to stay cheap to draw. */
private fun plantWindows(
	into: MutableList<OutlinePoint>,
	random: Random,
	left: Float,
	right: Float,
	top: Float,
	baseY: Float,
	chance: Float
) {
	val cols = ((right - left) / 0.012f).roundToInt().coerceIn(1, 5)
	val rows = ((baseY - top) / 0.018f).roundToInt().coerceIn(1, 6)

	for (row in 0 until rows) {
		for (col in 0 until cols) {
			if (random.nextFloat() > chance) {
				continue
			}

			val x = left + (right - left) * ((col + 0.5f) / cols)
			val y = top + (baseY - top) * ((row + 0.55f) / (rows + 0.5f))
			into += OutlinePoint(x, y)
		}
	}
}

/** Bare sea horizon — the sloop is drawn separately so it stays recognizable. */
private fun flatSea() = listOf(OutlinePoint(0f, SEA_HORIZON), OutlinePoint(1f, SEA_HORIZON))

/**
 * A sloop under sail: chunky dark hull, thin mast, tall mainsail with a gently concave leech, and a jib flying from the bow.
 * Heights derive from the boat's own on-screen length (length × aspect converts x units to y units), so the shape survives every aspect.
 */
private fun sailboat(start: Float, length: Float, aspectRatio: Float): List<SceneryGlyph> {
	val water = SEA_HORIZON
	val rise = length * aspectRatio
	val deck = water - rise * 0.14f
	val boom = water - rise * 0.24f
	val mastTop = water - rise
	val mastX = start + length * 0.45f

	val hull = listOf(
		OutlinePoint(start, water),
		OutlinePoint(start + length * 0.06f, deck),
		OutlinePoint(start + length * 0.94f, deck),
		OutlinePoint(start + length, water)
	)
	val mast = listOf(
		OutlinePoint(mastX - length * 0.01f, mastTop),
		OutlinePoint(mastX + length * 0.01f, mastTop),
		OutlinePoint(mastX + length * 0.01f, deck),
		OutlinePoint(mastX - length * 0.01f, deck)
	)

	// The leech (trailing edge) bellies toward the mast; sampled points fake the curve within the straight-edged outline contract.
	val boomEnd = OutlinePoint(start + length * 0.82f, boom)
	val mainsail = mutableListOf(OutlinePoint(mastX, mastTop), OutlinePoint(mastX, boom), boomEnd)
	for (step in 1..3) {
		val t = step / 4f
		val x = lerp(boomEnd.x, mastX, t) - sin(t * TAU * 0.5f) * length * 0.05f
		val y = lerp(boomEnd.y, mastTop, t)
		mainsail += OutlinePoint(x, y)
	}

	// The jib hangs off the forestay: masthead down to the bow, clew stopping short of the mast so the two sails read separately.
	val jib = listOf(
		OutlinePoint(mastX - length * 0.03f, mastTop + rise * 0.08f),
		OutlinePoint(start + length * 0.04f, boom),
		OutlinePoint(mastX - length * 0.05f, boom)
	)

	return listOf(
		SceneryGlyph(hull, SceneryMaterial.HULL),
		SceneryGlyph(mast, SceneryMaterial.HULL),
		SceneryGlyph(mainsail, SceneryMaterial.SAIL),
		SceneryGlyph(jib, SceneryMaterial.SAIL)
	)
}

/**
 * A coastal bluff on the left dropping to a sandy shelf on the right.
 * Stays below the sloop's mast so the two-plane depth order still holds.
 */
private fun coastalBluff(random: Random): List<OutlinePoint> {
	val points = mutableListOf<OutlinePoint>()
	val steps = 12

	for (index in 0..steps) {
		val t = index / steps.toFloat()
		val x = t
		val y = when {
			t < 0.30f -> 0.845f + sin(t * 14f) * 0.006f + random.nextFloat(0.005f)
			t < 0.46f -> {
				val slide = (t - 0.30f) / 0.16f
				lerp(0.85f, 0.935f, slide * slide) + random.nextFloat(0.004f)
			}
			else -> {
				val shelf = 0.935f + sin(t * 9f) * 0.005f
				(shelf + random.nextFloat(0.008f)).coerceIn(0.91f, 0.955f)
			}
		}

		points += OutlinePoint(x, y.coerceIn(0.83f, 0.955f))
	}

	return points
}

/**
 * Thatched parasols planted on the bluff crest so the canopy sticks up into the sky.
 * Buried on the sand shelf (same tone as the near fill) they vanish completely.
 */
private fun beachParasols(random: Random, bluff: List<OutlinePoint>, featureScale: Float): List<OutlinePoint> = List(2) { index ->
	val x = (0.08f + index * 0.11f * featureScale + random.nextFloat(0.012f)).coerceAtMost(0.28f)
	val crestY = sampleOutlineY(bluff, x)

	OutlinePoint(x, crestY)
}

/** Seagull anchors above the water; the renderer drifts them and flaps their wings. */
private fun beachGulls(random: Random, featureScale: Float): List<SceneryFauna> = List(4) {
	SceneryFauna(
		kind = SceneryFaunaKind.GULL,
		baseX = random.nextFloat(0.05f, 0.75f),
		baseY = random.nextFloat(0.70f, 0.78f),
		scale = random.nextFloat(0.9f, 1.3f) * featureScale,
		phase = random.nextFloat(TAU),
		speed = random.nextFloat(0.012f, 0.03f)
	)
}

/** Sharks and a whale in the water beyond the beach — fins and a slow breach, not a nature documentary. */
private fun beachMarine(random: Random, featureScale: Float): List<SceneryFauna> {
	val sharks = List(2) {
		SceneryFauna(
			kind = SceneryFaunaKind.SHARK,
			baseX = random.nextFloat(0.5f, 0.8f),
			baseY = SEA_HORIZON + random.nextFloat(0.015f, 0.04f),
			scale = random.nextFloat(2f, 3f) * featureScale,
			phase = random.nextFloat(TAU),
			speed = random.nextFloat(0.008f, 0.018f)
		)
	}

	val whale = SceneryFauna(
		kind = SceneryFaunaKind.WHALE,
		baseX = random.nextFloat(0.53f, 0.7f),
		baseY = SEA_HORIZON + 0.03f,
		scale = random.nextFloat(1.3f, 1.6f) * featureScale,
		phase = random.nextFloat(TAU),
		speed = random.nextFloat(0.004f, 0.008f)
	)

	return sharks + whale
}

/**
 * A mountain skyline of broad massifs — shoulders, soft crests, shallow saddles — not a row of shark-tooth spikes.
 * Each massif gets several points so slopes read as ridges instead of single-tip zigzags.
 */
private fun mountainRange(
	random: Random,
	count: Int,
	crestLow: Float,
	crestHigh: Float,
	saddleLow: Float,
	saddleHigh: Float,
	broadCrests: Boolean
): List<OutlinePoint> {
	val points = mutableListOf(OutlinePoint(0f, random.nextFloat(saddleLow, saddleHigh)))
	val edges = segmentEdges(random, count)

	for (index in 0 until count) {
		val left = edges[index]
		val right = edges[index + 1]
		val span = right - left
		val crest = random.nextFloat(crestLow, crestHigh)
		val saddle = random.nextFloat(saddleLow, saddleHigh)

		// Rising shoulder — halfway up before the crest, so the slope isn't a straight knife-edge.
		val riseX = left + span * random.nextFloat(0.18f, 0.3f)
		val riseY = lerp(points.last().y, crest, random.nextFloat(0.45f, 0.6f))
		points += OutlinePoint(riseX, riseY)

		if (broadCrests && span > 0.12f) {
			// A short ridge top: two close points at nearly the same height, maybe a gentle dip between.
			val crestLeft = left + span * random.nextFloat(0.38f, 0.46f)
			val crestRight = left + span * random.nextFloat(0.55f, 0.65f)
			val crestDip = crest + random.nextFloat(0.008f, 0.018f)
			points += OutlinePoint(crestLeft, crest)
			if (random.nextFloat() < 0.65f) {
				points += OutlinePoint((crestLeft + crestRight) * 0.5f, crestDip)
			}
			points += OutlinePoint(crestRight, crest + random.nextFloat(0.006f))
		} else {
			val summit = left + span * random.nextFloat(0.4f, 0.6f)
			points += OutlinePoint(summit, crest)
			// Soften the tip with a near-side shoulder so it isn't a perfect triangle.
			val fallX = summit + (right - summit) * random.nextFloat(0.35f, 0.55f)
			points += OutlinePoint(fallX, lerp(crest, saddle, random.nextFloat(0.4f, 0.6f)))
		}

		points += OutlinePoint(right, saddle)
	}

	return points
}

/** Gentle rolling farmland hills — broader, softer than mountain peaks. */
private fun rollingHills(random: Random, count: Int, topLow: Float, topHigh: Float): List<OutlinePoint> {
	val points = mutableListOf<OutlinePoint>()
	val edges = segmentEdges(random, count)

	for (index in 0..count) {
		val x = edges[index]
		val crest = random.nextFloat(topLow, topHigh)
		val y = if (index == 0 || index == count) {
			crest + 0.02f
		} else {
			crest
		}

		points += OutlinePoint(x, y.coerceIn(0.76f, 0.94f))
	}

	return points
}

/**
 * The farmhouse as painted near-plane glyphs — a red barn body under a dark roof, chimney rising from behind the roof slope.
 * Replaces the old silhouette cut: a house welded into the hill outline could only ever be hill-colored.
 * Heights derive from the house's own on-screen width (span × aspect converts x units to y units), so the barn keeps its proportions at every aspect.
 */
private fun farmhouseGlyphs(hills: List<OutlinePoint>, random: Random, featureScale: Float, aspectRatio: Float): List<SceneryGlyph> {
	val houseLeft = random.nextFloat(0.56f, 0.7f)
	val houseWidth = 0.08f * featureScale
	val houseRight = (houseLeft + houseWidth).coerceAtMost(0.92f)
	val span = houseRight - houseLeft
	val groundY = sampleOutlineY(hills, (houseLeft + houseRight) * 0.5f)
	val rise = span * aspectRatio
	val eaves = groundY - rise * 0.6f
	val ridge = eaves - rise * 0.55f
	val overhang = span * 0.07f

	// The body sinks a touch below the ground sample so a hill dip can't slide daylight under the barn.
	val body = listOf(
		OutlinePoint(houseLeft, eaves),
		OutlinePoint(houseRight, eaves),
		OutlinePoint(houseRight, groundY + rise * 0.25f),
		OutlinePoint(houseLeft, groundY + rise * 0.25f)
	)
	val roof = listOf(
		OutlinePoint(houseLeft - overhang, eaves),
		OutlinePoint(houseLeft + span * 0.5f, ridge),
		OutlinePoint(houseRight + overhang, eaves)
	)

	val chimneyWidth = (span * 0.1f).coerceAtLeast(0.003f)
	val chimneyLeft = houseLeft + span * 0.68f
	val chimneyRight = (chimneyLeft + chimneyWidth).coerceAtMost(houseRight - span * 0.05f)
	val chimney = listOf(
		OutlinePoint(chimneyLeft, ridge - rise * 0.33f),
		OutlinePoint(chimneyRight, ridge - rise * 0.33f),
		OutlinePoint(chimneyRight, eaves),
		OutlinePoint(chimneyLeft, eaves)
	)

	// Chimney before roof: the roof fill hides its lower half, so the stack reads as rising from behind the slope.
	return listOf(
		SceneryGlyph(body, SceneryMaterial.BARN, SceneryPlane.NEAR),
		SceneryGlyph(chimney, SceneryMaterial.HULL, SceneryPlane.NEAR),
		SceneryGlyph(roof, SceneryMaterial.HULL, SceneryPlane.NEAR)
	)
}

/** A post-and-rail fence along the wheat boundary: posts planted on the [ground] contour with two rails threaded through them. */
private fun pastureFence(random: Random, ground: List<OutlinePoint>, featureScale: Float): List<List<OutlinePoint>> {
	val start = random.nextFloat(0.12f, 0.27f)
	val spacing = 0.028f * featureScale
	val postTops = List(6) { index ->
		val x = start + index * spacing

		OutlinePoint(x, sampleOutlineY(ground, x) - 0.02f)
	}

	val posts = postTops.map { top ->
		listOf(top, OutlinePoint(top.x, top.y + 0.024f))
	}

	val rails = listOf(0.006f, 0.014f).map { drop ->
		postTops.map { OutlinePoint(it.x, it.y + drop) }
	}

	return posts + rails
}

/** A windmill on the near pasture — short tower planted on the hill, hub just above the crest. */
private fun pastureWindmill(random: Random, hills: List<OutlinePoint>, featureScale: Float): SceneryWindmill {
	val hubX = random.nextFloat(0.20f, 0.38f)
	val groundY = sampleOutlineY(hills, hubX)
	val towerH = 0.038f * featureScale
	val hubY = (groundY - towerH).coerceAtLeast(0.78f)

	return SceneryWindmill(hubX = hubX, hubY = hubY, groundY = groundY, scale = featureScale)
}

/**
 * Cuts the windmill tower into the near hill so the mast is part of the ground silhouette.
 * Sails stay as a separate animated draw at [mill.hubX]/[mill.hubY].
 */
private fun attachWindmillTower(hills: List<OutlinePoint>, mill: SceneryWindmill): List<OutlinePoint> {
	val half = (0.009f * mill.scale).coerceAtLeast(0.004f)
	val left = (mill.hubX - half).coerceAtLeast(0.02f)
	val right = (mill.hubX + half).coerceAtMost(0.48f)
	val before = hills.filter { it.x < left }
	val after = hills.filter { it.x > right }

	return before + listOf(
		OutlinePoint(left, mill.groundY),
		OutlinePoint(left, mill.hubY),
		OutlinePoint(right, mill.hubY),
		OutlinePoint(right, mill.groundY)
	) + after
}

/** Linear y sample along a left-to-right outline, for planting the farmhouse on the hill. */
private fun sampleOutlineY(outline: List<OutlinePoint>, x: Float): Float {
	if (outline.isEmpty()) {
		return 0.9f
	}

	if (x <= outline.first().x) {
		return outline.first().y
	}

	if (x >= outline.last().x) {
		return outline.last().y
	}

	for (index in 0 until outline.lastIndex) {
		val left = outline[index]
		val right = outline[index + 1]
		if (x in left.x..right.x) {
			val span = (right.x - left.x).coerceAtLeast(1e-4f)
			val t = (x - left.x) / span

			return lerp(left.y, right.y, t)
		}
	}

	return outline.last().y
}

/** [count] slot boundaries of randomized widths, normalized so the first edge is exactly 0 and the last exactly 1. */
private fun segmentEdges(random: Random, count: Int): FloatArray {
	val widths = FloatArray(count) { random.nextFloat(0.6f, 1.5f) }
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

/**
 * Like [scaled] but growing with the square root of the width gain, so rotating a device keeps the same physical spacing between features.
 * Linear growth packs ~2× the buildings per inch in landscape — a wall, not a skyline.
 */
private fun scaledSparse(baseCount: Int, aspectRatio: Float) = (baseCount * sqrt(aspectRatio / PORTRAIT_ASPECT)).roundToInt().coerceAtLeast(2)

private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

private const val PORTRAIT_ASPECT = 0.46f

/** Where the coast scene's sea meets the sky, as a fraction of screen height. */
private const val SEA_HORIZON = 0.85f

/** Hard ceiling on metropolis window dots so the per-frame scenery pass stays cheap on a live wallpaper. */
private const val MAX_WINDOWS = 72

/** Only the tallest far towers get a red aviation light — sparse on purpose, especially in landscape. */
private const val BEACON_COUNT = 3

/** Summits must rise above this (smaller y = higher) to earn a snowcap; lower peaks stay bare. */
private const val SNOW_SUMMIT_MAX = 0.755f

/** How far a snowcap reaches down from its summit before the snowline cuts it off. */
private const val SNOWCAP_DEPTH = 0.032f

/** Beach boat/umbrellas refuse to shrink below this when the screen goes wide. */
private const val MIN_BEACH_FEATURE_SCALE = 0.7f

private const val TAU = (Math.PI * 2.0).toFloat()
