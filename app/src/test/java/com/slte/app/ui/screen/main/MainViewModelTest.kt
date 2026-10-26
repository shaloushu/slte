package com.slte.app.ui.screen.main

import com.slte.app.R
import com.slte.app.domain.repository.DnsCache
import com.slte.app.kernel.KernelConfig
import com.slte.app.kernel.KernelProxy
import com.slte.app.kernel.api.KernelApi
import com.slte.app.kernel.api.KernelVpnState
import com.slte.app.kernel.api.KernelVpnStatus
import com.slte.app.support.MainDispatcherRule
import com.slte.app.support.stubKernelApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val kernelManager = mockk<KernelApi>(relaxed = true)
    private val kernelProxy = mockk<KernelProxy>(relaxed = true)
    private val kernelConfig = mockk<KernelConfig>(relaxed = true)
    private val dnsCache = mockk<DnsCache>(relaxed = true)
    private val subscriptionUpdater = mockk<SubscriptionUpdater>(relaxed = true)
    private val dataWriter = mockk<DashboardDataWriter>(relaxed = true)

    private fun viewModel(
        hasPlan: Boolean = true,
        connected: Boolean = false,
    ): MainViewModel {
        kernelProxy.stubKernelApi()
        every { kernelManager.vpnConnected } returns MutableStateFlow(connected)
        every { kernelManager.vpnStatus } returns
            MutableStateFlow(
                if (connected) KernelVpnStatus(KernelVpnState.RUNNING) else KernelVpnStatus(),
            )
        every { kernelManager.profileLoaded } returns MutableStateFlow(0)
        every { dataWriter.applyCached(any()) } answers {
            firstArg<MutableStateFlow<DashboardData>>().value =
                DashboardData(hasPlan = hasPlan, isConnected = connected)
        }
        return MainViewModel(mainRule.dispatcher, kernelManager, kernelProxy, kernelConfig, dnsCache, subscriptionUpdater, dataWriter)
    }

    @Test
    fun `无套餐时点击连接不启动 VPN`() = runTest(mainRule.dispatcher) {
        val vm = viewModel(hasPlan = false)
        advanceUntilIdle()

        vm.toggleConnection()
        advanceUntilIdle()

        verify(exactly = 0) { kernelManager.startVpn() }
        verify(exactly = 0) { kernelManager.stopVpn() }
    }

    @Test
    fun `内核配置不可用时提示内核不可用且不启动`() = runTest(mainRule.dispatcher) {
        coEvery { kernelConfig.ensureProfile() } returns null
        val vm = viewModel(hasPlan = true)
        advanceUntilIdle()

        vm.toggleConnection()
        advanceUntilIdle()

        assertEquals(R.string.error_vpn_kernel_unavailable, vm.data.value.errorMessageRes)
        assertTrue("失败后应复位连接中", !vm.data.value.isConnecting)
        verify(exactly = 0) { kernelManager.startVpn() }
    }

    @Test
    fun `配置就绪时启动 VPN`() = runTest(mainRule.dispatcher) {
        coEvery { kernelConfig.ensureProfile() } returns mockk(relaxed = true)
        val vm = viewModel(hasPlan = true)
        advanceUntilIdle()

        vm.toggleConnection()
        advanceUntilIdle()

        verify { kernelManager.startVpn() }
    }

    @Test
    fun `已连接时点击断开`() = runTest(mainRule.dispatcher) {
        val vm = viewModel(hasPlan = true, connected = true)
        advanceUntilIdle()

        vm.toggleConnection()
        advanceUntilIdle()

        verify { kernelManager.stopVpn() }
        verify(exactly = 0) { kernelManager.startVpn() }
    }

    @Test
    fun `连接中再次点击取消连接`() = runTest(mainRule.dispatcher) {
        coEvery { kernelConfig.ensureProfile() } returns mockk(relaxed = true)
        every { kernelManager.vpnStatus } returns MutableStateFlow(
            com.slte.app.kernel.api.KernelVpnStatus(state = com.slte.app.kernel.api.KernelVpnState.STOPPED),
        )
        val vm = viewModel(hasPlan = true)
        advanceUntilIdle()

        vm.toggleConnection()
        vm.toggleConnection()
        advanceUntilIdle()

        // 第二次点击是取消：连接协程被取消，不再触发 startVpn
        verify(exactly = 0) { kernelManager.startVpn() }
        verify(exactly = 1) { kernelManager.stopVpn() }
        assertTrue(!vm.data.value.isConnecting)
    }

    @Test
    fun `切换代理模式写入状态并通知内核`() = runTest(mainRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.setProxyMode("global")
        advanceUntilIdle()

        assertEquals("global", vm.data.value.proxyMode)
        coVerify { kernelProxy.setProxyMode("global") }
    }

    @Test
    fun `内核连接状态同步到首页并清空 DNS 缓存`() = runTest(mainRule.dispatcher) {
        kernelProxy.stubKernelApi()
        val status = MutableStateFlow(KernelVpnStatus())
        every { kernelManager.vpnConnected } returns MutableStateFlow(false)
        every { kernelManager.vpnStatus } returns status
        every { kernelManager.profileLoaded } returns MutableStateFlow(0)
        val vm =
            MainViewModel(
                mainRule.dispatcher,
                kernelManager,
                kernelProxy,
                kernelConfig,
                dnsCache,
                subscriptionUpdater,
                dataWriter,
            )
        advanceUntilIdle()

        status.value = KernelVpnStatus(KernelVpnState.RUNNING)
        advanceUntilIdle()

        verify { dnsCache.clear() }
        assertTrue(vm.data.value.isConnected)
    }

    @Test
    fun `取消订阅更新会真正取消进行中的协程`() = runTest(mainRule.dispatcher) {
        var cancelled = false
        coEvery { subscriptionUpdater.updateSubscription(any(), any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }

        val vm = viewModel()
        advanceUntilIdle()

        vm.updateSubscription()
        runCurrent()
        vm.cancelUpdating()
        advanceUntilIdle()

        assertTrue("只清 isUpdating 是假取消：协程还在跑，之后仍会照自己的结果改写界面", cancelled)
        assertEquals(false, vm.data.value.isUpdating)
    }

    @Test
    fun `重复触发订阅更新时旧的一次被取消`() = runTest(mainRule.dispatcher) {
        var firstCancelled = false
        var started = 0
        coEvery { subscriptionUpdater.updateSubscription(any(), any()) } coAnswers {
            started++
            if (started == 1) {
                try {
                    awaitCancellation()
                } finally {
                    firstCancelled = true
                }
            }
        }

        val vm = viewModel()
        advanceUntilIdle()

        vm.updateSubscription()
        runCurrent()
        vm.updateSubscription()
        advanceUntilIdle()

        assertEquals("同一时间只应有一个订阅更新在跑", 2, started)
        assertTrue("新一次更新开始前必须先取消旧的", firstCancelled)
    }
}
