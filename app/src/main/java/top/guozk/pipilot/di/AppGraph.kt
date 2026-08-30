package top.guozk.pipilot.di

import android.content.Context
import top.guozk.pipilot.coresessions.FileSessionIndexCache
import top.guozk.pipilot.coresessions.SessionIndexRepository
import top.guozk.pipilot.hosts.ConnectionDiagnostics
import top.guozk.pipilot.hosts.HostProfileStore
import top.guozk.pipilot.hosts.HostTokenStore
import top.guozk.pipilot.hosts.KeystoreHostTokenStore
import top.guozk.pipilot.hosts.SharedPreferencesHostProfileStore
import top.guozk.pipilot.sessions.BridgeSessionIndexRemoteDataSource
import top.guozk.pipilot.sessions.BridgeSessionShareRemoteDataSource
import top.guozk.pipilot.sessions.ClientIdentityStore
import top.guozk.pipilot.sessions.RpcSessionController
import top.guozk.pipilot.sessions.SessionController
import top.guozk.pipilot.sessions.SessionCwdPreferenceStore
import top.guozk.pipilot.sessions.SessionSavedStateStore
import top.guozk.pipilot.sessions.ShareNavigationCoordinator
import top.guozk.pipilot.sessions.SharedPreferencesClientIdentityStore
import top.guozk.pipilot.sessions.SharedPreferencesSavedSessionStorage
import top.guozk.pipilot.sessions.SharedPreferencesSessionCwdPreferenceStore

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
