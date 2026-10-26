package com.slte.app.kernel

interface SpeedResultStore {
    fun saveSpeedResults(results: Map<String, Int>)

    fun getSpeedResults(): Map<String, Int>?

    /** 订阅更新后调用：旧延迟不再可信，直接清掉。 */
    fun clearSpeedResults()
}
