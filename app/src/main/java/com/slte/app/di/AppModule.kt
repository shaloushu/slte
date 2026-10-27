package com.slte.app.di

import com.slte.app.data.local.LocaleStore
import com.slte.app.data.local.SessionManager
import com.slte.app.data.local.SessionStore
import com.slte.app.data.local.ThemePreference
import com.slte.app.data.remote.ApiBackend
import com.slte.app.data.remote.BackendAdapterFactory
import com.slte.app.data.remote.FallbackDns
import com.slte.app.data.remote.SubscribeSourceImpl
import com.slte.app.data.remote.api.AuthApi
import com.slte.app.data.remote.config.CrispManager
import com.slte.app.data.remote.config.RemoteConfig
import com.slte.app.data.repository.AuthRepositoryImpl
import com.slte.app.data.repository.GiftCardRepositoryImpl
import com.slte.app.data.repository.InviteRepositoryImpl
import com.slte.app.data.repository.OrderRepositoryImpl
import com.slte.app.data.repository.RemoteUpdateRepository
import com.slte.app.data.repository.ServerRepositoryImpl
import com.slte.app.data.repository.SubscribeRepositoryImpl
import com.slte.app.domain.repository.AuthRepository
import com.slte.app.domain.repository.DnsCache
import com.slte.app.domain.repository.GiftCardRepository
import com.slte.app.domain.repository.InviteRepository
import com.slte.app.domain.repository.LocaleRepository
import com.slte.app.domain.repository.OrderRepository
import com.slte.app.domain.repository.ServerRepository
import com.slte.app.domain.repository.SessionRepository
import com.slte.app.domain.repository.SubscribeRepository
import com.slte.app.domain.repository.ThemeRepository
import com.slte.app.domain.repository.UpdateRepository
import com.slte.app.domain.service.SupportChat
import com.slte.app.kernel.AppRemoteConfig
import com.slte.app.kernel.SpeedResultStore
import com.slte.app.kernel.SubscribeSource
import com.slte.app.kernel.api.KernelApi
import com.slte.app.kernel.factory.DefaultKernelFactory
import com.slte.app.kernel.factory.KernelFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun provideAppRemoteConfig(remoteConfig: RemoteConfig): AppRemoteConfig = remoteConfig

    @Provides
    @Singleton
    fun provideSpeedResultStore(store: SessionStore): SpeedResultStore = store

    @Provides
    @Singleton
    fun provideSessionRepository(manager: SessionManager): SessionRepository = manager

    @Provides
    @Singleton
    fun provideLocaleRepository(store: LocaleStore): LocaleRepository = store

    @Provides
    @Singleton
    fun provideThemeRepository(preference: ThemePreference): ThemeRepository = preference

    @Provides
    @Singleton
    fun provideDnsCache(dns: FallbackDns): DnsCache = dns

    @Provides
    @Singleton
    fun provideSupportChat(manager: CrispManager): SupportChat = manager

    @Provides
    @Singleton
    fun provideAuthRepository(repository: AuthRepositoryImpl): AuthRepository = repository

    @Provides
    @Singleton
    fun provideKernelFactory(impl: DefaultKernelFactory): KernelFactory = impl

    @Provides
    @Singleton
    fun provideKernelApi(factory: KernelFactory): KernelApi = factory.current()

    @Provides
    @Singleton
    fun provideSubscribeSource(source: SubscribeSourceImpl): SubscribeSource = source

    @Provides
    @Singleton
    fun provideSubscribeRepository(repository: SubscribeRepositoryImpl): SubscribeRepository = repository

    @Provides
    @Singleton
    fun provideUpdateRepository(repository: RemoteUpdateRepository): UpdateRepository = repository

    @Provides
    @Singleton
    fun provideOrderRepository(repository: OrderRepositoryImpl): OrderRepository = repository

    @Provides
    @Singleton
    fun provideServerRepository(repository: ServerRepositoryImpl): ServerRepository = repository

    @Provides
    @Singleton
    fun provideInviteRepository(repository: InviteRepositoryImpl): InviteRepository = repository

    @Provides
    @Singleton
    fun provideGiftCardRepository(repository: GiftCardRepositoryImpl): GiftCardRepository = repository

    @Provides
    @Singleton
    fun provideAuthApi(
        backendAdapterFactory: BackendAdapterFactory,
        remoteConfig: RemoteConfig,
    ): AuthApi {
        val cfg = remoteConfig.data
        val backend =
            ApiBackend(
                type = cfg.apiType,
                baseUrl = cfg.apiBaseUrl,
            )
        return backendAdapterFactory.createAuthApi(backend)
    }
}
