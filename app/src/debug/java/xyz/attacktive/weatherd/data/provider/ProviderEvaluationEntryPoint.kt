package xyz.attacktive.weatherd.data.provider

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProviderEvaluationEntryPoint {
	fun settingsRepository(): SettingsRepository
	fun weatherComparisonService(): WeatherComparisonService
}
