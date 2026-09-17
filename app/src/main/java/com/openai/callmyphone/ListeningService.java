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
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.util.Arrays;

public class ListeningService extends Service {
    public static final String ACTION_START_LISTENING = "com.openai.callmyphone.START_LISTENING";
    public static final String ACTION_STOP_LISTENING = "com.openai.callmyphone.STOP_LISTENING";
    public static final String ACTION_TEST_RING = "com.openai.callmyphone.TEST_RING";
    public static final String ACTION_STOP_RING = "com.openai.callmyphone.STOP_RING";

    private static final String PREFS = "call_my_phone_prefs";
    private static final String KEY_NAME = "phone_name";
    private static final String KEY_LISTENING = "listening_active";
    private static final String KEY_LANG = "app_language";
    private static final String KEY_PROFILE = "voice_profile";
    private static final String KEY_PROFILE_NAME = "voice_profile_name";
    private static final String CHANNEL_ID = "name_listening";
    private static final int NOTIFICATION_ID = 5001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private volatile boolean listeningDesired;
    private volatile boolean ringing;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private double[][] voiceTemplate;
    private PowerManager.WakeLock wakeLock;

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn;
    private int previousAlarmVolume = -1;

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
            stopListeningAndSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP_RING.equals(action)) {
            stopRinging();
            stopListeningAndSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_TEST_RING.equals(action)) {
            listeningDesired = false;
            prefs.edit().putBoolean(KEY_LISTENING, false).commit();
            startAsForeground(true);
            triggerRinging();
            return START_NOT_STICKY;
        }

        if (action == null && !prefs.getBoolean(KEY_LISTENING, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String profileText = prefs.getString(KEY_PROFILE, "");
        String profileName = prefs.getString(KEY_PROFILE_NAME, "");
        String currentName = prefs.getString(KEY_NAME, "");
        voiceTemplate = VoiceMatcher.decode(profileText);
        if (voiceTemplate.length < 4 || currentName == null || !currentName.equals(profileName)) {
            prefs.edit().putBoolean(KEY_LISTENING, false).commit();
            stopSelf();
            return START_NOT_STICKY;
        }

        listeningDesired = true;
        prefs.edit().putBoolean(KEY_LISTENING, true).commit();
        startAsForeground(false);
        acquireWakeLock();
        startSilentMicrophoneLoop();
        return START_STICKY;
    }

    private void startSilentMicrophoneLoop() {
        stopAudioOnly();
        listeningDesired = true;

        audioThread = new Thread(() -> {
            int min = AudioRecord.getMinBufferSize(
                    VoiceMatcher.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(min, 4096);
            AudioRecord local = null;
            try {
                local = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        VoiceMatcher.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
                if (local.getState() != AudioRecord.STATE_INITIALIZED) return;
                audioRecord = local;
                local.startRecording();

                final int blockSize = 800; // 50 ms at 16 kHz
                short[] block = new short[blockSize];
                short[] preRoll = new short[blockSize * 4]; // last 200 ms
                int preUsed = 0;
                short[] utterance = new short[VoiceMatcher.SAMPLE_RATE * 3];
                int used = 0;
                boolean inSpeech = false;
                int quietBlocks = 0;
                double noiseFloor = 220.0;
                long ignoreUntil = 0L;

                while (listeningDesired && !ringing) {
                    int n = local.read(block, 0, block.length);
                    if (n <= 0) continue;
                    double level = VoiceMatcher.rms(block, n);

                    if (!inSpeech) {
                        if (level < Math.max(650.0, noiseFloor * 2.0)) {
                            noiseFloor = noiseFloor * 0.96 + Math.min(level, 900.0) * 0.04;
                        }

                        appendPreRoll(preRoll, block, n);
                        preUsed = Math.min(preRoll.length, preUsed + n);

                        double startThreshold = Math.max(650.0, noiseFloor * 2.35);
                        if (System.currentTimeMillis() >= ignoreUntil && level >= startThreshold) {
                            inSpeech = true;
                            quietBlocks = 0;
                            used = Math.min(preUsed, utterance.length);
                            int from = preRoll.length - used;
                            System.arraycopy(preRoll, from, utterance, 0, used);
                            if (used + n <= utterance.length) {
                                System.arraycopy(block, 0, utterance, used, n);
                                used += n;
                            }
                        }
                        continue;
                    }

                    if (used + n <= utterance.length) {
                        System.arraycopy(block, 0, utterance, used, n);
                        used += n;
                    }

                    double stopThreshold = Math.max(430.0, noiseFloor * 1.55);
                    if (level < stopThreshold) quietBlocks++;
                    else quietBlocks = 0;

                    boolean longEnough = used >= VoiceMatcher.SAMPLE_RATE / 4;
                    boolean finished = longEnough && quietBlocks >= 7;
                    boolean full = used >= utterance.length - blockSize;

                    if (finished || full) {
                        short[] candidate = Arrays.copyOf(utterance, used);
                        boolean match = VoiceMatcher.isMatch(voiceTemplate, candidate);
                        inSpeech = false;
                        quietBlocks = 0;
                        used = 0;
                        Arrays.fill(preRoll, (short) 0);
                        preUsed = 0;
                        ignoreUntil = System.currentTimeMillis() + 350;

                        if (match && listeningDesired && !ringing) {
                            handler.post(this::triggerRinging);
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (local != null) {
                    try { local.stop(); } catch (Throwable ignored) {}
                    try { local.release(); } catch (Throwable ignored) {}
                }
                if (audioRecord == local) audioRecord = null;
            }
        }, "silent-name-listener");
        audioThread.start();
    }

    private void appendPreRoll(short[] preRoll, short[] block, int n) {
        if (n >= preRoll.length) {
            System.arraycopy(block, n - preRoll.length, preRoll, 0, preRoll.length);
            return;
        }
        System.arraycopy(preRoll, n, preRoll, 0, preRoll.length - n);
        System.arraycopy(block, 0, preRoll, preRoll.length - n, n);
    }

    private void stopAudioOnly() {
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try { ar.stop(); } catch (Throwable ignored) {}
            try { ar.release(); } catch (Throwable ignored) {}
        }
        audioThread = null;
    }

    private boolean isHebrew() {
        return "he".equalsIgnoreCase(prefs.getString(KEY_LANG, "en"));
    }

    private void startAsForeground(boolean isRinging) {
        Notification notification = buildNotification(isRinging);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallMyPhone:Listening");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        }
    }

    private void triggerRinging() {
        if (ringing) return;
        ringing = true;
        listeningDesired = false;
        prefs.edit().putBoolean(KEY_LISTENING, false).commit();
        stopAudioOnly();
        releaseWakeLock();
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
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);

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
        } catch (Throwable ignored) {}
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
                    vibrator.vibrate(effect,
                            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build());
                } else {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM).build();
                    vibrator.vibrate(effect, attrs);
                }
            } else {
                vibrator.vibrate(pattern, 1);
            }
        } catch (Throwable ignored) {}
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
                Boolean hasFlash = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
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
        } catch (CameraAccessException | SecurityException | IllegalArgumentException ignored) {}
    }

    private void stopRinging() {
        if (!ringing) return;
        ringing = false;
        handler.removeCallbacks(blinkTorch);
        setTorch(false);
        torchOn = false;
        stopAlarmSound();
        stopVibration();
    }

    private void stopListeningAndSelf() {
        listeningDesired = false;
        prefs.edit().putBoolean(KEY_LISTENING, false).commit();
        stopAudioOnly();
        releaseWakeLock();
        stopRinging();
        removeForeground();
        stopSelf();
    }

    private Notification buildNotification(boolean isRinging) {
        String name = prefs.getString(KEY_NAME, "");
        boolean he = isHebrew();

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ListeningService.class);
        stopIntent.setAction(isRinging ? ACTION_STOP_RING : ACTION_STOP_LISTENING);
        PendingIntent stopPending = PendingIntent.getService(
                this, isRinging ? 12 : 11, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = isRinging
                ? (he ? "מצאתי אותך" : "Phone found")
                : (he ? "מקשיב בשקט לשם של הפלאפון" : "Silently listening for your phone name");
        String text = isRinging
                ? (he ? "הפלאפון מצלצל עכשיו" : "Alarm is ringing")
                : (he ? "קרא “" + name + "” כדי לגרום לפלאפון לצלצל"
                      : "Call “" + name + "” to make this phone ring");
        String actionText = isRinging
                ? (he ? "עצור" : "Stop")
                : (he ? "כבה האזנה" : "Stop listening");

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
                        actionText,
                        stopPending).build());
        if (isRinging) b.setPriority(Notification.PRIORITY_MAX);
        return b.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Call My Phone",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Background name listening");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private void removeForeground() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override
    public void onDestroy() {
        listeningDesired = false;
        prefs.edit().putBoolean(KEY_LISTENING, false).commit();
        stopAudioOnly();
        releaseWakeLock();
        stopRinging();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
