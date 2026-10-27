package com.slte.app.support

import com.slte.app.kernel.KernelProxy
import com.slte.app.kernel.api.KernelApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers

fun KernelProxy.stubKernelApi(): KernelProxy {
    val inner = mockk<KernelApi>(relaxed = true)
    every { manager } returns inner
    coEvery { inner.awaitClash() } returns null
    // 测速流用 faultReporter 的 dispatcher 做 flowOn：mock 出来的 reporter 会返回 mock 的
    // dispatcher（context 里带 Job，flowOn 直接抛异常），这里换成真实的直连 dispatcher
    every { faultReporter.ioDispatcher } returns Dispatchers.Unconfined
    coEvery { safe<Any?>(any(), any(), any(), any()) } coAnswers {
        try {
            arg<suspend () -> Any?>(3).invoke()
        } catch (e: Exception) {
            firstArg<Any?>()
        }
    }
    return this
}
