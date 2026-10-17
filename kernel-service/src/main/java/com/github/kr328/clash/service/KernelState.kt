package com.github.kr328.clash.service

/** 内核生命周期；TUN 与配置均就绪才进入 Running。 */
object KernelState {
    enum class State { Stopped, Starting, Running, Stopping, Failed }

    @Volatile
    var state: State = State.Stopped
        private set

    @Volatile
    var reason: String? = null
        private set

    private var tunAttached = false
    private var profileLoaded = false

    @Synchronized
    fun starting() {
        state = State.Starting
        reason = null
        tunAttached = false
        profileLoaded = false
    }

    @Synchronized
    fun restartingTun() {
        tunAttached = false
        if (state == State.Running || state == State.Starting) {
            state = State.Starting
        }
    }

    @Synchronized
    fun tunAttached() {
        tunAttached = true
        promote()
    }

    @Synchronized
    fun profileLoaded() {
        profileLoaded = true
        promote()
    }

    @Synchronized
    fun stopping() {
        if (state != State.Failed) {
            state = State.Stopping
        }
    }

    @Synchronized
    fun destroyed(reason: String?) {
        state = if (reason != null) State.Failed else State.Stopped
        this.reason = reason
        tunAttached = false
        profileLoaded = false
    }

    @Synchronized
    fun failed(reason: String?) {
        state = State.Failed
        this.reason = reason
    }

    @Synchronized
    fun isRunning(): Boolean = state == State.Running

    private fun promote() {
        if (tunAttached && profileLoaded && state != State.Failed && state != State.Stopping) {
            state = State.Running
            reason = null
        }
    }
}
