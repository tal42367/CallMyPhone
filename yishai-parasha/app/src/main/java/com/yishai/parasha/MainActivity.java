package com.yishai.parasha;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

public class MainActivity extends ComponentActivity implements TextToSpeech.OnInitListener {
    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private File recordingsDir;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                notifyWebPermissions(hasAllMediaPermissions());
            });

    private final ActivityResultLauncher<Intent> recorderLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (webView == null) return;

                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    int index = data.getIntExtra("index", -1);
                    String fileName = data.getStringExtra("file_name");
                    double answerStartSec = data.getDoubleExtra("answer_start_sec", 2.5);
                    String transcript = data.getStringExtra("transcript");
                    if (transcript == null) transcript = "";

                    if (index >= 0 && fileName != null) {
                        String mediaUrl = "https://appassets.androidplatform.net/media/" + Uri.encode(fileName);
                        String js = "if(window.onNativeClipRecorded){window.onNativeClipRecorded(" +
                                index + "," + JSONObject.quote(mediaUrl) + "," + answerStartSec + "," +
                                JSONObject.quote(transcript) + ");}";
                        webView.evaluateJavascript(js, null);
                    }
                } else {
                    int index = -1;
                    if (result.getData() != null) {
                        index = result.getData().getIntExtra("index", -1);
                    }
                    webView.evaluateJavascript(
                            "if(window.onNativeClipCancelled){window.onNativeClipCancelled(" + index + ");}",
                            null
                    );
                }
            });

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        recordingsDir = new File(getFilesDir(), "recordings");
        if (!recordingsDir.exists()) recordingsDir.mkdirs();

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
                .addPathHandler("/media/", new WebViewAssetLoader.InternalStoragePathHandler(this, recordingsDir))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
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
                @Override public void onStart(String utteranceId) { }

                @Override
                public void onDone(String utteranceId) {
                    if ("yishai_interviewer".equals(utteranceId)) notifyWebTtsDone();
                }

                @Override
                public void onError(String utteranceId) {
                    if ("yishai_interviewer".equals(utteranceId)) notifyWebTtsDone();
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
                        "if(window.onNativePermissionsResult){window.onNativePermissionsResult(" +
                                (granted ? "true" : "false") + ");}",
                        null
                );
            }
        });
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
                } else {
                    permissionLauncher.launch(new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                    });
                }
            });
        }

        @JavascriptInterface
        public void startNativeRecording(
                final int index,
                final String question,
                final double rate,
                final boolean includeReaction
        ) {
            runOnUiThread(() -> {
                if (!hasAllMediaPermissions()) {
                    notifyWebPermissions(false);
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "if(window.onNativeClipCancelled){window.onNativeClipCancelled(" + index + ");}",
                                null
                        );
                    }
                    return;
                }

                Intent intent = new Intent(MainActivity.this, RecorderActivity.class);
                intent.putExtra("index", index);
                intent.putExtra("question", question);
                intent.putExtra("voice_rate", rate);
                intent.putExtra("include_reaction", includeReaction);
                recorderLauncher.launch(intent);
            });
        }

        @JavascriptInterface
        public void deleteRecording(final String mediaUrl) {
            runOnUiThread(() -> {
                try {
                    String prefix = "https://appassets.androidplatform.net/media/";
                    if (mediaUrl != null && mediaUrl.startsWith(prefix)) {
                        String name = Uri.decode(mediaUrl.substring(prefix.length()));
                        File f = new File(recordingsDir, name);
                        if (f.exists()) f.delete();
                    }
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public boolean isNativeApp() {
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
