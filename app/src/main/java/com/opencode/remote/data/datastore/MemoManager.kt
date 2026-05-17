package com.opencode.remote.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.opencode.remote.data.api.dto.MemoEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.memoDataStore: DataStore<Preferences> by preferencesDataStore(name = "memo_prefs")

/**
 * Manages per-project memo persistence via DataStore.
 * Follows the same pattern as [ServerManager] — JSON-serialized list stored as a single string key.
 */
class MemoManager(
    private val context: Context,
    private val json: Json,
) {
    companion object {
        private const val TAG = "MemoManager"
        private const val KEY_MEMOS = "project_memos"
    }

    private object Keys {
        val MEMOS = stringPreferencesKey(KEY_MEMOS)
    }

    /** Reactive flow of memos for a given project directory. */
    fun memosForDirectory(directory: String): Flow<List<MemoEntry>> =
        context.memoDataStore.data.map { prefs ->
            val all = decodeMemos(prefs)
            all.filter { it.directory == directory }
        }

    /** Load memos for a directory (one-shot). */
    suspend fun loadMemos(directory: String): List<MemoEntry> {
        val prefs = context.memoDataStore.data.first()
        return decodeMemos(prefs).filter { it.directory == directory }
    }

    /** Add a new memo entry. */
    suspend fun addMemo(entry: MemoEntry) {
        context.memoDataStore.edit { prefs ->
            val all = decodeMemos(prefs).toMutableList()
            all.add(entry)
            encodeMemos(prefs, all)
        }
    }

    /** Update an existing memo (matched by id). */
    suspend fun updateMemo(updated: MemoEntry) {
        context.memoDataStore.edit { prefs ->
            val all = decodeMemos(prefs).toMutableList()
            val idx = all.indexOfFirst { it.id == updated.id }
            if (idx >= 0) {
                all[idx] = updated
            } else {
                all.add(updated)
            }
            encodeMemos(prefs, all)
        }
    }

    /** Delete a memo by id. */
    suspend fun deleteMemo(memoId: String) {
        context.memoDataStore.edit { prefs ->
            val all = decodeMemos(prefs).toMutableList()
            all.removeAll { it.id == memoId }
            encodeMemos(prefs, all)
        }
    }

    private fun decodeMemos(prefs: Preferences): List<MemoEntry> {
        val jsonStr = prefs[Keys.MEMOS] ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(MemoEntry.serializer()), jsonStr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode memos", e)
            emptyList()
        }
    }

    private fun encodeMemos(prefs: MutablePreferences, memos: List<MemoEntry>) {
        try {
            prefs[Keys.MEMOS] = json.encodeToString(ListSerializer(MemoEntry.serializer()), memos)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode memos", e)
        }
    }
}
