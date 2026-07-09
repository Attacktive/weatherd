package xyz.attacktive.weatherd.di

import javax.inject.Singleton
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

private val Context.dataStore by preferencesDataStore(name = "weatherd_settings")

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
	@Provides
	@Singleton
	fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore
}
