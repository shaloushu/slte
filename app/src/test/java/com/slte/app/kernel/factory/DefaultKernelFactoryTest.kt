package com.slte.app.kernel.factory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.slte.app.BuildConfig
import com.slte.app.kernel.api.KernelType
import com.slte.app.kernel.binder.MihomoBinderKernelApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultKernelFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun factory(): DefaultKernelFactory {
        val mihomo = MihomoBinderKernelApi(context)
        return DefaultKernelFactory(context, javax.inject.Provider { mihomo })
    }

    @Test
    fun `默认构建只暴露 mihomo`() {
        assertEquals(listOf(KernelType.MIHOMO), factory().availableKernels())
    }

    @Test
    fun `单内核包 current 恒定返回唯一实现`() {
        val factory = factory()
        assertEquals(KernelType.MIHOMO, factory.current().type)
        assertEquals(KernelType.MIHOMO, factory.current().type)
    }

    @Test
    fun `smart 内核与 mihomo 共享引擎且能力一致`() {
        val mihomo = MihomoBinderKernelApi(context)
        val factory = DefaultKernelFactory(context, javax.inject.Provider { mihomo })
        val smart = factory.create(KernelType.MIHOMO_SMART)

        assertEquals(KernelType.MIHOMO_SMART, smart.type)
        assertEquals(mihomo.capabilities, smart.capabilities)
    }

    @Test
    fun `构建参数解析只保留已注册内核`() {
        val parsed =
            BuildConfig.AVAILABLE_KERNELS
                .split(',')
                .mapNotNull { KernelType.fromStorageKey(it.trim().lowercase()) }
                .distinct()
        assertTrue(parsed.isNotEmpty())
        assertTrue(parsed.all { it == KernelType.MIHOMO })
    }

    @Test
    fun `未构建的内核切换被拒绝`() = runTest {
        val factory = factory()
        val rejected = runCatching { factory.switchTo(KernelType.MIHOMO_SMART) }
        assertTrue(rejected.isFailure)
    }
}
