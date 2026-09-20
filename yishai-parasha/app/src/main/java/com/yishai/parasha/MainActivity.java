package com.yishai.parasha;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private static final int REQUEST_MEDIA_PERMISSIONS = 1001;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        tts = new TextToSpeech(this, this);

        webView = new WebView(this);
        setContentView(webView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean cameraOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                    boolean audioOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

                    List<String> allowed = new ArrayList<>();
                    for (String res : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res) && cameraOk) {
                            allowed.add(res);
                        } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res) && audioOk) {
                            allowed.add(res);
                        }
                    }

                    if (!allowed.isEmpty()) {
                        request.grant(allowed.toArray(new String[0]));
                    } else {
                        request.deny();
                    }
                });
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(new Locale("he", "IL"));
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED;
            tts.setSpeechRate(0.92f);
            tts.setPitch(0.95f);

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) { }

                @Override
                public void onDone(String utteranceId) {
                    if ("yishai_interviewer".equals(utteranceId)) {
                        notifyWebTtsDone();
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    if ("yishai_interviewer".equals(utteranceId)) {
                        notifyWebTtsDone();
                    }
                }
            });
        }
    }

    private boolean hasAllMediaPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void notifyWebPermissions(boolean granted) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "if(window.onNativePermissionsResult){window.onNativePermissionsResult(" + (granted ? "true" : "false") + ");}",
                        null
                );
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_PERMISSIONS) {
            notifyWebPermissions(hasAllMediaPermissions());
        }
    }

    private void notifyWebTtsDone() {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "if(window.onInterviewerDone){window.onInterviewerDone();}",
                        null
                );
            }
        });
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void speak(final String text, final double rate) {
            runOnUiThread(() -> {
                if (ttsReady) {
                    float safeRate = (float)Math.max(0.65, Math.min(1.25, rate));
                    tts.setSpeechRate(safeRate);
                    tts.stop();
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yishai_interviewer");
                } else {
                    notifyWebTtsDone();
                }
            });
        }

        @JavascriptInterface
        public boolean hasMediaPermissions() {
            return hasAllMediaPermissions();
        }

        @JavascriptInterface
        public void requestMediaPermissions() {
            runOnUiThread(() -> {
                if (hasAllMediaPermissions()) {
                    notifyWebPermissions(true);
                    return;
                }
                requestPermissions(new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                }, REQUEST_MEDIA_PERMISSIONS);
            });
        }

        @JavascriptInterface
        public boolean isNativeApp() {
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
