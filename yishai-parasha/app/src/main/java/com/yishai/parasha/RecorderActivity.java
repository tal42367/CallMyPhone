package com.yishai.parasha;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

public class RecorderActivity extends ComponentActivity implements TextToSpeech.OnInitListener {
    private PreviewView previewView;
    private TextView questionView;
    private TextView statusView;
    private TextView captionView;
    private Button startButton;
    private Button stopButton;
    private Button cancelButton;

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean ttsReady = false;
    private boolean cameraReady = false;
    private boolean cancelRequested = false;
    private boolean shouldListen = false;
    private boolean recognitionStarting = false;

    private int index;
    private String question;
    private double voiceRate;
    private double voicePitch;
    private boolean includeReaction;
    private File outputFile;
    private long recordingStartedAt;
    private double answerStartSec = 2.5;

    private final StringBuilder transcript = new StringBuilder();
    private String partialTranscript = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        index = getIntent().getIntExtra("index", -1);
        question = getIntent().getStringExtra("question");
        voiceRate = getIntent().getDoubleExtra("voice_rate", 0.92);
        voicePitch = getIntent().getDoubleExtra("voice_pitch", 0.78);
        includeReaction = getIntent().getBooleanExtra("include_reaction", false);
        if (question == null) question = "";

        buildUi();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishCancelled();
            return;
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(buildRecognitionListener());
        }

        tts = new TextToSpeech(this, this);
        bindCamera();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(16), dp(16), dp(16), dp(12));
        top.setBackgroundColor(0xAA000000);

        questionView = new TextView(this);
        questionView.setText(question);
        questionView.setTextColor(Color.WHITE);
        questionView.setTextSize(20);
        questionView.setGravity(Gravity.CENTER);
        questionView.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(questionView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        statusView = new TextView(this);
        statusView.setText("מכין מצלמה…");
        statusView.setTextColor(0xFFD7E3FF);
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(8), 0, 0);
        top.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        ));

        captionView = new TextView(this);
        captionView.setText("");
        captionView.setTextColor(Color.WHITE);
        captionView.setTextSize(22);
        captionView.setGravity(Gravity.CENTER);
        captionView.setTypeface(null, android.graphics.Typeface.BOLD);
        captionView.setPadding(dp(14), dp(10), dp(14), dp(10));
        captionView.setBackgroundColor(0x99000000);
        captionView.setVisibility(TextView.INVISIBLE);

        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        captionParams.setMargins(dp(20), 0, dp(20), dp(225));
        root.addView(captionView, captionParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(14), dp(16), dp(22));
        bottom.setBackgroundColor(0xAA000000);

        startButton = new Button(this);
        startButton.setText("🎙️ התחל צילום ושאלה");
        startButton.setTextSize(18);
        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> beginRecording());
        bottom.addView(startButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
        ));

        stopButton = new Button(this);
        stopButton.setText("■ סיימתי לענות");
        stopButton.setTextSize(18);
        stopButton.setEnabled(false);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
        );
        stopParams.topMargin = dp(10);
        bottom.addView(stopButton, stopParams);
        stopButton.setOnClickListener(v -> stopAndSave());

        cancelButton = new Button(this);
        cancelButton.setText("ביטול");
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
        );
        cancelParams.topMargin = dp(10);
        bottom.addView(cancelButton, cancelParams);
        cancelButton.setOnClickListener(v -> cancelRecording());

        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        ));

        setContentView(root);
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(
                                QualitySelector.from(
                                        Quality.HD,
                                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                                )
                        )
                        .build();

                videoCapture = VideoCapture.withOutput(recorder);
                cameraProvider.unbindAll();

                try {
                    cameraProvider.bindToLifecycle(
                            this,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            videoCapture
                    );
                } catch (Exception frontError) {
                    cameraProvider.bindToLifecycle(
                            this,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            videoCapture
                    );
                }

                cameraReady = true;
                updateReadyState();
            } catch (Exception e) {
                statusView.setText("לא הצלחתי לפתוח את המצלמה");
                startButton.setEnabled(false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void updateReadyState() {
        if (cameraReady && ttsReady) {
            statusView.setText("מוכן. התמקם בפריים ולחץ התחל");
            startButton.setEnabled(true);
        } else if (cameraReady) {
            statusView.setText("המצלמה מוכנה, מכין את קול המראיין…");
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(new Locale("he", "IL"));
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED;
            tts.setSpeechRate((float)Math.max(0.65, Math.min(1.25, voiceRate)));
            tts.setPitch((float)Math.max(0.65, Math.min(1.10, voicePitch)));

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }

                @Override
                public void onDone(String utteranceId) {
                    if ("native_interviewer".equals(utteranceId)) {
                        answerStartSec = Math.max(
                                0.5,
                                (SystemClock.elapsedRealtime() - recordingStartedAt) / 1000.0
                        );
                        runOnUiThread(() -> {
                            statusView.setText("עכשיו תענה — הכתוביות נוצרות אוטומטית");
                            stopButton.setEnabled(true);
                            startHebrewRecognition();
                        });
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    if ("native_interviewer".equals(utteranceId)) {
                        runOnUiThread(() -> {
                            statusView.setText("עכשיו תענה");
                            stopButton.setEnabled(true);
                            startHebrewRecognition();
                        });
                    }
                }
            });
            updateReadyState();
        } else {
            ttsReady = false;
            statusView.setText("קול המראיין לא זמין במכשיר");
        }
    }

    private RecognitionListener buildRecognitionListener() {
        return new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                recognitionStarting = false;
            }

            @Override public void onBeginningOfSpeech() { }

            @Override public void onRmsChanged(float rmsdB) { }

            @Override public void onBufferReceived(byte[] buffer) { }

            @Override public void onEndOfSpeech() { }

            @Override
            public void onError(int error) {
                recognitionStarting = false;
                if (shouldListen && activeRecording != null && !cancelRequested) {
                    handler.postDelayed(() -> startHebrewRecognition(), 700);
                }
            }

            @Override
            public void onResults(Bundle results) {
                recognitionStarting = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    appendTranscript(matches.get(0));
                }
                partialTranscript = "";
                updateCaptionPreview();
                if (shouldListen && activeRecording != null && !cancelRequested) {
                    handler.postDelayed(() -> startHebrewRecognition(), 350);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    partialTranscript = matches.get(0).trim();
                    updateCaptionPreview();
                }
            }

            @Override public void onEvent(int eventType, Bundle params) { }
        };
    }

    private void startHebrewRecognition() {
        if (speechRecognizer == null || recognitionStarting || !shouldListen) return;

        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
            recognitionStarting = true;
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            recognitionStarting = false;
        }
    }

    private void appendTranscript(String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return;

        String existing = transcript.toString().trim();
        if (!existing.isEmpty() && existing.endsWith(clean)) return;

        if (transcript.length() > 0) transcript.append(" ");
        transcript.append(clean);
    }

    private String finalTranscript() {
        String base = transcript.toString().trim();
        String partial = partialTranscript == null ? "" : partialTranscript.trim();
        if (!partial.isEmpty() && !base.endsWith(partial)) {
            if (!base.isEmpty()) base += " ";
            base += partial;
        }
        return base.trim();
    }

    private void updateCaptionPreview() {
        String text = finalTranscript();
        if (text.isEmpty()) {
            captionView.setVisibility(TextView.INVISIBLE);
        } else {
            captionView.setVisibility(TextView.VISIBLE);
            String[] words = text.split("\\s+");
            int start = Math.max(0, words.length - 10);
            StringBuilder tail = new StringBuilder();
            for (int i = start; i < words.length; i++) {
                if (tail.length() > 0) tail.append(" ");
                tail.append(words[i]);
            }
            captionView.setText(tail.toString());
        }
    }

    private void beginRecording() {
        if (!cameraReady || videoCapture == null || activeRecording != null) return;

        startButton.setEnabled(false);
        stopButton.setEnabled(false);
        statusView.setText("מתחיל צילום…");
        transcript.setLength(0);
        partialTranscript = "";
        captionView.setVisibility(TextView.INVISIBLE);
        shouldListen = false;

        File dir = new File(getFilesDir(), "recordings");
        if (!dir.exists()) dir.mkdirs();
        outputFile = new File(dir, "yishai_" + System.currentTimeMillis() + ".mp4");

        FileOutputOptions options = new FileOutputOptions.Builder(outputFile).build();
        PendingRecording pending = videoCapture.getOutput()
                .prepareRecording(this, options)
                .withAudioEnabled();

        cancelRequested = false;
        activeRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                recordingStartedAt = SystemClock.elapsedRealtime();
                statusView.setText("המראיין שואל…");
                String spoken = (includeReaction ? "יפה מאוד ישי. " : "") + question;
                tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "native_interviewer");
            }

            if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                activeRecording = null;
                shouldListen = false;

                if (cancelRequested || fin.hasError()) {
                    if (outputFile != null && outputFile.exists()) outputFile.delete();
                    finishCancelled();
                    return;
                }

                Intent result = new Intent();
                result.putExtra("index", index);
                result.putExtra("file_name", outputFile.getName());
                result.putExtra("answer_start_sec", answerStartSec);
                result.putExtra("transcript", finalTranscript());
                setResult(RESULT_OK, result);
                finish();
            }
        });

        shouldListen = true;
    }

    private void stopAndSave() {
        stopButton.setEnabled(false);
        cancelButton.setEnabled(false);
        statusView.setText("שומר את התשובה והכתוביות…");
        shouldListen = false;
        recognitionStarting = false;

        if (speechRecognizer != null) {
            try { speechRecognizer.stopListening(); } catch (Exception ignored) { }
        }
        if (tts != null) tts.stop();
        if (activeRecording != null) activeRecording.stop();
    }

    private void cancelRecording() {
        cancelRequested = true;
        shouldListen = false;
        recognitionStarting = false;

        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
        }
        if (tts != null) tts.stop();

        if (activeRecording != null) {
            activeRecording.stop();
        } else {
            if (outputFile != null && outputFile.exists()) outputFile.delete();
            finishCancelled();
        }
    }

    private void finishCancelled() {
        Intent result = new Intent();
        result.putExtra("index", index);
        setResult(RESULT_CANCELED, result);
        finish();
    }

    @Override
    public void onBackPressed() {
        cancelRecording();
    }

    @Override
    protected void onDestroy() {
        shouldListen = false;
        handler.removeCallbacksAndMessages(null);

        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
        }
        if (activeRecording != null) {
            try { activeRecording.close(); } catch (Exception ignored) { }
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
