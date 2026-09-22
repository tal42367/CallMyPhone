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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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


    private File fileFromMediaUrl(String mediaUrl) {
        try {
            String prefix = "https://appassets.androidplatform.net/media/";
            if (mediaUrl == null || !mediaUrl.startsWith(prefix)) return null;
            String name = Uri.decode(mediaUrl.substring(prefix.length()));
            File f = new File(recordingsDir, name);
            if (!f.exists()) return null;
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private void notifyCloudTranscript(int index, String text) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String js = "if(window.onCloudTranscript){window.onCloudTranscript(" +
                    index + "," + JSONObject.quote(text == null ? "" : text) + ");}";
            webView.evaluateJavascript(js, null);
        });
    }

    private void notifyCloudTranscriptError(int index, String message) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String js = "if(window.onCloudTranscriptError){window.onCloudTranscriptError(" +
                    index + "," + JSONObject.quote(message == null ? "transcription failed" : message) + ");}";
            webView.evaluateJavascript(js, null);
        });
    }

    private String readText(InputStream input) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private void transcribeWithOpenAI(
            int index,
            File file,
            String apiKey,
            String prompt,
            boolean translateToHebrew
    ) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String boundary = "----YishaiBoundary" + System.currentTimeMillis();
                URL url = new URL("https://api.openai.com/v1/audio/transcriptions");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(180000);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                    writeFormField(out, boundary, "model", "gpt-4o-transcribe");

                    // When true translation is requested, do not force Hebrew here.
                    // Let transcription detect the spoken language first.
                    if (!translateToHebrew) {
                        writeFormField(out, boundary, "language", "he");
                        if (prompt != null && !prompt.trim().isEmpty()) {
                            writeFormField(out, boundary, "prompt", prompt.trim());
                        }
                    }

                    out.writeBytes("--" + boundary + "\r\n");
                    out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"answer.mp4\"\r\n");
                    out.writeBytes("Content-Type: video/mp4\r\n\r\n");

                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }

                    out.writeBytes("\r\n--" + boundary + "--\r\n");
                    out.flush();
                }

                int code = conn.getResponseCode();
                InputStream body = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
                String response = body != null ? readText(body) : "";

                if (code < 200 || code >= 300) {
                    notifyCloudTranscriptError(index, "Transcription HTTP " + code);
                    return;
                }

                JSONObject json = new JSONObject(response);
                String sourceText = json.optString("text", "").trim();

                if (sourceText.isEmpty()) {
                    notifyCloudTranscript(index, "");
                    return;
                }

                if (!translateToHebrew) {
                    notifyCloudTranscript(index, sourceText);
                    return;
                }

                String hebrew = translateTextToHebrew(apiKey, sourceText);
                notifyCloudTranscript(index, hebrew);
            } catch (Exception e) {
                notifyCloudTranscriptError(index, e.getClass().getSimpleName());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private String translateTextToHebrew(String apiKey, String sourceText) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.openai.com/v1/responses");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            JSONObject request = new JSONObject();
            request.put("model", "gpt-5.6-luna");
            request.put(
                    "input",
                    "Translate the following spoken transcript faithfully into natural Hebrew. " +
                    "If it is already Hebrew, keep the wording in Hebrew and only normalize obvious punctuation. " +
                    "Do not explain, label, summarize, or add anything. Return only the Hebrew subtitle text.\n\n" +
                    sourceText
            );
            request.put("max_output_tokens", 1200);

            byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
            try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
                out.write(payload);
                out.flush();
            }

            int code = conn.getResponseCode();
            InputStream body = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = body != null ? readText(body) : "";

            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Translation HTTP " + code);
            }

            JSONObject json = new JSONObject(response);
            JSONArray output = json.optJSONArray("output");
            if (output != null) {
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    if (item == null) continue;
                    JSONArray content = item.optJSONArray("content");
                    if (content == null) continue;

                    for (int j = 0; j < content.length(); j++) {
                        JSONObject part = content.optJSONObject(j);
                        if (part == null) continue;
                        if ("output_text".equals(part.optString("type"))) {
                            String text = part.optString("text", "").trim();
                            if (!text.isEmpty()) return text;
                        }
                    }
                }
            }

            throw new IllegalStateException("No Hebrew translation returned");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void writeFormField(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void speak(final String text, final double rate, final double pitch) {
            runOnUiThread(() -> {
                if (ttsReady) {
                    float safeRate = (float)Math.max(0.65, Math.min(1.25, rate));
                    tts.setSpeechRate(safeRate);
                    float safePitch = (float)Math.max(0.65, Math.min(1.10, pitch));
                    tts.setPitch(safePitch);
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
                final double pitch,
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
                intent.putExtra("voice_pitch", pitch);
                intent.putExtra("include_reaction", includeReaction);
                recorderLauncher.launch(intent);
            });
        }

        @JavascriptInterface
        public void transcribeClip(
                final int index,
                final String mediaUrl,
                final String apiKey,
                final String prompt,
                final boolean translateToHebrew
        ) {
            if (apiKey == null || apiKey.trim().isEmpty()) {
                notifyCloudTranscriptError(index, "missing API key");
                return;
            }
            File file = fileFromMediaUrl(mediaUrl);
            if (file == null) {
                notifyCloudTranscriptError(index, "clip not found");
                return;
            }
            transcribeWithOpenAI(index, file, apiKey.trim(), prompt, translateToHebrew);
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
