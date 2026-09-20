package com.example

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DownloadItem(
    val id: Long,
    val fileName: String,
    val url: String,
    val filePath: String?,
    val mimeType: String?,
    val fileSize: Long,
    val timestamp: Long,
    val status: String // "DOWNLOADING", "COMPLETED", "FAILED"
)

object DownloadHistoryStore {

    private const val PREFS_NAME = "anup_web_downloads"
    private const val KEY_DOWNLOADS = "downloads_list"

    fun getDownloads(context: Context): List<DownloadItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_DOWNLOADS, null) ?: return emptyList()
        val list = mutableListOf<DownloadItem>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    DownloadItem(
                        id = obj.optLong("id", 0L),
                        fileName = obj.optString("fileName", "download"),
                        url = obj.optString("url", ""),
                        filePath = obj.optString("filePath", null),
                        mimeType = obj.optString("mimeType", null),
                        fileSize = obj.optLong("fileSize", 0L),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        status = obj.optString("status", "COMPLETED")
                    )
                )
            }
        } catch (_: Exception) {}
        return list.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun addOrUpdateDownload(context: Context, item: DownloadItem) {
        val current = getDownloads(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.id == item.id }
        if (existingIndex >= 0) {
            current[existingIndex] = item
        } else {
            current.add(0, item)
        }
        saveDownloads(context, current)
    }

    @Synchronized
    fun updateStatus(context: Context, id: Long, status: String, filePath: String? = null, fileSize: Long? = null) {
        val current = getDownloads(context).toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            val old = current[index]
            current[index] = old.copy(
                status = status,
                filePath = filePath ?: old.filePath,
                fileSize = fileSize ?: old.fileSize
            )
            saveDownloads(context, current)
        }
    }

    @Synchronized
    fun removeDownload(context: Context, id: Long) {
        val current = getDownloads(context).toMutableList()
        current.removeAll { it.id == id }
        saveDownloads(context, current)
    }

    @Synchronized
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_DOWNLOADS).apply()
    }

    private fun saveDownloads(context: Context, list: List<DownloadItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        list.take(100).forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("fileName", item.fileName)
                put("url", item.url)
                put("filePath", item.filePath ?: "")
                put("mimeType", item.mimeType ?: "")
                put("fileSize", item.fileSize)
                put("timestamp", item.timestamp)
                put("status", item.status)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_DOWNLOADS, array.toString()).apply()
    }
}
