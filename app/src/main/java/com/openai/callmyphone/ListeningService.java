package com.openai.callmyphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

public class ListeningService extends Service implements RecognitionListener {
    public static final String ACTION_START_LISTENING = "com.openai.callmyphone.START_LISTENING";
    public static final String ACTION_STOP_LISTENING = "com.openai.callmyphone.STOP_LISTENING";
    public static final String ACTION_TEST_RING = "com.openai.callmyphone.TEST_RING";
    public static final String ACTION_STOP_RING = "com.openai.callmyphone.STOP_RING";

    private static final String PREFS = "call_my_phone_prefs";
    private static final String KEY_NAME = "phone_name";
    private static final String KEY_LISTENING = "listening_active";
    private static final String CHANNEL_ID = "name_listening";
    private static final int NOTIFICATION_ID = 5001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private boolean listeningDesired;
    private boolean ringing;
    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn;
    private int previousAlarmVolume = -1;

    private final Runnable restartRecognizer = new Runnable() {
        @Override public void run() {
            if (listeningDesired && !ringing) startRecognitionNow();
        }
    };

    private final Runnable blinkTorch = new Runnable() {
        @Override public void run() {
            if (!ringing) {
                setTorch(false);
                return;
            }
            torchOn = !torchOn;
            setTorch(torchOn);
            handler.postDelayed(this, 350);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
        setupTorch();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP_LISTENING.equals(action)) {
            listeningDesired = false;
            prefs.edit().putBoolean(KEY_LISTENING, false).apply();
            stopRinging(false);
            destroyRecognizer();
            removeForeground();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP_RING.equals(action)) {
            stopRinging(true);
            return START_STICKY;
        }

        startAsForeground(false);

        if (ACTION_TEST_RING.equals(action)) {
            listeningDesired = prefs.getBoolean(KEY_LISTENING, false);
            triggerRinging();
            return START_STICKY;
        }

        listeningDesired = true;
        prefs.edit().putBoolean(KEY_LISTENING, true).apply();
        ensureRecognizer();
        startRecognitionNow();
        return START_STICKY;
    }

    private void startAsForeground(boolean isRinging) {
        Notification notification = buildNotification(isRinging);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void ensureRecognizer() {
        if (recognizer != null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            } else {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            }
            recognizer.setRecognitionListener(this);
        } catch (Throwable t) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
    }

    private void startRecognitionNow() {
        if (!listeningDesired || ringing) return;
        ensureRecognizer();
        if (recognizer == null) return;
        handler.removeCallbacks(restartRecognizer);
        try {
            recognizer.cancel();
            recognizer.startListening(recognizerIntent);
        } catch (Throwable t) {
            scheduleRestart(1500);
        }
    }

    private void scheduleRestart(long delayMs) {
        handler.removeCallbacks(restartRecognizer);
        if (listeningDesired && !ringing) handler.postDelayed(restartRecognizer, delayMs);
    }

    private void inspectResults(android.os.Bundle results) {
        if (results == null || ringing) return;
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) return;
        String target = normalize(prefs.getString(KEY_NAME, ""));
        if (target.isEmpty()) return;

        for (String candidate : matches) {
            if (containsName(normalize(candidate), target)) {
                triggerRinging();
                return;
            }
        }
    }

    private boolean containsName(String spoken, String target) {
        if (spoken.equals(target)) return true;
        String[] words = spoken.split("\\s+");
        for (String word : words) if (word.equals(target)) return true;
        return false;
    }

    private String normalize(String s) {
        if (s == null) return "";
        String out = s.toLowerCase(Locale.ROOT).trim();
        out = Normalizer.normalize(out, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        out = out.replaceAll("[\\p{Punct}׳״]", " ").replaceAll("\\s+", " ").trim();
        return out;
    }

    private void triggerRinging() {
        if (ringing) return;
        ringing = true;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
        }
        startAsForeground(true);
        startAlarmSound();
        startVibration();
        handler.removeCallbacks(blinkTorch);
        handler.post(blinkTorch);
    }

    private void startAlarmSound() {
        stopAlarmSound();
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            previousAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);

            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Throwable ignored) {
        }
    }

    private void stopAlarmSound() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Throwable ignored) {}
            try { mediaPlayer.release(); } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
        if (previousAlarmVolume >= 0) {
            try {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0);
            } catch (Throwable ignored) {}
            previousAlarmVolume = -1;
        }
    }

    private void startVibration() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            }
            long[] pattern = {0, 700, 350, 700, 350};
            if (Build.VERSION.SDK_INT >= 26) {
                VibrationEffect effect = VibrationEffect.createWaveform(pattern, 1);
                if (Build.VERSION.SDK_INT >= 33) {
                    vibrator.vibrate(effect, new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build());
                } else {
                    AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
                    vibrator.vibrate(effect, attrs);
                }
            } else {
                vibrator.vibrate(pattern, 1);
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopVibration() {
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Throwable ignored) {}
        }
    }

    private void setupTorch() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : cameraManager.getCameraIdList()) {
                Boolean hasFlash = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    torchCameraId = id;
                    break;
                }
            }
        } catch (Throwable ignored) {
            torchCameraId = null;
        }
    }

    private void setTorch(boolean enabled) {
        if (cameraManager == null || torchCameraId == null || Build.VERSION.SDK_INT < 23) return;
        try {
            cameraManager.setTorchMode(torchCameraId, enabled);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException ignored) {
        }
    }

    private void stopRinging(boolean resumeListening) {
        if (!ringing) return;
        ringing = false;
        handler.removeCallbacks(blinkTorch);
        setTorch(false);
        torchOn = false;
        stopAlarmSound();
        stopVibration();

        if (resumeListening && listeningDesired) {
            startAsForeground(false);
            scheduleRestart(800);
        } else if (!listeningDesired) {
            removeForeground();
        }
    }

    private Notification buildNotification(boolean isRinging) {
        String name = prefs.getString(KEY_NAME, "");
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ListeningService.class);
        stopIntent.setAction(isRinging ? ACTION_STOP_RING : ACTION_STOP_LISTENING);
        PendingIntent stopPending = PendingIntent.getService(this, isRinging ? 12 : 11, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = getString(isRinging ? R.string.notification_ringing_title : R.string.notification_listening_title);
        String text = isRinging ? getString(R.string.notification_ringing_text)
                : getString(R.string.notification_listening_text, name);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPending)
                .setOngoing(!isRinging)
                .setCategory(isRinging ? Notification.CATEGORY_ALARM : Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,
                        getString(isRinging ? R.string.notification_stop : R.string.notification_stop_listening),
                        stopPending).build());
        if (isRinging) b.setPriority(Notification.PRIORITY_MAX);
        return b.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_listening_title));
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private void removeForeground() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void destroyRecognizer() {
        handler.removeCallbacks(restartRecognizer);
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
    }

    @Override public void onReadyForSpeech(android.os.Bundle params) {}
    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() { scheduleRestart(450); }
    @Override public void onError(int error) {
        long delay = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) ? 1800 : 700;
        scheduleRestart(delay);
    }
    @Override public void onResults(android.os.Bundle results) {
        inspectResults(results);
        if (!ringing) scheduleRestart(350);
    }
    @Override public void onPartialResults(android.os.Bundle partialResults) { inspectResults(partialResults); }
    @Override public void onEvent(int eventType, android.os.Bundle params) {}

    @Override
    public void onDestroy() {
        listeningDesired = false;
        prefs.edit().putBoolean(KEY_LISTENING, false).apply();
        stopRinging(false);
        destroyRecognizer();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
