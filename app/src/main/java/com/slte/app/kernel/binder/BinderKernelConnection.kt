package com.slte.app.kernel.binder

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.slte.app.kernel.api.KernelBindingState
import com.slte.app.kernel.api.KernelVpnState
import com.slte.app.kernel.api.KernelVpnStatus
import com.slte.app.utils.AppLog
import com.slte.app.utils.sanitizeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Binder 连接与重连；状态变化通过回调交给 [BinderKernelApi] 发布。 */
internal class BinderKernelConnection(
    private val context: Context,
    private val mainScope: CoroutineScope,
    private val ioScope: CoroutineScope,
    private val onBindingState: (KernelBindingState) -> Unit,
    private val onVpnStatus: (KernelVpnStatus) -> Unit,
    private val onProfileLoaded: () -> Unit,
) {
    private val stateReader = VpnStateReader(context)

    private val _bindingState = MutableStateFlow(KernelBindingState.DISCONNECTED)
    val bindingState: StateFlow<KernelBindingState> = _bindingState.asStateFlow()

    @Volatile
    private var remote: IRemoteService? = null

    @Volatile
    private var bound = false
    private var receiverRegistered = false
    private var rebindJob: Job? = null
    private var rebindAttempts = 0

    @Volatile
    private var syncSeq = 0

    private val connection: ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder,
            ) {
                AppLog.d("SLTE-Kernel", "onServiceConnected: $name")

                bound = true
                rebindJob?.cancel()
                rebindJob = null
                rebindAttempts = 0
                remote = service.unwrap(IRemoteService::class)
                _bindingState.value = KernelBindingState.CONNECTED
                onBindingState(KernelBindingState.CONNECTED)
                syncVpnState()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleBindingLost("onServiceDisconnected: $name")
            }

            override fun onBindingDied(name: ComponentName?) {
                handleBindingLost("onBindingDied: $name")
            }

            override fun onNullBinding(name: ComponentName?) {
                handleBindingLost("onNullBinding: $name")
            }
        }

    private val statusReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                when (intent?.action) {
                    Intents.ACTION_CLASH_STARTED -> {
                        ++syncSeq
                        onVpnStatus(KernelVpnStatus(KernelVpnState.RUNNING))
                    }
                    Intents.ACTION_CLASH_STOPPED -> {
                        val reason = intent.getStringExtra(Intents.EXTRA_STOP_REASON)
                        ++syncSeq
                        onVpnStatus(
                            KernelVpnStatus(
                                state = if (reason.isNullOrBlank()) KernelVpnState.STOPPED else KernelVpnState.FAILED,
                                reason = reason,
                            ),
                        )
                    }
                    Intents.ACTION_PROFILE_LOADED -> onProfileLoaded()
                }
            }
        }

    fun bind() {
        mainScope.launch { bindLocked() }
    }

    fun unbind() {
        rebindJob?.cancel()
        rebindJob = null
        unbindQuietly()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(statusReceiver) }
            receiverRegistered = false
        }
        remote = null
        bound = false
    }

    suspend fun awaitRemote(): IRemoteService? {
        remote?.let { return it }

        if (_bindingState.value == KernelBindingState.DISCONNECTED || _bindingState.value == KernelBindingState.FAILED) {
            withContext(kotlinx.coroutines.Dispatchers.Main.immediate) { bindLocked() }
        }

        val connected =
            withTimeoutOrNull(BINDER_CONNECT_TIMEOUT_MS) {
                bindingState.first { it == KernelBindingState.CONNECTED || it == KernelBindingState.FAILED }
            }
        if (connected != KernelBindingState.CONNECTED) return null
        return remote
    }

    private fun bindLocked() {
        if (bound || remote != null) return
        _bindingState.value = KernelBindingState.CONNECTING
        onBindingState(KernelBindingState.CONNECTING)
        if (doBind()) {
            bound = true
            rebindAttempts = 0
            registerStatusReceiver()
            syncVpnState()
        } else {
            _bindingState.value = KernelBindingState.RETRYING
            onBindingState(KernelBindingState.RETRYING)
            scheduleRebind()
        }
    }

    private fun doBind(): Boolean = try {
        context.bindService(
            Intent(context, RemoteService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    } catch (e: Exception) {
        AppLog.w("SLTE-Kernel", "bindService failed: ${sanitizeLog(e.message ?: "Unknown")}")
        false
    }

    private fun handleBindingLost(reason: String) {
        AppLog.w("SLTE-Kernel", "$reason → 复位绑定状态并进入退避重绑")
        remote = null
        bound = false
        _bindingState.value = KernelBindingState.RETRYING
        onBindingState(KernelBindingState.RETRYING)
        rebindAttempts = 0
        ++syncSeq
        onVpnStatus(KernelVpnStatus(KernelVpnState.STOPPED))
        unbindQuietly()
        scheduleRebind()
    }

    private fun unbindQuietly() {
        try {
            context.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            AppLog.d("SLTE-Kernel", "unbindService: ${sanitizeLog(e.message ?: "Unknown")}")
        }
    }

    private fun registerStatusReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        ContextCompat.registerReceiver(
            context,
            statusReceiver,
            IntentFilter().apply {
                addAction(Intents.ACTION_CLASH_STARTED)
                addAction(Intents.ACTION_CLASH_STOPPED)
                addAction(Intents.ACTION_PROFILE_LOADED)
            },
            com.github.kr328.clash.common.constants.Permissions.RECEIVE_SELF_BROADCASTS,
            null,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun scheduleRebind() {
        if (bound || rebindJob?.isActive == true) return
        rebindJob =
            mainScope.launch {
                while (!bound && remote == null && rebindAttempts < MAX_REBIND_ATTEMPTS) {
                    val backoffMs = minOf(INITIAL_REBIND_DELAY_MS shl rebindAttempts, MAX_REBIND_DELAY_MS)
                    AppLog.d("SLTE-Kernel", "rebind #$rebindAttempts in ${backoffMs}ms")
                    delay(backoffMs)

                    if (bound || remote != null) return@launch
                    rebindAttempts++
                    if (doBind()) {
                        bound = true
                        _bindingState.value = KernelBindingState.CONNECTING
                        onBindingState(KernelBindingState.CONNECTING)
                        registerStatusReceiver()

                        return@launch
                    }
                }
                if (!bound && remote == null) {
                    _bindingState.value = KernelBindingState.FAILED
                    onBindingState(KernelBindingState.FAILED)
                    AppLog.w("SLTE-Kernel", "rebind 已重试 $MAX_REBIND_ATTEMPTS 次仍失败，等待下次按需 bind()")
                }
            }
    }

    private fun syncVpnState() {
        val seq = ++syncSeq
        ioScope.launch {
            val status = stateReader.query()
            if (seq == syncSeq) onVpnStatus(status)
        }
    }

    private companion object {
        const val INITIAL_REBIND_DELAY_MS = 1_000L
        const val MAX_REBIND_DELAY_MS = 30_000L
        const val MAX_REBIND_ATTEMPTS = 5
        const val BINDER_CONNECT_TIMEOUT_MS = 5_000L
    }
}
