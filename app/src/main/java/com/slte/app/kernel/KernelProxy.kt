package com.slte.app.kernel

import android.content.Context
import androidx.core.content.edit
import com.slte.app.kernel.api.KernelApi
import com.slte.app.kernel.api.KernelServerInfo
import com.slte.app.kernel.api.KernelTunnelMode
import com.slte.app.kernel.api.SelectionGroupType
import com.slte.app.kernel.api.SelectionType
import com.slte.app.utils.AppLog
import com.slte.app.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

data class IpGeoInfo(
    val ip: String,
    val countryCode: String?,
)

@Singleton
class KernelProxy
@Inject
constructor(
    internal val faultReporter: KernelFaultReporter,
    internal val manager: KernelApi,
    internal val config: KernelConfig,
    internal val speedResultStore: SpeedResultStore,
    internal val geoIpResolver: GeoIpResolver,
    @ApplicationContext internal val context: Context,
) {
    internal val modePrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal suspend fun <T> safe(
        default: T,
        operation: String,
        tag: String = DEFAULT_FAULT_TAG,
        block: suspend () -> T,
    ): T = faultReporter.guard(default, operation, tag, block)

    /** 唤醒内核：确保配置已加载，后面的健康检查/切换才有意义。 */
    suspend fun warmUp(): Boolean = safe(false, "warmUp") {
        val clash = manager.awaitClash() ?: return@safe false
        config.ensureProfile()
        clash.loadActiveProfile()
        true
    }

    suspend fun coreVersion(): String? = safe(null, "coreVersion") {
        manager.awaitClash()?.coreVersion()
    }

    suspend fun proxyMode(): String? = safe(null, "proxyMode") {
        val clash = manager.awaitClash() ?: return@safe null

        val mode =
            clash.queryPersistedProxyMode()
                ?: clash.queryTunnelMode()
        when (mode) {
            KernelTunnelMode.GLOBAL -> Constants.PROXY_MODE_GLOBAL
            KernelTunnelMode.RULE -> Constants.DEFAULT_PROXY_MODE
            KernelTunnelMode.DIRECT -> Constants.PROXY_MODE_DIRECT
            KernelTunnelMode.SCRIPT -> Constants.PROXY_MODE_SCRIPT
        }
    }

    suspend fun setProxyMode(mode: String) = safe(Unit, "setProxyMode") {
        AppLog.d("SLTE-Kernel", "setProxyMode: $mode")

        modePrefs.edit { putString(KEY_PROXY_MODE, mode) }
        val clash = manager.awaitClash()
        if (clash == null) {
            AppLog.d("SLTE-Kernel", "setProxyMode: clash=null，已本地保存，待内核就绪后同步")
            return@safe
        }
        clash.setPersistedProxyMode(tunnelModeOf(mode))
        AppLog.d("SLTE-Kernel", "setProxyMode: override written, sending broadcast")
        manager.notifyOverrideChanged()
    }

    suspend fun ensurePersistedMode() = safe(Unit, "ensurePersistedMode") {
        val clash = manager.awaitClash() ?: return@safe
        val saved = modePrefs.getString(KEY_PROXY_MODE, null) ?: return@safe
        val target = tunnelModeOf(saved)
        val current = clash.queryPersistedProxyMode()
        if (current != target) {
            clash.setPersistedProxyMode(target)
            manager.notifyOverrideChanged()
            AppLog.d("SLTE-Kernel", "ensurePersistedMode: synced $saved")
        }
    }

    private fun tunnelModeOf(mode: String): KernelTunnelMode = when (mode) {
        Constants.PROXY_MODE_GLOBAL -> KernelTunnelMode.GLOBAL
        Constants.PROXY_MODE_DIRECT -> KernelTunnelMode.DIRECT
        Constants.PROXY_MODE_SCRIPT -> KernelTunnelMode.SCRIPT
        else -> KernelTunnelMode.RULE
    }

    /**
     * 记录用户在服务器页做出的选择，重启后由 [ensurePersistedSelection] 重放。
     *
     * 节点选择现在是双层持久化：内核侧 store-selected 已开启（native/config/process.go），
     * 配置加载瞬间由 cache.db 恢复流量路径；应用侧这份是 UI 真值来源，
     * 并兜底内核恢复不了的场景（订阅改名/节点被删时回落自动选择）。
     */
    internal fun persistSelection(type: SelectionType, node: String? = null) {
        modePrefs.edit {
            putString(KEY_SELECTION_TYPE, type.name)
            if (node != null) putString(KEY_SELECTION_NODE, node) else remove(KEY_SELECTION_NODE)
        }
    }

    private fun clearPersistedSelection() {
        modePrefs.edit {
            remove(KEY_SELECTION_TYPE)
            remove(KEY_SELECTION_NODE)
        }
    }

    /**
     * 把上次的选择重放给内核。连接建立、订阅更新都会经由 [refreshSelectionAndMeasure] 走到这里。
     * MANUAL 的节点已改名或被删时恢复不了：清掉记录、落回自动选择，不阻塞连接流程。
     */
    suspend fun ensurePersistedSelection() = safe(Unit, "ensurePersistedSelection") {
        val clash = manager.awaitClash() ?: return@safe
        // 分组还没加载完就先不重放：此时切换必失败，会把用户的选择误当成"节点已不存在"清掉
        if (manager.selectorGroup() == null && manager.waitForSelectorGroup() == null) return@safe
        val savedType = modePrefs.getString(KEY_SELECTION_TYPE, null) ?: return@safe
        val target = SelectionType.entries.firstOrNull { it.name == savedType } ?: run {
            clearPersistedSelection()
            return@safe
        }
        val savedNode = modePrefs.getString(KEY_SELECTION_NODE, null)
        val info = manager.selectionInfo()
        if (info?.selection == target && (target != SelectionType.MANUAL || info.node == savedNode)) return@safe

        val restored =
            when (target) {
                SelectionType.AUTO -> manager.applyAutoSelection()
                SelectionType.FALLBACK -> manager.applyFallbackSelection()
                SelectionType.MANUAL -> savedNode != null && manager.selectNode(savedNode)
            }
        if (restored) {
            AppLog.d("SLTE-Kernel", "ensurePersistedSelection: 已恢复 type=$target node=$savedNode")
        } else {
            AppLog.w("SLTE-Kernel", "ensurePersistedSelection: 恢复失败 type=$target node=$savedNode，落回自动选择")
            clearPersistedSelection()
            manager.applyAutoSelection()
        }
    }

    suspend fun tunStackMode(): String = safe(DEFAULT_TUN_STACK, "tunStackMode") {
        val clash = manager.awaitClash()
        if (clash != null) {
            val current = clash.tunStackMode()
            modePrefs.edit { putString(KEY_TUN_STACK, current) }
            return@safe current
        }
        modePrefs.getString(KEY_TUN_STACK, null) ?: DEFAULT_TUN_STACK
    }

    suspend fun setTunStack(mode: String) = safe(Unit, "setTunStack") {
        val normalized = if (mode in TUN_STACK_VALUES) mode else DEFAULT_TUN_STACK
        modePrefs.edit { putString(KEY_TUN_STACK, normalized) }
        val clash = manager.awaitClash()
        if (clash == null) {
            AppLog.w("SLTE-Kernel", "setTunStack: clash=null，已本地保存，待内核就绪后同步")
            return@safe
        }
        clash.setTunStackMode(normalized)
        AppLog.d("SLTE-Kernel", "setTunStack: $normalized")
    }

    /** 连接建立、订阅更新后：重放持久化的选择 + 触发全量健康检查。 */
    suspend fun refreshSelectionAndMeasure() = safe(Unit, "refreshSelectionAndMeasure") {
        ensurePersistedSelection()
        manager.triggerHealthCheckAll()
    }

    suspend fun selectNode(name: String): Boolean = safe(false, "selectNode") {
        val result = manager.selectNode(name)
        if (result) persistSelection(SelectionType.MANUAL, name)
        result
    }

    suspend fun selectAuto(): Boolean = safe(false, "selectAuto") {
        val result = manager.applyAutoSelection()
        if (result) persistSelection(SelectionType.AUTO)
        result
    }

    suspend fun selectFallback(): Boolean = safe(false, "selectFallback") {
        val result = manager.applyFallbackSelection()
        if (result) persistSelection(SelectionType.FALLBACK)
        result
    }

    suspend fun nodeNames(): List<String> = safe(emptyList(), "nodeNames") {
        manager.nodeNames()
    }

    suspend fun serverInfo(): KernelServerInfo? = safe(null, "serverInfo") {
        manager.selectionInfo()
    }

    suspend fun groupByTypeCurrentNode(type: SelectionGroupType): String? = safe(null, "groupByTypeCurrentNode") {
        manager.currentNodeOf(type)
    }

    suspend fun ensureGlobalSelection() = safe(Unit, "ensureGlobalSelection") {
        manager.ensureGlobalSelection()
    }

    fun liveSelectionFlow(intervalMs: Long = LIVE_SELECTION_POLL_MS): Flow<LiveSelection> = manager.liveSelectionFlow(intervalMs)

    internal val ipClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private const val PREFS_NAME = "slte_kernel_mode"
        private const val KEY_PROXY_MODE = "proxy_mode"
        private const val KEY_TUN_STACK = "tun_stack"
        private const val KEY_SELECTION_TYPE = "selection_type"
        private const val KEY_SELECTION_NODE = "selection_node"

        private const val DEFAULT_TUN_STACK = "system"
        private val TUN_STACK_VALUES = setOf("system", "gvisor", "mixed")

        internal const val IPIFY_V4_URL = "https://api.ipify.org"
        internal const val IPIFY_V6_URL = "https://api6.ipify.org"
    }
}
