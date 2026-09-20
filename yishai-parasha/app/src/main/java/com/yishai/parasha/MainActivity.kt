package com.yishai.parasha

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        tts = TextToSpeech(this, this)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val cameraOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    val audioOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    val allowed = request.resources.filter { res ->
                        (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE && cameraOk) ||
                        (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE && audioOk)
                    }.toTypedArray()
                    if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                }
            }
        }
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("he", "IL"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            tts?.setSpeechRate(0.92f)
            tts?.setPitch(0.95f)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                if (ttsReady) {
                    tts?.stop()
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yishai_interviewer")
                }
            }
        }

        @JavascriptInterface
        fun isNativeApp(): Boolean = true
    }

    override fun onDestroy() {
        webView.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
