package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.service.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

class DynamicNotificationModule(service: Service) : Module<Unit>(service) {
    private fun update() {
        val now = Clash.queryTrafficNow()
        val total = Clash.queryTrafficTotal()

        val uploading = now.trafficUpload()
        val downloading = now.trafficDownload()
        val uploaded = total.trafficUpload()
        val downloaded = total.trafficDownload()

        notifyTraffic(service, uploading, downloading, uploaded, downloaded)
    }

    override suspend fun run() = coroutineScope {
        var shouldUpdate = service.getSystemService<PowerManager>()?.isInteractive ?: true

        val screenToggle = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (true) {
            select<Unit> {
                screenToggle.onReceive {
                    when (it.action) {
                        Intent.ACTION_SCREEN_ON ->
                            shouldUpdate = true
                        Intent.ACTION_SCREEN_OFF ->
                            shouldUpdate = false
                    }
                }
                if (shouldUpdate) {
                    ticker.onReceive {
                        update()
                    }
                }
            }
        }
    }

    companion object {
        fun notifyTraffic(
            service: Service,
            uploading: String,
            downloading: String,
            uploaded: String,
            downloaded: String,
        ) {
            val notification = notificationBuilder(service)
                .setContentTitle(notificationTitle(service))
                .setContentText(service.getString(R.string.clash_notification_content, "$uploading/s", "$downloading/s"))
                .setSubText(service.getString(R.string.clash_notification_content, uploaded, downloaded))
                .build()
            NotificationManagerCompat.from(service).notify(R.id.nf_clash_status, notification)
        }
    }
}
