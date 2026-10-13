package com.slte.app.data.remote

import com.slte.app.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRulesTest {
    @Test
    fun `认证接口路径判定`() {
        assertTrue(AuthRules.isAuthPath("/api/v1/user/info"))
        assertTrue(AuthRules.isAuthPath("/api/v1/user/subscribe"))
        assertFalse(AuthRules.isAuthPath("/api/v1/subscribe/token"))
        assertFalse(AuthRules.isAuthPath("/api/v1/orders"))
        assertFalse(AuthRules.isAuthPath(""))
    }

    @Test
    fun `响应体文本判定登录态失效`() {
        assertTrue(AuthRules.isAuthFailureBodyText("未登录"))
        assertTrue(AuthRules.isAuthFailureBodyText("登陆已过期"))
        assertTrue(AuthRules.isAuthFailureBodyText("登录已过期"))
        assertTrue(AuthRules.isAuthFailureBodyText("UNAUTHORIZED"))
        assertTrue(AuthRules.isAuthFailureBodyText("TOKEN EXPIRED"))
        assertTrue(AuthRules.isAuthFailureBodyText("{\"message\":\"invalid token\"}"))
        assertFalse(AuthRules.isAuthFailureBodyText(""))
        assertFalse(AuthRules.isAuthFailureBodyText(null))
        assertFalse(AuthRules.isAuthFailureBodyText("无套餐"))
        assertFalse(AuthRules.isAuthFailureBodyText("订阅已过期"))
    }

    @Test
    fun `403空响应体在认证接口也判为登录态失效`() {
        assertTrue(AuthRules.isAuthFailureBody(""))
        assertTrue(AuthRules.isAuthFailureBody(null))
        assertTrue(AuthRules.isAuthFailureBody("未登录"))
        assertTrue(AuthRules.isAuthFailureBody("{\"message\":\"invalid token\"}"))
        assertFalse(AuthRules.isAuthFailureBody("无套餐"))
    }

    @Test
    fun `注入token：有token处白名单且无Authorization头`() {
        assertTrue(
            AuthRules.decide("tok", isAllowedHost = true, hasAuthHeader = false, 200, false, isAuthPath = true).attachToken,
        )

        assertFalse(AuthRules.decide(null, true, false, 200, false, isAuthPath = true).attachToken)

        assertFalse(AuthRules.decide("tok", false, false, 200, false, isAuthPath = true).attachToken)

        assertFalse(AuthRules.decide("tok", true, true, 200, false, isAuthPath = true).attachToken)
    }

    @Test
    fun `认证接口401清会话`() {
        assertTrue(AuthRules.decide("tok", true, false, 401, false, isAuthPath = true).clearSession)

        assertTrue(AuthRules.decide("tok", true, false, 401, true, isAuthPath = true).clearSession)

        assertFalse(AuthRules.decide(null, true, false, 401, false, isAuthPath = true).clearSession)
    }

    @Test
    fun `非认证接口401不再踢登录`() {
        // 白名单内非 auth 路径（订阅 CDN/WAF 等）的 401 是接口级失败，不是登录态失效
        assertFalse(AuthRules.decide("tok", true, false, 401, false, isAuthPath = false).clearSession)
        assertFalse(AuthRules.decide("tok", true, false, 401, true, isAuthPath = false).clearSession)
    }

    @Test
    fun `403仅在认证接口且响应体含失效关键词时清会话`() {
        assertTrue(AuthRules.decide("tok", true, false, 403, isAuthFailureBody = true, isAuthPath = true).clearSession)

        assertFalse(AuthRules.decide("tok", true, false, 403, isAuthFailureBody = false, isAuthPath = true).clearSession)

        assertFalse(AuthRules.decide("tok", true, false, 403, isAuthFailureBody = true, isAuthPath = false).clearSession)
    }

    @Test
    fun `200及非失效状态不清会话`() {
        assertFalse(AuthRules.decide("tok", true, false, 200, false, isAuthPath = true).clearSession)
        assertFalse(AuthRules.decide("tok", true, false, 400, true, isAuthPath = true).clearSession)
        assertFalse(AuthRules.decide("tok", true, false, 500, false, isAuthPath = true).clearSession)
    }

    @Test
    fun `注入与清会话相互独立`() {
        val noToken = AuthRules.decide(null, true, false, 401, false, isAuthPath = true)
        assertFalse(noToken.attachToken)
        assertFalse(noToken.clearSession)

        val withHeader = AuthRules.decide("tok", true, true, 401, false, isAuthPath = true)
        assertFalse(withHeader.attachToken)
        assertTrue(withHeader.clearSession)

        val notAllowedHost = AuthRules.decide("tok", false, false, 401, false, isAuthPath = true)
        assertFalse(notAllowedHost.attachToken)
        assertFalse(notAllowedHost.clearSession)
    }

    @Test
    fun `响应体Unicode转义后仍能判定登录态失效`() {
        // 面板实测返回：{"message":"\u672a\u767b\u5f55\u6216\u767b\u9646\u5df2\u8fc7\u671f"}（未登录或登陆已过期）
        val escaped = "{\"message\": \"\\u672a\\u767b\\u5f55\\u6216\\u767b\\u9646\\u5df2\\u8fc7\\u671f\"}"

        assertTrue(AuthRules.isAuthFailureBodyText(escaped))
        assertTrue(AuthRules.isAuthFailureBody(escaped))
        // 转义后仍不含失效关键词的业务消息不误判
        assertFalse(AuthRules.isAuthFailureBodyText("{\"message\":\"\\u8ba2\\u9605\\u5df2\\u8fc7\\u671f\"}"))
    }

    @Test
    fun `订阅下载携带本会话凭据返回401或403才判为疑似失效`() {
        val subscribePath = BuildConfig.SUBSCRIBE_PATH
        assertTrue(AuthRules.isSubscribePath(subscribePath))
        assertFalse(AuthRules.isSubscribePath("/api/v1/user/info"))

        // 白名单主机直连：Authorization 即本会话凭据
        assertTrue(
            AuthRules.isSubscribeCredentialFailure(403, subscribePath, null, "ad-1", "ad-1", "sub-1"),
        )
        // 自定义订阅域名：仅 token 查询参数匹配
        assertTrue(
            AuthRules.isSubscribeCredentialFailure(401, "/custom/sub", "sub-1", null, "ad-1", "sub-1"),
        )
        // 非 401/403 不算
        assertFalse(
            AuthRules.isSubscribeCredentialFailure(200, subscribePath, "sub-1", null, "ad-1", "sub-1"),
        )
        // 未携带本会话凭据（CDN/WAF 拦截）不算
        assertFalse(
            AuthRules.isSubscribeCredentialFailure(403, subscribePath, "other", null, "ad-1", "sub-1"),
        )
        // 非订阅路径且未匹配订阅 token（用户接口 401/403 由既有门控处理）
        assertFalse(
            AuthRules.isSubscribeCredentialFailure(403, "/api/v1/user/info", null, null, "ad-1", "sub-1"),
        )
    }
}
