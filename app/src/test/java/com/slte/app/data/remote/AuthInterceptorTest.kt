package com.slte.app.data.remote

import com.slte.app.data.local.SessionStore
import com.slte.app.support.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthInterceptorTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val sessionStore = mockk<SessionStore>(relaxed = true)
    private val interceptor = AuthInterceptor(sessionStore)

    fun request(
        url: String = "https://example.com${ApiPaths.AUTH}login",
        authorization: String? = null,
    ): Request {
        val builder = Request.Builder().url(url)
        if (authorization != null) builder.addHeader("Authorization", authorization)
        return builder.build()
    }

    fun chain(
        request: Request,
        code: Int,
        body: String = "{}",
    ): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        // 响应挂在实际发出的请求上（含注入的 Authorization），否则测试桩模拟不出凭据回传
        every { chain.proceed(any()) } answers {
            Response
                .Builder()
                .request(firstArg())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("stub")
                .body(body.toResponseBody())
                .build()
        }
        return chain
    }

    @Test
    fun `白名单主机无凭据头时注入token`() {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain = chain(request(), code = 200)

        interceptor.intercept(chain)

        verify { chain.proceed(match { it.header("Authorization") == "token-a" }) }
    }

    @Test
    fun `非白名单主机不注入凭据`() {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain = chain(request(url = "https://evil.example.org/api/v1/user/info"), code = 200)

        interceptor.intercept(chain)

        verify { chain.proceed(match { it.header("Authorization") == null }) }
    }

    @Test
    fun `已有凭据头时不覆盖`() {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain = chain(request(authorization = "token-existing"), code = 200)

        interceptor.intercept(chain)

        verify { chain.proceed(match { it.header("Authorization") == "token-existing" }) }
    }

    @Test
    fun `未登录时不注入也不清会话`() {
        every { sessionStore.getAuthData() } returns null
        val chain = chain(request(), code = 401)

        interceptor.intercept(chain)

        verify { chain.proceed(match { it.header("Authorization") == null }) }
        verify(exactly = 0) { sessionStore.clear() }
    }

    @Test
    fun `认证接口401在收集器启动前发生时仍可收到失效token`() = runTest(mainRule.dispatcher) {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain = chain(request(), code = 401)

        interceptor.intercept(chain)

        verify(exactly = 0) { sessionStore.clear() }
        val expiredToken = AtomicReference<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            interceptor.authErrorEvents.collect { expiredToken.set(it) }
        }
        assertEquals("token-a", expiredToken.get())
    }

    @Test
    fun `401时token已被更换则不清会话`() = runTest(mainRule.dispatcher) {
        every { sessionStore.getAuthData() } returnsMany listOf("token-a", "token-b")
        val chain = chain(request(), code = 401)
        val expiredToken = AtomicReference<String?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            interceptor.authErrorEvents.collect { expiredToken.set(it) }
        }

        interceptor.intercept(chain)

        verify(exactly = 0) { sessionStore.clear() }
        assertEquals(null, expiredToken.get())
    }

    @Test
    fun `认证接口403且响应体为失效关键词时不直接清会话`() {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain = chain(request(), code = 403, body = """{"msg":"登录已过期"}""")

        interceptor.intercept(chain)

        verify(exactly = 0) { sessionStore.clear() }
    }

    @Test
    fun `认证接口403但响应体为业务错误时不清会话`() {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain = chain(request(), code = 403, body = """{"msg":"余额不足"}""")

        interceptor.intercept(chain)

        verify(exactly = 0) { sessionStore.clear() }
    }

    @Test
    fun `非认证接口的401不再踢登录`() {
        // guest/config 等非 /api/v1/user/ 路径的 401 是接口级失败（如 CDN/WAF），
        // 不应清掉整个登录态；令牌过期的踢出只发生在认证接口上
        every { sessionStore.getAuthData() } returns "token-a"
        val chain = chain(request(url = "https://example.com${ApiPaths.PREFIX}/guest/comm/config"), code = 401)

        interceptor.intercept(chain)

        verify(exactly = 0) { sessionStore.clear() }
    }

    @Test
    fun `非白名单主机的401不触发会话失效`() {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain = chain(request(url = "https://third-party.example.org/api/v1/user/info"), code = 401)

        interceptor.intercept(chain)

        verify(exactly = 0) { sessionStore.clear() }
    }

    @Test
    fun `订阅下载带本会话订阅token被拒时发出疑似事件`() = runTest(mainRule.dispatcher) {
        every { sessionStore.getAuthData() } returns "token-a"
        every { sessionStore.getSubscribeToken() } returns "sub-1"
        val chain =
            chain(
                request(url = "https://example.com/api/v1/client/subscribe?token=sub-1"),
                code = 403,
                body = """{"message":"token is error"}""",
            )
        val suspect = AtomicReference(false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            interceptor.sessionSuspectEvents.collect { suspect.set(true) }
        }

        interceptor.intercept(chain)

        assertTrue(suspect.get())
        verify(exactly = 0) { sessionStore.clear() }
    }

    @Test
    fun `订阅下载凭据头被拒时发出疑似事件`() = runTest(mainRule.dispatcher) {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain =
            chain(
                request(url = "https://example.com/api/v1/client/subscribe", authorization = "token-a"),
                code = 403,
            )
        val suspect = AtomicReference(false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            interceptor.sessionSuspectEvents.collect { suspect.set(true) }
        }

        interceptor.intercept(chain)

        assertTrue(suspect.get())
    }

    @Test
    fun `订阅下载未携带本会话凭据的403不发疑似事件`() = runTest(mainRule.dispatcher) {
        // 自定义订阅域名不在白名单：不注入凭据，CDN/WAF 的 403 不应触发疑似失效
        every { sessionStore.getAuthData() } returns "token-a"
        every { sessionStore.getSubscribeToken() } returns "sub-1"
        val chain =
            chain(
                request(url = "https://cdn.example.org/api/v1/client/subscribe?token=other"),
                code = 403,
            )
        val suspect = AtomicReference(false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            interceptor.sessionSuspectEvents.collect { suspect.set(true) }
        }

        interceptor.intercept(chain)

        assertFalse(suspect.get())
    }

    @Test
    fun `非订阅路径的403不发疑似事件`() = runTest(mainRule.dispatcher) {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain =
            chain(
                request(url = "https://example.com/api/v1/guest/comm/config"),
                code = 403,
                body = """{"message":"forbidden"}""",
            )
        val suspect = AtomicReference(false)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            interceptor.sessionSuspectEvents.collect { suspect.set(true) }
        }

        interceptor.intercept(chain)

        assertFalse(suspect.get())
    }

    @Test
    fun `响应对象原样返回`() {
        every { sessionStore.getAuthData() } returns "token-a"
        val chain = chain(request(), code = 200)

        val response = interceptor.intercept(chain)

        assertNotNull(response)
        assertEquals(200, response.code)
    }
}
