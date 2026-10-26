package com.slte.app.kernel

import android.content.Context
import com.slte.app.data.local.InMemoryPreferences
import com.slte.app.kernel.api.KernelApi
import com.slte.app.kernel.api.KernelClash
import com.slte.app.support.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 验证 KernelProxy 对内核编排方法的委托与偏好持久化。 */
class SelectNodeTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val prefs = InMemoryPreferences()
    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<KernelApi>(relaxed = true)
    private val clash = mockk<KernelClash>(relaxed = true)
    private val config = mockk<KernelConfig>(relaxed = true)
    private val store = mockk<SpeedResultStore>(relaxed = true)
    private val geoIp = mockk<GeoIpResolver>(relaxed = true)
    private val reporter = KernelFaultReporter(mainRule.dispatcher)

    private val group = "节点选择"

    private fun kernelProxy(): KernelProxy {
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.packageName } returns "com.slte.app"
        coEvery { manager.awaitClash() } returns clash
        return KernelProxy(reporter, manager, config, store, geoIp, context)
    }

    @Test
    fun `手动选择节点委托给内核并持久化`() = runTest(mainRule.dispatcher) {
        coEvery { manager.selectNode("日本01") } returns true
        val kernel = kernelProxy()

        assertTrue(kernel.selectNode("日本01"))
        assertEquals("MANUAL", prefs.getString("selection_type", null))
        assertEquals("日本01", prefs.getString("selection_node", null))
    }

    @Test
    fun `选择失败不持久化`() = runTest(mainRule.dispatcher) {
        coEvery { manager.selectNode(any()) } returns false
        val kernel = kernelProxy()

        assertFalse(kernel.selectNode("日本01"))
        assertEquals(null, prefs.getString("selection_type", null))
    }

    @Test
    fun `列出节点名委托给内核`() = runTest(mainRule.dispatcher) {
        coEvery { manager.nodeNames() } returns listOf("[vless]香港01", "[ss]日本01")
        val kernel = kernelProxy()

        assertEquals(listOf("[vless]香港01", "[ss]日本01"), kernel.nodeNames())
    }

    @Test
    fun `切换到自动选择并持久化`() = runTest(mainRule.dispatcher) {
        coEvery { manager.applyAutoSelection() } returns true
        val kernel = kernelProxy()

        assertTrue(kernel.selectAuto())
        assertEquals("AUTO", prefs.getString("selection_type", null))
    }

    @Test
    fun `切换到故障转移并持久化`() = runTest(mainRule.dispatcher) {
        coEvery { manager.applyFallbackSelection() } returns true
        val kernel = kernelProxy()

        assertTrue(kernel.selectFallback())
        assertEquals("FALLBACK", prefs.getString("selection_type", null))
    }
}
