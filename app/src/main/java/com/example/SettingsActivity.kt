package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupGoogleAccountSection()
        setupSearchEngineSection()
        setupPrivacySection()
        setupPermissionsSection()
        setupAppearanceSection()
        setupDefaultBrowserSection()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupGoogleAccountSection() {
        updateAccountUI()

        binding.btnAccountAction.setOnClickListener {
            val (name, _) = BrowserPreferences.getSyncedAccount(this)
            if (name == null) {
                // Sign In
                GoogleAuthHelper.signIn(
                    activity = this,
                    scope = lifecycleScope,
                    onSuccess = { signedName, email, _ ->
                        Toast.makeText(this, "Signed in as $signedName ($email)", Toast.LENGTH_SHORT).show()
                        updateAccountUI()
                    },
                    onError = { err ->
                        Toast.makeText(this, "Sign in failed: $err", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                // Sign Out
                GoogleAuthHelper.signOut(this, lifecycleScope) {
                    Toast.makeText(this, "Signed out of Google Sync", Toast.LENGTH_SHORT).show()
                    updateAccountUI()
                }
            }
        }
    }

    private fun updateAccountUI() {
        val (name, email) = BrowserPreferences.getSyncedAccount(this)
        if (name != null && email != null) {
            binding.tvSettingsAccountName.text = name
            binding.tvSettingsAccountEmail.text = getString(R.string.account_signed_in_prefix, email)
            binding.btnAccountAction.text = getString(R.string.sign_out)
        } else {
            binding.tvSettingsAccountName.text = getString(R.string.account_sync_title)
            binding.tvSettingsAccountEmail.text = getString(R.string.account_not_signed_in)
            binding.btnAccountAction.text = getString(R.string.sign_in)
        }
    }

    private fun setupSearchEngineSection() {
        when (BrowserPreferences.getSearchEngine(this)) {
            BrowserPreferences.ENGINE_BING -> binding.rbBing.isChecked = true
            BrowserPreferences.ENGINE_DUCKDUCKGO -> binding.rbDuckDuckGo.isChecked = true
            else -> binding.rbGoogle.isChecked = true
        }

        binding.rgSearchEngine.setOnCheckedChangeListener { _, checkedId ->
            val engine = when (checkedId) {
                R.id.rbBing -> BrowserPreferences.ENGINE_BING
                R.id.rbDuckDuckGo -> BrowserPreferences.ENGINE_DUCKDUCKGO
                else -> BrowserPreferences.ENGINE_GOOGLE
            }
            BrowserPreferences.setSearchEngine(this, engine)
            Toast.makeText(this, "Default search set to ${engine.replaceFirstChar { it.uppercase() }}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPrivacySection() {
        binding.switchBlockPopups.isChecked = BrowserPreferences.isBlockPopups(this)
        binding.switchBlockPopups.setOnCheckedChangeListener { _, isChecked ->
            BrowserPreferences.setBlockPopups(this, isChecked)
        }

        // Clear Browsing History
        binding.rowClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_history)
                .setMessage("Are you sure you want to clear your visited pages and active session?")
                .setPositiveButton("Clear") { _, _ ->
                    BrowserPreferences.clearActiveUrl(this)
                    try {
                        val webView = WebView(this)
                        webView.clearHistory()
                        webView.destroy()
                    } catch (_: Exception) {}
                    Toast.makeText(this, "Browsing history cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Clear Cache
        binding.rowClearCache.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_cache)
                .setMessage("Clear cached web files and images to free up storage?")
                .setPositiveButton("Clear") { _, _ ->
                    try {
                        val webView = WebView(this)
                        webView.clearCache(true)
                        webView.destroy()
                        cacheDir.deleteRecursively()
                    } catch (_: Exception) {}
                    Toast.makeText(this, "Web cache cleared successfully", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Clear Cookies
        binding.rowClearCookies.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_cookies)
                .setMessage("Clearing cookies will sign you out of most active websites.")
                .setPositiveButton("Clear") { _, _ ->
                    CookieManager.getInstance().removeAllCookies { success ->
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                        runOnUiThread {
                            Toast.makeText(this, if (success) "Cookies removed" else "Cookies cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupPermissionsSection() {
        binding.switchCamera.isChecked = BrowserPreferences.isCameraAllowed(this)
        binding.switchCamera.setOnCheckedChangeListener { _, isChecked ->
            BrowserPreferences.setCameraAllowed(this, isChecked)
        }

        binding.switchMic.isChecked = BrowserPreferences.isMicAllowed(this)
        binding.switchMic.setOnCheckedChangeListener { _, isChecked ->
            BrowserPreferences.setMicAllowed(this, isChecked)
        }

        binding.switchLocation.isChecked = BrowserPreferences.isLocationAllowed(this)
        binding.switchLocation.setOnCheckedChangeListener { _, isChecked ->
            BrowserPreferences.setLocationAllowed(this, isChecked)
        }

        binding.switchNotifications.isChecked = BrowserPreferences.isNotificationsAllowed(this)
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            BrowserPreferences.setNotificationsAllowed(this, isChecked)
        }
    }

    private fun setupAppearanceSection() {
        val currentMode = BrowserPreferences.getThemeMode(this)
        binding.switchDarkMode.isChecked = currentMode == "dark"

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) "dark" else "light"
            BrowserPreferences.setThemeMode(this, mode)
        }
    }

    private fun setupDefaultBrowserSection() {
        binding.btnSetDefaultBrowser.setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    startActivity(intent)
                } else {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Open System Settings > Default Apps to select Anup Web", Toast.LENGTH_LONG).show()
            }
        }
    }
}
