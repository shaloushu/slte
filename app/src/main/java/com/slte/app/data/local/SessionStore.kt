package com.slte.app.data.local

import android.content.SharedPreferences
import androidx.core.content.edit
import com.slte.app.data.repository.CachePolicy
import com.slte.app.domain.model.ServerNode
import com.slte.app.domain.model.SpecialNodeSnapshot
import com.slte.app.domain.model.SubscribeInfo
import com.slte.app.domain.model.User
import com.slte.app.kernel.SpeedResultStore
import com.slte.app.utils.AppLog
import com.slte.app.utils.sanitizeLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class SessionStore
@Inject
constructor(
    @SessionPrefs private val prefs: SharedPreferences,
) : SpeedResultStore {

    fun hasSession(): Boolean = prefs.contains(KEY_AUTH_DATA) && prefs.contains(KEY_SUBSCRIBE_TOKEN)

    fun getAuthData(): String? = prefs.getString(KEY_AUTH_DATA, null)

    fun getEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun getSubscribeToken(): String? = prefs.getString(KEY_SUBSCRIBE_TOKEN, null)

    fun getSubscribeUrl(): String? = prefs.getString(KEY_SUBSCRIBE_URL, null)

    fun saveSubscribeUrl(url: String) {
        prefs.edit { putString(KEY_SUBSCRIBE_URL, url) }
    }

    fun save(
        authData: String,
        email: String,
        subscribeToken: String,
    ) {
        prefs.edit {
            putString(KEY_AUTH_DATA, authData)
            putString(KEY_EMAIL, email)
            putString(KEY_SUBSCRIBE_TOKEN, subscribeToken)
            // 记录缓存归属账号：同账号重新登录保留本地数据，换账号才清（见 AuthRepository）
            putString(KEY_CACHE_OWNER, normalizeOwner(email))
        }
    }

    /** 本地缓存的归属账号（小写邮箱）；null = 未知，按"需要清理"处理。 */
    fun cacheOwner(): String? = prefs.getString(KEY_CACHE_OWNER, null)

    private fun normalizeOwner(email: String): String = email.trim().lowercase()

    private inline fun <reified T> readCached(
        key: String,
        decode: (String) -> T,
    ): T? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            decode(raw)
        } catch (e: Exception) {
            AppLog.w("SLTE-Session", "SessionStore: 缓存解析失败 key=$key: ${sanitizeLog(e.message ?: "Unknown")}")
            prefs.edit { remove(key) }
            null
        }
    }

    fun saveSubscribeInfo(info: SubscribeInfo) {
        prefs.edit {
            putString(KEY_SUBSCRIBE_INFO, Json.encodeToString(info))
        }
    }

    fun getSubscribeInfo(): SubscribeInfo? = readCached(KEY_SUBSCRIBE_INFO) { Json.decodeFromString<SubscribeInfo>(it) }

    fun saveUserInfo(user: User) {
        prefs.edit {
            putString(KEY_USER_INFO, Json.encodeToString(user))
        }
    }

    fun getUserInfo(): User? = readCached(KEY_USER_INFO) { Json.decodeFromString<User>(it) }

    fun saveSubscriptionUpdatedAt(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(KEY_SUBSCRIPTION_UPDATED_AT, timestamp) }
    }

    fun getSubscriptionUpdatedAt(): Long = prefs.getLong(KEY_SUBSCRIPTION_UPDATED_AT, 0L)

    fun getServerNodesFetchedAt(): Long = prefs.getLong(KEY_SERVER_NODES_FETCHED_AT, 0L)

    fun saveServerNodes(nodes: List<ServerNode>) {
        prefs.edit {
            putString(KEY_SERVER_NODES, Json.encodeToString(nodes))
            putLong(KEY_SERVER_NODES_FETCHED_AT, System.currentTimeMillis())
        }
    }

    fun getServerNodes(): List<ServerNode>? = readCached(KEY_SERVER_NODES) { Json.decodeFromString<List<ServerNode>>(it) }

    fun clearServerNodes() {
        prefs.edit {
            remove(KEY_SERVER_NODES)
            remove(KEY_SERVER_NODES_FETCHED_AT)
        }
    }

    fun saveAutoNodeSnapshot(snapshot: SpecialNodeSnapshot) {
        prefs.edit { putString(KEY_SPECIAL_AUTO, Json.encodeToString(snapshot)) }
    }

    fun getAutoNodeSnapshot(): SpecialNodeSnapshot? = readCached(KEY_SPECIAL_AUTO) { Json.decodeFromString<SpecialNodeSnapshot>(it) }

    fun saveFallbackNodeSnapshot(snapshot: SpecialNodeSnapshot) {
        prefs.edit { putString(KEY_SPECIAL_FALLBACK, Json.encodeToString(snapshot)) }
    }

    fun getFallbackNodeSnapshot(): SpecialNodeSnapshot? = readCached(KEY_SPECIAL_FALLBACK) { Json.decodeFromString<SpecialNodeSnapshot>(it) }

    fun clearSpecialNodeSnapshots() {
        prefs.edit {
            remove(KEY_SPECIAL_AUTO)
            remove(KEY_SPECIAL_FALLBACK)
        }
    }

    override fun saveSpeedResults(results: Map<String, Int>) {
        prefs.edit {
            putString(KEY_SPEED_RESULTS, Json.encodeToString(results))
            putLong(KEY_SPEED_RESULTS_AT, System.currentTimeMillis())
        }
    }

    override fun getSpeedResults(): Map<String, Int>? {
        val savedAt = prefs.getLong(KEY_SPEED_RESULTS_AT, 0L)
        if (!CachePolicy.isFresh(savedAt, System.currentTimeMillis(), CachePolicy.LATENCY_TTL_MS)) return null
        return readCached(KEY_SPEED_RESULTS) { Json.decodeFromString<Map<String, Int>>(it) }
    }

    override fun clearSpeedResults() {
        prefs.edit {
            remove(KEY_SPEED_RESULTS)
            remove(KEY_SPEED_RESULTS_AT)
            remove(KEY_OFFLINE_NODES)
            remove(KEY_OFFLINE_NODES_AT)
        }
    }

    /** 探测确认"后端不在了"的节点（内核名），与延迟缓存同生命周期。 */
    override fun saveOfflineNodes(names: Set<String>) {
        prefs.edit {
            putString(KEY_OFFLINE_NODES, Json.encodeToString(names))
            putLong(KEY_OFFLINE_NODES_AT, System.currentTimeMillis())
        }
    }

    override fun getOfflineNodes(): Set<String>? {
        val savedAt = prefs.getLong(KEY_OFFLINE_NODES_AT, 0L)
        if (!CachePolicy.isFresh(savedAt, System.currentTimeMillis(), CachePolicy.LATENCY_TTL_MS)) return null
        return readCached(KEY_OFFLINE_NODES) { Json.decodeFromString<Set<String>>(it) }
    }

    fun clear() {
        prefs.edit {
            remove(KEY_AUTH_DATA)
            remove(KEY_EMAIL)
            remove(KEY_SUBSCRIBE_TOKEN)
            remove(KEY_SUBSCRIBE_URL)
            remove(KEY_SUBSCRIBE_INFO)
            remove(KEY_SUBSCRIPTION_UPDATED_AT)
            remove(KEY_USER_INFO)
            remove(KEY_SERVER_NODES)
            remove(KEY_SERVER_NODES_FETCHED_AT)
            remove(KEY_SPEED_RESULTS)
            remove(KEY_SPEED_RESULTS_AT)
            remove(KEY_SPECIAL_AUTO)
            remove(KEY_SPECIAL_FALLBACK)
            remove(KEY_OFFLINE_NODES)
            remove(KEY_OFFLINE_NODES_AT)
            remove(KEY_CACHE_OWNER)
        }
    }

    fun clearDataCache() {
        prefs.edit {
            remove(KEY_SUBSCRIBE_URL)
            remove(KEY_SUBSCRIBE_INFO)
            remove(KEY_SUBSCRIPTION_UPDATED_AT)
            remove(KEY_USER_INFO)
            remove(KEY_SERVER_NODES)
            remove(KEY_SERVER_NODES_FETCHED_AT)
            remove(KEY_SPEED_RESULTS)
            remove(KEY_SPEED_RESULTS_AT)
            remove(KEY_SPECIAL_AUTO)
            remove(KEY_SPECIAL_FALLBACK)
            remove(KEY_OFFLINE_NODES)
            remove(KEY_OFFLINE_NODES_AT)
        }
    }

    internal companion object {

        internal const val PREFS_NAME = "slte_session"
        internal const val KEY_ALIAS = "slte_session_master_key"
        private const val KEY_AUTH_DATA = "auth_data"
        private const val KEY_EMAIL = "email"
        private const val KEY_SUBSCRIBE_TOKEN = "subscribe_token"
        private const val KEY_SUBSCRIBE_URL = "subscribe_url"
        private const val KEY_SUBSCRIBE_INFO = "subscribe_info"
        private const val KEY_SUBSCRIPTION_UPDATED_AT = "subscription_updated_at"
        private const val KEY_USER_INFO = "user_info"
        private const val KEY_SERVER_NODES = "server_nodes"
        private const val KEY_SERVER_NODES_FETCHED_AT = "server_nodes_fetched_at"
        private const val KEY_SPEED_RESULTS = "speed_results"
        private const val KEY_SPEED_RESULTS_AT = "speed_results_at"
        private const val KEY_SPECIAL_AUTO = "special_node_auto"
        private const val KEY_SPECIAL_FALLBACK = "special_node_fallback"
        private const val KEY_OFFLINE_NODES = "offline_nodes"
        private const val KEY_OFFLINE_NODES_AT = "offline_nodes_at"
        private const val KEY_CACHE_OWNER = "cache_owner"
    }
}
