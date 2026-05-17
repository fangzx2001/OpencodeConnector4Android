package com.opencode.remote.data.datastore

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.opencode.remote.data.api.dto.ServerInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.serverDataStore: DataStore<Preferences> by preferencesDataStore(name = "server_prefs")

private const val SERVER_ENCRYPTED_PREFS_NAME = "server_encrypted_prefs"

class ServerManager(
    private val context: Context,
    private val json: Json,
) {
    companion object {
        private const val TAG = "ServerManager"
        private const val KEY_SAVED_SERVERS = "saved_servers"
        private const val KEY_LAST_ACTIVE_SERVER_ID = "last_active_server_id"
        private const val KEY_MIGRATION_DONE = "server_migration_done"
        private const val PASSWORD_KEY_PREFIX = "server_pwd_"
    }

    private object Keys {
        val SAVED_SERVERS = stringPreferencesKey(KEY_SAVED_SERVERS)
        val LAST_ACTIVE_SERVER_ID = stringPreferencesKey(KEY_LAST_ACTIVE_SERVER_ID)
        val MIGRATION_DONE = booleanPreferencesKey(KEY_MIGRATION_DONE)
    }

    // --- Existing DataStore keys for migration ---
    private object LegacyKeys {
        val HOST = stringPreferencesKey("connection_host")
        val PORT = intPreferencesKey("connection_port")
        val USERNAME = stringPreferencesKey("connection_username")
        val USE_TLS = booleanPreferencesKey("connection_use_tls")
        val INSECURE_TRUST = booleanPreferencesKey("connection_insecure_trust")
    }

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences? by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                SERVER_ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
            null
        }
    }

    // Use the same encrypted prefs file as ConnectionPreferences for migration access
    private val legacyEncryptedPrefs: SharedPreferences? by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                "connection_encrypted_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create legacy EncryptedSharedPreferences", e)
            null
        }
    }

    val servers: Flow<List<ServerInfo>> = context.serverDataStore.data
        .map { prefs ->
            val jsonStr = prefs[Keys.SAVED_SERVERS]
            if (jsonStr.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    json.decodeFromString(ListSerializer(ServerInfo.serializer()), jsonStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to deserialize servers", e)
                    emptyList()
                }
            }
        }

    val lastActiveServerId: Flow<String?> = context.serverDataStore.data
        .map { prefs ->
            prefs[Keys.LAST_ACTIVE_SERVER_ID]
        }

    suspend fun saveServers(servers: List<ServerInfo>) {
        try {
            val jsonStr = json.encodeToString(ListSerializer(ServerInfo.serializer()), servers)
            context.serverDataStore.edit { it[Keys.SAVED_SERVERS] = jsonStr }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save servers", e)
        }
    }

    suspend fun addServer(info: ServerInfo, password: String) {
        val current = servers.first().toMutableList()
        // Deduplicate by host+port+username to prevent duplicates
        val existingIndex = current.indexOfFirst {
            it.id == info.id ||
                (it.host == info.host && it.port == info.port && it.username == info.username)
        }
        if (existingIndex >= 0) {
            // Update existing entry (keep original id)
            current[existingIndex] = info.copy(id = current[existingIndex].id)
        } else {
            current.add(info)
        }
        saveServers(current)
        setPassword(info.id, password)
    }

    suspend fun deleteServer(id: String) {
        val current = servers.first().toMutableList()
        current.removeAll { it.id == id }
        saveServers(current)
        removePassword(id)
    }

    fun getPassword(id: String): String? {
        return try {
            encryptedPrefs?.getString("${PASSWORD_KEY_PREFIX}$id", null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read password for server $id", e)
            null
        }
    }

    private fun setPassword(id: String, password: String) {
        try {
            encryptedPrefs?.edit()?.putString("${PASSWORD_KEY_PREFIX}$id", password)?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write password for server $id", e)
        }
    }

    private fun removePassword(id: String) {
        try {
            encryptedPrefs?.edit()?.remove("${PASSWORD_KEY_PREFIX}$id")?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove password for server $id", e)
        }
    }

    suspend fun saveLastActiveServerId(id: String) {
        try {
            context.serverDataStore.edit { it[Keys.LAST_ACTIVE_SERVER_ID] = id }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save last active server id", e)
        }
    }

    /**
     * If the old single-server config (connection_host) exists and saved_servers is empty,
     * auto-migrate the old config into a ServerInfo entry and copy the old password.
     */
    suspend fun migrateIfNeeded() {
        try {
            val prefs = context.serverDataStore.data.first()
            if (prefs[Keys.MIGRATION_DONE] == true) return

            val currentServers = prefs[Keys.SAVED_SERVERS]
            if (!currentServers.isNullOrBlank()) {
                // servers already exist, mark migration done
                context.serverDataStore.edit { it[Keys.MIGRATION_DONE] = true }
                return
            }

            // Read legacy fields from the existing connection_prefs DataStore
            val legacyPrefs = context.dataStore.data.first()
            val host = legacyPrefs[LegacyKeys.HOST].orEmpty()
            if (host.isBlank()) {
                // No legacy config to migrate
                context.serverDataStore.edit { it[Keys.MIGRATION_DONE] = true }
                return
            }

            val port = legacyPrefs[LegacyKeys.PORT] ?: 4096
            val username = legacyPrefs[LegacyKeys.USERNAME].orEmpty()
            val useTls = legacyPrefs[LegacyKeys.USE_TLS] ?: false
            val insecureTrust = legacyPrefs[LegacyKeys.INSECURE_TRUST] ?: false

            val serverId = "migrated_${System.currentTimeMillis()}"
            val serverName = if (username.isNotBlank()) "$username@$host" else host

            val migratedServer = ServerInfo(
                id = serverId,
                name = serverName,
                host = host,
                port = port,
                username = username,
                useTls = useTls,
                insecureTrust = insecureTrust,
            )

            // Copy password from legacy encrypted prefs
            val legacyPassword = try {
                legacyEncryptedPrefs?.getString("encrypted_password", null).orEmpty()
            } catch (_: Exception) {
                ""
            }

            saveServers(listOf(migratedServer))
            if (legacyPassword.isNotEmpty()) {
                setPassword(serverId, legacyPassword)
            }
            saveLastActiveServerId(serverId)

            context.serverDataStore.edit { it[Keys.MIGRATION_DONE] = true }
            Log.i(TAG, "Migrated legacy connection to server: $serverName")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
        }
    }
}
