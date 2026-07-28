package com.ayagmar.pimobile.di

import android.content.Context
import com.ayagmar.pimobile.coresessions.FileSessionIndexCache
import com.ayagmar.pimobile.coresessions.SessionIndexRepository
import com.ayagmar.pimobile.hosts.ConnectionDiagnostics
import com.ayagmar.pimobile.hosts.HostProfileStore
import com.ayagmar.pimobile.hosts.HostTokenStore
import com.ayagmar.pimobile.hosts.KeystoreHostTokenStore
import com.ayagmar.pimobile.hosts.SharedPreferencesHostProfileStore
import com.ayagmar.pimobile.sessions.BridgeSessionIndexRemoteDataSource
import com.ayagmar.pimobile.sessions.BridgeSessionShareRemoteDataSource
import com.ayagmar.pimobile.sessions.ClientIdentityStore
import com.ayagmar.pimobile.sessions.RpcSessionController
import com.ayagmar.pimobile.sessions.SessionController
import com.ayagmar.pimobile.sessions.SessionCwdPreferenceStore
import com.ayagmar.pimobile.sessions.SessionSavedStateStore
import com.ayagmar.pimobile.sessions.ShareNavigationCoordinator
import com.ayagmar.pimobile.sessions.SharedPreferencesClientIdentityStore
import com.ayagmar.pimobile.sessions.SharedPreferencesSavedSessionStorage
import com.ayagmar.pimobile.sessions.SharedPreferencesSessionCwdPreferenceStore

class AppGraph(
    context: Context,
) {
    private val appContext = context.applicationContext

    val clientIdentityStore: ClientIdentityStore by lazy {
        SharedPreferencesClientIdentityStore(appContext)
    }

    val sessionController: SessionController by lazy {
        RpcSessionController(clientId = clientIdentityStore.getClientId())
    }

    val sessionCwdPreferenceStore: SessionCwdPreferenceStore by lazy {
        SharedPreferencesSessionCwdPreferenceStore(appContext)
    }

    val sessionSavedStateStore: SessionSavedStateStore by lazy {
        SessionSavedStateStore(SharedPreferencesSavedSessionStorage(appContext))
    }

    val hostProfileStore: HostProfileStore by lazy {
        SharedPreferencesHostProfileStore(appContext)
    }

    val hostTokenStore: HostTokenStore by lazy {
        KeystoreHostTokenStore(appContext)
    }

    val sessionIndexRepository: SessionIndexRepository by lazy {
        SessionIndexRepository(
            remoteDataSource = BridgeSessionIndexRemoteDataSource(hostProfileStore, hostTokenStore),
            cache = FileSessionIndexCache(appContext.cacheDir.toPath().resolve("session-index-cache")),
        )
    }

    val connectionDiagnostics: ConnectionDiagnostics by lazy { ConnectionDiagnostics() }

    val sessionShareRemoteDataSource: BridgeSessionShareRemoteDataSource by lazy {
        BridgeSessionShareRemoteDataSource(hostProfileStore, hostTokenStore)
    }

    /** One delivery owner for the entire application process. */
    val shareNavigationCoordinator: ShareNavigationCoordinator by lazy {
        ShareNavigationCoordinator(
            profileStore = hostProfileStore,
            tokenStore = hostTokenStore,
            shareSource = sessionShareRemoteDataSource,
            sessionController = sessionController,
        )
    }
}
