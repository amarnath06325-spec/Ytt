package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.databinding.ActivityMainBinding
import com.example.databinding.PopupChromeMenuBinding
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var isLoadingPage = false
    private var isIncognitoMode = false
    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null

    // For file uploads / downloads
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    // Desktop user agent string
    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private var defaultUserAgent: String = ""

    // Pending Download State for runtime permission request
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadUserAgent: String? = null
    private var pendingDownloadContentDisposition: String? = null
    private var pendingDownloadMimeType: String? = null

    // BroadcastReceiver for DownloadManager completion
    private var downloadCompleteReceiver: BroadcastReceiver? = null

    // Runtime Permission Launcher
    private val requestStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                val url = pendingDownloadUrl
                if (url != null) {
                    executeNativeDownload(
                        url = url,
                        userAgent = pendingDownloadUserAgent ?: defaultUserAgent,
                        contentDisposition = pendingDownloadContentDisposition,
                        mimeType = pendingDownloadMimeType
                    )
                }
            } else {
                Toast.makeText(this, "Storage permission is required to save downloads", Toast.LENGTH_LONG).show()
            }
            pendingDownloadUrl = null
            pendingDownloadUserAgent = null
            pendingDownloadContentDisposition = null
            pendingDownloadMimeType = null
        }

    // Runtime Permission Launcher
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            results.forEach { (permission, isGranted) ->
                if (!isGranted) {
                    when (permission) {
                        Manifest.permission.CAMERA ->
                            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
                        Manifest.permission.RECORD_AUDIO ->
                            Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
                        Manifest.permission.ACCESS_FINE_LOCATION ->
                            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    // Google Sign In Client & ActivityResultLauncher
    private lateinit var googleSignInClient: GoogleSignInClient
    private val googleSignInLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                GoogleAuthHelper.handleSignInResult(
                    data = result.data,
                    onSuccess = { account ->
                        val email = account.email ?: ""
                        val name = account.displayName ?: email.substringBefore("@")
                        val photoUrl = account.photoUrl?.toString()
                        val idToken = account.idToken

                        BrowserPreferences.saveSyncedAccount(
                            this,
                            name = name,
                            email = email,
                            photoUrl = photoUrl
                        )

                        Toast.makeText(this, "Synced with Google: $name ($email)", Toast.LENGTH_SHORT).show()
                        updateProfileStatusUI()

                        if (!idToken.isNullOrBlank()) {
                            passCredentialToWebView(idToken, email)
                        }
                    },
                    onError = { errorMessage ->
                        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
                        updateProfileStatusUI()
                    }
                )
            } else {
                Toast.makeText(this, "Sign in cancelled", Toast.LENGTH_SHORT).show()
                updateProfileStatusUI()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme mode before inflating view
        BrowserPreferences.applyTheme(BrowserPreferences.getThemeMode(this))
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDownloadReceiver()
        setupWebView()
        setupTopAddressBar()
        setupHomeShortcuts()
        setupIncognitoMode()
        setupProfileAndSync()
        setupBackPressedHandler()
        checkInitialPermissions()

        // Handle incoming intent (e.g. system default browser link) or restore session
        handleIncomingIntentOrSession(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntentOrSession(intent)
    }

    override fun onResume() {
        super.onResume()
        updateProfileStatusUI()
    }

    override fun onPause() {
        super.onPause()
        if (!isIncognitoMode && binding.webView.visibility == View.VISIBLE && binding.webView.url != null) {
            BrowserPreferences.saveActiveUrl(this, binding.webView.url)
        }
        if (!isIncognitoMode) {
            CookieManager.getInstance().flush()
        }
    }

    // -------------------------------------------------------------
    // WebView Configuration & Clients
    // -------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        defaultUserAgent = webView.settings.userAgentString

        // Cookie persistence
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true

            // Multi-window support for Google OAuth & popup logins
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            // Media & HTML5 video playback
            mediaPlaybackRequiresUserGesture = false

            // Zoom & Responsive rendering
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false

            // Caching & fast performance
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        // Apply desktop mode if previously enabled
        if (BrowserPreferences.isDesktopMode(this)) {
            webView.settings.userAgentString = desktopUserAgent
        }

        // Custom WebChromeClient for Video Fullscreen, Popups, Progress & Permissions
        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.pageProgressBar.visibility = View.VISIBLE
                    binding.pageProgressBar.progress = newProgress
                } else {
                    binding.pageProgressBar.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (binding.webView.visibility == View.VISIBLE && !binding.etUrl.hasFocus()) {
                    val currentUrl = view?.url
                    if (!currentUrl.isNullOrBlank()) {
                        binding.etUrl.setText(currentUrl)
                    }
                }
            }

            // Smart HTML5 / YouTube Fullscreen video playback
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    onHideCustomView()
                    return
                }

                customVideoView = view
                customVideoCallback = callback

                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.browserMainLayout.visibility = View.GONE

                // Allow dynamic screen rotation in fullscreen mode
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() {
                if (customVideoView == null) return

                binding.fullscreenContainer.visibility = View.GONE
                binding.fullscreenContainer.removeView(customVideoView)
                customVideoView = null
                customVideoCallback?.onCustomViewHidden()
                customVideoCallback = null

                binding.browserMainLayout.visibility = View.VISIBLE
                // Restore standard orientation
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            // Google OAuth and Pop-up window handling
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (BrowserPreferences.isBlockPopups(this@MainActivity) && !isUserGesture) {
                    return false
                }

                // Temporary WebView to capture popup navigation and handle OAuth
                val popupWebView = WebView(this@MainActivity)
                popupWebView.settings.javaScriptEnabled = true
                popupWebView.settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true)

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false

                        // Detect Google Sign In / OAuth redirect
                        if (url.contains("accounts.google.com") || url.contains("oauth") || url.contains("signin")) {
                            // Trigger native Google Credential Chooser
                            triggerGoogleSignInForWeb()
                        }

                        // Load redirected URL into main browser view
                        loadWebUrl(url)
                        popupWebView.destroy()
                        return true
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popupWebView
                resultMsg?.sendToTarget()
                return true
            }

            // Geolocation permission
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (!BrowserPreferences.isLocationAllowed(this@MainActivity)) {
                    callback?.invoke(origin, false, false)
                    return
                }

                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    callback?.invoke(origin, true, false)
                } else {
                    requestPermissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                    callback?.invoke(origin, true, false)
                }
            }

            // Camera and Microphone permissions
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val requestedResources = request.resources
                val grantedList = mutableListOf<String>()

                for (res in requestedResources) {
                    if (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                        BrowserPreferences.isCameraAllowed(this@MainActivity)
                    ) {
                        grantedList.add(res)
                    } else if (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE &&
                        BrowserPreferences.isMicAllowed(this@MainActivity)
                    ) {
                        grantedList.add(res)
                    }
                }

                if (grantedList.isNotEmpty()) {
                    request.grant(grantedList.toTypedArray())
                } else {
                    request.deny()
                }
            }
        }

        // Custom WebViewClient for fast navigation and UI sync
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Intercept 'Continue with Google' or OAuth URLs
                if (url.contains("accounts.google.com/o/oauth2") ||
                    (url.contains("google.com") && url.contains("signin"))
                ) {
                    triggerGoogleSignInForWeb()
                }

                // Handle external protocols (tel, mailto, etc.)
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (_: Exception) {
                        return true
                    }
                }

                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                isLoadingPage = true
                binding.btnReloadStop.setImageResource(R.drawable.ic_close)
                binding.btnReloadStop.contentDescription = getString(R.string.stop)

                if (!url.isNullOrBlank() && !binding.etUrl.hasFocus()) {
                    binding.etUrl.setText(url)
                }

                // Update security lock indicator
                if (url?.startsWith("https://") == true) {
                    binding.ivSecurityStatus.setImageResource(R.drawable.ic_lock)
                    binding.ivSecurityStatus.setColorFilter(
                        ContextCompat.getColor(this@MainActivity, R.color.google_green)
                    )
                } else {
                    binding.ivSecurityStatus.setImageResource(R.drawable.ic_shield)
                    binding.ivSecurityStatus.setColorFilter(
                        ContextCompat.getColor(this@MainActivity, R.color.browser_text_secondary)
                    )
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                isLoadingPage = false
                binding.btnReloadStop.setImageResource(R.drawable.ic_refresh)
                binding.btnReloadStop.contentDescription = getString(R.string.reload)
                binding.pageProgressBar.visibility = View.GONE

                if (!isIncognitoMode) {
                    CookieManager.getInstance().flush()
                    if (!url.isNullOrBlank()) {
                        BrowserPreferences.saveActiveUrl(this@MainActivity, url)
                    }
                }
            }
        }

        // Intercept file download requests (PDF, images, APK, videos) using Android's native DownloadManager
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            handleDownloadRequest(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun setupDownloadReceiver() {
        downloadCompleteReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (downloadId != -1L) {
                        handleDownloadCompletion(downloadId)
                    }
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadCompleteReceiver, filter)
        }
    }

    private fun handleDownloadCompletion(downloadId: Long) {
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = if (statusIndex != -1) cursor.getInt(statusIndex) else -1

                val titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE)
                val title = if (titleIndex != -1) cursor.getString(titleIndex) ?: "file" else "file"

                val sizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val totalSize = if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L

                val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val localUriStr = if (uriIndex != -1) cursor.getString(uriIndex) else null
                val filePath = localUriStr?.let { Uri.parse(it).path }

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    DownloadHistoryStore.updateStatus(
                        context = this,
                        id = downloadId,
                        status = "COMPLETED",
                        filePath = filePath,
                        fileSize = if (totalSize > 0) totalSize else null
                    )
                    Toast.makeText(this, "Download complete: $title", Toast.LENGTH_SHORT).show()
                } else if (status == DownloadManager.STATUS_FAILED) {
                    DownloadHistoryStore.updateStatus(
                        context = this,
                        id = downloadId,
                        status = "FAILED"
                    )
                    Toast.makeText(this, "Download failed: $title", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun handleDownloadRequest(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownloadUrl = url
                pendingDownloadUserAgent = userAgent
                pendingDownloadContentDisposition = contentDisposition
                pendingDownloadMimeType = mimeType
                requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
        }
        executeNativeDownload(url, userAgent, contentDisposition, mimeType)
    }

    private fun executeNativeDownload(
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val guessFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("cookie", cookies)
                }
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading $guessFileName")
                setTitle(guessFileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessFileName)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (dm != null) {
                val downloadId = dm.enqueue(request)
                // Track in in-app DownloadHistoryStore
                val item = DownloadItem(
                    id = downloadId,
                    fileName = guessFileName,
                    url = url,
                    filePath = null,
                    mimeType = mimeType,
                    fileSize = 0L,
                    status = "DOWNLOADING",
                    timestamp = System.currentTimeMillis()
                )
                DownloadHistoryStore.addOrUpdateDownload(this, item)
                Toast.makeText(this, "Downloading $guessFileName...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Download Manager unavailable", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------
    // Top Address Bar & Omnibox Actions
    // -------------------------------------------------------------
    private fun setupTopAddressBar() {
        // Home Button
        binding.btnHome.setOnClickListener {
            showHomeTab()
        }

        // Enter key in omnibox
        binding.etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val input = binding.etUrl.text.toString().trim()
                if (input.isNotEmpty()) {
                    hideKeyboard()
                    val targetUrl = BrowserPreferences.buildSearchUrl(this, input)
                    loadWebUrl(targetUrl)
                }
                true
            } else {
                false
            }
        }

        // Reload / Stop Button
        binding.btnReloadStop.setOnClickListener {
            if (isLoadingPage) {
                binding.webView.stopLoading()
                isLoadingPage = false
                binding.btnReloadStop.setImageResource(R.drawable.ic_refresh)
                binding.pageProgressBar.visibility = View.GONE
            } else {
                if (binding.webView.visibility == View.VISIBLE) {
                    binding.webView.reload()
                } else {
                    val input = binding.etUrl.text.toString().trim()
                    if (input.isNotEmpty()) {
                        loadWebUrl(BrowserPreferences.buildSearchUrl(this, input))
                    }
                }
            }
        }

        // 3-Dot Settings Menu
        binding.btnMenu.setOnClickListener { view ->
            showBrowserPopupMenu(view)
        }
    }

    private fun showBrowserPopupMenu(anchorView: View) {
        val inflater = LayoutInflater.from(this)
        val menuBinding = PopupChromeMenuBinding.inflate(inflater, null, false)

        val popupWindow = PopupWindow(
            menuBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 16f
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
        }

        // Apply incognito visual styling to menu if active
        if (isIncognitoMode) {
            menuBinding.chromeMenuContainer.setBackgroundResource(R.drawable.bg_chrome_menu_incognito)
            menuBinding.tvMenuIncognitoTitle.text = getString(R.string.exit_incognito)
            menuBinding.ivMenuIncognitoIcon.setImageResource(R.drawable.ic_close)
        } else {
            menuBinding.chromeMenuContainer.setBackgroundResource(R.drawable.bg_chrome_menu)
            menuBinding.tvMenuIncognitoTitle.text = getString(R.string.new_incognito_tab)
            menuBinding.ivMenuIncognitoIcon.setImageResource(R.drawable.ic_incognito)
        }

        // Desktop site checkbox state
        val isDesktop = BrowserPreferences.isDesktopMode(this)
        menuBinding.menuCbDesktopSite.isChecked = isDesktop

        // Quick Action 1: Forward
        menuBinding.menuBtnForward.isEnabled = binding.webView.canGoForward()
        menuBinding.menuBtnForward.alpha = if (binding.webView.canGoForward()) 1.0f else 0.4f
        menuBinding.menuBtnForward.setOnClickListener {
            popupWindow.dismiss()
            if (binding.webView.canGoForward()) {
                binding.webView.goForward()
            }
        }

        // Quick Action 2: Reload
        menuBinding.menuBtnReload.setOnClickListener {
            popupWindow.dismiss()
            binding.webView.reload()
        }

        // Quick Action 3: Bookmark
        menuBinding.menuBtnBookmark.setOnClickListener {
            popupWindow.dismiss()
            val currentUrl = binding.webView.url ?: ""
            val currentTitle = binding.webView.title ?: "Page"
            if (currentUrl.isNotBlank()) {
                Toast.makeText(this, "Bookmarked: $currentTitle", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Cannot bookmark blank page", Toast.LENGTH_SHORT).show()
            }
        }

        // Quick Action 4: Download Page / File
        menuBinding.menuBtnDownload.setOnClickListener {
            popupWindow.dismiss()
            val currentUrl = binding.webView.url
            if (!currentUrl.isNullOrBlank() && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) {
                handleDownloadRequest(
                    url = currentUrl,
                    userAgent = binding.webView.settings.userAgentString,
                    contentDisposition = null,
                    mimeType = null
                )
            } else {
                Toast.makeText(this, "Nothing to download on this page", Toast.LENGTH_SHORT).show()
            }
        }

        // Quick Action 5: Page Info
        menuBinding.menuBtnInfo.setOnClickListener {
            popupWindow.dismiss()
            val currentUrl = binding.webView.url ?: "None"
            val isSecure = currentUrl.startsWith("https://")
            MaterialAlertDialogBuilder(this)
                .setTitle(if (isSecure) "Connection is secure" else "Connection not secure")
                .setMessage("URL: $currentUrl\n\nSecurity: ${if (isSecure) "Encrypted with HTTPS" else "Unencrypted HTTP"}")
                .setPositiveButton("OK", null)
                .show()
        }

        // Menu Item: New Tab
        menuBinding.menuRowNewTab.setOnClickListener {
            popupWindow.dismiss()
            if (isIncognitoMode) {
                exitIncognitoMode()
            }
            showHomeTab()
        }

        // Menu Item: New Incognito Tab / Exit Incognito
        menuBinding.menuRowIncognito.setOnClickListener {
            popupWindow.dismiss()
            if (isIncognitoMode) {
                exitIncognitoMode()
            } else {
                enterIncognitoMode()
            }
        }

        // Menu Item: History
        menuBinding.menuRowHistory.setOnClickListener {
            popupWindow.dismiss()
            MaterialAlertDialogBuilder(this)
                .setTitle("Browsing History")
                .setMessage("Recent tabs and synced history are enabled with your Google Account.")
                .setPositiveButton("Close", null)
                .show()
        }

        // Menu Item: Downloads History
        menuBinding.menuRowDownloads.setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        // Menu Item: Desktop site
        menuBinding.menuRowDesktopSite.setOnClickListener {
            popupWindow.dismiss()
            val newMode = !menuBinding.menuCbDesktopSite.isChecked
            menuBinding.menuCbDesktopSite.isChecked = newMode
            BrowserPreferences.setDesktopMode(this, newMode)
            binding.webView.settings.userAgentString =
                if (newMode) desktopUserAgent else defaultUserAgent
            binding.webView.reload()
            Toast.makeText(
                this,
                if (newMode) "Desktop site enabled" else "Mobile site enabled",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Menu Item: Clear Browsing Data
        menuBinding.menuRowClearData.setOnClickListener {
            popupWindow.dismiss()
            binding.webView.clearCache(true)
            Toast.makeText(this, "Browsing cache cleared", Toast.LENGTH_SHORT).show()
        }

        // Menu Item: Settings
        menuBinding.menuRowSettings.setOnClickListener {
            popupWindow.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Anchor popup under the 3-dot button
        popupWindow.showAsDropDown(anchorView, -180, 0, Gravity.NO_GRAVITY)
    }

    // -------------------------------------------------------------
    // Home Tab & Shortcuts (New Tab Page)
    // -------------------------------------------------------------
    private fun setupHomeShortcuts() {
        // NTP Center Search Card
        binding.cardNtpSearch.setOnClickListener {
            binding.etUrl.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etUrl, InputMethodManager.SHOW_IMPLICIT)
        }

        // Shortcuts Grid Click Listeners
        binding.shortcutGoogle.setOnClickListener { loadWebUrl("https://www.google.com") }
        binding.shortcutYoutube.setOnClickListener { loadWebUrl("https://www.youtube.com") }
        binding.shortcutFacebook.setOnClickListener { loadWebUrl("https://www.facebook.com") }
        binding.shortcutPinterest.setOnClickListener { loadWebUrl("https://www.pinterest.com") }
        binding.shortcutInstagram.setOnClickListener { loadWebUrl("https://www.instagram.com") }
        binding.shortcutGithub.setOnClickListener { loadWebUrl("https://www.github.com") }
        binding.shortcutWikipedia.setOnClickListener { loadWebUrl("https://www.wikipedia.org") }
        binding.shortcutReddit.setOnClickListener { loadWebUrl("https://www.reddit.com") }

        // NTP Sync Prompt Pill
        binding.pillSyncPrompt.setOnClickListener {
            triggerGoogleSignIn()
        }
    }

    // -------------------------------------------------------------
    // Incognito / Private Browsing Mode
    // -------------------------------------------------------------
    private fun setupIncognitoMode() {
        // Exit button on toolbar
        binding.btnIncognitoIndicator.setOnClickListener {
            exitIncognitoMode()
        }

        // Search card on Incognito landing screen
        binding.cardIncognitoSearch.setOnClickListener {
            binding.etUrl.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etUrl, InputMethodManager.SHOW_IMPLICIT)
        }

        // Exit button on Incognito landing screen
        binding.btnExitIncognitoLarge.setOnClickListener {
            exitIncognitoMode()
        }
    }

    fun enterIncognitoMode() {
        if (isIncognitoMode) return
        isIncognitoMode = true

        // Configure WebView settings for incognito (no disk cache, no persistent cookies)
        binding.webView.settings.apply {
            cacheMode = WebSettings.LOAD_NO_CACHE
            domStorageEnabled = false
            databaseEnabled = false
            saveFormData = false
        }
        CookieManager.getInstance().setAcceptCookie(false)

        // Clear existing session from WebView
        binding.webView.stopLoading()
        binding.webView.clearHistory()
        binding.webView.clearCache(true)
        binding.webView.clearFormData()

        // Apply dark incognito theme styling to browser chrome
        applyIncognitoChromeTheme(true)

        // Show incognito home tab
        showIncognitoLandingPage()

        Toast.makeText(this, "Entered Incognito Mode", Toast.LENGTH_SHORT).show()
    }

    fun exitIncognitoMode() {
        if (!isIncognitoMode) return
        isIncognitoMode = false

        // Clear all session artifacts produced during incognito
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.webView.clearHistory()
        binding.webView.clearCache(true)
        binding.webView.clearFormData()
        binding.webView.clearSslPreferences()

        // Restore normal WebView settings
        binding.webView.settings.apply {
            cacheMode = WebSettings.LOAD_DEFAULT
            domStorageEnabled = true
            databaseEnabled = true
            saveFormData = true
        }
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        // Restore regular theme styling to browser chrome
        applyIncognitoChromeTheme(false)

        // Return to standard Home Tab
        showHomeTab()

        Toast.makeText(this, "Exited Incognito Mode", Toast.LENGTH_SHORT).show()
    }

    private fun showIncognitoLandingPage() {
        hideKeyboard()
        binding.webView.visibility = View.GONE
        binding.homeTabContainer.visibility = View.GONE
        binding.incognitoTabContainer.visibility = View.VISIBLE
        binding.etUrl.setText("")
        binding.etUrl.hint = "Search privately or type URL"
        binding.ivSecurityStatus.setImageResource(R.drawable.ic_incognito)
        binding.ivSecurityStatus.setColorFilter(
            ContextCompat.getColor(this, R.color.incognito_accent)
        )
        binding.btnReloadStop.setImageResource(R.drawable.ic_refresh)
        binding.pageProgressBar.visibility = View.GONE
    }

    private fun applyIncognitoChromeTheme(isIncognito: Boolean) {
        if (isIncognito) {
            binding.topToolbar.setBackgroundColor(
                ContextCompat.getColor(this, R.color.incognito_toolbar_bg)
            )
            binding.omniboxContainer.setBackgroundResource(R.drawable.bg_omnibox_incognito)
            binding.etUrl.setTextColor(
                ContextCompat.getColor(this, R.color.incognito_text_primary)
            )
            binding.etUrl.setHintTextColor(
                ContextCompat.getColor(this, R.color.incognito_text_secondary)
            )
            binding.btnHome.setColorFilter(
                ContextCompat.getColor(this, R.color.incognito_text_primary)
            )
            binding.btnReloadStop.setColorFilter(
                ContextCompat.getColor(this, R.color.incognito_text_primary)
            )
            binding.btnMenu.setColorFilter(
                ContextCompat.getColor(this, R.color.incognito_text_primary)
            )
            binding.pageProgressBar.progressTintList =
                ContextCompat.getColorStateList(this, R.color.incognito_accent)
            binding.profileContainer.visibility = View.GONE
            binding.btnIncognitoIndicator.visibility = View.VISIBLE
        } else {
            binding.topToolbar.setBackgroundColor(
                ContextCompat.getColor(this, R.color.browser_toolbar_bg)
            )
            binding.omniboxContainer.setBackgroundResource(R.drawable.bg_omnibox)
            binding.etUrl.setTextColor(
                ContextCompat.getColor(this, R.color.browser_text_primary)
            )
            binding.etUrl.setHintTextColor(
                ContextCompat.getColor(this, R.color.browser_text_secondary)
            )
            binding.btnHome.setColorFilter(
                ContextCompat.getColor(this, R.color.browser_text_secondary)
            )
            binding.btnReloadStop.setColorFilter(
                ContextCompat.getColor(this, R.color.browser_text_secondary)
            )
            binding.btnMenu.setColorFilter(
                ContextCompat.getColor(this, R.color.browser_text_secondary)
            )
            binding.pageProgressBar.progressTintList =
                ContextCompat.getColorStateList(this, R.color.browser_primary)
            binding.profileContainer.visibility = View.VISIBLE
            binding.btnIncognitoIndicator.visibility = View.GONE
        }
    }

    // -------------------------------------------------------------
    // Google Identity / GIS Integration
    // -------------------------------------------------------------
    private fun setupProfileAndSync() {
        googleSignInClient = GoogleAuthHelper.getGoogleSignInClient(this)

        binding.profileContainer.setOnClickListener {
            triggerGoogleSignIn()
        }
        updateProfileStatusUI()
    }

    private fun triggerGoogleSignIn() {
        val (currentName, currentEmail, _) = BrowserPreferences.getSyncedAccount(this)
        if (!currentName.isNullOrBlank() && !currentEmail.isNullOrBlank()) {
            // Already synced, open settings
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        GoogleAuthHelper.startSignIn(googleSignInClient) { intent ->
            googleSignInLauncher.launch(intent)
        }
    }

    private fun triggerGoogleSignInForWeb() {
        GoogleAuthHelper.startSignIn(googleSignInClient) { intent ->
            googleSignInLauncher.launch(intent)
        }
    }

    private fun passCredentialToWebView(token: String, email: String) {
        // Pass OAuth token / credential to webpage if listening for Google One Tap
        val js = """
            if (window.onGoogleCredentialReceived) {
                window.onGoogleCredentialReceived({ token: '$token', email: '$email' });
            } else {
                window.postMessage({ type: 'GOOGLE_CREDENTIAL_RESPONSE', token: '$token', email: '$email' }, '*');
            }
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun updateProfileStatusUI() {
        val (name, email, photoUrl) = BrowserPreferences.getSyncedAccount(this)
        if (name != null && email != null) {
            binding.viewSyncStatusBadge.visibility = View.VISIBLE
            binding.viewSyncStatusBadge.setBackgroundColor(
                ContextCompat.getColor(this, R.color.google_green)
            )
            binding.tvSyncStatus.text = "Synced: $name"

            // Update profile icon in toolbar
            if (!photoUrl.isNullOrBlank()) {
                ImageLoaderHelper.loadCircularImage(
                    lifecycleScope,
                    binding.ivProfile,
                    photoUrl
                )
            } else {
                binding.ivProfile.setImageResource(R.drawable.ic_account_circle)
            }
        } else {
            binding.viewSyncStatusBadge.visibility = View.GONE
            binding.tvSyncStatus.text = getString(R.string.account_not_signed_in)
            binding.ivProfile.setImageResource(R.drawable.ic_account_circle)
        }
    }

    // -------------------------------------------------------------
    // Navigation & State Management
    // -------------------------------------------------------------
    fun loadWebUrl(url: String) {
        binding.homeTabContainer.visibility = View.GONE
        binding.incognitoTabContainer.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.etUrl.setText(url)
        binding.webView.loadUrl(url)
    }

    private fun showHomeTab() {
        if (isIncognitoMode) {
            showIncognitoLandingPage()
            return
        }
        hideKeyboard()
        binding.webView.visibility = View.GONE
        binding.incognitoTabContainer.visibility = View.GONE
        binding.homeTabContainer.visibility = View.VISIBLE
        binding.etUrl.setText("")
        binding.etUrl.hint = getString(R.string.search_or_type_url)
        binding.ivSecurityStatus.setImageResource(R.drawable.ic_search)
        binding.ivSecurityStatus.setColorFilter(
            ContextCompat.getColor(this, R.color.browser_primary)
        )
        binding.btnReloadStop.setImageResource(R.drawable.ic_refresh)
        binding.pageProgressBar.visibility = View.GONE
    }

    private fun handleIncomingIntentOrSession(intent: Intent?) {
        val intentData = intent?.dataString
        if (!intentData.isNullOrBlank()) {
            loadWebUrl(intentData)
            return
        }

        // Check restored session URL
        val savedUrl = BrowserPreferences.getActiveUrl(this)
        if (!savedUrl.isNullOrBlank()) {
            loadWebUrl(savedUrl)
        } else {
            showHomeTab()
        }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1. Exit Fullscreen Video if active
                if (binding.fullscreenContainer.visibility == View.VISIBLE) {
                    binding.webView.webChromeClient?.onHideCustomView()
                    return
                }

                // 2. Navigate back in WebView history
                if (binding.webView.visibility == View.VISIBLE && binding.webView.canGoBack()) {
                    binding.webView.goBack()
                    return
                }

                // 3. If on a webpage with no further history, return to appropriate NTP
                if (binding.webView.visibility == View.VISIBLE) {
                    if (isIncognitoMode) {
                        showIncognitoLandingPage()
                    } else {
                        showHomeTab()
                    }
                    return
                }

                // 4. If in Incognito mode on the landing page, exiting back exits Incognito
                if (isIncognitoMode) {
                    exitIncognitoMode()
                    return
                }

                // 5. If already on Home Tab NTP, exit the app
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let {
            imm?.hideSoftInputFromWindow(it.windowToken, 0)
        }
        binding.etUrl.clearFocus()
    }

    private fun checkInitialPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
        downloadCompleteReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            downloadCompleteReceiver = null
        }
        binding.webView.destroy()
        super.onDestroy()
    }
}
