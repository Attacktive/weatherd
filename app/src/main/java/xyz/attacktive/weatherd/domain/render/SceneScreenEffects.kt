package xyz.attacktive.weatherd.domain.render

import android.graphics.Canvas

/**
 * Screen-anchored weather effects that draw after any world-space translation has been restored.
 * Glass droplets stay fixed to the visible viewport and the existing overlay labels remain the topmost renderer-owned content.
 */
internal class SceneScreenEffects(private val renderer: SceneRenderer) {
	private val glassDropletLayer = GlassDropletLayer()

	fun render(canvas: Canvas, width: Int, height: Int, params: SceneParams, timeSeconds: Float) {
		glassDropletLayer.draw(canvas, width.toFloat(), height.toFloat(), params, timeSeconds)
		renderer.renderOverlayLabels(canvas, width, height, params)
	}
}
