package xyz.attacktive.weatherd.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.attacktive.weatherd.data.provider.ConfiguredWeatherProvider
import xyz.attacktive.weatherd.domain.provider.WeatherProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class WeatherProviderModule {
	@Binds
	abstract fun bindWeatherProvider(provider: ConfiguredWeatherProvider): WeatherProvider
}
