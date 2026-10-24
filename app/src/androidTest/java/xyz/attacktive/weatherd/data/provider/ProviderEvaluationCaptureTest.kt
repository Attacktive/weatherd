package xyz.attacktive.weatherd.data.provider

import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.repository.SettingsMutation
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

@RunWith(AndroidJUnit4::class)
class ProviderEvaluationCaptureTest {
	@Test
	fun captureProviderEvaluation() {
		val arguments = InstrumentationRegistry.getArguments()
		Assume.assumeTrue(
			"Provider evaluation capture test is opt-in; pass -e providerEvaluation true to execute",
			arguments.getString(ARG_PROVIDER_EVALUATION)?.equals("true", ignoreCase = true) == true
		)

		runBlocking {
			capture(arguments)
		}
	}

	private suspend fun capture(arguments: android.os.Bundle) {
		val parsedArguments = parseArguments(arguments)
		val instrumentation = InstrumentationRegistry.getInstrumentation()
		val context = instrumentation.targetContext

		val entryPoint = EntryPointAccessors.fromApplication(
			context.applicationContext,
			ProviderEvaluationEntryPoint::class.java
		)
		val settingsRepository = entryPoint.settingsRepository()
		val weatherComparisonService = entryPoint.weatherComparisonService()

		val currentSettings = updateAndVerifySettings(
			settingsRepository = settingsRepository,
			parsedArguments = parsedArguments
		)

		val captureStartedAtEpochSeconds = System.currentTimeMillis() / 1000L
		val captureResult = weatherComparisonService.capture(currentSettings)
		assertNotNull("WeatherComparisonService.capture returned null", captureResult)
		val comparisonCapture = checkNotNull(captureResult)

		val reportText = comparisonCapture.toReport(ObservedSky.UNSURE, BuildConfig.VERSION_NAME)
		val captureJsonText = buildCaptureJson(
			parsedArguments = parsedArguments,
			settings = currentSettings,
			capture = comparisonCapture,
			captureStartedAtEpochSeconds = captureStartedAtEpochSeconds
		)

		exportOutputs(
			context = context,
			outputName = parsedArguments.outputName,
			reportText = reportText,
			captureJsonText = captureJsonText
		)
	}

	private data class ParsedArguments(
		val stationId: String,
		val stationLatitude: Double,
		val stationLongitude: Double,
		val outputName: String
	)

	private fun parseArguments(arguments: android.os.Bundle): ParsedArguments {
		val stationId = checkNotNull(arguments.getString(ARG_STATION_ID)) {
			"Missing required instrumentation argument: $ARG_STATION_ID"
		}

		val stationLatitudeString = checkNotNull(arguments.getString(ARG_STATION_LATITUDE)) {
			"Missing required instrumentation argument: $ARG_STATION_LATITUDE"
		}

		val stationLongitudeString = checkNotNull(arguments.getString(ARG_STATION_LONGITUDE)) {
			"Missing required instrumentation argument: $ARG_STATION_LONGITUDE"
		}

		val outputName = checkNotNull(arguments.getString(ARG_OUTPUT_NAME)) {
			"Missing required instrumentation argument: $ARG_OUTPUT_NAME"
		}

		check(stationId.isNotBlank()) {
			"Invalid stationId: $stationId"
		}

		val stationLatitude = checkNotNull(stationLatitudeString.toDoubleOrNull()) {
			"Invalid $ARG_STATION_LATITUDE: $stationLatitudeString"
		}

		val stationLongitude = checkNotNull(stationLongitudeString.toDoubleOrNull()) {
			"Invalid $ARG_STATION_LONGITUDE: $stationLongitudeString"
		}

		check(stationLatitude in -90.0..90.0) {
			"Station latitude out of valid range: $stationLatitude"
		}

		check(stationLongitude in -180.0..180.0) {
			"Station longitude out of valid range: $stationLongitude"
		}

		check(outputName.isNotBlank() && !outputName.contains("..") && !outputName.contains("/") && !outputName.contains("\\")) {
			"Invalid outputName: $outputName"
		}

		return ParsedArguments(
			stationId = stationId,
			stationLatitude = stationLatitude,
			stationLongitude = stationLongitude,
			outputName = outputName
		)
	}

