package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.model.*
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
interface IClashManager {
    fun queryTunnelState(): TunnelState
    fun coreVersion(): String
    fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String>
    fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup

    /** 在后台进程加载当前激活配置（无需启动 VPN，供离线测速等场景使用） */
    suspend fun loadActiveProfile()

    fun patchSelector(group: String, name: String): Boolean

    /** 对单个节点跑一次真实测速并分类失败原因（离线/超时）；节点不存在时 kind 返回 timeout */
    fun urlTest(name: String, timeoutMs: Int): UrlTestResult

    suspend fun healthCheck(group: String)
    fun healthCheckAll()

    fun queryOverride(slot: OverrideSlot): ConfigurationOverride
    fun patchOverride(slot: OverrideSlot, configuration: ConfigurationOverride)

    /** 查询当前 TUN 堆栈模式（system/gvisor/mixed） */
    fun tunStackMode(): String

    /** 切换 TUN 堆栈模式并持久化；运行中会原地重启 TUN 使设置立即生效 */
    fun setTunStackMode(mode: String)

    fun setLogObserver(observer: ILogObserver?)
}
