package com.example.ytdownloader.storage

import android.content.Context
import com.example.ytdownloader.model.FormatItem
import com.example.ytdownloader.model.VideoInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class UiStateStorage(context: Context) {

    private val prefs = context.getSharedPreferences("ui_state_storage", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_VIDEO_INFO = "video_info"
        private const val KEY_FORMATS = "formats"
        private const val KEY_SELECTED_FORMAT = "selected_format"
        private const val KEY_VIDEO_SECTION_EXPANDED = "video_section_expanded"
    }

    fun saveLastUrl(url: String) {
        prefs.edit().putString(KEY_LAST_URL, url).apply()
    }

    fun getLastUrl(): String {
        return prefs.getString(KEY_LAST_URL, "") ?: ""
    }

    fun saveVideoInfo(info: VideoInfo?) {
        prefs.edit().putString(KEY_VIDEO_INFO, gson.toJson(info)).apply()
    }

    fun getVideoInfo(): VideoInfo? {
        val json = prefs.getString(KEY_VIDEO_INFO, null) ?: return null
        return try {
            gson.fromJson(json, VideoInfo::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun saveFormats(formats: List<FormatItem>) {
        prefs.edit().putString(KEY_FORMATS, gson.toJson(formats)).apply()
    }

    fun getFormats(): List<FormatItem> {
        val json = prefs.getString(KEY_FORMATS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<FormatItem>>() {}.type
            gson.fromJson<List<FormatItem>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSelectedFormat(format: FormatItem?) {
        prefs.edit().putString(KEY_SELECTED_FORMAT, gson.toJson(format)).apply()
    }

    fun getSelectedFormat(): FormatItem? {
        val json = prefs.getString(KEY_SELECTED_FORMAT, null) ?: return null
        return try {
            gson.fromJson(json, FormatItem::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun saveVideoSectionExpanded(expanded: Boolean) {
        prefs.edit().putBoolean(KEY_VIDEO_SECTION_EXPANDED, expanded).apply()
    }

    fun isVideoSectionExpanded(): Boolean {
        return prefs.getBoolean(KEY_VIDEO_SECTION_EXPANDED, true)
    }

    fun clearVideoState() {
        prefs.edit()
            .remove(KEY_VIDEO_INFO)
            .remove(KEY_FORMATS)
            .remove(KEY_SELECTED_FORMAT)
            .apply()
    }
}