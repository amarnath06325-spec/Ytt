package com.example

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import java.net.URLEncoder

object BrowserPreferences {

    private const val PREFS_NAME = "anup_web_preferences"

    // Keys
    private const val KEY_LAST_URL = "key_last_active_url"
    private const val KEY_SEARCH_ENGINE = "key_search_engine"
    private const val KEY_BLOCK_POPUPS = "key_block_popups"
    private const val KEY_PERM_CAMERA = "key_perm_camera"
    private const val KEY_PERM_MIC = "key_perm_mic"
    private const val KEY_PERM_LOCATION = "key_perm_location"
    private const val KEY_PERM_NOTIFICATIONS = "key_perm_notifications"
    private const val KEY_THEME_MODE = "key_theme_mode" // "system", "dark", "light"
    private const val KEY_ACCOUNT_EMAIL = "key_account_email"
    private const val KEY_ACCOUNT_NAME = "key_account_name"
    private const val KEY_ACCOUNT_PHOTO = "key_account_photo"
    private const val KEY_DESKTOP_MODE = "key_desktop_mode"

    const val ENGINE_GOOGLE = "google"
    const val ENGINE_BING = "bing"
    const val ENGINE_DUCKDUCKGO = "duckduckgo"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Session Restore
    fun saveActiveUrl(context: Context, url: String?) {
        getPrefs(context).edit().apply {
            if (url.isNullOrBlank() || url.startsWith("about:") || url.startsWith("data:")) {
                remove(KEY_LAST_URL)
            } else {
                putString(KEY_LAST_URL, url)
            }
            apply()
        }
    }

    fun getActiveUrl(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_URL, null)
    }

    fun clearActiveUrl(context: Context) {
        getPrefs(context).edit().remove(KEY_LAST_URL).apply()
    }

    // Default Search Engine
    fun getSearchEngine(context: Context): String {
        return getPrefs(context).getString(KEY_SEARCH_ENGINE, ENGINE_GOOGLE) ?: ENGINE_GOOGLE
    }

    fun setSearchEngine(context: Context, engine: String) {
        getPrefs(context).edit().putString(KEY_SEARCH_ENGINE, engine).apply()
    }

    fun buildSearchUrl(context: Context, query: String): String {
        val trimmed = query.trim()
        // If it looks like a URL, format properly
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        if (trimmed.contains(".") && !trimmed.contains(" ") && trimmed.length > 3) {
            return "https://$trimmed"
        }

        val encodedQuery = try {
            URLEncoder.encode(trimmed, "UTF-8")
        } catch (_: Exception) {
            trimmed
        }

        return when (getSearchEngine(context)) {
            ENGINE_BING -> "https://www.bing.com/search?q=$encodedQuery"
            ENGINE_DUCKDUCKGO -> "https://duckduckgo.com/?q=$encodedQuery"
            else -> "https://www.google.com/search?q=$encodedQuery"
        }
    }

    // Privacy & Security: Pop-ups
    fun isBlockPopups(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BLOCK_POPUPS, true)
    }

    fun setBlockPopups(context: Context, block: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BLOCK_POPUPS, block).apply()
    }

    // Permissions toggles
    fun isCameraAllowed(context: Context): Boolean = getPrefs(context).getBoolean(KEY_PERM_CAMERA, true)
    fun setCameraAllowed(context: Context, allowed: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_PERM_CAMERA, allowed).apply()

    fun isMicAllowed(context: Context): Boolean = getPrefs(context).getBoolean(KEY_PERM_MIC, true)
    fun setMicAllowed(context: Context, allowed: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_PERM_MIC, allowed).apply()

    fun isLocationAllowed(context: Context): Boolean = getPrefs(context).getBoolean(KEY_PERM_LOCATION, true)
    fun setLocationAllowed(context: Context, allowed: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_PERM_LOCATION, allowed).apply()

    fun isNotificationsAllowed(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PERM_NOTIFICATIONS, true)
    fun setNotificationsAllowed(context: Context, allowed: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_PERM_NOTIFICATIONS, allowed).apply()

    // Appearance / Theme Mode
    fun getThemeMode(context: Context): String =
        getPrefs(context).getString(KEY_THEME_MODE, "system") ?: "system"

    fun setThemeMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_THEME_MODE, mode).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: String) {
        val nightMode = when (mode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    // Google Account Sync
    fun getSyncedAccount(context: Context): Triple<String?, String?, String?> {
        val name = getPrefs(context).getString(KEY_ACCOUNT_NAME, null)
        val email = getPrefs(context).getString(KEY_ACCOUNT_EMAIL, null)
        val photoUrl = getPrefs(context).getString(KEY_ACCOUNT_PHOTO, null)
        return Triple(name, email, photoUrl)
    }

    fun saveSyncedAccount(context: Context, name: String, email: String, photoUrl: String? = null) {
        getPrefs(context).edit()
            .putString(KEY_ACCOUNT_NAME, name)
            .putString(KEY_ACCOUNT_EMAIL, email)
            .apply {
                if (photoUrl != null) {
                    putString(KEY_ACCOUNT_PHOTO, photoUrl)
                } else {
                    remove(KEY_ACCOUNT_PHOTO)
                }
            }
            .apply()
    }

    fun clearSyncedAccount(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_ACCOUNT_PHOTO)
            .apply()
    }

    fun isDesktopMode(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_DESKTOP_MODE, false)

    fun setDesktopMode(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply()
}
