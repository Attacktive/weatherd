package xyz.attacktive.weatherd.di

import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import android.content.Context
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.data.api.GeocodingApiService
import xyz.attacktive.weatherd.data.api.MetNoApiService
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
	fun provideOpenMeteoApiService(retrofit: Retrofit): OpenMeteoApiService = retrofit.create(OpenMeteoApiService::class.java)

	@Provides
	@Singleton
	fun provideMetNoApiService(@ApplicationContext context: Context, okHttpClient: OkHttpClient, json: Json): MetNoApiService = createMetNoApiService(
		okHttpClient,
		json,
		MET_NO_BASE_URL,
		Cache(context.cacheDir.resolve(MET_NO_CACHE_DIRECTORY), MET_NO_CACHE_BYTES)
	)

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

internal fun createMetNoApiService(okHttpClient: OkHttpClient, json: Json, baseUrl: String, cache: Cache? = null): MetNoApiService {
	val clientBuilder = okHttpClient.newBuilder()
	if (cache != null) {
		clientBuilder.cache(cache)
	}

	val metNoClient = clientBuilder
		.addInterceptor { chain ->
			val request = chain.request().newBuilder()
				.header("User-Agent", "weatherd/${BuildConfig.VERSION_NAME} github.com/Attacktive/weatherd")
				.build()

			chain.proceed(request)
		}
		.build()

	return Retrofit.Builder()
		.baseUrl(baseUrl)
		.client(metNoClient)
		.addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
		.build()
		.create(MetNoApiService::class.java)
}

internal const val MET_NO_BASE_URL = "https://api.met.no/"
private const val MET_NO_CACHE_DIRECTORY = "met-no-http"
private const val MET_NO_CACHE_BYTES = 10L * 1024L * 1024L
