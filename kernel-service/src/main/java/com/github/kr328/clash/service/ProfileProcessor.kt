package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.AtomicFs
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import java.util.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    suspend fun apply(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val pending =
                        PendingDao().queryByUUID(uuid) ?: throw IllegalArgumentException("profile $uuid not found")

                    pending.enforceFieldValid()

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    pending
                }

                Clash.setAgeSecretKey(snapshot.ageSecretKey?.takeIf { it.isNotBlank() })

                // 缺少预处理后的 config.yaml 时拒绝提交，避免绕过脱敏。
                val prepared = context.processingDir.resolve("config.yaml")
                if (!prepared.isFile || prepared.length() == 0L) {
                    throw IllegalArgumentException("Profile content is not prepared, re-import the subscription")
                }

                profileLock.withLock {
                    if (PendingDao().queryByUUID(snapshot.uuid) == snapshot) {
                        val imported = context.importedDir.resolve(snapshot.uuid.toString())
                        val staging = context.importedDir.resolve("${snapshot.uuid}.tmp")

                        context.importedDir.mkdirs()
                        staging.deleteRecursively()
                        if (!context.processingDir.renameTo(staging)) {
                            context.processingDir.copyRecursively(staging, overwrite = true)
                            context.processingDir.deleteRecursively()
                        }
                        AtomicFs.replaceDirectory(staging, imported)

                        val old = ImportedDao().queryByUUID(snapshot.uuid)
                        val new = Imported(
                            snapshot.uuid,
                            snapshot.name,
                            snapshot.type,
                            snapshot.source,
                            snapshot.interval,
                            old?.upload ?: 0,
                            old?.download ?: 0,
                            old?.total ?: 0,
                            old?.expire ?: 0,
                            old?.createdAt ?: System.currentTimeMillis(),
                            ageSecretKey = snapshot.ageSecretKey,
                        )
                        if (old != null) {
                            ImportedDao().update(new)
                        } else {
                            ImportedDao().insert(new)
                        }

                        PendingDao().remove(snapshot.uuid)

                        context.pendingDir.resolve(snapshot.uuid.toString()).deleteRecursively()

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)

                val pending = context.pendingDir.resolve(uuid.toString())
                val imported = context.importedDir.resolve(uuid.toString())

                pending.deleteRecursively()
                imported.deleteRecursively()

                // 不广播 PROFILE_CHANGED：该广播的语义是"请加载这个配置"，而它刚被删掉。
                // 若被删的恰好是当前激活项，ConfigurationModule 会因查不到记录抛 NPE →
                // LoadException → 整个内核服务退出，表现为 VPN 无声断开（App 侧看到连接开关弹回）。
                // 删除后由 App 决定新的激活项并广播（KernelConfig.ensureProfile 在激活项变化时会广播）。
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)

                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        when {
            name.isBlank() -> throw IllegalArgumentException("Empty name")

            source.isEmpty() && type != Profile.Type.File -> throw IllegalArgumentException("Invalid url")

            source.isNotEmpty() && scheme != "https" -> throw IllegalArgumentException("Only https subscriptions are supported")

            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 -> throw IllegalArgumentException("Invalid interval")
        }
    }
}
