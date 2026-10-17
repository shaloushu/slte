package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep

@Keep
interface LogcatInterface {
    /** true 表示结束订阅并释放原生回调。 */
    fun received(jsonPayload: String): Boolean
}
