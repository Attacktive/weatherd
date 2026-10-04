package xyz.attacktive.weatherd.domain.repository

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import xyz.attacktive.weatherd.domain.model.AppearancePresetSnapshot
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.SavedAppearancePreset
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset

sealed interface AppearancePresetStorageState {
	data class Ready(val presets: List<SavedAppearancePreset>): AppearancePresetStorageState
	data class Unreadable(val reason: AppearancePresetUnreadableReason): AppearancePresetStorageState
}

sealed interface AppearancePresetUnreadableReason {
	data class UnsupportedVersion(val version: Int): AppearancePresetUnreadableReason
	data object Malformed: AppearancePresetUnreadableReason
}

class AppearancePresetNameConflictException(val existingId: String): IllegalArgumentException()

private class AppearancePresetStorageUnreadableException: IllegalStateException()

@Singleton
class AppearancePresetRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
	private val json = Json { ignoreUnknownKeys = true }

	val state: Flow<AppearancePresetStorageState> = dataStore.data.map { preferences ->
		decode(preferences[PRESETS_KEY])
	}

	suspend fun create(name: String, snapshot: AppearancePresetSnapshot): Result<Unit> = mutate { presets ->
		val normalizedName = normalizeName(name)
		val conflict = presets.firstOrNull { sameName(it.name, normalizedName) }
		if (conflict != null) {
			throw AppearancePresetNameConflictException(conflict.id)
		}

		presets + SavedAppearancePreset(id = UUID.randomUUID().toString(), name = normalizedName, snapshot = snapshot)
	}

	suspend fun replace(id: String, name: String, snapshot: AppearancePresetSnapshot): Result<Unit> = mutate { presets ->
		val normalizedName = normalizeName(name)
		val existing = presets.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown preset id")
		val conflict = presets.firstOrNull { it.id != id && sameName(it.name, normalizedName) }
		if (conflict != null) {
			throw AppearancePresetNameConflictException(conflict.id)
		}

		presets.map { preset ->
			if (preset.id == existing.id) {
				existing.copy(name = normalizedName, snapshot = snapshot)
			} else {
				preset
			}
		}
	}

	suspend fun rename(id: String, name: String): Result<Unit> = mutate { presets ->
		val normalizedName = normalizeName(name)
		val existing = presets.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Unknown preset id")
		val conflict = presets.firstOrNull { it.id != id && sameName(it.name, normalizedName) }
		if (conflict != null) {
			throw AppearancePresetNameConflictException(conflict.id)
		}

		presets.map { preset ->
			if (preset.id == existing.id) {
				existing.copy(name = normalizedName)
			} else {
				preset
			}
		}
	}

	suspend fun delete(id: String): Result<Unit> = mutate { presets ->
		presets.filterNot { it.id == id }
	}

	private suspend fun mutate(transform: (List<SavedAppearancePreset>) -> List<SavedAppearancePreset>): Result<Unit> = runCatching {
		dataStore.edit { preferences ->
			val decoded = decode(preferences[PRESETS_KEY])
			if (decoded !is AppearancePresetStorageState.Ready) {
				throw AppearancePresetStorageUnreadableException()
			}

			preferences[PRESETS_KEY] = encode(transform(decoded.presets))
		}
	}

	private fun encode(presets: List<SavedAppearancePreset>) = json.encodeToString(
		StoredEnvelope(
			schemaVersion = APPEARANCE_PRESET_SCHEMA_VERSION,
			presets = presets.map { it.toStored() }
		)
	)

	private fun decode(raw: String?): AppearancePresetStorageState {
		if (raw == null) {
			return AppearancePresetStorageState.Ready(emptyList())
		}

		return try {
			val envelope = json.decodeFromString<StoredEnvelope>(raw)
			when (envelope.schemaVersion) {
				APPEARANCE_PRESET_SCHEMA_VERSION -> decodeVersionOne(envelope)
				else -> AppearancePresetStorageState.Unreadable(AppearancePresetUnreadableReason.UnsupportedVersion(envelope.schemaVersion))
			}
		} catch (_: Exception) {
			AppearancePresetStorageState.Unreadable(AppearancePresetUnreadableReason.Malformed)
		}
	}

	private fun decodeVersionOne(envelope: StoredEnvelope): AppearancePresetStorageState {
		val presets = envelope.presets.map { it.toDomain() }
		require(presets.map { it.id }.distinct().size == presets.size)
		require(presets.map { it.name.lowercase() }.distinct().size == presets.size)

		return AppearancePresetStorageState.Ready(presets)
	}

	private fun normalizeName(name: String): String {
		val normalized = name.trim()
		require(normalized.isNotEmpty())
		require(normalized.length <= APPEARANCE_PRESET_NAME_MAX_LENGTH)

		return normalized
	}

	private fun sameName(first: String, second: String) = first.equals(second, ignoreCase = true)
}

