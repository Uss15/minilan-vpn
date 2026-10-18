// Shows the shared MiniLAN web UI (same as on PC) in a full-screen WebView, served by LocalApi.
// Phone-only actions (VPN consent, connect/disconnect, clipboard, links) go through a small JS bridge.
package com.minilan

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {
    companion object {
        /** One API server per process, so it survives activity recreation (rotation etc.). */
        private var api: LocalApi? = null
    }

    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }
    private var pendingServer = ""
    private var pendingName = ""
    private var pendingHost = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0a0b14")
        window.navigationBarColor = Color.parseColor("#0a0b14")

        val app = applicationContext
        val local = api ?: LocalApi(
            page = { app.assets.open("index.html") },
            offlineState = {
                val p = app.getSharedPreferences("ui", Context.MODE_PRIVATE)
                JSONObject()
                    .put("server", p.getString("server", app.getString(R.string.default_server)))
                    .put("name", p.getString("name", Build.MODEL))
                    .put("connecting", MiniLanVpnService.connecting)
                    .put("error", MiniLanVpnService.lastError ?: JSONObject.NULL)
            },
            engine = { MiniLanVpnService.engine },
            hostInfo = { MiniLanVpnService.hostInfo() },
        ).start().also { api = it }

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0a0b14"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webChromeClient = WebChromeClient() // enables prompt() for room passwords
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    if (req.url.host == "127.0.0.1") return false
                    startActivity(Intent(Intent.ACTION_VIEW, req.url)) // external links open in the browser
                    return true
                }
            }
            addJavascriptInterface(Bridge(local.token), "MiniLANAndroid")
            loadUrl("http://127.0.0.1:${local.port}/")
        }
        setContentView(web)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    inner class Bridge(private val tok: String) {
        @JavascriptInterface fun token(): String = tok

        @JavascriptInterface fun connect(server: String, name: String) = runOnUiThread {
            var s = server.trim()
            if (!Regex("^https?://").containsMatchIn(s)) s = "http://$s"
            if (!s.startsWith("https") && !Regex(":\\d+/?$").containsMatchIn(s)) s = s.trimEnd('/') + ":8080"
            pendingServer = s
            pendingHost = false
            pendingName = name.trim().ifEmpty { Build.MODEL }
            prefs.edit().putString("server", pendingServer).putString("name", pendingName).apply()
            MiniLanVpnService.lastError = null
            val consent = VpnService.prepare(this@MainActivity)
            if (consent != null) @Suppress("DEPRECATION") startActivityForResult(consent, 1)
            else startVpn()
        }

        /** Host the MiniLAN server on this phone (friends on the same Wi-Fi or this phone's hotspot join it). */
        @JavascriptInterface fun host(name: String) = runOnUiThread {
            pendingHost = true
            pendingServer = ""
            pendingName = name.trim().ifEmpty { Build.MODEL }
            prefs.edit().putString("name", pendingName).apply()
            MiniLanVpnService.lastError = null
            val consent = VpnService.prepare(this@MainActivity)
            if (consent != null) @Suppress("DEPRECATION") startActivityForResult(consent, 1) else startVpn()
        }

        @JavascriptInterface fun disconnect() = runOnUiThread {
            startService(Intent(this@MainActivity, MiniLanVpnService::class.java).setAction(MiniLanVpnService.ACTION_DISCONNECT))
        }

        @JavascriptInterface fun openUrl(url: String) = runOnUiThread {
            if (url.startsWith("https://")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        @JavascriptInterface fun copy(text: String) = runOnUiThread {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MiniLAN", text))
        }
    }

    private fun startVpn() {
        val i = Intent(this, MiniLanVpnService::class.java).setAction(MiniLanVpnService.ACTION_CONNECT)
            .putExtra("server", pendingServer).putExtra("name", pendingName).putExtra("host", pendingHost)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != 1) return
        if (resultCode == RESULT_OK) startVpn() else MiniLanVpnService.lastError = "VPN permission was not granted"
    }
}
