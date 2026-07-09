package xyz.attacktive.weatherd.di

import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.data.api.GeocodingApiService
import xyz.attacktive.weatherd.data.api.OpenMeteoApiService

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
	@Provides
	@Singleton
	fun provideJson() = Json { ignoreUnknownKeys = true }

	@Provides
	@Singleton
	fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
		.connectTimeout(30, TimeUnit.SECONDS)
		.readTimeout(30, TimeUnit.SECONDS)
		.writeTimeout(30, TimeUnit.SECONDS)
		.apply {
			if (BuildConfig.DEBUG) {
				val loggingInterceptor = HttpLoggingInterceptor()
					.apply {
						level = HttpLoggingInterceptor.Level.BASIC
					}

				addInterceptor(loggingInterceptor)
			}
		}
		.build()

	@Provides
	@Singleton
	fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
		.baseUrl("https://api.open-meteo.com/")
		.client(okHttpClient)
		.addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
		.build()

	@Provides
	@Singleton
	fun provideOpenMeteoApiService(retrofit: Retrofit): OpenMeteoApiService =
		retrofit.create(OpenMeteoApiService::class.java)

	// Geocoding is a separate Open-Meteo host, so it gets its own Retrofit while sharing the OkHttp client and Json.
	@Provides
	@Singleton
	fun provideGeocodingApiService(okHttpClient: OkHttpClient, json: Json): GeocodingApiService = Retrofit.Builder()
		.baseUrl("https://geocoding-api.open-meteo.com/")
		.client(okHttpClient)
		.addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
		.build()
		.create(GeocodingApiService::class.java)
}
