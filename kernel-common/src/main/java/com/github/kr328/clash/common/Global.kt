package com.github.kr328.clash.common

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object Global : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {
    val application: Application
        get() = application_

    private lateinit var application_: Application

    fun init(application: Application) {
        this.application_ = application
    }
}
