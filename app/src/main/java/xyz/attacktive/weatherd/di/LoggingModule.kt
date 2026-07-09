package xyz.attacktive.weatherd.di

import javax.inject.Singleton
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.attacktive.weatherd.util.AppLogger
import xyz.attacktive.weatherd.util.LogcatLogger

@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {
	@Provides
	@Singleton
	fun provideAppLogger(): AppLogger = LogcatLogger()
}
