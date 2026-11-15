package xyz.attacktive.weatherd.domain.render

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.TreeSeason

/** Contributor artwork prepared offline; both surfaces share immutable, season-specific source bitmaps. */
internal class TreescapeLayer(private val resources: Resources) {
	private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val meadowDestination = RectF()
	private val treeDestination = RectF()
	private val lighting = SceneryLighting(SceneryPlane.NEAR)
	private var cachedWidth = 0f
	private var cachedHeight = 0f

	fun isTextureReady(season: TreeSeason) = prepared[concreteSeason(season)] != null

	fun prewarm(season: TreeSeason) {
		val selected = concreteSeason(season)
		if (isTextureReady(selected)) {
			return
		}

		synchronized(TreescapeLayer::class.java) {
			if (isTextureReady(selected)) {
				return
			}

			val artwork = when (selected) {
				TreeSeason.AUTO, TreeSeason.SPRING -> decodeArtwork(R.drawable.treescape_meadow_spring, R.drawable.treescape_trees_spring)
				TreeSeason.SUMMER -> decodeArtwork(R.drawable.treescape_meadow_summer, R.drawable.treescape_trees_summer)
				TreeSeason.AUTUMN -> decodeArtwork(R.drawable.treescape_meadow_autumn, R.drawable.treescape_trees_autumn)
				TreeSeason.WINTER -> decodeArtwork(R.drawable.treescape_meadow_winter, R.drawable.treescape_trees_winter)
			}

			prepared = prepared + (selected to artwork)
		}
	}

	private fun decodeArtwork(meadowResource: Int, treeResource: Int): Artwork {
		val options = BitmapFactory.Options().apply { inScaled = false }

		return Artwork(
			checkNotNull(BitmapFactory.decodeResource(resources, meadowResource, options)),
			checkNotNull(BitmapFactory.decodeResource(resources, treeResource, options))
		)
	}

	fun draw(canvas: Canvas, width: Float, height: Float, params: SceneParams) {
		if (params.backdropScene != BackdropScene.TREESCAPE || width <= 0f || height <= 0f) {
			return
		}

		val artwork = prepared[concreteSeason(params.treeSeason)] ?: return
		if (width != cachedWidth || height != cachedHeight) {
			val meadowScale = width / artwork.meadow.width
			val meadowHeight = artwork.meadow.height * meadowScale
			val meadowTop = height - min(meadowHeight, height * 0.34f)
			meadowDestination.set(0f, meadowTop, width, meadowTop + meadowHeight)

			val treeWidth = min(width, height * 0.78f)
			val treeScale = treeWidth / artwork.trees.width
			val treeHeight = artwork.trees.height * treeScale
			val treeLeft = (width - treeWidth) * 0.5f
			// Source-space horizon and root inset keep trunks planted while wide viewports crop the meadow, not the crowns.
			val treeBottom = meadowTop + 32f * meadowScale + 36f * treeScale
			treeDestination.set(treeLeft, treeBottom - treeHeight, treeLeft + treeWidth, treeBottom)
			cachedWidth = width
			cachedHeight = height
		}

		lighting.applyTo(paint, params)
		canvas.drawBitmap(artwork.meadow, null, meadowDestination, paint)
		canvas.drawBitmap(artwork.trees, null, treeDestination, paint)
	}

	private class Artwork(val meadow: Bitmap, val trees: Bitmap)

	private companion object {
		@Volatile private var prepared = emptyMap<TreeSeason, Artwork>()

		fun concreteSeason(season: TreeSeason) = if (season == TreeSeason.AUTO) TreeSeason.SPRING else season
	}
}
