package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.StreamFlixApp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

class WebViewResolver(private val context: Context) {

    private companion object {
        private const val INITIAL_SCALE = 85
        private const val PAGE_ZOOM = "0.85"
    }

    data class Result(
        val html: String,
        val evaluatedValue: String? = null,
        val finalUrl: String? = null,
    )

    private var webView: WebView? = null
    private var dialog: AlertDialog? = null
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val TAG = "WebviewResolver"

    private var cursorX = 0f
    private var cursorY = 0f
    private var virtualCursor: ImageView? = null
    private var pollingCount = 0
    private var loginKeyboardPrimed = false
    private var deferredLoadUrl: String? = null
    private var deferredLoadHeaders: Map<String, String> = emptyMap()
    private var lastMobileZoomUrl: String? = null
    private val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    private val activityContext: Activity?
        get() = context.findActivity() ?: StreamFlixApp.currentActivity

    private val challengeKeywords = listOf(
        "Just a moment...", "cf-browser-verification", "challenge-running", "Checking your browser", "cloudflare"
    )

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        completion: ((currentUrl: String, html: String, cookies: String) -> Boolean)? = null,
        shouldAllowNavigation: ((url: String, isMainFrame: Boolean) -> Boolean)? = null,
        pageReadyScriptProvider: ((currentUrl: String, html: String, cookies: String) -> String?)? = null,
        showImmediately: Boolean = false,
    ): String {
        return getResult(
            url,
            headers,
            completion,
            shouldAllowNavigation,
            null,
            pageReadyScriptProvider,
            showImmediately
        ).html
    }

    suspend fun getResult(
        url: String,
        headers: Map<String, String> = emptyMap(),
        completion: ((currentUrl: String, html: String, cookies: String) -> Boolean)? = null,
        shouldAllowNavigation: ((url: String, isMainFrame: Boolean) -> Boolean)? = null,
        valueScript: String? = null,
        pageReadyScriptProvider: ((currentUrl: String, html: String, cookies: String) -> String?)? = null,
        showImmediately: Boolean = false,
    ): Result = mutex.withLock {
        Log.d(TAG, "[WebView] Fetching: $url (IsTV: $isTv)")
        pollingCount = 0
        loginKeyboardPrimed = false
        val result = withTimeoutOrNull(120000) {
            suspendCancellableCoroutine { continuation ->
                mainHandler.post {
                    setupWebView(
                        url,
                        headers,
                        completion,
                        shouldAllowNavigation,
                        valueScript,
                        pageReadyScriptProvider,
                        showImmediately,
                        continuation
                    )
                }
                continuation.invokeOnCancellation { cleanup() }
            }
        }
        if (result == null) Log.e(TAG, "[WebView] Global Timeout for $url")
        return@withLock result ?: Result(
            html = "<html><body>Timeout</body></html>",
            finalUrl = url,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(
        url: String,
        headers: Map<String, String>,
        completion: ((currentUrl: String, html: String, cookies: String) -> Boolean)?,
        shouldAllowNavigation: ((url: String, isMainFrame: Boolean) -> Boolean)?,
        valueScript: String?,
        pageReadyScriptProvider: ((currentUrl: String, html: String, cookies: String) -> String?)?,
        showImmediately: Boolean,
        continuation: kotlinx.coroutines.CancellableContinuation<Result>
    ) {
        webView = WebView(context).apply {
            setBackgroundColor(Color.WHITE)
            // IMPORTANTE: Su TV non deve essere focusable per lasciare il controllo al container
            isFocusable = !isTv
            isFocusableInTouchMode = !isTv
            isClickable = true

            // Stabilità Rendering Software per Android TV 9 (come da registro)
            if (isTv) {
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = NetworkClient.USER_AGENT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
            }
            setInitialScale(INITIAL_SCALE)

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            if (!isTv) {
                setOnTouchListener { view, _ ->
                    view.requestFocus()
                    view.requestFocusFromTouch()
                    showSoftKeyboard(view)
                    false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?,
                ): Boolean {
                    Log.d(TAG, "[WebView] Blocking popup window creation")
                    return false
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val targetUrl = request?.url?.toString().orEmpty()
                    val isMainFrame = request?.isForMainFrame ?: true
                    if (targetUrl.isBlank()) return false
                    val allowed = shouldAllowNavigation?.invoke(targetUrl, isMainFrame) ?: true
                    if (!allowed) {
                        Log.d(TAG, "[WebView] Blocked navigation: $targetUrl (mainFrame=$isMainFrame)")
                    }
                    return !allowed
                }

                override fun onPageFinished(view: WebView?, currentUrl: String?) {
                    Log.d(TAG, "[WebView] onPageFinished: $currentUrl")
                    applyMobilePageZoomOnce(view, currentUrl)
                    mainHandler.postDelayed({
                        if (webView != null) {
                            checkChallengeStatus(
                                view,
                                currentUrl ?: url,
                                completion,
                                continuation,
                                valueScript,
                                pageReadyScriptProvider
                            )
                        }
                    }, 1500)
                }
            }
        }
        Log.d(TAG, "[WebView] Opened")

        if (showImmediately && activityContext != null) {
            deferredLoadUrl = url
            deferredLoadHeaders = headers
            showVisibleChallenge(continuation)
        } else {
            webView?.loadUrl(url, headers)
        }
    }

    private fun applyMobilePageZoomOnce(view: WebView?, currentUrl: String?) {
        if (view == null || currentUrl.isNullOrBlank() || lastMobileZoomUrl == currentUrl) return
        lastMobileZoomUrl = currentUrl
        view.evaluateJavascript(
            """
                (function() {
                    const zoom = '$PAGE_ZOOM';
                    document.documentElement.style.zoom = zoom;
                    if (document.body) {
                        document.body.style.zoom = zoom;
                    }
                    return zoom;
                })();
            """.trimIndent(),
            null
        )
    }

    private fun checkChallengeStatus(
        view: WebView?,
        currentUrl: String,
        completion: ((currentUrl: String, html: String, cookies: String) -> Boolean)?,
        continuation: kotlinx.coroutines.CancellableContinuation<Result>,
        valueScript: String? = null,
        pageReadyScriptProvider: ((currentUrl: String, html: String, cookies: String) -> String?)? = null,
    ) {
        if (continuation.isCompleted || webView == null) return

        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(currentUrl) ?: ""
        val hasClearance = cookies.contains("cf_clearance")

        view?.evaluateJavascript("(function() { return document.documentElement.innerHTML; })();") { html ->
            val cleanHtml = decodeJavascriptValue(html).orEmpty()

            val isChallenge = challengeKeywords.any { cleanHtml.contains(it, ignoreCase = true) }
            val hasContent = cleanHtml.contains("article") || cleanHtml.contains("iframe") ||
                    cleanHtml.contains("TPost") || cleanHtml.contains("grid-item") ||
                    cleanHtml.contains("optnslst") // Rilevamento server Cine24h (come da registro)
            val success = completion?.invoke(currentUrl, cleanHtml, cookies)
                ?: ((!isChallenge && hasContent && cleanHtml.length > 1000) || hasClearance)

            if (!isTv && !loginKeyboardPrimed && currentUrl.contains("/login", ignoreCase = true)) {
                loginKeyboardPrimed = true
                primeLoginFocus(view)
            }

            val pageReadyScript = pageReadyScriptProvider?.invoke(currentUrl, cleanHtml, cookies)
            if (!pageReadyScript.isNullOrBlank()) {
                view?.evaluateJavascript(pageReadyScript, null)
            }

            Log.d(
                TAG,
                "[WebView] Status -> Challenge: $isChallenge, Content: $hasContent, Clearance: $hasClearance, Polling: $pollingCount"
            )

            // Se rileviamo sblocco, chiudiamo tutto subito
            if (success) {
                Log.d(TAG, "[WebView] SUCCESS detected! Closing bypass.")
                cookieManager.flush()
                if (continuation.isActive) {
                    finalizeResult(view, cleanHtml, currentUrl, valueScript, continuation)
                }
                return@evaluateJavascript
            }

            // Se dopo 2 polling (circa 3-4 secondi) non c'è contenuto, mostriamo il dialog per sbloccare
            if (dialog == null && activityContext != null && (isChallenge || (pollingCount >= 2 && !hasContent))) {
                Log.d(TAG, "[WebView] Visible challenge UI needed")
                showVisibleChallenge(continuation)
            }

            pollingCount++
            if (pollingCount < 80) {
                mainHandler.postDelayed({
                    checkChallengeStatus(
                        view,
                        currentUrl,
                        completion,
                        continuation,
                        valueScript,
                        pageReadyScriptProvider
                    )
                }, 2000)
            } else {
                Log.w(TAG, "[WebView] Max polling reached")
                if (continuation.isActive) finalizeResult(view, cleanHtml, currentUrl, valueScript, continuation)
            }
        }
    }

    private fun finalizeResult(
        view: WebView?,
        html: String,
        finalUrl: String?,
        valueScript: String?,
        continuation: kotlinx.coroutines.CancellableContinuation<Result>,
    ) {
        if (valueScript.isNullOrBlank() || view == null) {
            continuation.resume(
                Result(
                    html = "<html>$html</html>",
                    finalUrl = finalUrl,
                )
            )
            cleanup()
            return
        }

        view.evaluateJavascript(valueScript) { evaluated ->
            if (continuation.isActive) {
                continuation.resume(
                    Result(
                        html = "<html>$html</html>",
                        evaluatedValue = evaluated?.trim(),
                        finalUrl = finalUrl,
                    )
                )
            }
            cleanup()
        }
    }

    private fun decodeJavascriptValue(value: String?): String? {
        val encoded = value?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return when (val decoded = runCatching { JSONTokener(encoded).nextValue() }.getOrNull()) {
            is String -> decoded
            null -> encoded.removeSurrounding("\"")
            else -> decoded.toString()
        }
    }

    private fun showVisibleChallenge(continuation: kotlinx.coroutines.CancellableContinuation<Result>) {
        if (dialog != null || webView == null) return
        mainHandler.post {
            try {
                val uiContext = activityContext ?: return@post
                // CONTAINER TV: Intercetta i tasti globalmente (Soluzione "Meravigliosa")
                val rootContainer = object : RelativeLayout(uiContext) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (isTv) {
                                val step = 30f
                                when (event.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        cursorY -= step; updateCursorPosition(); return true
                                    }

                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        cursorY += step; updateCursorPosition(); return true
                                    }

                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        cursorX -= step; updateCursorPosition(); return true
                                    }

                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        cursorX += step; updateCursorPosition(); return true
                                    }

                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                        Log.d(TAG, "[WebView] TV OK Key -> Simulating Mouse")
                                        simulateHumanMouseClick()
                                        return true
                                    }
                                }
                            }

                            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                                Log.d(TAG, "[WebView] BACK Key -> Cancelling bypass")
                                dialog?.cancel()
                                return true
                            }
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                    setBackgroundColor(Color.BLACK)
                    isFocusable = isTv
                    isFocusableInTouchMode = isTv
                }

                if (isTv) {
                    val btnInfo = Button(uiContext).apply {
                        id = View.generateViewId()
                        text = context.getString(R.string.bypass_tv_instructions)
                        setBackgroundColor(Color.parseColor("#4CAF50"))
                        setTextColor(Color.WHITE)
                        textSize = 20f
                        stateListAnimator = null
                        isFocusable = false
                    }
                    val infoParams = RelativeLayout.LayoutParams(-1, 200)
                    infoParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                    rootContainer.addView(btnInfo, infoParams)

                    val webContainer = FrameLayout(uiContext).apply {
                        id = View.generateViewId()
                        setBackgroundColor(Color.WHITE)
                    }
                    val webParams = RelativeLayout.LayoutParams(-1, -1)
                    webParams.addRule(RelativeLayout.BELOW, btnInfo.id)
                    rootContainer.addView(webContainer, webParams)

                    (webView?.parent as? ViewGroup)?.removeView(webView)
                    webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

                    virtualCursor = ImageView(uiContext).apply {
                        setImageResource(android.R.drawable.ic_menu_mylocation)
                        setColorFilter(Color.RED)
                        layoutParams = FrameLayout.LayoutParams(80, 80)
                        elevation = 100f
                    }
                    rootContainer.addView(virtualCursor)
                } else {
                    // Mobile: Solo WebView a tutto schermo
                    (webView?.parent as? ViewGroup)?.removeView(webView)
                    rootContainer.addView(webView, RelativeLayout.LayoutParams(-1, -1))
                }

                dialog = AlertDialog.Builder(uiContext, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                    .setView(rootContainer)
                    .setCancelable(true)
                    .setOnCancelListener {
                        Log.d(TAG, "[WebView] Challenge cancelled by user")
                        if (continuation.isActive) {
                            continuation.resume(
                                Result(
                                    html = "<html><body>User cancelled</body></html>",
                                    finalUrl = deferredLoadUrl ?: webView?.url,
                                )
                            )
                        }
                        cleanup()
                    }
                    .create()

                dialog?.show()

                if (isTv) {
                    rootContainer.post {
                        cursorX = rootContainer.width / 2f
                        cursorY = rootContainer.height / 2f
                        updateCursorPosition()
                        rootContainer.requestFocus()
                    }
                }
                Log.d(TAG, "[WebView] Challenge Dialog DISPLAYED (isTv: $isTv)")

                if (!isTv) {
                    dialog?.window?.setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    )
                    webView?.requestFocus(View.FOCUS_DOWN)
                    webView?.post {
                        webView?.requestFocusFromTouch()
                        showSoftKeyboard(webView)
                    }
                }

                val pendingUrl = deferredLoadUrl
                if (!pendingUrl.isNullOrBlank()) {
                    val pendingHeaders = deferredLoadHeaders
                    deferredLoadUrl = null
                    deferredLoadHeaders = emptyMap()
                    webView?.post {
                        webView?.loadUrl(pendingUrl, pendingHeaders)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[WebView] CRITICAL UI ERROR", e)
                cleanup()
            }
        }
    }

    private fun primeLoginFocus(view: WebView?) {
        view ?: return
        view.post {
            view.requestFocus()
            view.requestFocusFromTouch()
            showSoftKeyboard(view)
            view.evaluateJavascript(
                "(function(){var el=document.querySelector('input[name=\"username\"], input[type=\"text\"], input:not([type]), input[type=\"email\"]'); if(el){el.focus(); el.click();} return true;})();",
                null
            )
        }
    }

    private fun showSoftKeyboard(view: View?) {
        view ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun updateCursorPosition() {
        virtualCursor?.let { cursor ->
            cursor.translationX = cursorX - 40
            cursor.translationY = cursorY - 40
            cursor.bringToFront()
        }
    }

    private fun simulateHumanMouseClick() {
        webView?.let { wv ->
            val location = IntArray(2)
            wv.getLocationOnScreen(location)
            val relX = cursorX - location[0]
            val relY = cursorY - location[1]

            val downTime = SystemClock.uptimeMillis()
            val propM = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val coordM = MotionEvent.PointerCoords().apply { x = relX; y = relY; pressure = 1f; size = 1f }

            val hover = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_HOVER_MOVE,
                1,
                arrayOf(propM),
                arrayOf(coordM),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0
            )
            wv.dispatchGenericMotionEvent(hover); hover.recycle()

            val eventDown = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                1,
                arrayOf(propM),
                arrayOf(coordM),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0
            )
            wv.dispatchTouchEvent(eventDown)

            mainHandler.postDelayed({
                coordM.x += 1f; coordM.y += 1f
                val eventUp = MotionEvent.obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    1,
                    arrayOf(propM),
                    arrayOf(coordM),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_MOUSE,
                    0
                )
                wv.dispatchTouchEvent(eventUp)
                eventDown.recycle(); eventUp.recycle()
                CookieManager.getInstance().flush()
                Log.d(TAG, "[WebView] Simulated Mouse Click at ($relX, $relY)")
            }, 200)
        }
    }

    private fun cleanup() {
        val action: () -> Unit = {
            try {
                deferredLoadUrl = null
                deferredLoadHeaders = emptyMap()
                dialog?.dismiss()
                dialog = null
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                virtualCursor = null
                Log.d(TAG, "[WebView] Closed")
            } catch (e: Exception) {
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}

