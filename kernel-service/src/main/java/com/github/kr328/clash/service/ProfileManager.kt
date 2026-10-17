package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.directoryLastModified
import com.github.kr328.clash.service.util.generateProfileUUID
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import java.io.FileNotFoundException
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileManager(private val context: Context) :
    IProfileManager,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {
    private val store = ServiceStore(context)

    init {
        launch {
            Database.database
        }
    }

    override suspend fun create(type: Profile.Type, name: String, source: String, ageSecretKey: String?): UUID {
        val uuid = generateProfileUUID()
        val pending = Pending(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            interval = 0,
            upload = 0,
            total = 0,
            download = 0,
            expire = 0,
            ageSecretKey = ageSecretKey,
        )

        PendingDao().insert(pending)

        context.pendingDir.resolve(uuid.toString()).apply {
            deleteRecursively()
            mkdirs()

            @Suppress("BlockingMethodInNonBlockingContext")
            resolve("config.yaml").createNewFile()
            resolve("providers").mkdir()
        }

        return uuid
    }

    override suspend fun commit(uuid: UUID) {
        ProfileProcessor.apply(context, uuid)

        // 内核侧定时抓取会绕过 app 脱敏并热重载原始订阅，订阅更新由 App 端统一负责
    }

    override suspend fun delete(uuid: UUID) {
        ProfileProcessor.delete(context, uuid)
    }

    override suspend fun queryByUUID(uuid: UUID): Profile? = resolveProfile(uuid)

    override suspend fun queryAll(): List<Profile> {
        val uuids = withContext(Dispatchers.IO) {
            (ImportedDao().queryAllUUIDs() + PendingDao().queryAllUUIDs()).distinct()
        }

        return uuids.mapNotNull { resolveProfile(it) }
    }

    override suspend fun queryActive(): Profile? {
        val active = store.activeProfile ?: return null

        return if (ImportedDao().exists(active)) {
            resolveProfile(active)
        } else {
            null
        }
    }

    override suspend fun setActive(profile: Profile) {
        ProfileProcessor.active(context, profile.uuid)
    }

    private suspend fun resolveProfile(uuid: UUID): Profile? {
        val imported = ImportedDao().queryByUUID(uuid)
        val pending = PendingDao().queryByUUID(uuid)

        val active = store.activeProfile
        val name = pending?.name ?: imported?.name ?: return null
        val type = pending?.type ?: imported?.type ?: return null
        val source = pending?.source ?: imported?.source ?: return null
        val interval = pending?.interval ?: imported?.interval ?: return null
        val upload = pending?.upload ?: imported?.upload ?: return null
        val download = pending?.download ?: imported?.download ?: return null
        val total = pending?.total ?: imported?.total ?: return null
        val expire = pending?.expire ?: imported?.expire ?: return null

        return Profile(
            uuid = uuid,
            name = name,
            type = type,
            source = source,
            active = active != null && imported?.uuid == active,
            interval = interval,
            upload = upload,
            download = download,
            total = total,
            expire = expire,
            updatedAt = resolveUpdatedAt(uuid),
            imported = imported != null,
            pending = pending != null,
            ageSecretKey = if (pending != null) pending.ageSecretKey else imported?.ageSecretKey,
        )
    }

    private fun resolveUpdatedAt(uuid: UUID): Long = context.pendingDir.resolve(uuid.toString()).directoryLastModified
        ?: context.importedDir.resolve(uuid.toString()).directoryLastModified
        ?: -1

    private fun cloneImportedFiles(source: UUID, target: UUID = source) {
        val s = context.importedDir.resolve(source.toString())
        val t = context.pendingDir.resolve(target.toString())

        if (!s.exists()) {
            throw FileNotFoundException("profile $source not found")
        }

        t.deleteRecursively()

        s.copyRecursively(t)
    }
}
