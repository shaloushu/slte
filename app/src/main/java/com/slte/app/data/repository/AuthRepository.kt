package com.slte.app.data.repository

import com.slte.app.data.local.CredentialStore
import com.slte.app.data.local.SessionStore
import com.slte.app.data.remote.api.AuthApi
import com.slte.app.data.remote.api.dto.LoginResponseDto
import com.slte.app.domain.model.EmailCodePurpose
import com.slte.app.domain.model.PasswordChangeOutcome
import com.slte.app.domain.model.RegisterConfig
import com.slte.app.domain.model.SessionNotice
import com.slte.app.domain.model.SessionState
import com.slte.app.domain.model.User
import com.slte.app.domain.repository.AuthRepository
import com.slte.app.domain.repository.SessionRepository
import com.slte.app.domain.repository.SubscribeRepository
import com.slte.app.utils.AppLog
import com.slte.app.utils.sanitizeLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
@Singleton
class AuthRepositoryImpl
@Inject
constructor(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    private val credentialStore: CredentialStore,
    private val sessionManager: SessionRepository,
    private val subscribeRepository: SubscribeRepository,
) : AuthRepository {

    override val sessionState: StateFlow<SessionState>
        get() = sessionManager.sessionState

    override val sessionNotices: SharedFlow<SessionNotice>
        get() = sessionManager.sessionNotices

    override fun saveCredentials(
        email: String,
        password: String,
    ) = credentialStore.save(email, password)

    override fun clearCredentials() = credentialStore.clear()

    override fun clearSavedPassword() = credentialStore.clearPassword()

    override fun savedEmail(): String? = credentialStore.getSavedEmail()

    override fun savedPassword(): String? = credentialStore.getSavedPassword()

    private val revokeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 换账号才清缓存：同账号重新登录（token 刷新）保留本地数据，避免"无套餐/空节点"闪动。
     * 必须在 setLoggedIn 之前调用——setLoggedIn 会把 owner 写成新账号。
     */
    private fun invalidateCacheIfAccountChanged(email: String) {
        val owner = sessionStore.cacheOwner()
        if (owner == null || owner != email.trim().lowercase()) {
            subscribeRepository.invalidateCache()
        }
    }

    override suspend fun login(
        email: String,
        password: String,
    ): Result<User> = runApi {
        val response = authApi.login(email.trim(), password)
        val user = response.toDomainUser(email.trim())
        invalidateCacheIfAccountChanged(email)
        sessionManager.setLoggedIn(user)
        user
    }

    override suspend fun register(
        email: String,
        password: String,
        emailCode: String?,
        inviteCode: String?,
    ): Result<User> = runApi {
        val response = authApi.register(email.trim(), password, emailCode, inviteCode)
        val user = response.toDomainUser(email.trim())
        invalidateCacheIfAccountChanged(email)
        sessionManager.setLoggedIn(user)
        user
    }

    private var cachedRegisterConfig: RegisterConfig? = null

    /** 注册页直接读这份配置：登录页进注册页前刚拉过，避免重复请求，也不靠路由传参耦合。 */
    override fun cachedRegisterConfig(): RegisterConfig? = cachedRegisterConfig

    override suspend fun fetchRegisterConfig(): Result<RegisterConfig> = runApi {
        authApi.fetchRegisterConfig().also { cachedRegisterConfig = it }
    }

    override suspend fun forgotPassword(
        email: String,
        emailCode: String,
        newPassword: String,
    ): Result<Unit> = runApi {
        authApi.forgotPassword(email.trim(), emailCode.trim(), newPassword)
    }

    override suspend fun sendEmailCode(
        email: String,
        purpose: EmailCodePurpose,
    ): Result<Unit> = runApi {
        authApi.sendEmailCode(email.trim(), purpose)
    }

    override suspend fun updateRemindExpire(enabled: Boolean): Result<Unit> = runApi {
        authApi.updateRemindExpire(enabled)
        val current = sessionManager.sessionState.value as? SessionState.LoggedIn
        if (current != null) {
            val updated = current.user.copy(remindExpire = if (enabled) 1 else 0)
            sessionManager.updateUser(updated)
            subscribeRepository.updateCachedUserInfo(updated)
        }
    }

    override suspend fun updateRemindTraffic(enabled: Boolean): Result<Unit> = runApi {
        authApi.updateRemindTraffic(enabled)
        val current = sessionManager.sessionState.value as? SessionState.LoggedIn
        if (current != null) {
            val updated = current.user.copy(remindTraffic = if (enabled) 1 else 0)
            sessionManager.updateUser(updated)
            subscribeRepository.updateCachedUserInfo(updated)
        }
    }

    override suspend fun changePassword(
        oldPassword: String,
        newPassword: String,
    ): Result<PasswordChangeOutcome> = runApi {
        authApi.changePassword(oldPassword, newPassword)
        val email = (sessionManager.sessionState.value as? SessionState.LoggedIn)?.user?.email ?: sessionStore.getEmail()
        if (email != null && credentialStore.getSavedEmail()?.equals(email, ignoreCase = true) == true) {
            // The server change is already committed; keep the remembered password in sync
            // even if the follow-up login has a transient network failure.
            credentialStore.save(email.trim(), newPassword)
        }
        val current = sessionManager.sessionState.value as? SessionState.LoggedIn
        if (current == null) return@runApi PasswordChangeOutcome.SIGN_IN_REQUIRED

        val response =
            try {
                authApi.login(current.user.email.trim(), newPassword)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionManager.clearSession(notice = SessionNotice.PASSWORD_CHANGED_REQUIRES_SIGN_IN)
                return@runApi PasswordChangeOutcome.SIGN_IN_REQUIRED
            }
        val user = response.toDomainUser(current.user.email.trim())
        invalidateCacheIfAccountChanged(current.user.email)
        sessionManager.setLoggedIn(user)
        PasswordChangeOutcome.SESSION_RESTORED
    }

    override fun logout() {
        val authData = sessionStore.getAuthData()
        if (authData != null) {
            revokeScope.launch {
                runCatching {
                    val current = sessionStore.getAuthData()
                    if (current == null || current == authData) {
                        authApi.revokeActiveSessions(authData)
                    }
                }.onFailure { e ->
                    AppLog.w("SLTE-Repo", "revokeActiveSessions failed: ${sanitizeLog(e.message ?: "Unknown")}")
                }
            }
        }
        sessionManager.clearSession()
    }

    private fun LoginResponseDto.toDomainUser(email: String) = User(
        id = token,
        displayName = email,
        email = email,
        authData = authData,
        subscribeToken = token,
    )
}
