package com.example.ytdownloader.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.ytdownloader.model.PlaylistInfo
import com.example.ytdownloader.model.PlaylistItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PlaylistStateStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("playlist_ui_state_v2", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun savePlaylistUrl(url: String) {
        prefs.edit().putString(KEY_URL, url).apply()
    }

    fun getPlaylistUrl(): String? = prefs.getString(KEY_URL, null)

    fun savePlaylistInfo(url: String, info: PlaylistInfo, items: List<PlaylistItem>) {
        prefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_INFO, gson.toJson(info))
            .putString(KEY_ITEMS, gson.toJson(items))
            .apply()
    }

    fun getPlaylistInfo(): PlaylistInfo? {
        val json = prefs.getString(KEY_INFO, null) ?: return null
        return runCatching {
            gson.fromJson(json, PlaylistInfo::class.java)
        }.getOrNull()
    }

    fun saveItems(items: List<PlaylistItem>) {
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }

    fun getItems(): List<PlaylistItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<PlaylistItem>>() {}.type
            gson.fromJson<List<PlaylistItem>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    // Сохраняется для совместимости с вызовами из ViewModel
    fun saveQueueState(currentIndex: Int, isDownloading: Boolean) {
        prefs.edit()
            .putInt(KEY_CURRENT_INDEX, currentIndex)
            .putBoolean(KEY_IS_DOWNLOADING, isDownloading)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_URL = "playlist_url"
        const val KEY_INFO = "playlist_info"
        const val KEY_ITEMS = "playlist_items"
        const val KEY_CURRENT_INDEX = "current_index"
        const val KEY_IS_DOWNLOADING = "is_downloading"
    }
}