package com.slte.app.ui.screen.main

import com.slte.app.R
import com.slte.app.data.repository.OrderRepositoryImpl
import com.slte.app.domain.model.OrderInfo
import com.slte.app.domain.model.ServerNode
import com.slte.app.domain.model.ServerType
import com.slte.app.domain.model.SpecialNodeSnapshot
import com.slte.app.domain.model.SubscribeInfo
import com.slte.app.domain.model.User
import com.slte.app.domain.repository.ServerRepository
import com.slte.app.domain.repository.SubscribeRepository
import com.slte.app.domain.usecase.DaysUntilExpiryUseCase
import com.slte.app.kernel.KernelConfig
import com.slte.app.kernel.KernelManager
import com.slte.app.kernel.KernelProxy
import com.slte.app.kernel.ProfileUpdateResult
import com.slte.app.support.MainDispatcherRule
import com.slte.app.utils.Constants
import com.slte.app.utils.ErrorMessages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionUpdaterTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val subscribeRepository = mockk<SubscribeRepository>(relaxed = true)
    private val kernelConfig = mockk<KernelConfig>(relaxed = true)
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val kernelProxy = mockk<KernelProxy>(relaxed = true)
    private val kernelManager = mockk<KernelManager>(relaxed = true)
    private val orderRepository = mockk<OrderRepositoryImpl>(relaxed = true)
    private val expiryUseCase = mockk<DaysUntilExpiryUseCase>(relaxed = true)

    private val dataWriter = DashboardDataWriter(subscribeRepository, serverRepository, expiryUseCase)

    private val neverRunScope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))

    @After
    fun tearDown() {
        neverRunScope.cancel()
    }

    private fun updater() = SubscriptionUpdater(
        subscribeRepository = subscribeRepository,
        kernelConfig = kernelConfig,
        serverRepository = serverRepository,
        kernelProxy = kernelProxy,
        kernelManager = kernelManager,
        orderRepository = orderRepository,
        dataWriter = dataWriter,
    )

    private fun plan() = SubscribeInfo(
        planName = "进阶套餐",
        transferEnable = 400L * 1024 * 1024 * 1024,
        usedTraffic = 65L * 1024 * 1024 * 1024,
        expiredAt = System.currentTimeMillis() / 1000L + 49L * 86_400,
        planId = 7,
    )

    private fun noPlan() = SubscribeInfo(planName = "", transferEnable = 0L, usedTraffic = 0L, expiredAt = 0L, planId = 0)

    private fun data(
        hasPlan: Boolean = true,
        planName: String = "",
        errorMessageRes: Int? = null,
    ) = MutableStateFlow(DashboardData(hasPlan = hasPlan, planName = planName, errorMessageRes = errorMessageRes))

    private fun stubExpiry() {
        every { expiryUseCase(any()) } returns 49
    }

    @Test
    fun `手动更新遇到进行中的更新时排队等待而不是丢弃`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data()
        val gate = CompletableDeferred<Result<SubscribeInfo>>()
        var calls = 0
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } coAnswers {
            calls++
            if (calls == 1) gate.await() else Result.success(plan())
        }
        coEvery { kernelConfig.updateProfile() } returns ProfileUpdateResult.UPDATED
        val updater = updater()

        val first = launch { updater.updateSubscription(state, neverRunScope) }
        runCurrent()
        assertTrue("第一次更新应已占用更新槽", state.value.isUpdating)

        val second = launch { updater.updateSubscription(state, neverRunScope) }
        runCurrent()

        gate.complete(Result.success(plan()))
        first.join()
        second.join()

        coVerify(exactly = 2) { subscribeRepository.fetchSubscribeInfo(force = true) }
        assertEquals(R.string.dashboard_refresh_done, state.value.errorMessageRes)
        assertFalse(state.value.isUpdating)
    }

    @Test
    fun `更新结束后释放更新槽`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data()
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.failure(java.io.IOException("boom"))
        val updater = updater()

        updater.updateSubscription(state, neverRunScope)
        updater.updateSubscription(state, neverRunScope)

        coVerify(exactly = 2) { subscribeRepository.fetchSubscribeInfo(force = true) }
    }

    @Test
    fun `无套餐时不拉取订阅`() = runTest(mainRule.dispatcher) {
        val state = data(hasPlan = false)

        updater().updateSubscription(state, neverRunScope)

        coVerify(exactly = 0) { subscribeRepository.fetchSubscribeInfo(any()) }
        assertFalse(state.value.isUpdating)
    }

    @Test
    fun `更新成功应用套餐并提示刷新完成`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(planName = "旧套餐")
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.success(plan())
        coEvery { kernelConfig.updateProfile() } returns ProfileUpdateResult.UPDATED
        val updater = updater()

        updater.updateSubscription(state, neverRunScope)
        advanceUntilIdle()

        val value = state.value
        assertEquals(R.string.dashboard_refresh_done, value.errorMessageRes)
        assertEquals("进阶套餐", value.planName)
        assertEquals(49, value.daysUntilExpired)
        assertTrue(value.hasPlan)
        assertFalse("更新完成后不应仍标记更新中", value.isUpdating)
        assertTrue(value.dataLoaded)
        coVerify(exactly = 0) { serverRepository.invalidateCache() }
    }

    @Test
    fun `更新失败写入订阅错误并复位更新态`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val boom = java.io.IOException("boom")
        val state = data(planName = "旧套餐")
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.failure(boom)

        updater().updateSubscription(state, neverRunScope)

        assertEquals(ErrorMessages.forSubscribe(boom), state.value.errorMessageRes)
        assertFalse(state.value.isUpdating)
        assertEquals("失败时保留原有展示", "旧套餐", state.value.planName)
        coVerify(exactly = 0) { kernelConfig.updateProfile() }
    }

    @Test
    fun `内核更新失败时提示订阅信息接口错误`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data()
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.success(plan())
        coEvery { kernelConfig.updateProfile() } returns ProfileUpdateResult.FAILED

        updater().updateSubscription(state, neverRunScope)
        advanceUntilIdle()

        assertEquals(R.string.api_error_subscribe_info, state.value.errorMessageRes)
        assertEquals("进阶套餐", state.value.planName)
    }

    @Test
    fun `静默更新遇到无套餐订阅时不覆盖展示`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(planName = "旧套餐")
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.success(noPlan())

        updater().maybeSilentUpdate(state, neverRunScope)

        assertEquals(null, state.value.errorMessageRes)
        assertEquals("旧套餐", state.value.planName)
        coVerify(exactly = 0) { kernelConfig.updateProfile() }
    }

    @Test
    fun `静默更新失败不打扰用户`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(planName = "旧套餐")
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.failure(java.io.IOException("boom"))

        updater().maybeSilentUpdate(state, neverRunScope)

        assertEquals(null, state.value.errorMessageRes)
        assertEquals("旧套餐", state.value.planName)
    }

    @Test
    fun `手动刷新成功应用套餐并清除错误`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(planName = "旧套餐", errorMessageRes = R.string.error_network)
        coEvery { subscribeRepository.fetchSubscribeInfo() } returns Result.success(plan())

        updater().refresh(state)

        assertEquals(null, state.value.errorMessageRes)
        assertEquals("进阶套餐", state.value.planName)
        assertEquals(49, state.value.daysUntilExpired)
        assertFalse(state.value.isRefreshing)
        assertTrue(state.value.dataLoaded)
    }

    @Test
    fun `静默更新成功时应用新套餐且不打扰用户`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(planName = "旧套餐")
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.success(plan())
        coEvery { kernelConfig.updateProfile() } returns ProfileUpdateResult.UPDATED

        updater().maybeSilentUpdate(state, neverRunScope)

        assertEquals(null, state.value.errorMessageRes)
        assertEquals("进阶套餐", state.value.planName)
        assertTrue(state.value.hasPlan)
        assertEquals(49, state.value.daysUntilExpired)
        coVerify(exactly = 0) { serverRepository.invalidateCache() }
    }

    @Test
    fun `强制刷新节点时直接拉取并覆盖节点名`() = runTest(mainRule.dispatcher) {
        val fakeServers = FakeServerRepository(listOf(node("香港01")))
        val writer = DashboardDataWriter(subscribeRepository, fakeServers, expiryUseCase)
        val state = data()

        writer.loadServers(this, state, force = true)
        advanceUntilIdle()

        assertEquals(listOf(true), fakeServers.forces)
        assertEquals("香港01", state.value.serverName)
    }

    private fun node(name: String) = ServerNode(id = 1, name = name, type = ServerType.VLESS, host = "h.example.com", port = 443)

    private class FakeServerRepository(private val nodes: List<ServerNode>) : ServerRepository {
        val forces = mutableListOf<Boolean>()

        override suspend fun fetchServers(force: Boolean): Result<List<ServerNode>> {
            forces += force
            return Result.success(nodes)
        }

        override fun getCachedServers(): List<ServerNode>? = null

        override fun invalidateCache() = Unit

        override fun saveAutoNodeSnapshot(snapshot: SpecialNodeSnapshot) = Unit

        override fun getAutoNodeSnapshot(): SpecialNodeSnapshot? = null

        override fun saveFallbackNodeSnapshot(snapshot: SpecialNodeSnapshot) = Unit

        override fun getFallbackNodeSnapshot(): SpecialNodeSnapshot? = null

        override fun invalidateSpecialNodeSnapshots() = Unit
    }

    @Test
    fun `静默更新内核失败时不覆盖展示且不提示`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(planName = "旧套餐")
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.success(plan())
        coEvery { kernelConfig.updateProfile() } returns ProfileUpdateResult.FAILED

        updater().maybeSilentUpdate(state, neverRunScope)

        assertEquals(null, state.value.errorMessageRes)
        assertEquals("旧套餐", state.value.planName)
    }

    @Test
    fun `购买后首轮订单即已完成时立即收敛并应用套餐`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(hasPlan = false, planName = "")
        coEvery { orderRepository.getOrderDetail("TN-1") } returns
            Result.success(
                OrderInfo(
                    id = 1,
                    tradeNo = "TN-1",
                    planName = "进阶套餐",
                    totalAmount = 5_000,
                    status = 1,
                    createdAt = 1_700_000_000L,
                    expiredAt = 1_800_000_000L,
                ),
            )
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.success(plan())
        coEvery { subscribeRepository.fetchUserInfo() } returns
            Result.success(User(id = "test-user", displayName = "测试用户"))
        coEvery { kernelConfig.updateProfile() } returns ProfileUpdateResult.FAILED
        coEvery { serverRepository.fetchServers(force = true) } returns Result.success(emptyList())

        updater().refreshAfterPurchase(state, tradeNo = "TN-1", scope = this)
        advanceUntilIdle()

        assertEquals(null, state.value.errorMessageRes)
        assertEquals("进阶套餐", state.value.planName)
        assertTrue(state.value.hasPlan)
        assertFalse(state.value.isUpdating)
        assertEquals(Constants.PLACEHOLDER_DASH, state.value.serverName)
        coVerify(exactly = 1) { orderRepository.getOrderDetail("TN-1") }
    }

    @Test
    fun `购买后轮询超时未激活时提示开通超时`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(hasPlan = false, planName = "")
        val baseEpochMillis = 1_700_000_000_000L
        coEvery { orderRepository.getOrderDetail("TN-1") } returns
            Result.success(
                OrderInfo(
                    id = 1,
                    tradeNo = "TN-1",
                    planName = "进阶套餐",
                    totalAmount = 5_000,
                    status = 0,
                    createdAt = 1_700_000_000L,
                    expiredAt = 0L,
                ),
            )
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.success(noPlan())
        coEvery { subscribeRepository.fetchUserInfo() } returns
            Result.success(User(id = "test-user", displayName = "测试用户"))
        coEvery { serverRepository.fetchServers(force = true) } returns Result.success(emptyList())

        val updater =
            updater().also { it.nowMillis = { baseEpochMillis + testScheduler.currentTime } }
        updater.refreshAfterPurchase(state, tradeNo = "TN-1", scope = this)
        advanceUntilIdle()

        assertEquals(R.string.purchase_activation_timeout, state.value.errorMessageRes)
        assertFalse(state.value.isUpdating)
        coVerify(atLeast = 2) { subscribeRepository.fetchSubscribeInfo(force = true) }
        coVerify(exactly = 0) { kernelConfig.updateProfile() }
    }

    @Test
    fun `内核首次失败时刷新订阅链接并重试一次`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(planName = "旧套餐")
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.success(plan())
        coEvery { kernelConfig.updateProfile() } returnsMany
            listOf(ProfileUpdateResult.FAILED, ProfileUpdateResult.UPDATED)

        updater().updateSubscription(state, neverRunScope)

        coVerify(exactly = 2) { kernelConfig.updateProfile() }
        coVerify(exactly = 2) { subscribeRepository.fetchSubscribeInfo(force = true) }
        assertEquals(R.string.dashboard_refresh_done, state.value.errorMessageRes)
        assertEquals("进阶套餐", state.value.planName)
    }

    @Test
    fun `两次内核更新都失败时报订阅信息接口错误`() = runTest(mainRule.dispatcher) {
        stubExpiry()
        val state = data(planName = "旧套餐")
        coEvery { subscribeRepository.fetchSubscribeInfo(force = true) } returns Result.success(plan())
        coEvery { kernelConfig.updateProfile() } returns ProfileUpdateResult.FAILED

        updater().updateSubscription(state, neverRunScope)

        coVerify(exactly = 2) { kernelConfig.updateProfile() }
        assertEquals(R.string.api_error_subscribe_info, state.value.errorMessageRes)
        assertEquals("进阶套餐", state.value.planName)
    }
}
