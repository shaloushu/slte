package com.slte.app.ui.screen.main

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slte.app.R
import com.slte.app.di.IoDispatcher
import com.slte.app.domain.repository.DnsCache
import com.slte.app.kernel.KernelBridge
import com.slte.app.kernel.KernelConfig
import com.slte.app.kernel.KernelProxy
import com.slte.app.kernel.KernelVpnState
import com.slte.app.kernel.KernelVpnStatus
import com.slte.app.kernel.NodeNameResolver
import com.slte.app.kernel.ensureGlobalSelection
import com.slte.app.kernel.fetchPublicIp
import com.slte.app.kernel.liveSelectionFlow
import com.slte.app.kernel.refreshSelectionAndMeasure
import com.slte.app.utils.AppLog
import com.slte.app.utils.ErrorMessages
import com.slte.app.utils.sanitizeLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class MainViewModel
@Inject
constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val kernelManager: KernelBridge,
    private val kernelProxy: KernelProxy,
    private val kernelConfig: KernelConfig,
    private val dnsCache: DnsCache,
    private val subscriptionUpdater: SubscriptionUpdater,
    private val dataWriter: DashboardDataWriter,
) : ViewModel() {
    private val _data = MutableStateFlow(DashboardData())
    val data: StateFlow<DashboardData> = _data.asStateFlow()

    private var connectTimeoutJob: Job? = null

    init {

        dataWriter.applyCached(_data)
        dataWriter.seedServerName(_data)
        refresh()
        dataWriter.loadServers(viewModelScope, _data)
        observeKernelState()
        observeProfileLoaded()
        viewModelScope.launch { subscriptionUpdater.maybeSilentUpdate(_data, viewModelScope) }

        viewModelScope.launch {
            repeat(10) {
                if (kernelProxy.warmUp()) return@launch
                delay(1000)
            }
        }
    }

    private fun observeProfileLoaded() {
        viewModelScope.launch {
            kernelManager.profileLoaded.collect {
                refreshKernelInfo()
            }
        }
    }

    private fun observeKernelState() {
        viewModelScope.launch {
            kernelManager.vpnStatus.collect { status ->
                handleVpnStatus(status)
            }
        }
    }

    private fun handleVpnStatus(status: KernelVpnStatus) {
        val connected = status.state == KernelVpnState.RUNNING
        val connecting =
            status.state == KernelVpnState.STARTING ||
                status.state == KernelVpnState.STOPPING
        val wasConnected = _data.value.isConnected

        if (status.state != KernelVpnState.STARTING) {
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
        }

        _data.update { state ->
            when (status.state) {
                KernelVpnState.RUNNING ->
                    state.copy(isConnected = true, isConnecting = false, errorMessageRes = null)
                KernelVpnState.STARTING, KernelVpnState.STOPPING ->
                    state.copy(isConnected = false, isConnecting = true)
                KernelVpnState.FAILED ->
                    state.copy(
                        isConnected = false,
                        isConnecting = false,
                        errorMessageRes = R.string.error_vpn_kernel_unavailable,
                    )
                KernelVpnState.STOPPED ->
                    state.copy(isConnected = false, isConnecting = false)
            }
        }

        if (status.state == KernelVpnState.FAILED) {
            AppLog.w("SLTE-Main", "VPN 启动失败: ${status.reason ?: "Unknown"}")
        }
        if (connected && !wasConnected) {
            dnsCache.clear()
            viewModelScope.launch { startAutoSelectionAndTest() }
            refreshKernelInfo()
        }
    }

    private fun startConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob =
            viewModelScope.launch {
                delay(CONNECT_TIMEOUT_MS)
                if (_data.value.isConnecting && !_data.value.isConnected) {
                    AppLog.w("SLTE-Main", "VPN 连接超时，请求停止")
                    kernelManager.stopVpn()
                    _data.update {
                        it.copy(
                            isConnecting = false,
                            errorMessageRes = R.string.error_vpn_kernel_unavailable,
                        )
                    }
                }
            }
    }

    /**
     * 节点名与国旗的实时来源：直接跟随内核当前的落点节点。
     *
     * 自动选择/故障转移时内核会随健康检查结果自己换节点，这里只是把"它现在用的是哪个"
     * 实时反映到界面上——不依赖手动测速或更新订阅。
     *
     * 由界面在「已连接且首页可见」时调用，离开页面/断开即随协程取消，不在后台常驻轮询。
     */
    suspend fun watchLiveSelection() {
        kernelProxy.liveSelectionFlow().collect { live ->
            val kernelName = live.node ?: return@collect
            val display = NodeNameResolver.displayName(kernelName)
            _data.update { state ->
                if (state.hasPlan) {
                    state.copy(serverName = display)
                } else {
                    state
                }
            }
        }
    }

    /**
     * 立刻把选择方式交给内核（自动选择/故障转移即时生效），再在后台跑一次测速把延迟写进缓存。
     *
     * 测速是流式的：谁先出结果谁先落盘，不会阻塞界面，也不会等所有节点测完才切。
     */
    private suspend fun startAutoSelectionAndTest() {
        kernelProxy.refreshSelectionAndMeasure()
    }

    fun refreshKernelInfo() {
        viewModelScope.launch {
            withContext(ioDispatcher) {
                kernelProxy.ensureGlobalSelection()

                kernelProxy.ensurePersistedMode()
                kernelProxy.proxyMode()?.let { mode ->
                    _data.update { it.copy(proxyMode = mode) }
                }
                if (_data.value.hasPlan) {
                    kernelProxy.fetchPublicIp()?.let { info ->
                        _data.update {
                            it.copy(
                                currentIp = info.ip,
                                ipCountryCode = info.countryCode,
                            )
                        }
                    }
                }
            }
        }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch { subscriptionUpdater.refresh(_data, force = force) }
    }

    private var updateJob: Job? = null

    fun updateSubscription() {
        // 串行化：先等上一次更新真正结束再启动新的。
        // 取消是异步的——旧协程要到 finally 才释放 SubscriptionUpdater 的互斥锁；
        // 若不等它结束就启动新协程，新协程会被 tryLock 静默丢弃，界面会一直停在"更新中"。
        val previous = updateJob
        updateJob =
            viewModelScope.launch {
                previous?.cancelAndJoin()
                subscriptionUpdater.updateSubscription(_data, viewModelScope)
            }
    }

    fun refreshAfterPurchase(tradeNo: String? = null): Job = subscriptionUpdater.refreshAfterPurchase(_data, tradeNo, viewModelScope)

    fun finishPurchaseRefresh() {
        dataWriter.finishPurchaseRefresh(_data)
    }

    fun toggleConnection() {
        val current = _data.value
        if (current.isConnecting) return

        if (!current.hasPlan) return

        AppLog.i("SLTE-Main", "toggleConnection: connected=${current.isConnected} -> ${!current.isConnected}")
        if (current.isConnected) {
            kernelManager.stopVpn()
        } else {
            _data.update { it.copy(isConnecting = true, errorMessageRes = null) }
            viewModelScope.launch {
                try {
                    val profile = kernelConfig.ensureProfile()
                    if (profile == null) {
                        AppLog.w("SLTE-Main", "toggleConnection: ensureProfile 返回 null，内核不可用")
                        _data.update {
                            it.copy(
                                isConnecting = false,
                                errorMessageRes = R.string.error_vpn_kernel_unavailable,
                            )
                        }
                        return@launch
                    }
                    kernelManager.startVpn()
                    startConnectTimeout()
                } catch (e: Exception) {
                    AppLog.w("SLTE-Main", "toggleConnection: 启动失败 ${sanitizeLog(e.message ?: "Unknown")}")
                    _data.update {
                        it.copy(
                            isConnecting = false,
                            errorMessageRes = ErrorMessages.networkError(),
                        )
                    }
                }
            }
        }
    }

    fun vpnRequestIntent(): Intent? = kernelManager.vpnRequestIntent()

    fun setProxyMode(mode: String) {
        _data.update { it.copy(proxyMode = mode) }
        viewModelScope.launch {
            kernelProxy.setProxyMode(mode)
        }
    }

    fun clearError() {
        _data.update { it.copy(errorMessageRes = null) }
    }

    /**
     * 用户点掉更新遮罩：真正取消进行中的订阅更新。
     *
     * 只把 isUpdating 置 false 是假取消——协程还活着，会把订阅重新下载、内核配置重写、
     * 节点列表刷新和测速照跑一遍，并在结束后按自己的结果改写界面状态，
     * 用户以为取消了、实际什么都没取消。
     */
    fun cancelUpdating() {
        // 只取消、不置空引用：保留到协程真正结束，下一次 updateSubscription 才能 join 到锁释放
        updateJob?.cancel()
        _data.update { it.copy(isUpdating = false) }
    }

    fun onVpnPermissionDenied() {
        AppLog.w("SLTE-Main", "VPN 授权被拒绝，连接未建立")
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        _data.update {
            it.copy(isConnecting = false, errorMessageRes = R.string.error_vpn_permission_denied)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