@Serializable
private data class StoredEnvelope(
	val schemaVersion: Int,
	val presets: List<StoredPreset>
)

@Serializable
private data class StoredPreset(
	val id: String,
	val name: String,
	val snapshot: StoredSnapshot
) {
	fun toDomain(): SavedAppearancePreset {
		require(id.isNotBlank())
		val normalizedName = name.trim()
		require(normalizedName.isNotEmpty())
		require(normalizedName.length <= APPEARANCE_PRESET_NAME_MAX_LENGTH)

		return SavedAppearancePreset(id = id, name = normalizedName, snapshot = snapshot.toDomain())
	}
}

@Serializable
private data class StoredSnapshot(
	val backdropScene: String,
	val showWeatherLabel: Boolean,
	val showLocationLabel: Boolean,
	val precipitationIntensityScale: Float,
	val windIntensityScale: Float,
	val cloudIntensityScale: Float,
	val cloudSizeScale: Float,
	val cloudCountScale: Float,
	val cloudContrastScale: Float,
	val skyBrightnessScale: Float,
	val nightBrightnessScale: Float,
	val skySaturationScale: Float,
	val skyColorPreset: String,
	val sunVisible: Boolean,
	val moonVisible: Boolean,
	val sunSizeScale: Float,
	val sunColorPreset: String,
	val lensFlareEnabled: Boolean,
	val lensFlareMotionEnabled: Boolean
) {
	fun toDomain() = AppearancePresetSnapshot(
		backdropScene = enumValue(backdropScene, BackdropScene.entries),
		showWeatherLabel = showWeatherLabel,
		showLocationLabel = showLocationLabel,
		precipitationIntensityScale = precipitationIntensityScale,
		windIntensityScale = windIntensityScale,
		cloudIntensityScale = cloudIntensityScale,
		cloudSizeScale = cloudSizeScale,
		cloudCountScale = cloudCountScale,
		cloudContrastScale = cloudContrastScale,
		skyBrightnessScale = skyBrightnessScale,
		nightBrightnessScale = nightBrightnessScale,
		skySaturationScale = skySaturationScale,
		skyColorPreset = enumValue(skyColorPreset, SkyColorPreset.entries),
		sunVisible = sunVisible,
		moonVisible = moonVisible,
		sunSizeScale = sunSizeScale,
		sunColorPreset = enumValue(sunColorPreset, SunColorPreset.entries),
		lensFlareEnabled = lensFlareEnabled,
		lensFlareMotionEnabled = lensFlareMotionEnabled
	)
}

private fun SavedAppearancePreset.toStored() = StoredPreset(
	id = id,
	name = name,
	snapshot = snapshot.toStored()
)

private fun AppearancePresetSnapshot.toStored() = StoredSnapshot(
	backdropScene = backdropScene.name,
	showWeatherLabel = showWeatherLabel,
	showLocationLabel = showLocationLabel,
	precipitationIntensityScale = precipitationIntensityScale,
	windIntensityScale = windIntensityScale,
	cloudIntensityScale = cloudIntensityScale,
	cloudSizeScale = cloudSizeScale,
	cloudCountScale = cloudCountScale,
	cloudContrastScale = cloudContrastScale,
	skyBrightnessScale = skyBrightnessScale,
	nightBrightnessScale = nightBrightnessScale,
	skySaturationScale = skySaturationScale,
	skyColorPreset = skyColorPreset.name,
	sunVisible = sunVisible,
	moonVisible = moonVisible,
	sunSizeScale = sunSizeScale,
	sunColorPreset = sunColorPreset.name,
	lensFlareEnabled = lensFlareEnabled,
	lensFlareMotionEnabled = lensFlareMotionEnabled
)

private fun <T : Enum<T>> enumValue(name: String, values: Iterable<T>) = values.firstOrNull { it.name == name } ?: throw IllegalArgumentException("Unknown enum value")

/** Bump whenever persisted preset snapshot fields are added, removed, renamed, or change meaning. */
internal const val APPEARANCE_PRESET_SCHEMA_VERSION = 1
internal const val APPEARANCE_PRESET_NAME_MAX_LENGTH = 40
internal const val APPEARANCE_PRESETS_KEY_NAME = "appearance_presets_json"
private val PRESETS_KEY = stringPreferencesKey(APPEARANCE_PRESETS_KEY_NAME)
