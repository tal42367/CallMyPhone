package com.yishai.parasha;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
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
import java.util.Locale;

public class RecorderActivity extends ComponentActivity implements TextToSpeech.OnInitListener {
    private PreviewView previewView;
    private TextView questionView;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private Button cancelButton;

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean cameraReady = false;
    private boolean cancelRequested = false;

    private int index;
    private String question;
    private double voiceRate;
    private boolean includeReaction;
    private File outputFile;
    private long recordingStartedAt;
    private double answerStartSec = 2.5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        index = getIntent().getIntExtra("index", -1);
        question = getIntent().getStringExtra("question");
        voiceRate = getIntent().getDoubleExtra("voice_rate", 0.92);
        includeReaction = getIntent().getBooleanExtra("include_reaction", false);
        if (question == null) question = "";

        buildUi();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishCancelled();
            return;
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
            tts.setPitch(0.95f);

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
                            statusView.setText("עכשיו תענה");
                            stopButton.setEnabled(true);
                        });
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    if ("native_interviewer".equals(utteranceId)) {
                        runOnUiThread(() -> {
                            statusView.setText("עכשיו תענה");
                            stopButton.setEnabled(true);
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

    private void beginRecording() {
        if (!cameraReady || videoCapture == null || activeRecording != null) return;

        startButton.setEnabled(false);
        stopButton.setEnabled(false);
        statusView.setText("מתחיל צילום…");

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

                if (cancelRequested || fin.hasError()) {
                    if (outputFile != null && outputFile.exists()) outputFile.delete();
                    finishCancelled();
                    return;
                }

                Intent result = new Intent();
                result.putExtra("index", index);
                result.putExtra("file_name", outputFile.getName());
                result.putExtra("answer_start_sec", answerStartSec);
                setResult(RESULT_OK, result);
                finish();
            }
        });
    }

    private void stopAndSave() {
        stopButton.setEnabled(false);
        cancelButton.setEnabled(false);
        statusView.setText("שומר את התשובה…");
        if (tts != null) tts.stop();
        if (activeRecording != null) activeRecording.stop();
    }

    private void cancelRecording() {
        cancelRequested = true;
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
