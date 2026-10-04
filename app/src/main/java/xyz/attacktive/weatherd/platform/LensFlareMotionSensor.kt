package xyz.attacktive.weatherd.platform

import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.attacktive.weatherd.domain.render.LensFlareTilt

/** One non-wake-up motion subscription and neutral pose for the preview and visible wallpaper engines. */
@Singleton
class LensFlareMotionSensor @Inject constructor(@ApplicationContext context: Context): SensorEventListener {
	private val sensorManager = context.getSystemService(SensorManager::class.java)
	private val sensor = findLensFlareMotionSensor(sensorManager)
	private val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
	private val owners = mutableSetOf<Any>()
	private val tilt = LensFlareTilt()
	private var listening = false

	val offsetX get() = tilt.offsetX
	val offsetY get() = tilt.offsetY

	/** Call on the render thread when a surface gains or loses visible, motion-enabled reflections. */
	@MainThread
	fun setActive(owner: Any, active: Boolean) {
		if (active) {
			if (sensor == null || !owners.add(owner)) {
				return
			}

			if (!listening) {
				tilt.reset()
				listening = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) == true
			}
		} else {
			if (!owners.remove(owner) || owners.isNotEmpty()) {
				return
			}

			if (listening) {
				sensorManager?.unregisterListener(this)
			}

			listening = false
			tilt.reset()
		}
	}

	/** Advances the visible reflection offset once for each rendered frame. */
	@MainThread
	fun advance(frameTimeNanos: Long) {
		if (!listening) {
			return
		}

		tilt.advance(frameTimeNanos)
	}

	override fun onSensorChanged(event: SensorEvent) {
		if (!listening) {
			return
		}

		tilt.update(
			gravityX = event.values[0],
			gravityY = event.values[1],
			gravityZ = event.values[2],
			displayRotation = display?.rotation ?: Surface.ROTATION_0,
			timestampNanos = event.timestamp
		)
	}

	override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Gravity is preferred for stable tilt; accelerometer-only devices need no gyroscope or compass. */
fun lensFlareMotionSupported(context: Context) = findLensFlareMotionSensor(context.getSystemService(SensorManager::class.java)) != null

private fun findLensFlareMotionSensor(manager: SensorManager?) = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY, false) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, false)
