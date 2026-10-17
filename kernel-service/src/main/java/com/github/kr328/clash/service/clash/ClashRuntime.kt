package com.github.kr328.clash.service.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.clash.module.Module
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val globalLock = Mutex()

interface ClashRuntimeScope {
    fun <E, T : Module<E>> install(
        module: T,
        critical: Boolean = false,
    ): T
}

interface ClashRuntime {
    fun launch()
    fun requestGc()
}

fun CoroutineScope.clashRuntime(
    onCriticalFailure: (String) -> Unit = {},
    block: suspend ClashRuntimeScope.() -> Unit,
): ClashRuntime {
    return object : ClashRuntime {
        override fun launch() {
            // 模块故障隔离；关键模块失败时主动停机。
            launch(Dispatchers.IO + SupervisorJob()) {
                val runtimeScope = this
                globalLock.withLock {
                    Log.d("ClashRuntime: initialize")

                    try {
                        Clash.reset()
                        Clash.clearOverride(Clash.OverrideSlot.Session)

                        val scope = object : ClashRuntimeScope {
                            override fun <E, T : Module<E>> install(
                                module: T,
                                critical: Boolean,
                            ): T {
                                launch {
                                    try {
                                        module.execute()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        val message = "${module.javaClass.simpleName}: ${e.message ?: e.javaClass.simpleName}"
                                        Log.e(message, e)
                                        if (critical) {
                                            onCriticalFailure(message)
                                            runtimeScope.cancel()
                                        }
                                    }
                                }

                                return module
                            }
                        }

                        scope.block()

                        cancel()
                    } finally {
                        withContext(NonCancellable) {
                            Clash.reset()
                            Clash.clearOverride(Clash.OverrideSlot.Session)

                            Log.d("ClashRuntime: destroyed")
                        }
                    }
                }
            }
        }

        override fun requestGc() {
            Clash.forceGc()
        }
    }
}
