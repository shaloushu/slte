package com.slte.app

import android.app.Application
import com.github.kr328.clash.common.Global
import com.slte.app.data.local.LocaleStore
import com.slte.app.data.remote.config.CrispConfig
import com.slte.app.data.remote.config.CrispManager
import com.slte.app.data.remote.config.RemoteConfig
import com.slte.app.kernel.KernelBridge
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
class SlteApplication : Application() {
    @Inject
    lateinit var crispManager: CrispManager

    @Inject
    lateinit var kernelManager: KernelBridge

    @Inject
    lateinit var remoteConfig: RemoteConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun attachBaseContext(base: android.content.Context?) {
        val wrapped =
            if (base != null && getProcessName() == base.packageName) {
                LocaleStore.wrapBase(base)
            } else {
                base
            }
        super.attachBaseContext(wrapped)
        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Geo 只由主进程安装：:background 进程只读，避免每次启动重复解包 24MB
        if (getProcessName() == packageName) {
            Thread { installGeoFiles() }.start()
            remoteConfig.startFetch()
            remoteConfig.startProbeLoop()
            observeCrispConfig()
            kernelManager.bind()
        }
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
        val GEO_ASSETS = listOf("geoip.metadb", "geosite.dat", "ASN.mmdb")
    }
}
