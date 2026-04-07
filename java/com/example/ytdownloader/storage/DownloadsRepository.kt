package com.example.ytdownloader.storage

import android.content.Context
import com.example.ytdownloader.model.DownloadedItem
import com.example.ytdownloader.model.PlaylistItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DownloadsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("downloads_repo", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_ITEMS = "items"
    }

    fun getAll(): List<DownloadedItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DownloadedItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(item: DownloadedItem) {
        val updated = getAll().toMutableList().apply {
            add(0, item)
        }
        prefs.edit().putString(KEY_ITEMS, gson.toJson(updated)).apply()
    }

    fun remove(uriString: String) {
        val updated = getAll().filterNot { it.uriString == uriString }
        prefs.edit().putString(KEY_ITEMS, gson.toJson(updated)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    fun containsPlaylistItem(item: PlaylistItem): Boolean {
        val normalizedTitle = normalize(item.title)
        val normalizedId = normalize(item.id)

        return getAll().any { downloaded ->
            val downloadedTitle = normalize(downloaded.title)
            val downloadedFile = normalize(downloaded.fileName)

            downloadedTitle == normalizedTitle ||
                    downloadedFile.contains(normalizedId) ||
                    downloadedFile.contains(normalizedTitle)
        }
    }

    private fun normalize(value: String): String {
        return value.lowercase().trim()
    }
}