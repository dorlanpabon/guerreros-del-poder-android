package com.dorlanpabon.ultimatewarriors

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dorlanpabon.ultimatewarriors.audio.SoundManager
import com.dorlanpabon.ultimatewarriors.data.AssetRepository
import com.dorlanpabon.ultimatewarriors.ui.GameSurfaceView

class MainActivity : Activity() {
    private lateinit var repo: AssetRepository
    private lateinit var sound: SoundManager
    private lateinit var gameView: GameSurfaceView
    private var tiktokWebView: WebView? = null
    @Volatile private var autoDisableSimOnRealEvent = true

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

        val root = FrameLayout(this)
        root.addView(
            gameView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        setupDirectTikTokHooks(root)
        enableImmersiveMode()
    }

    private fun setupDirectTikTokHooks(root: FrameLayout) {
        val prefs = getSharedPreferences("uw_tiktok_prefs", Context.MODE_PRIVATE)
        val savedUser = prefs.getString("direct_user", "") ?: ""
        val savedHost = prefs.getString("tikfinity_host", "127.0.0.1") ?: "127.0.0.1"
        if (savedUser.isNotBlank()) {
            gameView.tiktok.directUsername = "@${savedUser.removePrefix("@")}"
        }
        gameView.tiktok.tikFinityHost = savedHost

        gameView.tiktok.onRequestPhoneConnectDialog = {
            runOnUiThread { showTikTokPhoneDialog() }
        }
        gameView.tiktok.onStartDirectPhoneListener = { cleanUser ->
            runOnUiThread { startBackgroundWebViewListener(root, cleanUser) }
        }
        gameView.tiktok.onStopDirectPhoneListener = {
            runOnUiThread { stopBackgroundWebViewListener(root) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startBackgroundWebViewListener(root: FrameLayout, cleanUser: String) {
        stopBackgroundWebViewListener(root)
        val mgr = gameView.tiktok
        mgr.directPhoneConnected = false
        mgr.directStatusText = "Cargando tiktok.com/@$cleanUser/live..."

        val wv = WebView(this).apply {
            alpha = 0.01f
            isFocusable = false
            isClickable = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Desktop Chrome UA loads the full TikTok Live chat DOM without 'Open App' gate
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            addJavascriptInterface(AndroidTikTokJsBridge(), "AndroidTikTokBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(TIKTOK_DOM_OBSERVER_JS, null)
                }
            }
        }
        tiktokWebView = wv
        root.addView(wv, FrameLayout.LayoutParams(1, 1))
        wv.loadUrl("https://www.tiktok.com/@$cleanUser/live")
    }

    private fun stopBackgroundWebViewListener(root: FrameLayout) {
        tiktokWebView?.let { wv ->
            runCatching {
                wv.stopLoading()
                root.removeView(wv)
                wv.destroy()
            }
        }
        tiktokWebView = null
    }

    private inner class AndroidTikTokJsBridge {
        @JavascriptInterface
        fun onChat(user: String, text: String) {
            if (autoDisableSimOnRealEvent) gameView.tiktok.simulatorEnabled = false
            gameView.tiktok.directPhoneConnected = true
            gameView.tiktok.directStatusText = "Chat en vivo activo"
            gameView.tiktok.enqueueChat(user, text)
        }

        @JavascriptInterface
        fun onGift(user: String, giftName: String, count: Int) {
            if (autoDisableSimOnRealEvent) gameView.tiktok.simulatorEnabled = false
            gameView.tiktok.directPhoneConnected = true
            gameView.tiktok.directStatusText = "Regalo recibido en vivo"
            gameView.tiktok.enqueueGift(user, giftName, count)
        }

        @JavascriptInterface
        fun onLike(user: String, count: Int) {
            if (autoDisableSimOnRealEvent) gameView.tiktok.simulatorEnabled = false
            gameView.tiktok.directPhoneConnected = true
            gameView.tiktok.enqueueLike(user, count)
        }

        @JavascriptInterface
        fun onViewers(count: Int) {
            if (count > 0) gameView.tiktok.viewerCount = count
        }

        @JavascriptInterface
        fun onStatus(connected: Boolean, info: String) {
            gameView.tiktok.directPhoneConnected = connected
            gameView.tiktok.directStatusText = info.take(42)
        }
    }

    private fun showTikTokPhoneDialog() {
        val prefs = getSharedPreferences("uw_tiktok_prefs", Context.MODE_PRIVATE)
        val mgr = gameView.tiktok
        val isEs = repo.isSpanish()

        val pad = (resources.displayMetrics.density * 18).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val infoTv = TextView(this).apply {
            text = if (isEs) {
                "Conecta tu transmisión EN VIVO directamente desde este teléfono (sin PC).\n" +
                    "Escribe el @usuario que está transmitiendo en TikTok ahora mismo:"
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

        val hostLabel = TextView(this).apply {
            text = if (isEs) {
                "\nOpcional — Host/IP TikFinity (:21213) o Termux (ej. 127.0.0.1):"
            } else {
                "\nOptional — TikFinity (:21213) Host/IP:"
            }
            textSize = 13f
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
            isChecked = autoDisableSimOnRealEvent
        }
        container.addView(autoSimCb)

        val webhookTv = TextView(this).apply {
            text = "\nWebhook local activo:\nhttp://${mgr.getLocalIpAddress()}:${mgr.webhookPort}/event"
            textSize = 12f
            setTextColor(Color.rgb(30, 120, 180))
        }
        container.addView(webhookTv)

        AlertDialog.Builder(this)
            .setTitle(if (isEs) "Configurar TikTok Live en Teléfono" else "Configure On-Phone TikTok Live")
            .setView(container)
            .setPositiveButton(if (isEs) "Conectar @usuario" else "Connect @user") { _, _ ->
                val rawUser = userInput.text?.toString()?.trim().orEmpty()
                val rawHost = hostInput.text?.toString()?.trim().orEmpty().ifEmpty { "127.0.0.1" }
                autoDisableSimOnRealEvent = autoSimCb.isChecked
                mgr.tikFinityHost = rawHost
                prefs.edit()
                    .putString("direct_user", rawUser)
                    .putString("tikfinity_host", rawHost)
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
              function muteAllMedia() {
                document.querySelectorAll('video,audio').forEach(el => {
                  try { el.muted = true; el.volume = 0; if (!el.paused) el.pause(); } catch(e) {}
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
              function scanDom() {
                muteAllMedia();
                if (!window.AndroidTikTokBridge) return;
                const bodyTxt = (document.body && document.body.innerText) || '';
                if (bodyTxt.includes('LIVE has ended') || bodyTxt.includes('El LIVE ha finalizado') || bodyTxt.includes('is currently offline')) {
                  window.AndroidTikTokBridge.onStatus(false, 'LIVE offline o finalizado');
                }
                // Chat rows
                const chatRows = document.querySelectorAll('[data-e2e="chat-message"], [class*="DivChatMessage"], [class*="ChatMessage"]');
                if (chatRows.length > 0) {
                  window.AndroidTikTokBridge.onStatus(true, 'Conectado al chat en vivo');
                }
                chatRows.forEach(row => {
                  const full = extractText(row);
                  if (!full || seen.has(full)) return;
                  seen.add(full);
                  if (seen.size > 400) seen.clear();
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
                // Gift rows
                const giftRows = document.querySelectorAll('[data-e2e="gift-message"], [class*="GiftMessage"]');
                giftRows.forEach(row => {
                  const full = extractText(row);
                  if (!full || seen.has('G:' + full)) return;
                  seen.add('G:' + full);
                  const userEl = row.querySelector('[data-e2e="message-owner-name"], span, a');
                  const user = extractText(userEl) || 'donor';
                  const multMatch = full.match(/x\s*(\d+)/i);
                  const count = multMatch ? parseInt(multMatch[1], 10) : 1;
                  window.AndroidTikTokBridge.onGift(user, full, count || 1);
                });
                // Likes & Viewers
                const likeEl = document.querySelector('[data-e2e="like-count"], [class*="LikeCount"]');
                if (likeEl) {
                  const likes = parseHumanNum(extractText(likeEl));
                  if (likes > 0) {
                    if (lastLikes >= 0 && likes > lastLikes) {
                      window.AndroidTikTokBridge.onLike('@live_likes', Math.min(200, likes - lastLikes));
                    }
                    lastLikes = likes;
                  }
                }
                const viewerEl = document.querySelector('[data-e2e="live-people-count"], [class*="ViewerCount"]');
                if (viewerEl) {
                  const v = parseHumanNum(extractText(viewerEl));
                  if (v > 0) window.AndroidTikTokBridge.onViewers(v);
                }
              }
              const obs = new MutationObserver(() => scanDom());
              if (document.body) obs.observe(document.body, { childList: true, subtree: true });
              setInterval(scanDom, 1200);
              setTimeout(() => {
                if (window.AndroidTikTokBridge) {
                  window.AndroidTikTokBridge.onStatus(true, 'Página LIVE abierta en teléfono');
                }
                scanDom();
              }, 1500);
            })();
        """.trimIndent()
    }
}
