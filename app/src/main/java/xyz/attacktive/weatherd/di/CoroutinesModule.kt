package xyz.attacktive.weatherd.di

import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Marks the [CoroutineScope] that lives as long as the process, as opposed to one tied to a screen or to a service connection. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
	/**
	 * A scope for work that has to finish even though whatever asked for it is gone, such as copying a picked photo into the store after the user has left Settings.
	 * Nothing cancels it: it is a singleton in the [SingletonComponent], so its lifetime is the process's.
	 * [SupervisorJob] keeps one failed job from taking the scope down with it, and [Dispatchers.Default] is only the scope's default -- the work it hosts hops to its own dispatcher.
	 */
	@Provides
	@Singleton
	@ApplicationScope
	fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
