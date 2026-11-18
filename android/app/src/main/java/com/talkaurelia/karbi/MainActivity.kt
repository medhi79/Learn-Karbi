package com.talkaurelia.karbi

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private lateinit var db: KarbiDb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = Room.databaseBuilder(applicationContext, KarbiDb::class.java, "talk_aurelia_karbi.db")
            .fallbackToDestructiveMigration()
            .build()

        webView = WebView(this)
        setContentView(webView)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("en", "IN")
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AureliaBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/www/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.onAndroidBackPressed && window.onAndroidBackPressed();", null)
            }
        })
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    inner class AureliaBridge {
        @JavascriptInterface
        fun speakNative(text: String, lang: String) {
            val clean = text.replace(Regex("[*#_`~>|]"), "").trim()
            tts?.language = if (lang == "as-IN") Locale("as", "IN") else Locale("en", "IN")
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "aurelia_tts")
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finishAndRemoveTask() }
        }
    }
}
