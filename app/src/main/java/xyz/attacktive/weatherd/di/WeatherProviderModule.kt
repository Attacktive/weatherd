package xyz.attacktive.weatherd.di

import javax.inject.Qualifier
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.attacktive.weatherd.data.provider.OpenMeteoWeatherProvider
import xyz.attacktive.weatherd.domain.provider.WeatherProvider

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultWeatherProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class WeatherProviderModule {
	@Binds
	@Singleton
	@DefaultWeatherProvider
	abstract fun bindDefaultWeatherProvider(provider: OpenMeteoWeatherProvider): WeatherProvider
}
