package com.slte.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import com.github.kr328.clash.common.Global
import com.slte.app.data.local.LocaleStore
import com.slte.app.data.remote.config.CrispConfig
import com.slte.app.data.remote.config.CrispManager
import com.slte.app.data.remote.config.RemoteConfig
import com.slte.app.kernel.api.KernelApi
import com.slte.app.utils.AppLog
import com.slte.app.utils.GeoInstaller
import com.slte.app.utils.sanitizeLog
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class SlteApplication :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var crispManager: CrispManager

    @Inject
    lateinit var kernelManager: KernelApi

    @Inject
    lateinit var remoteConfig: RemoteConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var startedActivities = 0

    override fun attachBaseContext(base: android.content.Context?) {
        val wrapped =
            if (base != null && getProcessName() == base.packageName) {
                LocaleStore.wrapBase(base)
            } else {
                base
            }
        super.attachBaseContext(wrapped)
        Global.init(this)
        // 必须在任何 Bridge 调用前选定内核（切换内核后进程重启会重新执行这里）
        runCatching {
            com.github.kr328.clash.core.bridge.Bridge.selectKernel(readKernelVariant(this))
            com.github.kr328.clash.core.bridge.Bridge.initialize()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Geo 只由主进程安装：:background 进程只读，避免每次启动重复解包 24MB
        if (getProcessName() == packageName) {
            Thread { installGeoFiles() }.start()
            remoteConfig.startFetch()
            observeCrispConfig()
            kernelManager.bind()
            registerActivityLifecycleCallbacks(ForegroundCallbacks())
        }
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader
        .Builder(context)
        .components { add(SvgDecoder.Factory()) }
        .build()

    private inner class ForegroundCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            remoteConfig.startProbeLoop()
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) {
                remoteConfig.stopProbeLoop()
            }
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun observeCrispConfig() {
        scope.launch {
            remoteConfig.dataFlow.collect { cfg ->
                crispManager.init(
                    this@SlteApplication,
                    CrispConfig(cfg.crispWebsiteId, cfg.crispEnabled),
                )
            }
        }
    }

    private fun installGeoFiles() {
        runCatching {
            val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            GeoInstaller.install(
                directory = File(filesDir, "clash"),
                version = version,
                entries = GEO_ASSETS.associateWith { name -> { assets.open(name) } },
            )
        }.onFailure {
            AppLog.w("SLTE-App", "Geo 资源安装失败: ${sanitizeLog(it.message ?: "Unknown")}")
        }
    }

    private companion object {
        val GEO_ASSETS = listOf("geoip.metadb", "geosite.dat", "ASN.mmdb", "Model.bin")
    }
}

/** 读取持久化的内核选择（与 DefaultKernelFactory 同一存储）。 */
private fun readKernelVariant(context: android.content.Context): String {
    val local =
        context.getSharedPreferences("service", android.content.Context.MODE_PRIVATE)
            .getString("kernel_type", null)
    return local?.trim()?.lowercase().orEmpty().ifEmpty { "mihomo" }
}
