package com.slte.app.kernel

import android.content.Context
import com.slte.app.BuildConfig
import com.slte.app.kernel.api.KernelApi
import com.slte.app.kernel.api.KernelProfileType
import com.slte.app.kernel.api.KernelType
import com.slte.app.utils.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun profileNameFor(email: String?): String {
    if (email.isNullOrBlank()) return "SLTE"
    val digest = MessageDigest.getInstance("SHA-256").digest(email.toByteArray(Charsets.UTF_8))
    return "SLTE-" + digest.joinToString("") { "%02x".format(it) }
}

enum class ProfileUpdateResult {
    UPDATED,
    UNCHANGED,
    FAILED,
}

@Singleton
class KernelConfig
@Inject
constructor(
    private val faultReporter: KernelFaultReporter,
    private val manager: KernelApi,
    private val subscribeSource: SubscribeSource,
    private val remoteConfig: AppRemoteConfig,
    @ApplicationContext private val context: Context,
) {

    private val profileMutex = Mutex()

    @Volatile
    private var healAttemptedFor: UUID? = null

    private suspend fun <T> safe(
        default: T,
        operation: String,
        tag: String = DEFAULT_FAULT_TAG,
        block: suspend () -> T,
    ): T = faultReporter.guard(default, operation, tag, block)

    suspend fun ensureProfile(): UUID? = profileMutex.withLock { ensureProfileLocked() }

    /**
     * 快速路径判定：本地已有可用配置（导入完成且文件存在）时无需等待网络。
     * 仅做本地检查，不触发下载；返回 true 时连接可直接 startVpn。
     */
    suspend fun hasReadyProfile(): Boolean = runCatching {
        val profiles = manager.awaitProfile() ?: return@runCatching false
        val expectedName = profileName()
        val profile = profiles.queryAll().firstOrNull { it.name == expectedName && it.imported } ?: return@runCatching false
        val dir = context.filesDir.resolve("imported/${profile.uuid}")
        dir.resolve(ProfileFiles.CONFIG_FILE).exists() && profiles.queryActive()?.uuid == profile.uuid
    }.getOrDefault(false)

    private suspend fun ensureProfileLocked(): UUID? = safe(null, "ensureProfileLocked") {
        cleanupStalePending()
        val profiles = manager.awaitProfile() ?: return@safe null
        val subscribeUrl = subscribeUrl() ?: return@safe null
        val expectedName = profileName()

        val byName = profiles.queryAll().filter { it.name == expectedName }
        val current = byName.firstOrNull { it.source == subscribeUrl }
        byName.filter { it != current }.forEach { profiles.delete(it.uuid) }
        profiles
            .queryAll()
            .filter { it.source == subscribeUrl && it.name != expectedName }
            .forEach { profiles.delete(it.uuid) }

        val uuid = current?.uuid ?: profiles.create(KernelProfileType.URL, expectedName, subscribeUrl)

        if (current == null || !current.imported) {
            if (!downloadSubscribeToPending(uuid)) return@safe null
            profiles.commit(uuid)
        } else if (healIfDefective(uuid)) {
            AppLog.w("SLTE-Kernel", "ensureProfile: 现有配置存在重名等致命问题，已用订阅重新写入")
            profiles.commit(uuid)
        }
        val profile = profiles.queryByUUID(uuid) ?: return@safe null
        val activeChanged = profiles.queryActive()?.uuid != uuid
        if (activeChanged) {
            profiles.setActive(uuid)
        }

        // 必须始终同步：切换内核类型时即使 profile 未变也要重写（yaml↔json / 预设）
        val synced = syncProfileFiles(uuid)
        if (activeChanged || synced) {
            manager.notifyProfileChanged(uuid)
        }
        uuid
    }

    private suspend fun downloadSubscribeToPending(uuid: UUID): Boolean {
        val yaml = readSubscribeYaml() ?: return false
        val domains = directDomains().ifEmpty { return false }
        val cleaned = sanitizeOrNull(yaml, domains) ?: return false
        if (!SubscriptionSanitizer.isKernelLoadable(cleaned)) {
            AppLog.w("SLTE-Kernel", "downloadSubscribeToPending: 订阅存在重名等致命问题，拒绝写入")
            return false
        }
        val dir = context.filesDir.resolve("pending/$uuid")
        return writeProfileDir(dir, cleaned)
    }

    private fun importedConfigDefective(uuid: UUID): Boolean {
        val file = context.filesDir.resolve("imported/$uuid/config.yaml")
        if (!file.exists()) return true
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        return !SubscriptionSanitizer.isKernelLoadable(text)
    }

    private suspend fun healIfDefective(uuid: UUID): Boolean {
        if (!importedConfigDefective(uuid)) return false
        if (healAttemptedFor == uuid) return false
        healAttemptedFor = uuid
        return downloadSubscribeToPending(uuid)
    }

    suspend fun updateProfile(): ProfileUpdateResult = safe(ProfileUpdateResult.FAILED, "updateProfile") {
        profileMutex.withLock {
            val profiles = manager.awaitProfile() ?: return@withLock ProfileUpdateResult.FAILED
            val subscribeUrl = subscribeUrl() ?: return@withLock ProfileUpdateResult.FAILED
            val expectedName = profileName()
            val profile =
                profiles.queryAll().firstOrNull {
                    it.name == expectedName && it.source == subscribeUrl && it.imported
                }
            if (profile == null) {
                AppLog.i("SLTE-Kernel", "updateProfile: 配置不存在，先重新导入")
                val uuid = ensureProfileLocked() ?: return@withLock ProfileUpdateResult.FAILED
                subscribeSource.saveSubscriptionUpdatedAt()
                return@withLock ProfileUpdateResult.UPDATED
            }

            AppLog.d("SLTE-Kernel", "updateProfile: downloading subscription")
            val yaml = readSubscribeYaml() ?: return@withLock ProfileUpdateResult.FAILED
            AppLog.d("SLTE-Kernel", "updateProfile: yaml size=${yaml.length}")

            val domains = directDomains().ifEmpty { return@withLock ProfileUpdateResult.FAILED }
            val cleaned = sanitizeOrNull(yaml, domains) ?: return@withLock ProfileUpdateResult.FAILED
            if (!SubscriptionSanitizer.isKernelLoadable(cleaned)) {
                AppLog.w("SLTE-Kernel", "updateProfile: 订阅存在重名等致命问题，保留现有配置")
                return@withLock ProfileUpdateResult.FAILED
            }
            val dir = context.filesDir.resolve("imported/${profile.uuid}")
            // 统一由写入路径判定是否变化（含 sing-box 的 config.json 栈变化）
            if (writeProfileDir(dir, cleaned, onlyIfChanged = true)) {
                manager.notifyProfileChanged(profile.uuid)
                subscribeSource.saveSubscriptionUpdatedAt()
                return@withLock ProfileUpdateResult.UPDATED
            }
            AppLog.d("SLTE-Kernel", "updateProfile: 订阅内容未变化，跳过内核重载")
            subscribeSource.saveSubscriptionUpdatedAt()
            return@withLock ProfileUpdateResult.UNCHANGED
        }
    }

    private suspend fun readSubscribeYaml(): String? {
        val body = subscribeSource.fetchSubscribeYaml()
        if (body == null) {
            AppLog.w("SLTE-Kernel", "readSubscribeYaml: 订阅响应体为空，拒绝写入")
            return null
        }
        val text = body.byteStream().use { readLimited(it, MAX_SUBSCRIPTION_BYTES) }
        if (text == null) {
            AppLog.w("SLTE-Kernel", "readSubscribeYaml: 订阅超过大小上限 ${MAX_SUBSCRIPTION_BYTES / 1024 / 1024}MB，拒绝写入")
            return null
        }
        if (!SubscriptionSanitizer.isValidSubscribeYaml(text)) {
            AppLog.w("SLTE-Kernel", "readSubscribeYaml: 响应不是有效 Clash 订阅，拒绝写入")
            return null
        }
        return text
    }

    private fun readLimited(
        input: java.io.InputStream,
        maxBytes: Int,
    ): String? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            buffer.write(chunk, 0, n)
        }

        return String(buffer.toByteArray(), Charsets.UTF_8)
    }

    /** profile 目录与当前内核对齐（直连域名或内核类型变化即重写）；返回是否需要通知内核重载。 */
    suspend fun injectDirectRule(uuid: UUID): Boolean = syncProfileFiles(uuid)

    private suspend fun syncProfileFiles(uuid: UUID): Boolean {
        val dir = context.filesDir.resolve("imported/$uuid")
        val pristine = dir.resolve(ProfileFiles.PRISTINE_FILE)
        val config = dir.resolve(ProfileFiles.CONFIG_FILE)
        if (!config.exists() && !pristine.exists()) return false

        val pristineText = runCatching {
            if (pristine.exists()) pristine.readText() else config.readText()
        }.getOrNull() ?: return false
        val cleaned = sanitizeOrNull(pristineText, directDomains()) ?: return false
        val facing = kernelFacing(cleaned)

        val configText = runCatching { config.readText() }.getOrNull().orEmpty()
        val marker = runCatching { dir.resolve(ProfileFiles.MARKER_FILE).readText() }.getOrNull().orEmpty()
        val changed = cleaned != pristineText || facing != configText || marker != manager.type.storageKey
        if (!changed) return false

        return writeProfileDir(dir, cleaned)
    }

    /** 一次写齐：内核加载的 config.yaml、pristine 副本、内核类型标记。 */
    /**
     * 写入 profile 目录；[onlyIfChanged] 为 true 时内容一致则跳过并返回 false。
     * 返回 true 表示确实写入了新内容。
     */
    private suspend fun writeProfileDir(
        dir: java.io.File,
        cleaned: String,
        onlyIfChanged: Boolean = false,
    ): Boolean {
        if (onlyIfChanged && dir.resolve(ProfileFiles.CONFIG_FILE).let { it.exists() && it.readText() == kernelFacing(cleaned) }) {
            return false
        }
        ProfileFiles.write(dir, kernelFacing(cleaned), cleaned, manager.type.storageKey)
        return true
    }

    /** mihomo_smart 注入智能分组预设；mihomo 原样。 */
    private fun kernelFacing(cleaned: String): String = if (manager.type == KernelType.MIHOMO_SMART) SmartPreset.apply(cleaned) else cleaned

    private fun profileName(): String = profileNameFor(subscribeSource.getEmail())

    suspend fun deleteAccountProfiles(email: String?): Boolean = safe(false, "deleteAccountProfiles") {
        val normalizedEmail = email?.trim()?.takeIf { it.isNotEmpty() } ?: return@safe false
        val profiles = manager.awaitProfile() ?: return@safe false
        val expectedName = profileNameFor(normalizedEmail)
        profiles
            .queryAll()
            .filter { it.name == expectedName }
            .forEach { profiles.delete(it.uuid) }
        true
    }

    private fun directDomains(): List<String> {
        val configured = remoteConfig.directDomains
        val current = apiDomain() ?: return configured
        return buildList {
            if (current !in this) add(current)
            configured.filter { it != current }.forEach { if (it !in this) add(it) }
        }
    }

    private fun sanitizeOrNull(
        yaml: String,
        domains: List<String>,
    ): String? = SubscriptionSanitizer.sanitize(yaml, domains).takeIf { it.isNotBlank() }

    private fun apiDomain(): String? {
        val host = runCatching { java.net.URI(remoteConfig.apiBaseUrl).host }.getOrNull() ?: return null
        val labels = host.split(".")
        return if (labels.size >= 2) labels.takeLast(2).joinToString(".") else host
    }

    private fun cleanupStalePending() {
        runCatching {
            val cutoff = System.currentTimeMillis() - PENDING_DIR_MAX_AGE_MS
            context.filesDir
                .resolve("pending")
                .listFiles()
                ?.filter { it.isDirectory && it.lastModified() < cutoff }
                ?.forEach { it.deleteRecursively() }
        }
    }

    companion object {

        const val MAX_SUBSCRIPTION_BYTES = 20 * 1024 * 1024

        private const val PENDING_DIR_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }

    private fun subscribeUrl(): String? = remoteConfig.apiBaseUrl.trimEnd('/') + BuildConfig.SUBSCRIBE_PATH
}
