package com.slte.app.kernel.mihomo

import com.slte.app.kernel.api.KernelApi
import com.slte.app.kernel.api.KernelType
import com.slte.app.kernel.binder.MihomoBinderKernelApi
import javax.inject.Inject
import javax.inject.Singleton

/** mihomo 智能内核：共享同一引擎实例，仅配置层差异（KernelConfig 注入 SmartPreset）。 */
@Singleton
class MihomoSmartKernelApi
@Inject
constructor(
    private val mihomo: MihomoBinderKernelApi,
) : KernelApi by mihomo {
    override val type: KernelType = KernelType.MIHOMO_SMART
}
