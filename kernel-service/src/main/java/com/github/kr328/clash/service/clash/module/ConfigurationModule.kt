package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.AtomicFs
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import java.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

class ConfigurationModule(
    service: Service,
    private val onLoaded: () -> Unit = {},
) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    override suspend fun run() {
        AtomicFs.recover(service.importedDir)

        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }
        Log.d("ConfigurationModule: listening")

        var loaded: UUID? = null

        reload.trySend(Unit)

        try {
            while (true) {
                val changed: UUID? = select {
                    broadcasts.onReceive {
                        if (it.action == Intents.ACTION_PROFILE_CHANGED) {
                            UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                        } else {
                            null
                        }
                    }
                    reload.onReceive {
                        null
                    }
                }
                Log.d("ConfigurationModule: event received, changed=$changed")

                try {
                    val current = store.activeProfile
                    if (current == null) {
                        // 未选择配置时保持等待。
                        Log.w("ConfigurationModule: no active profile, skip reload")
                        continue
                    }

                    if (current == loaded && changed != null && changed != loaded) {
                        continue
                    }

                    loaded = current

                    val active = ImportedDao().queryByUUID(current)
                    if (active == null) {
                        // 记录缺失时跳过重载；抛异常会触发 LoadException 并断开 VPN。
                        Log.w("ConfigurationModule: active profile $current not found, skip reload")
                        // 允许同名 UUID 重建后再次加载。
                        loaded = null
                        continue
                    }

                    Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })

                    Clash.load(service.importedDir.resolve(active.uuid.toString())).await()

                    val remove = SelectionDao().querySelections(active.uuid)
                        .filterNot { Clash.patchSelector(it.proxy, it.selected) }
                        .map { it.proxy }

                    SelectionDao().removeSelections(active.uuid, remove)

                    StatusProvider.currentProfile = active.name

                    service.sendProfileLoaded(current)
                    onLoaded()

                    Log.d("ConfigurationModule: reload done")
                    Log.d("Profile ${active.name} loaded")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return enqueueEvent(LoadException(e.message ?: "Unknown"))
                }
            }
        } finally {
            reload.close()
        }
    }
}
