package com.example.ytdownloader.updater

import android.content.Context

class YtDlpUpdatePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("ytdlp_update_prefs", Context.MODE_PRIVATE)

    companion object {
        const val MODE_BUILTIN = "builtin"
        const val MODE_UPDATED = "updated"

        private const val KEY_MODE = "ytdlp_mode"
        private const val KEY_INSTALLED_VERSION = "installed_version"
    }

    fun getMode(): String =
        prefs.getString(KEY_MODE, MODE_BUILTIN) ?: MODE_BUILTIN

    fun setMode(mode: String) =
        prefs.edit().putString(KEY_MODE, mode).apply()

    fun isUpdatedMode(): Boolean = getMode() == MODE_UPDATED

    fun getInstalledVersion(): String? =
        prefs.getString(KEY_INSTALLED_VERSION, null)

    fun setInstalledVersion(version: String) =
        prefs.edit().putString(KEY_INSTALLED_VERSION, version).apply()

    fun clearInstalledVersion() =
        prefs.edit().remove(KEY_INSTALLED_VERSION).apply()
}