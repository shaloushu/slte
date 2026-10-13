package com.slte.app.data.local

import com.slte.app.data.remote.AuthInterceptor
import com.slte.app.data.remote.FallbackDns
import com.slte.app.data.remote.api.AuthApi
import com.slte.app.data.remote.api.SessionProbeResult
import com.slte.app.data.remote.config.CrispManager
import com.slte.app.domain.model.SessionNotice
import com.slte.app.domain.model.SessionState
import com.slte.app.domain.model.User
import com.slte.app.domain.repository.SessionRepository
import com.slte.app.kernel.KernelBridge
import com.slte.app.kernel.KernelConfig
import com.slte.app.utils.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

@Singleton
class SessionManager
@Inject
constructor(
    private val sessionStore: SessionStore,
    private val authInterceptor: AuthInterceptor,
    private val authApi: AuthApi,
    private val crispManager: CrispManager,
    private val kernelManager: KernelBridge,
    private val kernelConfig: KernelConfig,
    private val fallbackDns: FallbackDns,
) : SessionRepository {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Loading)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _logoutEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val logoutEvents: SharedFlow<Unit> = _logoutEvents.asSharedFlow()

    private val _sessionNotices = MutableSharedFlow<SessionNotice>(extraBufferCapacity = 1)
    override val sessionNotices: SharedFlow<SessionNotice> = _sessionNotices.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val probeMutex = Mutex()

    private var lastProbeAtMs = 0L

    /** 单测可注入时钟，控制探测间隔判定。 */
    internal var nowMillis: () -> Long = System::currentTimeMillis

    init {
        restoreSession()
        observeAuthErrors()
    }

    private fun restoreSession() {
        if (sessionStore.hasSession()) {
            val authData = sessionStore.getAuthData()
            val email = sessionStore.getEmail()
            val subscribeToken = sessionStore.getSubscribeToken()
            if (authData != null && email != null && subscribeToken != null) {
                val user =
                    sessionStore.getUserInfo()?.takeIf { it.subscribeToken == subscribeToken }
                        ?: User(
                            id = subscribeToken,
                            displayName = email,
                            email = email,
                            authData = authData,
                            subscribeToken = subscribeToken,
                        )
                _sessionState.value = SessionState.LoggedIn(user)
                maybeProbeSession()
            } else {
                sessionStore.clear()
                _sessionState.value = SessionState.LoggedOut
            }
        } else {
            sessionStore.clear()
            _sessionState.value = SessionState.LoggedOut
        }
    }

    private fun observeAuthErrors() {
        scope.launch {
            authInterceptor.authErrorEvents.collect { expiredAuthData ->
                if (sessionStore.getAuthData() == expiredAuthData) {
                    clearSession(expired = true)
                }
            }
        }
        scope.launch {
            authInterceptor.sessionSuspectEvents.collect { maybeProbeSession() }
        }
    }

    /** 冷启动与订阅疑似失效时用 user/info 探测会话；仅服务端明确拒绝（401/403）才清理，网络异常保持登录。 */
    private fun maybeProbeSession() {
        scope.launch {
            if (!probeMutex.tryLock()) return@launch
            try {
                if (_sessionState.value !is SessionState.LoggedIn) return@launch
                val now = nowMillis()
                // 0 表示尚未探测过，不设间隔限制
                if (lastProbeAtMs != 0L && now - lastProbeAtMs < PROBE_MIN_INTERVAL_MS) return@launch
                val authData = sessionStore.getAuthData() ?: return@launch
                lastProbeAtMs = now
                val result = authApi.probeSession()
                if (result == SessionProbeResult.INVALID && sessionStore.getAuthData() == authData) {
                    AppLog.w("SLTE-Session", "会话探测确认失效，回到登录页")
                    clearSession(expired = true)
                }
            } finally {
                probeMutex.unlock()
            }
        }
    }

    override fun setLoggedIn(user: User) {
        _sessionState.value = SessionState.LoggedIn(user)
        sessionStore.save(user.authData, user.email, user.subscribeToken)
        sessionStore.saveUserInfo(user)
    }

    override fun updateUser(user: User) {
        if (_sessionState.value is SessionState.LoggedIn) {
            _sessionState.value = SessionState.LoggedIn(user)
        }
    }

    override fun clearSession(
        expired: Boolean,
        notice: SessionNotice?,
    ) {
        val email = sessionStore.getEmail()
        scope.launch {
            kernelConfig.deleteAccountProfiles(email)
            kernelManager.stopVpn()
            fallbackDns.clearCache()
        }
        _sessionState.value = SessionState.LoggedOut
        sessionStore.clear()
        crispManager.clearUser()
        _logoutEvents.tryEmit(Unit)
        val sessionNotice = notice ?: if (expired) SessionNotice.EXPIRED else null
        sessionNotice?.let(_sessionNotices::tryEmit)
    }

    private companion object {
        /** 探测最短间隔：订阅失败重试等连续触发时只确认一次。 */
        const val PROBE_MIN_INTERVAL_MS = 30_000L
    }
}
