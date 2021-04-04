package com.lb.getplaystoreinstalledappshistory

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.aurora.gplayapi.helpers.AuthHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.store.data.network.HttpClient
import com.aurora.store.data.providers.NativeDeviceInfoProvider
import com.aurora.store.util.AC2DMTask
import com.aurora.store.util.AC2DMUtil
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val cookieManager = CookieManager.getInstance()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val cachedEmail = defaultSharedPreferences.getString("email", null)
        val cachedAasToken = defaultSharedPreferences.getString("aasToken", null)
        if (cachedEmail != null && cachedAasToken != null) {
            onGotAasToken(applicationContext, cachedEmail, cachedAasToken)
        } else {
            webView = findViewById(R.id.webView)
            cookieManager.removeAllCookies(null)
            cookieManager.acceptThirdPartyCookies(webView)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                webView.settings.safeBrowsingEnabled = false
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val cookies = CookieManager.getInstance().getCookie(url)
                    val cookieMap: MutableMap<String, String> = AC2DMUtil.parseCookieString(cookies)
                    val oauthToken: String? = cookieMap[AUTH_TOKEN]
                    oauthToken?.let {
                        webView.evaluateJavascript("(function() { return document.getElementById('profileIdentifier').innerHTML; })();") {
                            val email = it.replace("\"".toRegex(), "")
                            Log.d("AppLog", "got email?${email.isNotBlank()} got oauthToken?${oauthToken.isNotBlank()}")
                            buildAuthData(applicationContext, email, oauthToken)
                        }
                    } ?: Log.d("AppLog", "could not get oauthToken")
                }
            }
            webView.settings.apply {
                allowContentAccess = true
                databaseEnabled = true
                domStorageEnabled = true
                javaScriptEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webView.loadUrl(EMBEDDED_SETUP_URL)
        }
    }

    companion object {
        const val EMBEDDED_SETUP_URL =
                "https://accounts.google.com/EmbeddedSetup/identifier?flowName=EmbeddedSetupAndroid"
        const val AUTH_TOKEN = "oauth_token"

        private fun buildAuthData(context: Context, email: String, oauthToken: String?) {
            thread {
                try {
                    val aC2DMResponse: Map<String, String> =
                            AC2DMTask().getAC2DMResponse(email, oauthToken)
                    val aasToken = aC2DMResponse["Token"]!!
                    PreferenceManager.getDefaultSharedPreferences(context)
                            .edit().putString("email", email).putString("aasToken", aasToken).apply()
                    onGotAasToken(context, email, aasToken)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        private fun onGotAasToken(context: Context, email: String, aasToken: String) {
            thread {
                val properties = NativeDeviceInfoProvider(context).getNativeDeviceProperties()
                val authData = AuthHelper.build(email, aasToken, properties)
                val purchaseHelper = PurchaseHelper(authData).using(HttpClient.getPreferredClient())
                var offset = 0
                Log.d("AppLog", "list of purchase history:")
                while (true) {
                    val purchaseHistory = purchaseHelper.getPurchaseHistory(offset)
                    if (purchaseHistory.isNullOrEmpty())
                        break
                    val size = purchaseHistory.size
                    offset += size
                    purchaseHistory.forEach {
                        Log.d("AppLog", "${it.packageName} ${it.displayName}")
                    }
                }
                Log.d("AppLog", "done")
            }
        }
    }
}
