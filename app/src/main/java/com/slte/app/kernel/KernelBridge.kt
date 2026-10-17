package com.slte.app.kernel

import android.content.Intent
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

enum class KernelBindingState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RETRYING,
    FAILED,
}

enum class KernelVpnState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

data class KernelVpnStatus(
    val state: KernelVpnState = KernelVpnState.STOPPED,
    val reason: String? = null,
)

/** App-side port for kernel service lifecycle and Binder access. */
interface KernelBridge {
    /** True only while the remote Binder endpoint is usable. */
    val bindingState: StateFlow<KernelBindingState>

    /** VPN state is distinct from Binder/service binding state. */
    val vpnConnected: StateFlow<Boolean>
    val vpnStatus: StateFlow<KernelVpnStatus>
    val profileLoaded: StateFlow<Int>

    fun bind()
    fun vpnRequestIntent(): Intent?
    fun startVpn()
    fun stopVpn()
    fun notifyProfileChanged(uuid: UUID)
    fun notifyOverrideChanged()

    /** Kernel operations use app-owned ports; generated AIDL interfaces stay behind the bridge. */
    suspend fun awaitClash(): KernelClash?
    suspend fun awaitProfile(): KernelProfiles?
}

interface KernelClash {
    fun queryTunnelMode(): KernelTunnelMode
    fun coreVersion(): String
    fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String>
    fun queryProxyGroup(name: String, sort: KernelProxySort): KernelProxyGroupSnapshot
    suspend fun loadActiveProfile()
    fun patchSelector(group: String, name: String): Boolean
    fun urlTestFailureKind(name: String, timeoutMs: Int): KernelUrlTestFailure?
    suspend fun healthCheck(group: String)
    fun healthCheckAll()
    fun queryPersistedProxyMode(): KernelTunnelMode?
    fun setPersistedProxyMode(mode: KernelTunnelMode)
    fun tunStackMode(): String
    fun setTunStackMode(mode: String)
}

interface KernelProfiles {
    suspend fun create(type: KernelProfileType, name: String, source: String = ""): UUID
    suspend fun commit(uuid: UUID)
    suspend fun delete(uuid: UUID)
    suspend fun queryByUUID(uuid: UUID): KernelProfile?
    suspend fun queryAll(): List<KernelProfile>
    suspend fun queryActive(): KernelProfile?
    suspend fun setActive(uuid: UUID)
}

enum class KernelProxySort {
    DEFAULT,
    DELAY,
}

enum class KernelTunnelMode {
    GLOBAL,
    RULE,
    DIRECT,
    SCRIPT,
}

enum class KernelUrlTestFailure {
    OFFLINE,
    TIMEOUT,
    OTHER,
}

enum class KernelProfileType {
    URL,
}

data class KernelProfile(
    val uuid: UUID,
    val name: String,
    val source: String,
    val imported: Boolean,
)

data class KernelProxyGroupSnapshot(
    val type: String,
    val proxies: List<KernelProxySnapshot>,
    val now: String,
)

data class KernelProxySnapshot(
    val name: String,
    val isGroup: Boolean,
    val delay: Int,
    val measured: Boolean,
)