	private suspend fun updateAndVerifySettings(
		settingsRepository: SettingsRepository,
		parsedArguments: ParsedArguments
	): AppSettings {
		settingsRepository.update(
			listOf(
				SettingsMutation.ManualLocation(
					latitude = parsedArguments.stationLatitude,
					longitude = parsedArguments.stationLongitude,
					label = parsedArguments.stationId
				),
				SettingsMutation.SceneSimulatorEnabled(false),
				SettingsMutation.SceneSimulatorActive(false)
			)
		)

		val settings = settingsRepository.settings.first()
		assertFalse("Settings useDeviceLocation must be false", settings.useDeviceLocation)
		assertFalse("Settings sceneSimulatorEnabled must be false", settings.sceneSimulatorEnabled)
		assertFalse("Settings sceneSimulatorActive must be false", settings.sceneSimulatorActive)

		val persistedLatitude = checkNotNull(settings.manualLatitude) {
			"Settings manualLatitude must not be null"
		}
		val persistedLongitude = checkNotNull(settings.manualLongitude) {
			"Settings manualLongitude must not be null"
		}

		assertTrue(
			"Persisted latitude ($persistedLatitude) differs from station latitude (${parsedArguments.stationLatitude})",
			abs(persistedLatitude - parsedArguments.stationLatitude) <= MAX_COORDINATE_ERROR_DEGREES
		)
		assertTrue(
			"Persisted longitude ($persistedLongitude) differs from station longitude (${parsedArguments.stationLongitude})",
			abs(persistedLongitude - parsedArguments.stationLongitude) <= MAX_COORDINATE_ERROR_DEGREES
		)
		assertEquals(parsedArguments.stationId, settings.manualLocationLabel)

		return settings
	}

	private fun buildCaptureJson(
		parsedArguments: ParsedArguments,
		settings: AppSettings,
		capture: WeatherComparisonCapture,
		captureStartedAtEpochSeconds: Long
	): String {
		val jsonElement = buildJsonObject {
			put("station_id", parsedArguments.stationId)
			put("location_source", LOCATION_SOURCE_MANUAL_STATION)
			put("device_location", settings.useDeviceLocation)
			put("location_verified", true)
			put("simulator_active", settings.sceneSimulatorActive)
			put("latitude", checkNotNull(settings.manualLatitude))
			put("longitude", checkNotNull(settings.manualLongitude))
			put("capture_started_at_epoch_seconds", captureStartedAtEpochSeconds)
			put("captured_at_epoch_seconds", capture.capturedAtEpochSeconds)
			put("app_version", BuildConfig.VERSION_NAME)
			put(
				"samples",
				buildJsonArray {
					for (sample in capture.samples) {
						add(
							buildJsonObject {
								put("provider", sample.provider.name)
								put("status", sample.status.name)
								putOrNull("valid_at_epoch_seconds", sample.validAtEpochSeconds)
								putOrNull("condition", sample.condition?.name)
								putOrNull("cloud_total_percent", sample.cloudCoverPercent)
								putOrNull("cloud_low_percent", sample.lowCloudPercent)
								putOrNull("cloud_mid_percent", sample.midCloudPercent)
								putOrNull("cloud_high_percent", sample.highCloudPercent)
								putOrNull("temperature_celsius", sample.temperatureCelsius?.takeIf { it.isFinite() })
								putOrNull("precipitation_millimeters", sample.precipitationMillimeters?.takeIf { it.isFinite() })
							}
						)
					}
				}
			)
		}

		val jsonFormatter = Json {
			prettyPrint = true
		}

		return jsonFormatter.encodeToString(JsonObject.serializer(), jsonElement) + "\n"
	}

	private fun JsonObjectBuilder.putOrNull(key: String, value: String?) = if (value != null) {
		put(key, value)
	} else {
		put(key, JsonNull)
	}

	private fun JsonObjectBuilder.putOrNull(key: String, value: Number?) = if (value != null) {
		put(key, value)
	} else {
		put(key, JsonNull)
	}

	private fun exportOutputs(
		context: Context,
		outputName: String,
		reportText: String,
		captureJsonText: String
	) {
		val evaluationDirectory = File(context.filesDir, "provider-evaluation")
		if (!evaluationDirectory.exists()) {
			check(evaluationDirectory.mkdirs() || evaluationDirectory.exists()) {
				"Failed to create evaluation directory: ${evaluationDirectory.absolutePath}"
			}
		}

		val outputDirectory = File(evaluationDirectory, outputName)
		check(!outputDirectory.exists()) {
			"Output directory already exists: ${outputDirectory.absolutePath}"
		}

		check(outputDirectory.mkdir()) {
			"Failed to create output directory: ${outputDirectory.absolutePath}"
		}

		val reportFile = File(outputDirectory, "report.txt")
		check(reportFile.createNewFile()) {
			"report.txt already exists: ${reportFile.absolutePath}"
		}

		reportFile.writeText(reportText)

		val captureFile = File(outputDirectory, "capture.json")
		check(captureFile.createNewFile()) {
			"capture.json already exists: ${captureFile.absolutePath}"
		}

		captureFile.writeText(captureJsonText)
	}

	private companion object {
		const val ARG_PROVIDER_EVALUATION = "providerEvaluation"
		const val ARG_STATION_ID = "stationId"
		const val ARG_STATION_LATITUDE = "stationLatitude"
		const val ARG_STATION_LONGITUDE = "stationLongitude"
		const val ARG_OUTPUT_NAME = "outputName"

		const val LOCATION_SOURCE_MANUAL_STATION = "MANUAL_STATION"
		const val MAX_COORDINATE_ERROR_DEGREES = 0.00001
	}
}
