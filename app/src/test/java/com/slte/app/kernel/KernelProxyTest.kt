package com.slte.app.kernel

import android.content.Context
import com.slte.app.data.local.InMemoryPreferences
import com.slte.app.kernel.api.KernelApi
import com.slte.app.kernel.api.KernelClash
import com.slte.app.kernel.api.KernelTunnelMode
import com.slte.app.support.MainDispatcherRule
import com.slte.app.utils.Constants
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KernelProxyTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val prefs = InMemoryPreferences()
    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<KernelApi>(relaxed = true)
    private val clash = mockk<KernelClash>(relaxed = true)
    private val config = mockk<KernelConfig>(relaxed = true)
    private val store = mockk<SpeedResultStore>(relaxed = true)
    private val geoIp = mockk<GeoIpResolver>(relaxed = true)
    private val reporter = KernelFaultReporter(mainRule.dispatcher)

    private fun proxy(): KernelProxy {
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.packageName } returns "com.slte.app"
        coEvery { manager.awaitClash() } returns clash
        return KernelProxy(reporter, manager, config, store, geoIp, context)
    }

    @Test
    fun `代理模式映射为界面常量`() = runTest(mainRule.dispatcher) {
        val proxy = proxy()

        every { clash.queryPersistedProxyMode() } returns KernelTunnelMode.GLOBAL
        assertEquals(Constants.PROXY_MODE_GLOBAL, proxy.proxyMode())

        every { clash.queryPersistedProxyMode() } returns KernelTunnelMode.DIRECT
        assertEquals(Constants.PROXY_MODE_DIRECT, proxy.proxyMode())

        every { clash.queryPersistedProxyMode() } returns KernelTunnelMode.SCRIPT
        assertEquals(Constants.PROXY_MODE_SCRIPT, proxy.proxyMode())

        every { clash.queryPersistedProxyMode() } returns KernelTunnelMode.RULE
        assertEquals(Constants.DEFAULT_PROXY_MODE, proxy.proxyMode())
    }

    @Test
    fun `持久化覆盖为空时回落到隧道状态模式`() = runTest(mainRule.dispatcher) {
        val proxy = proxy()
        every { clash.queryPersistedProxyMode() } returns null
        every { clash.queryTunnelMode() } returns KernelTunnelMode.GLOBAL

        assertEquals(Constants.PROXY_MODE_GLOBAL, proxy.proxyMode())
    }

    @Test
    fun `内核不可用时代理模式返回空`() = runTest(mainRule.dispatcher) {
        val proxy = proxy()
        coEvery { manager.awaitClash() } returns null

        assertNull(proxy.proxyMode())
    }

    @Test
    fun `内核不可用时切换只落本地不广播`() = runTest(mainRule.dispatcher) {
        val proxy = proxy()
        coEvery { manager.awaitClash() } returns null

        proxy.setProxyMode(Constants.PROXY_MODE_DIRECT)
        advanceUntilIdle()

        assertEquals(Constants.PROXY_MODE_DIRECT, prefs.getString("proxy_mode", null))
        verify(exactly = 0) { context.sendBroadcast(any(), any()) }
    }

    @Test
    fun `无持久化记录时不同步模式`() = runTest(mainRule.dispatcher) {
        val proxy = proxy()

        proxy.ensurePersistedMode()
        advanceUntilIdle()

        verify(exactly = 0) { clash.setPersistedProxyMode(any()) }
    }

    @Test
    fun `读取 TUN 模式优先内核并写入缓存`() = runTest(mainRule.dispatcher) {
        val proxy = proxy()
        every { clash.tunStackMode() } returns "gvisor"

        assertEquals("gvisor", proxy.tunStackMode())
        assertEquals("gvisor", prefs.getString("tun_stack", null))
    }

    @Test
    fun `内核不可用时 TUN 模式回退缓存再回退默认`() = runTest(mainRule.dispatcher) {
        val proxy = proxy()
        coEvery { manager.awaitClash() } returns null

        assertEquals("system", proxy.tunStackMode())

        prefs.edit().putString("tun_stack", "mixed").commit()
        assertEquals("mixed", proxy.tunStackMode())
    }

    @Test
    fun `设置 TUN 模式把非法值归一化为默认`() = runTest(mainRule.dispatcher) {
        val proxy = proxy()
        every { clash.tunStackMode() } returns "gvisor"

        proxy.setTunStack("bogus")
        advanceUntilIdle()

        assertEquals("system", prefs.getString("tun_stack", null))
        verify { clash.setTunStackMode("system") }
    }

    @Test
    fun `内核异常时返回默认值并上报带操作名的故障`() = runTest(mainRule.dispatcher) {
        val proxy = proxy()
        val fault = async { reporter.faults.first() }
        runCurrent()
        coEvery { manager.awaitClash() } throws IllegalStateException("内核不可用")

        assertNull(proxy.proxyMode())

        assertEquals("proxyMode", fault.await().operation)
        assertTrue(fault.await().cause is IllegalStateException)
    }
}
