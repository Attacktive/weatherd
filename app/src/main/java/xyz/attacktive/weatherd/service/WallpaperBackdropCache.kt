package xyz.attacktive.weatherd.service

import xyz.attacktive.weatherd.domain.render.SceneParams

internal data class CachedWallpaperBackdrop<T: Any>(
	val value: T,
	val width: Int,
	val height: Int,
	val signature: SceneParams
)

/**
 * Holds the live wallpaper's cached static backdrop and optional outgoing crossfade snapshot.
 * Parameterizing on [T] keeps lifecycle reuse, invalidation, and transition rules testable without Android bitmap allocation.
 */
internal class WallpaperBackdropCache<T: Any> {
	var current: CachedWallpaperBackdrop<T>? = null
		private set
	var previous: CachedWallpaperBackdrop<T>? = null
		private set
	var fadeStartSeconds: Float = 0f
		private set

	fun onSurfaceChanged(sceneWidth: Int, surfaceHeight: Int) {
		val cached = current
		if (cached == null || !shouldRetainWallpaperBackdrop(cached.width, cached.height, sceneWidth, surfaceHeight)) {
			current = null
			previous = null
		}
	}

	fun onSurfaceDestroyed() {
		previous = null
	}

	fun resolve(
		sceneWidth: Int,
		surfaceHeight: Int,
		signature: SceneParams,
		rasterize: () -> T
	): CachedWallpaperBackdrop<T> {
		val cached = current
		if (cached != null && shouldRetainWallpaperBackdrop(cached.width, cached.height, sceneWidth, surfaceHeight) && cached.signature == signature) {
			return cached
		}

		val fresh = CachedWallpaperBackdrop(rasterize(), sceneWidth, surfaceHeight, signature)
		current = fresh

		return fresh
	}

	fun updateTransition(
		outgoing: CachedWallpaperBackdrop<T>?,
		incoming: CachedWallpaperBackdrop<T>,
		timeSeconds: Float,
		motionActive: Boolean
	) {
		if (!motionActive) {
			previous = null

			return
		}

		if (shouldCrossfadeBackdrop(outgoing, incoming)) {
			// The scene flipped: keep the outgoing backdrop around and ease the new scene in over it.
			previous = outgoing
			fadeStartSeconds = timeSeconds

			return
		}

		if (incoming !== outgoing) {
			// A scrolling toggle or size change alters the virtual viewport; crossfading bitmaps with different viewports would slide the outgoing scene under the new one.
			previous = null
		}
	}

	fun clearPrevious() {
		previous = null
	}
}

internal fun shouldRetainWallpaperBackdrop(cachedWidth: Int, cachedHeight: Int, sceneWidth: Int, surfaceHeight: Int) =
	cachedWidth == sceneWidth && cachedHeight == surfaceHeight

internal fun <T: Any> shouldCrossfadeBackdrop(outgoing: CachedWallpaperBackdrop<T>?, current: CachedWallpaperBackdrop<T>) =
	outgoing != null && outgoing !== current && outgoing.width == current.width && outgoing.height == current.height
