package com.dorlanpabon.ultimatewarriors

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dorlanpabon.ultimatewarriors.audio.SoundManager
import com.dorlanpabon.ultimatewarriors.data.AssetRepository
import com.dorlanpabon.ultimatewarriors.tiktok.TikTokLiveManager
import com.dorlanpabon.ultimatewarriors.ui.GameSurfaceView

class MainActivity : Activity() {
    private lateinit var repo: AssetRepository
    private lateinit var sound: SoundManager
    private lateinit var gameView: GameSurfaceView
    private lateinit var rootLayout: FrameLayout
    private var tiktokWebView: WebView? = null
    private var previewOverlay: FrameLayout? = null
    private var previewStatusTv: TextView? = null
    private var isPreviewVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        repo = AssetRepository(applicationContext)
        sound = SoundManager(applicationContext, repo)
        gameView = GameSurfaceView(this, repo, sound)

        rootLayout = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        rootLayout.addView(
            gameView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(rootLayout)
        setupDirectTikTokHooks(rootLayout)
        enableImmersiveMode()
    }

    private fun setupDirectTikTokHooks(root: FrameLayout) {
        val prefs = getSharedPreferences("uw_tiktok_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("direct_user", "") ?: ""
        val savedHost = prefs.getString("tikfinity_host", "127.0.0.1") ?: "127.0.0.1"
        val mgr = gameView.tiktok

        if (savedUser.isNotBlank()) {
            mgr.directUsername = "@${savedUser.trim().trimStart('@')}"
        }
        mgr.tikFinityHost = savedHost
        mgr.localWebViewEnabled = prefs.getBoolean("local_webview_enabled", true)
        mgr.eulerStreamEnabled = prefs.getBoolean("euler_enabled", true)
        val savedKey = prefs.getString("euler_api_key", TikTokLiveManager.DEFAULT_EULER_API_KEY)
        mgr.eulerApiKey = if (!savedKey.isNullOrBlank()) savedKey else TikTokLiveManager.DEFAULT_EULER_API_KEY
        mgr.autoDisableSimOnRealEvent = prefs.getBoolean("auto_disable_sim", true)

        mgr.onRequestPhoneConnectDialog = {
            runOnUiThread { showTikTokPhoneDialog() }
        }
        mgr.onRequestWebViewPreview = {
            runOnUiThread { showWebViewPreviewOverlay() }
        }
        mgr.onStartDirectPhoneListener = { cleanUser ->
            runOnUiThread { startBackgroundWebViewListener(root, cleanUser) }
        }
        mgr.onStopDirectPhoneListener = {
            runOnUiThread { stopBackgroundWebViewListener(root) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startBackgroundWebViewListener(root: FrameLayout, cleanUser: String) {
        stopBackgroundWebViewListener(root)
        val mgr = gameView.tiktok
        mgr.localWebViewConnected = false
        if (!mgr.eulerConnected) {
            mgr.directPhoneConnected = false
            mgr.directStatusText = "Abriendo tiktok.com/@$cleanUser/live..."
        }
        mgr.updateDirectConnectionState()

        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
        }

        val wv = WebView(this).apply {
            alpha = 1.0f
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            translationX = -4200f
            translationY = 0f
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Desktop Chrome UA loads the full TikTok Live Desktop Chat Room
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            addJavascriptInterface(AndroidTikTokJsBridge(), "AndroidTikTokBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(TIKTOK_DOM_OBSERVER_JS, null)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    view?.evaluateJavascript(TIKTOK_DOM_OBSERVER_JS, null)
                }
            }
        }
        runCatching {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        }
        tiktokWebView = wv
        // Full 1280x900 desktop viewport positioned off-screen so TikTok renders full chat & Webcast stays active
        root.addView(wv, FrameLayout.LayoutParams(1280, 900))
        wv.loadUrl("https://www.tiktok.com/@$cleanUser/live")
    }

    private fun stopBackgroundWebViewListener(root: FrameLayout) {
        hideWebViewPreviewOverlay()
        tiktokWebView?.let { wv ->
            runCatching {
                wv.stopLoading()
                root.removeView(wv)
                wv.destroy()
            }
        }
        tiktokWebView = null
        gameView.tiktok.localWebViewConnected = false
        gameView.tiktok.updateDirectConnectionState()
    }

    private fun showWebViewPreviewOverlay() {
        val mgr = gameView.tiktok
        val cleanUser = mgr.directUsername.trim().trimStart('@').trim()
        if (tiktokWebView == null) {
            if (cleanUser.isNotEmpty()) {
                mgr.localWebViewEnabled = true
                startBackgroundWebViewListener(rootLayout, cleanUser)
            } else {
                showTikTokPhoneDialog()
                return
            }
        }
        val wv = tiktokWebView ?: return
        isPreviewVisible = true

        if (previewOverlay == null) {
            val density = resources.displayMetrics.density
            val topBarH = (56 * density).toInt()
            val overlay = FrameLayout(this).apply {
                setBackgroundColor(Color.argb(242, 10, 14, 24))
                isClickable = true
                isFocusable = true
            }
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.rgb(18, 25, 44))
                val pad = (8 * density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            val hideBtn = Button(this).apply {
                text = if (repo.isSpanish()) "◀ Ocultar (Seguir en 2do plano)" else "◀ Hide (Keep running)"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(25, 135, 84))
                setOnClickListener { hideWebViewPreviewOverlay() }
            }
            val reloadBtn = Button(this).apply {
                text = "🔄"
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(45, 95, 185))
                setOnClickListener { tiktokWebView?.reload() }
            }
            val statusTv = TextView(this).apply {
                text = mgr.directStatusText
                textSize = 11f
                setTextColor(Color.rgb(130, 235, 255))
                setPadding((8 * density).toInt(), 0, 0, 0)
                setSingleLine(true)
            }
            previewStatusTv = statusTv
            bar.addView(hideBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(reloadBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (6 * density).toInt()
            })
            bar.addView(statusTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            overlay.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, topBarH))
            rootLayout.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            previewOverlay = overlay
        } else {
            previewOverlay?.visibility = View.VISIBLE
            previewStatusTv?.text = mgr.directStatusText
        }

        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val density = resources.displayMetrics.density
        val topBarH = (60 * density)
        val scale = screenW / 1280f
        wv.pivotX = 0f
        wv.pivotY = 0f
        wv.scaleX = scale
        wv.scaleY = scale
        wv.translationX = 0f
        wv.translationY = topBarH
        wv.bringToFront()
        previewOverlay?.bringToFront()
        // Keep top bar visible above scaled webview
        wv.bringToFront()
        previewOverlay?.let { ov ->
            ov.getChildAt(0)?.bringToFront()
        }
    }

    private fun hideWebViewPreviewOverlay() {
        isPreviewVisible = false
        previewOverlay?.visibility = View.GONE
        tiktokWebView?.let { wv ->
            wv.scaleX = 1f
            wv.scaleY = 1f
            wv.translationX = -4200f
            wv.translationY = 0f
        }
        enableImmersiveMode()
    }

    private inner class AndroidTikTokJsBridge {
        @JavascriptInterface
        fun onChat(user: String, text: String) {
            val mgr = gameView.tiktok
            if (mgr.autoDisableSimOnRealEvent) mgr.simulatorEnabled = false
            mgr.localWebViewConnected = true
            mgr.directStatusText = "LIVE Local OK (Chat)"
            mgr.updateDirectConnectionState()
            runOnUiThread { previewStatusTv?.text = mgr.directStatusText }
            mgr.enqueueChat(user, text)
        }

        @JavascriptInterface
        fun onGift(user: String, giftName: String, count: Int, coins: Int) {
            val mgr = gameView.tiktok
            if (mgr.autoDisableSimOnRealEvent) mgr.simulatorEnabled = false
            mgr.localWebViewConnected = true
            mgr.directStatusText = "LIVE Local OK (Regalo)"
            mgr.updateDirectConnectionState()
            runOnUiThread { previewStatusTv?.text = mgr.directStatusText }
            mgr.enqueueGift(user, giftName, count, coinsOverride = coins)
        }

        @JavascriptInterface
        fun onLike(user: String, count: Int) {
            val mgr = gameView.tiktok
            if (mgr.autoDisableSimOnRealEvent) mgr.simulatorEnabled = false
            mgr.localWebViewConnected = true
            mgr.updateDirectConnectionState()
            mgr.enqueueLike(user, count)
        }

        @JavascriptInterface
        fun onViewers(count: Int) {
            if (count > 0) gameView.tiktok.viewerCount = count
        }

        @JavascriptInterface
        fun onStatus(connected: Boolean, info: String) {
            val mgr = gameView.tiktok
            mgr.localWebViewConnected = connected
            if (connected || !mgr.eulerConnected) {
                mgr.directStatusText = info.take(42)
            }
            mgr.updateDirectConnectionState()
            runOnUiThread { previewStatusTv?.text = mgr.directStatusText }
        }
    }

    private fun showTikTokPhoneDialog() {
        val prefs = getSharedPreferences("uw_tiktok_prefs", Context.MODE_PRIVATE)
        val mgr = gameView.tiktok
        val isEs = repo.isSpanish()

        val pad = (resources.displayMetrics.density * 16).toInt()
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        scroll.addView(container)

        val infoTv = TextView(this).apply {
            text = if (isEs) {
                "Conecta tu transmisión EN VIVO desde este teléfono (sin PC).\n" +
                    "Escribe el @usuario que está transmitiendo en TikTok:"
            } else {
                "Connect your TikTok LIVE directly from this phone (no PC needed).\n" +
                    "Enter the @username currently live on TikTok:"
            }
            textSize = 14f
        }
        container.addView(infoTv)

        val userInput = EditText(this).apply {
            hint = "@tu_usuario_tiktok"
            setText(mgr.directUsername.ifBlank { prefs.getString("direct_user", "") ?: "" })
            setSingleLine(true)
        }
        container.addView(userInput)

        val localCb = CheckBox(this).apply {
            text = if (isEs) {
                "Modo 100% Local SIN API (WebView Desktop integrado)"
            } else {
                "100% Local Mode NO API (Built-in Desktop WebView)"
            }
            isChecked = mgr.localWebViewEnabled
        }
        container.addView(localCb)

        val eulerCb = CheckBox(this).apply {
            text = if (isEs) {
                "Conectar también por Euler Stream WS (Opcional / Respaldo)"
            } else {
                "Also connect via Euler Stream WS (Optional backup)"
            }
            isChecked = mgr.eulerStreamEnabled
        }
        container.addView(eulerCb)

        val eulerKeyLabel = TextView(this).apply {
            text = if (isEs) "API Key Euler Stream (opcional si solo usas Modo Local):" else "Euler Stream API Key (optional):"
            textSize = 12f
        }
        container.addView(eulerKeyLabel)

        val eulerKeyInput = EditText(this).apply {
            hint = "euler_..."
            setText(mgr.eulerApiKey)
            setSingleLine(true)
        }
        container.addView(eulerKeyInput)

        val hostLabel = TextView(this).apply {
            text = if (isEs) {
                "\nOpcional — Host/IP TikFinity (:21213) o Termux (127.0.0.1):"
            } else {
                "\nOptional — TikFinity (:21213) Host/IP:"
            }
            textSize = 12f
        }
        container.addView(hostLabel)

        val hostInput = EditText(this).apply {
            hint = "127.0.0.1"
            setText(mgr.tikFinityHost)
            setSingleLine(true)
        }
        container.addView(hostInput)

        val autoSimCb = CheckBox(this).apply {
            text = if (isEs) "Apagar Simulador Bots al recibir chat real" else "Turn off Bot Simulator on real chat"
            isChecked = mgr.autoDisableSimOnRealEvent
        }
        container.addView(autoSimCb)

        val previewBtn = Button(this).apply {
            text = if (isEs) "👁️ Ver / Diagnosticar Página LIVE en el teléfono" else "👁️ View / Inspect LIVE WebView Page"
        }
        container.addView(previewBtn)

        val statusTv = TextView(this).apply {
            text = "\nEstado: ${mgr.directStatusText}\nWebhook local:\nhttp://${mgr.getLocalIpAddress()}:${mgr.webhookPort}/event"
            textSize = 12f
            setTextColor(Color.rgb(30, 120, 180))
        }
        container.addView(statusTv)

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (isEs) "Configurar TikTok Live en Teléfono" else "Configure On-Phone TikTok Live")
            .setView(scroll)
            .setPositiveButton(if (isEs) "Conectar @usuario" else "Connect @user") { _, _ ->
                val rawUser = userInput.text?.toString()?.trim().orEmpty()
                val rawHost = hostInput.text?.toString()?.trim().orEmpty().ifEmpty { "127.0.0.1" }
                val rawKey = eulerKeyInput.text?.toString()?.trim().orEmpty()
                mgr.localWebViewEnabled = localCb.isChecked
                mgr.eulerStreamEnabled = eulerCb.isChecked
                mgr.eulerApiKey = rawKey.ifEmpty { TikTokLiveManager.DEFAULT_EULER_API_KEY }
                mgr.autoDisableSimOnRealEvent = autoSimCb.isChecked
                mgr.tikFinityHost = rawHost
                prefs.edit()
                    .putString("direct_user", rawUser)
                    .putString("tikfinity_host", rawHost)
                    .putBoolean("local_webview_enabled", mgr.localWebViewEnabled)
                    .putBoolean("euler_enabled", mgr.eulerStreamEnabled)
                    .putString("euler_api_key", mgr.eulerApiKey)
                    .putBoolean("auto_disable_sim", mgr.autoDisableSimOnRealEvent)
                    .apply()
                if (rawUser.isNotBlank()) {
                    mgr.startDirectPhoneConnection(rawUser)
                }
                enableImmersiveMode()
            }
            .setNeutralButton(if (isEs) "Desconectar" else "Disconnect") { _, _ ->
                mgr.stopDirectPhoneConnection()
                enableImmersiveMode()
            }
            .setNegativeButton(if (isEs) "Cerrar" else "Close") { _, _ ->
                enableImmersiveMode()
            }
            .setOnDismissListener { enableImmersiveMode() }
            .show()

        previewBtn.setOnClickListener {
            val rawUser = userInput.text?.toString()?.trim().orEmpty()
            mgr.localWebViewEnabled = true
            if (rawUser.isNotBlank()) {
                val cleaned = rawUser.trimStart('@').trim()
                mgr.directUsername = "@$cleaned"
                prefs.edit().putString("direct_user", rawUser).putBoolean("local_webview_enabled", true).apply()
            }
            dlg.dismiss()
            showWebViewPreviewOverlay()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isPreviewVisible) {
            hideWebViewPreviewOverlay()
            return
        }
        if (!gameView.onBackPressed()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        sound.pauseAll()
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        sound.resumeAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        tiktokWebView?.let { wv -> runCatching { wv.destroy() } }
        tiktokWebView = null
        sound.release()
    }

    companion object {
        private val TIKTOK_DOM_OBSERVER_JS = """
            (function() {
              if (window.__uwInstalled) return;
              window.__uwInstalled = true;
              const seen = new Set();
              let lastLikes = -1;
              let lastViewers = -1;
              let roomConfirmed = false;

              function markSeen(key) {
                if (!key) return false;
                if (seen.has(key)) return false;
                seen.add(key);
                if (seen.size > 600) {
                  const first = seen.values().next().value;
                  seen.delete(first);
                }
                return true;
              }

              function muteAllMedia() {
                document.querySelectorAll('video,audio').forEach(el => {
                  try { el.muted = true; el.volume = 0; } catch(e) {}
                });
              }

              function parseHumanNum(txt) {
                if (!txt) return -1;
                const clean = txt.trim().toUpperCase().replace(/,/g, '');
                const m = clean.match(/([\d.]+)\s*([KM]?)/);
                if (!m) return -1;
                let n = parseFloat(m[1]);
                if (isNaN(n)) return -1;
                if (m[2] === 'K') n *= 1000;
                if (m[2] === 'M') n *= 1000000;
                return Math.floor(n);
              }

              function extractText(el) {
                return (el && el.innerText ? el.innerText : '').trim();
              }

              function checkHydrationAndGate() {
                if (!window.AndroidTikTokBridge) return;
                // Layer 2: Auto-bypass Watch LIVE / Ver LIVE gate buttons
                document.querySelectorAll('button, [role="button"]').forEach(btn => {
                  const t = (btn.innerText || '').trim().toLowerCase();
                  if (t === 'watch live' || t === 'ver live' || t === 'tap to watch live' || t === 'enter live room') {
                    try { btn.click(); } catch(e) {}
                  }
                });

                // Layer 1: Hydration state inspection (SIGI_STATE / __UNIVERSAL_DATA_FOR_REHYDRATION__)
                try {
                  const sigiEl = document.getElementById('SIGI_STATE') || document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
                  if (sigiEl && sigiEl.textContent) {
                    const rawStr = sigiEl.textContent;
                    const roomMatch = rawStr.match(/"roomId"\s*:\s*"?(\d+)"?/);
                    const statusMatch = rawStr.match(/"status"\s*:\s*(\d+)/);
                    const userCountMatch = rawStr.match(/"userCount"\s*:\s*(\d+)/);
                    if (userCountMatch) {
                      const vc = parseInt(userCountMatch[1], 10);
                      if (vc > 0 && vc !== lastViewers) {
                        lastViewers = vc;
                        window.AndroidTikTokBridge.onViewers(vc);
                      }
                    }
                    if (statusMatch && statusMatch[1] === '4') {
                      window.AndroidTikTokBridge.onStatus(false, 'LIVE offline o finalizado');
                      return;
                    }
                    if (roomMatch && statusMatch && statusMatch[1] === '2' && !roomConfirmed) {
                      roomConfirmed = true;
                      window.AndroidTikTokBridge.onStatus(true, 'Sala LIVE local conectada');
                    }
                  }
                } catch(e) {}

                const bodyTxt = ((document.body && document.body.innerText) || '').toLowerCase();
                if (bodyTxt.includes('verify to continue') || bodyTxt.includes('slide to complete') || bodyTxt.includes('captcha')) {
                  window.AndroidTikTokBridge.onStatus(false, 'Captcha web (toca Ver Pagina LIVE)');
                  return;
                }
                if (bodyTxt.includes('live has ended') || bodyTxt.includes('el live ha finalizado') || bodyTxt.includes('is currently offline')) {
                  window.AndroidTikTokBridge.onStatus(false, 'LIVE offline o finalizado');
                }
              }

              // Layer 3: React Fiber / Props Extractor
              function extractFromReactNode(node) {
                if (!node || typeof node !== 'object') return false;
                if (node.nodeName === 'SCRIPT' || node.nodeName === 'STYLE') return false;
                let foundAny = false;
                let curr = node;
                for (let depth = 0; depth < 4 && curr; depth++) {
                  try {
                    const keys = Object.keys(curr);
                    for (const k of keys) {
                      if (!k.startsWith('__reactProps$') && !k.startsWith('__reactFiber$')) continue;
                      const obj = curr[k];
                      if (!obj || typeof obj !== 'object') continue;
                      const candidates = [obj, obj.memoizedProps, obj.pendingProps];
                      for (const c of candidates) {
                        if (!c || typeof c !== 'object') continue;
                        const msg = c.message || c.msg || c.data || c.chatMessage || c.giftMessage || c;
                        if (!msg || typeof msg !== 'object') continue;
                        const userObj = msg.user || msg.author || msg.sender || (msg.data && msg.data.user);
                        const uid = userObj ? String(userObj.uniqueId || userObj.nickname || userObj.displayId || '') : '';
                        if (!uid || uid.length > 60) continue;
                        const content = msg.content || msg.comment || (msg.data && (msg.data.content || msg.data.comment)) || '';
                        const giftObj = msg.gift || msg.giftDetails || (msg.data && (msg.data.gift || msg.data.giftDetails));
                        const giftName = (giftObj && (giftObj.name || giftObj.giftName)) || msg.giftName || '';
                        const repeatCount = parseInt(msg.repeatCount || msg.comboCount || msg.groupCount || 1, 10) || 1;
                        const msgId = (msg.common && msg.common.msgId) || msg.msgId || msg.id || '';

                        if (giftName && typeof giftName === 'string') {
                          const gSig = 'RF_G:' + (msgId ? (msgId + ':' + repeatCount) : (uid + ':' + giftName + ':' + repeatCount));
                          if (markSeen(gSig)) {
                            const coins = parseInt((giftObj && (giftObj.diamondCount || giftObj.diamonds)) || msg.diamondCount || -1, 10);
                            window.AndroidTikTokBridge.onGift(uid, giftName, repeatCount, isNaN(coins) ? -1 : coins);
                            roomConfirmed = true;
                            foundAny = true;
                          }
                        } else if (content && typeof content === 'string' && content.length <= 240) {
                          const cSig = 'RF_C:' + (msgId || (uid + ':' + content));
                          if (markSeen(cSig)) {
                            window.AndroidTikTokBridge.onChat(uid, content);
                            roomConfirmed = true;
                            foundAny = true;
                          }
                        }
                      }
                    }
                  } catch(e) {}
                  curr = curr.parentElement;
                }
                return foundAny;
              }

              // Layer 4: Expanded DOM Fallback Scraper
              function scanDom() {
                muteAllMedia();
                if (!window.AndroidTikTokBridge) return;
                checkHydrationAndGate();

                const chatRows = document.querySelectorAll(
                  '[data-e2e*="chat-message"], [data-e2e*="comment-item"], [class*="DivChatMessage"], [class*="ChatMessage"], [class*="CommentItem"]'
                );
                if (chatRows.length > 0 && !roomConfirmed) {
                  roomConfirmed = true;
                  window.AndroidTikTokBridge.onStatus(true, 'Chat local en vivo activo');
                }
                chatRows.forEach(row => {
                  if (extractFromReactNode(row)) return;
                  const full = extractText(row);
                  if (!full || !markSeen('DOM_C:' + full)) return;
                  const userEl = row.querySelector('[data-e2e="message-owner-name"], a, span[class*="User"], span[class*="Nickname"]');
                  let user = extractText(userEl);
                  let msg = full;
                  if (user && full.startsWith(user)) {
                    msg = full.slice(user.length).replace(/^[:：\s]+/, '');
                  } else if (full.includes(':')) {
                    const idx = full.indexOf(':');
                    user = full.slice(0, idx).trim();
                    msg = full.slice(idx + 1).trim();
                  } else {
                    user = 'viewer';
                  }
                  if (msg) window.AndroidTikTokBridge.onChat(user || 'viewer', msg);
                });

                const giftRows = document.querySelectorAll(
                  '[data-e2e*="gift-message"], [data-e2e*="gift-item"], [class*="GiftMessage"], [class*="GiftRow"]'
                );
                giftRows.forEach(row => {
                  if (extractFromReactNode(row)) return;
                  const full = extractText(row);
                  if (!full || !markSeen('DOM_G:' + full)) return;
                  const userEl = row.querySelector('[data-e2e="message-owner-name"], span, a');
                  const user = extractText(userEl) || 'donor';
                  const multMatch = full.match(/x\s*(\d+)/i);
                  const count = multMatch ? parseInt(multMatch[1], 10) : 1;
                  window.AndroidTikTokBridge.onGift(user, full, count || 1, -1);
                });

                const likeEl = document.querySelector('[data-e2e="like-count"], [data-e2e*="like-count"], [class*="LikeCount"]');
                if (likeEl) {
                  const likes = parseHumanNum(extractText(likeEl));
                  if (likes > 0) {
                    if (lastLikes >= 0 && likes > lastLikes) {
                      window.AndroidTikTokBridge.onLike('@live_likes', Math.min(200, likes - lastLikes));
                    }
                    lastLikes = likes;
                  }
                }

                const viewerEl = document.querySelector('[data-e2e="live-people-count"], [data-e2e*="viewer"], [class*="ViewerCount"]');
                if (viewerEl) {
                  const v = parseHumanNum(extractText(viewerEl));
                  if (v > 0 && v !== lastViewers) {
                    lastViewers = v;
                    window.AndroidTikTokBridge.onViewers(v);
                  }
                }
              }

              const obs = new MutationObserver(mutations => {
                let shouldScanDom = false;
                for (const m of mutations) {
                  for (const n of m.addedNodes) {
                    if (n && n.nodeType === 1) {
                      if (!extractFromReactNode(n)) shouldScanDom = true;
                    }
                  }
                }
                if (shouldScanDom) scanDom();
              });
              if (document.body) obs.observe(document.body, { childList: true, subtree: true });
              setInterval(scanDom, 900);
              setTimeout(scanDom, 1000);
            })();
        """.trimIndent()
    }
}
