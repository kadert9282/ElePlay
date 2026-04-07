package com.example.ytdownloader.storage

import android.content.Context
import android.net.Uri

class StoragePrefs(context: Context) {

    private val prefs = context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SAVE_TREE_URI = "save_tree_uri"
    }

    fun setSaveTreeUri(uri: Uri?) {
        prefs.edit().putString(KEY_SAVE_TREE_URI, uri?.toString()).apply()
    }

    fun getSaveTreeUri(): Uri? {
        val value = prefs.getString(KEY_SAVE_TREE_URI, null) ?: return null
        return Uri.parse(value)
    }

    fun clearSaveTreeUri() {
        prefs.edit().remove(KEY_SAVE_TREE_URI).apply()
    }
}