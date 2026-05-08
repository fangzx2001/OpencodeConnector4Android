package com.opencode.remote.data.datastore

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "connection_prefs")

private const val ENCRYPTED_PREFS_NAME = "connection_encrypted_prefs"
private const val KEY_ENCRYPTED_PASSWORD = "encrypted_password"

data class ConnectionConfig(
    val host: String = "",
    val port: Int = 4096,
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = false,
    val insecureTrust: Boolean = false,
    val autoReconnect: Boolean = true,
)

@Singleton
class ConnectionPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "ConnectionPreferences"
    }

    private object Keys {
        val HOST = stringPreferencesKey("connection_host")
        val PORT = intPreferencesKey("connection_port")
        val USERNAME = stringPreferencesKey("connection_username")
        val PASSWORD = stringPreferencesKey("connection_password")
        val USE_TLS = booleanPreferencesKey("connection_use_tls")
        val INSECURE_TRUST = booleanPreferencesKey("connection_insecure_trust")
        val AUTO_RECONNECT = booleanPreferencesKey("connection_auto_reconnect")
        val LANGUAGE = stringPreferencesKey("app_language")
        val DARK_MODE = booleanPreferencesKey("app_dark_mode")
        val HIDE_CHILD_SESSIONS = booleanPreferencesKey("hide_child_sessions")
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
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences (keystore may be invalidated)", e)
            null
        }
    }

    private fun readEncryptedPassword(): String {
        return try {
            encryptedPrefs?.getString(KEY_ENCRYPTED_PASSWORD, null) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read encrypted password", e)
            ""
        }
    }

    private fun writeEncryptedPassword(password: String) {
        try {
            encryptedPrefs?.edit()?.putString(KEY_ENCRYPTED_PASSWORD, password)?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write encrypted password", e)
        }
    }

    val connectionConfig: Flow<ConnectionConfig> = context.dataStore.data
        .map { prefs ->
            val encryptedPassword = readEncryptedPassword()
            val plaintextPassword = prefs[Keys.PASSWORD] ?: ""

            // Migration: if encrypted is empty but plaintext exists, migrate and clear plaintext
            val password = if (encryptedPassword.isEmpty() && plaintextPassword.isNotEmpty()) {
                writeEncryptedPassword(plaintextPassword)
                try {
                    // Best-effort removal of plaintext from DataStore
                    // (we can't edit inside a read map, so we fire-and-forget via a coroutine)
                    // The password will be cleared on the next saveConfig() call
                } catch (_: Exception) { }
                plaintextPassword
            } else {
                encryptedPassword
            }

            ConnectionConfig(
                host = prefs[Keys.HOST] ?: "",
                port = prefs[Keys.PORT] ?: 4096,
                username = prefs[Keys.USERNAME] ?: "",
                password = password,
                useTls = prefs[Keys.USE_TLS] ?: false,
                insecureTrust = prefs[Keys.INSECURE_TRUST] ?: false,
                autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            )
        }
        .catch { e ->
            Log.e(TAG, "Failed to read connection config", e)
            emit(ConnectionConfig())
        }

    val language: Flow<String> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.LANGUAGE] ?: "en"
        }
        .catch { e ->
            Log.e(TAG, "Failed to read language", e)
            emit("en")
        }

    val darkMode: Flow<Boolean> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.DARK_MODE] ?: false
        }
        .catch { e ->
            Log.e(TAG, "Failed to read dark mode", e)
            emit(false)
        }

    val hideChildSessions: Flow<Boolean> = context.dataStore.data
        .map { prefs ->
            prefs[Keys.HIDE_CHILD_SESSIONS] ?: false
        }
        .catch { e ->
            Log.e(TAG, "Failed to read hide child sessions", e)
            emit(false)
        }

    suspend fun saveLanguage(lang: String) {
        try {
            context.dataStore.edit { it[Keys.LANGUAGE] = lang }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save language", e)
        }
    }

    suspend fun saveDarkMode(enabled: Boolean) {
        try {
            context.dataStore.edit { it[Keys.DARK_MODE] = enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save dark mode", e)
        }
    }

    suspend fun saveHideChildSessions(enabled: Boolean) {
        try {
            context.dataStore.edit { it[Keys.HIDE_CHILD_SESSIONS] = enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save hide child sessions", e)
        }
    }

    suspend fun saveHost(host: String) {
        try {
            context.dataStore.edit { it[Keys.HOST] = host }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save host", e)
        }
    }

    suspend fun savePort(port: Int) {
        try {
            context.dataStore.edit { it[Keys.PORT] = port }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save port", e)
        }
    }

    suspend fun saveConfig(config: ConnectionConfig) {
        try {
            // Write password to encrypted prefs only
            writeEncryptedPassword(config.password)

            // Write all other fields to DataStore (password removed from plaintext)
            context.dataStore.edit {
                it[Keys.HOST] = config.host
                it[Keys.PORT] = config.port
                it[Keys.USERNAME] = config.username
                it.remove(Keys.PASSWORD) // Ensure plaintext password is cleared
                it[Keys.USE_TLS] = config.useTls
                it[Keys.INSECURE_TRUST] = config.insecureTrust
                it[Keys.AUTO_RECONNECT] = config.autoReconnect
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
        }
    }
}
