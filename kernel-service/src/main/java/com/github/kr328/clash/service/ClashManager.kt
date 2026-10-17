package com.github.kr328.clash.service

import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendBroadcastSelf
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel

class ClashManager(private val context: Context) :
    IClashManager,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {
    private val store = ServiceStore(context)
    private var logReceiver: ReceiveChannel<LogMessage>? = null

    override fun queryTunnelState(): TunnelState = Clash.queryTunnelState()

    override fun coreVersion(): String = Clash.coreVersion()

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> = Clash.queryGroupNames(excludeNotSelectable)

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup = Clash.queryGroup(name, proxySort)

    override fun urlTest(name: String, timeoutMs: Int): UrlTestResult {
        // null = 内核里没有该节点（已从订阅删除），按超时处理，不误标离线
        return Clash.urlTest(name, timeoutMs) ?: UrlTestResult(0, UrlTestResult.KIND_TIMEOUT)
    }

    override suspend fun loadActiveProfile() {
        val active = store.activeProfile ?: return
        val imported = ImportedDao().queryByUUID(active) ?: return
        Clash.setAgeSecretKey(imported.ageSecretKey?.takeIf { it.isNotBlank() })
        Clash.load(context.importedDir.resolve(active.toString())).await()
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride = Clash.queryOverride(slot)

    override fun patchSelector(group: String, name: String): Boolean {
        return Clash.patchSelector(group, name).also {
            val current = store.activeProfile ?: return@also

            if (it) {
                SelectionDao().setSelected(Selection(current, group, name))
            } else {
                SelectionDao().removeSelected(current, group)
            }
        }
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    override fun tunStackMode(): String = store.tunStackMode

    override fun setTunStackMode(mode: String) {
        val normalized = if (mode in TUN_STACK_VALUES) mode else DEFAULT_TUN_STACK
        if (store.tunStackMode == normalized) return

        store.tunStackMode = normalized
        Log.i("Tun stack mode changed: $normalized")

        context.sendBroadcastSelf(Intent(Intents.ACTION_TUN_RESTART))
    }

    override suspend fun healthCheck(group: String) = Clash.healthCheck(group).await()

    override fun healthCheckAll() {
        Clash.healthCheckAll()
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()

                Clash.forceGc()
            }

            if (observer != null) {
                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_TUN_STACK = "system"
        private val TUN_STACK_VALUES = setOf("system", "gvisor", "mixed")
    }
}
