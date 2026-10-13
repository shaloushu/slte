package com.slte.app.ui.screen.main

import com.slte.app.R
import com.slte.app.domain.model.isOrderActivated
import com.slte.app.domain.repository.OrderRepository
import com.slte.app.domain.repository.ServerRepository
import com.slte.app.domain.repository.SubscribeRepository
import com.slte.app.kernel.KernelBridge
import com.slte.app.kernel.KernelConfig
import com.slte.app.kernel.KernelProxy
import com.slte.app.kernel.ProfileUpdateResult
import com.slte.app.kernel.refreshSelectionAndMeasure
import com.slte.app.utils.AppLog
import com.slte.app.utils.Constants
import com.slte.app.utils.ErrorMessages
import com.slte.app.utils.sanitizeLog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class SubscriptionUpdater
@Inject
constructor(
    private val subscribeRepository: SubscribeRepository,
    private val kernelConfig: KernelConfig,
    private val serverRepository: ServerRepository,
    private val kernelProxy: KernelProxy,
    private val kernelManager: KernelBridge,
    private val orderRepository: OrderRepository,
    private val dataWriter: DashboardDataWriter,
) {

    private val updateMutex = Mutex()

    internal var nowMillis: () -> Long = System::currentTimeMillis

    private suspend fun tryAcquireUpdateSlot(): Boolean = updateMutex.tryLock()

    suspend fun updateSubscription(
        data: MutableStateFlow<DashboardData>,
        scope: CoroutineScope,
    ) {
        if (!tryAcquireUpdateSlot()) return
        try {
            if (!data.value.hasPlan) return
            data.update { it.copy(isUpdating = true) }
            try {
                subscribeRepository.fetchSubscribeInfo(force = true).fold(
                    onSuccess = {
                        val kernelResult = updateProfileWithFreshLink()
                        val kernelOk = kernelResult != ProfileUpdateResult.FAILED
                        if (kernelOk) {
                            // 不清缓存，强制拉新节点原地覆盖：刷新期间旧列表保持可见
                            dataWriter.loadServers(scope, data, force = true)

                            scope.launch { autoSpeedTestAfterUpdate(configChanged = true) }
                        }
                        dataWriter.applySubscribeInfo(
                            data,
                            it,
                            errorMessageRes =
                            if (kernelOk) {
                                R.string.dashboard_refresh_done
                            } else {
                                R.string.api_error_subscribe_info
                            },
                        )
                    },
                    onFailure = { e ->
                        val resId =
                            ErrorMessages.forSubscribe(e)
                        data.update { it.copy(isUpdating = false, errorMessageRes = resId) }
                    },
                )
            } finally {
                // 协程被取消时 runApi 会重抛 CancellationException，onSuccess/onFailure 都不执行，
                // 这里兜底复位，避免"更新中"遮罩永久留在界面上。
                data.update { it.copy(isUpdating = false) }
            }
        } finally {
            updateMutex.unlock()
        }
    }

    private suspend fun updateProfileWithFreshLink(): ProfileUpdateResult {
        val first = kernelConfig.updateProfile()
        if (first != ProfileUpdateResult.FAILED) return first
        subscribeRepository.fetchSubscribeInfo(force = true)
        return kernelConfig.updateProfile()
    }

    suspend fun maybeSilentUpdate(
        data: MutableStateFlow<DashboardData>,
        scope: CoroutineScope,
    ) {
        if (!tryAcquireUpdateSlot()) return
        try {
            subscribeRepository.fetchSubscribeInfo(force = true).fold(
                onSuccess = { info ->

                    if (!info.hasPlan) return@fold
                    val result = kernelConfig.updateProfile()
                    if (result != ProfileUpdateResult.FAILED) {
                        dataWriter.loadServers(scope, data, force = true)
                        scope.launch { autoSpeedTestAfterUpdate(configChanged = true) }
                        dataWriter.applySubscribeInfo(data, info, errorMessageRes = null)
                    }
                },
                onFailure = { e ->

                    AppLog.w("SLTE-Main", "silent subscription update failed: ${sanitizeLog(e.message ?: "Unknown")}")
                },
            )
        } finally {
            updateMutex.unlock()
        }
    }

    fun refreshAfterPurchase(
        data: MutableStateFlow<DashboardData>,
        tradeNo: String? = null,
        scope: CoroutineScope,
    ): Job = scope.launch {
        updateMutex.withLock {
            data.update { it.copy(isUpdating = true) }
            val deadline = nowMillis() + PURCHASE_REFRESH_TIMEOUT_MS
            var info = subscribeRepository.fetchSubscribeInfo(force = true).getOrNull()
            var activated = true
            if (tradeNo != null) {
                activated = false
                while (nowMillis() < deadline && !activated) {
                    activated =
                        orderRepository
                            .getOrderDetail(tradeNo)
                            .getOrNull()
                            ?.status
                            ?.let(::isOrderActivated) == true
                    if (!activated) {
                        delay(3000)
                        info = subscribeRepository.fetchSubscribeInfo(force = true).getOrNull()
                    }
                }
            } else {
                while (nowMillis() < deadline && info?.hasPlan != true) {
                    delay(3000)
                    info = subscribeRepository.fetchSubscribeInfo(force = true).getOrNull()
                }
            }
            subscribeRepository.fetchUserInfo()
            if (activated) {
                info = subscribeRepository.fetchSubscribeInfo(force = true).getOrNull()
            }
            val hasPlan = info?.hasPlan == true
            val kernelResult = if (hasPlan) kernelConfig.updateProfile() else ProfileUpdateResult.FAILED
            var servers = serverRepository.fetchServers(force = true).getOrNull().orEmpty()
            if (servers.isEmpty() && hasPlan) {
                delay(3000)
                servers = serverRepository.fetchServers(force = true).getOrNull().orEmpty()
            }
            if (servers.isNotEmpty()) {
                data.update { it.copy(serverName = servers.first().name) }
            } else {
                data.update {
                    it.copy(
                        serverName = Constants.PLACEHOLDER_DASH,
                        currentIp = Constants.PLACEHOLDER_DASH,
                    )
                }
            }
            dataWriter.applySubscribeInfo(
                data,
                info ?: subscribeRepository.getCachedSubscribeInfo(),
                errorMessageRes =
                if (!activated && tradeNo != null) {
                    R.string.purchase_activation_timeout
                } else {
                    null
                },
            )
            if (hasPlan && kernelResult != ProfileUpdateResult.FAILED) {
                scope.launch { autoSpeedTestAfterUpdate(configChanged = kernelResult == ProfileUpdateResult.UPDATED) }
            }
        }
    }

    suspend fun refresh(
        data: MutableStateFlow<DashboardData>,
        force: Boolean = false,
    ) {
        data.update { it.copy(isRefreshing = true) }
        subscribeRepository.fetchSubscribeInfo(force = force).fold(
            onSuccess = { dataWriter.applySubscribeInfo(data, it, errorMessageRes = null) },
            onFailure = { e ->
                val resId =
                    ErrorMessages.forSubscribe(e)
                data.update {
                    it.copy(
                        isRefreshing = false,
                        dataLoaded = true,
                        errorMessageRes = resId,
                    )
                }
            },
        )
    }

    /**
     * 订阅更新后的收尾：先让自动选择/故障转移立刻生效，再流式测速（先出先写缓存，不等全部节点）。
     * configChanged 时重新测速：旧延迟保留显示，测出结果后逐个覆盖。
     */
    private suspend fun autoSpeedTestAfterUpdate(configChanged: Boolean) {
        if (configChanged && kernelManager.vpnConnected.value) {
            val before = kernelManager.profileLoaded.value
            withTimeoutOrNull(SPEED_TEST_WAIT_MS) {
                kernelManager.profileLoaded.first { it > before }
            }
            serverRepository.invalidateSpecialNodeSnapshots()
        }
        kernelProxy.refreshSelectionAndMeasure()
    }

    companion object {

        private const val SPEED_TEST_WAIT_MS = 10_000L

        private const val PURCHASE_REFRESH_TIMEOUT_MS = 60_000L
    }
}
