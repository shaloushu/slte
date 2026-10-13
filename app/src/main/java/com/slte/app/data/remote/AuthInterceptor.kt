package com.slte.app.data.remote

import com.slte.app.BuildConfig
import com.slte.app.data.local.SessionStore
import com.slte.app.data.remote.config.AllowedHosts
import com.slte.app.utils.AppLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.Interceptor
import okhttp3.Response

internal object AuthRules {

    const val AUTH_API_PREFIX = ApiPaths.AUTH

    const val SUBSCRIBE_TOKEN_PARAM = "token"

    data class Decision(

        val attachToken: Boolean,

        val clearSession: Boolean,
    )

    fun isAuthPath(encodedPath: String): Boolean = encodedPath.startsWith(AUTH_API_PREFIX)

    fun isSubscribePath(encodedPath: String): Boolean = encodedPath.endsWith(BuildConfig.SUBSCRIBE_PATH)

    fun isAuthFailureBodyText(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        if (matchesAuthFailureKeyword(body)) return true
        // 面板消息会被编码成 \uXXXX 转义中文，直接匹配会漏判，解码后再匹配一次
        return matchesAuthFailureKeyword(decodeUnicodeEscapes(body))
    }

    fun isAuthFailureBody(body: String?): Boolean = body.isNullOrBlank() || isAuthFailureBodyText(body)

    /** 订阅下载带本会话凭据返回 401/403：视为疑似失效，交给上层探测确认。CDN/WAF 的 403 不带凭据，不会命中。 */
    fun isSubscribeCredentialFailure(
        responseCode: Int,
        encodedPath: String,
        queryToken: String?,
        authHeader: String?,
        currentAuthData: String?,
        currentSubscribeToken: String?,
    ): Boolean {
        if (responseCode != 401 && responseCode != 403) return false
        val carriedAuthData = currentAuthData != null && authHeader == currentAuthData
        val carriedSubscribeToken = currentSubscribeToken != null && queryToken == currentSubscribeToken
        if (!carriedAuthData && !carriedSubscribeToken) return false
        return isSubscribePath(encodedPath) || carriedSubscribeToken
    }

    fun decide(
        token: String?,
        isAllowedHost: Boolean,
        hasAuthHeader: Boolean,
        responseCode: Int,
        isAuthFailureBody: Boolean,
        isAuthPath: Boolean,
    ): Decision {
        val attachToken = token != null && isAllowedHost && !hasAuthHeader
        // 401 与 403 同门控：白名单内非 auth 路径（订阅 CDN/WAF 等）的 401 不再踢登录，
        // 只要是 /api/v1/user/ 下的鉴权接口返回 401，令牌过期依然会被正确清理
        val authFailed =
            isAllowedHost &&
                (responseCode == 401 || (responseCode == 403 && isAuthFailureBody)) &&
                isAuthPath
        val clearSession = authFailed && token != null
        return Decision(attachToken = attachToken, clearSession = clearSession)
    }

    internal val AUTH_FAILURE_KEYWORDS: List<String> =
        listOf(
            "未登录",
            "登陆已过期",
            "登录已过期",
            "unauthorized",
            "unauthenticated",
            "token expired",
            "invalid token",
        )

    private val UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")

    private fun matchesAuthFailureKeyword(text: String): Boolean {
        val lower = text.lowercase()
        return AUTH_FAILURE_KEYWORDS.any { lower.contains(it) }
    }

    private fun decodeUnicodeEscapes(text: String): String {
        if (!text.contains("\\u")) return text
        return UNICODE_ESCAPE_REGEX.replace(text) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }
}

@Singleton
class AuthInterceptor
@Inject
constructor(
    private val sessionStore: SessionStore,
) : Interceptor {
    private val _authErrorEvents = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)

    val authErrorEvents: SharedFlow<String> = _authErrorEvents.asSharedFlow()

    private val _sessionSuspectEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 疑似登录态失效（订阅下载携带本会话凭据仍被拒），由上层探测确认后再清理会话。 */
    val sessionSuspectEvents: SharedFlow<Unit> = _sessionSuspectEvents.asSharedFlow()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = sessionStore.getAuthData()

        val canAttachToken = AllowedHosts.isAllowedHost(request.url.host)
        val decision =
            AuthRules.decide(
                token = token,
                isAllowedHost = canAttachToken,
                hasAuthHeader = request.header("Authorization") != null,
                responseCode = 0,
                isAuthFailureBody = false,
                isAuthPath = AuthRules.isAuthPath(request.url.encodedPath),
            )
        val authenticated =
            if (decision.attachToken && token != null) {
                request
                    .newBuilder()
                    .addHeader("Authorization", token)
                    .build()
            } else {
                if (token != null && !canAttachToken) {
                    AppLog.w("SLTE-Api", "非白名单主机，已跳过凭据注入: ${request.url.encodedPath}")
                }
                request
            }
        val response = chain.proceed(authenticated)

        val clearSession =
            AuthRules.decide(
                token = token,
                // Redirects can change hosts. Only trust an auth failure from an allowed host
                // that actually received this session's credential.
                isAllowedHost =
                AllowedHosts.isAllowedHost(response.request.url.host) &&
                    response.request.header("Authorization") == token,
                hasAuthHeader = response.request.header("Authorization") != null,
                responseCode = response.code,
                isAuthFailureBody = isAuthFailureResponse(response),
                isAuthPath = AuthRules.isAuthPath(response.request.url.encodedPath),
            ).clearSession
        val tokenStillCurrent = token != null && token == sessionStore.getAuthData()
        if (clearSession && tokenStillCurrent) {
            token?.let(_authErrorEvents::tryEmit)
        }
        if (tokenStillCurrent && (response.code == 401 || response.code == 403)) {
            val subscribeFailure =
                AuthRules.isSubscribeCredentialFailure(
                    responseCode = response.code,
                    encodedPath = response.request.url.encodedPath,
                    queryToken = response.request.url.queryParameter(AuthRules.SUBSCRIBE_TOKEN_PARAM),
                    authHeader = response.request.header("Authorization"),
                    currentAuthData = token,
                    currentSubscribeToken = sessionStore.getSubscribeToken(),
                )
            if (subscribeFailure) {
                AppLog.w("SLTE-Api", "订阅下载被拒（${response.code}），疑似登录态失效，等待探测确认")
                _sessionSuspectEvents.tryEmit(Unit)
            }
        }

        return response
    }

    private fun isAuthFailureResponse(response: Response): Boolean {
        if (!AuthRules.isAuthPath(response.request.url.encodedPath)) {
            return false
        }
        return isAuthFailureBody(response)
    }

    private fun isAuthFailureBody(response: Response): Boolean {
        val body =
            try {
                response.peekBody(MAX_PEEK_BYTES).string()
            } catch (_: Exception) {
                return false
            }
        return AuthRules.isAuthFailureBody(body)
    }

    private companion object {
        const val MAX_PEEK_BYTES = 4096L
    }
}
