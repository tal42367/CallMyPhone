package com.openai.callmyphone;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 1001;
    private static final String PREFS = "call_my_phone_prefs";
    private static final String KEY_NAME = "phone_name";
    private static final String KEY_LISTENING = "listening_active";
    private static final String KEY_LANG = "app_language";
    private static final String KEY_PROFILE = "voice_profile";
    private static final String KEY_PROFILE_NAME = "voice_profile_name";

    private EditText nameInput;
    private Button listenButton;
    private Button learnButton;
    private Button testButton;
    private Button stopAlarmButton;
    private Button languageButton;
    private TextView titleText;
    private TextView subtitleText;
    private TextView nameLabel;
    private TextView saveInfoText;
    private TextView statusText;
    private TextView noteText;
    private LinearLayout root;
    private SharedPreferences prefs;
    private boolean hebrew;
    private boolean pendingEnroll;
    private volatile boolean enrolling;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedLang = prefs.getString(KEY_LANG, "");
        if (savedLang == null || savedLang.isEmpty()) {
            savedLang = "he".equalsIgnoreCase(Locale.getDefault().getLanguage()) ? "he" : "en";
            prefs.edit().putString(KEY_LANG, savedLang).commit();
        }
        hebrew = "he".equals(savedLang);

        setContentView(buildUi());
        nameInput.setText(prefs.getString(KEY_NAME, ""));
        nameInput.setSelection(nameInput.getText().length());

        nameInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                prefs.edit().putString(KEY_NAME, cleanName(s == null ? "" : s.toString())).apply();
                if (!enrolling) refreshUi();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        languageButton.setOnClickListener(v -> toggleLanguage());
        learnButton.setOnClickListener(v -> requestEnrollment());
        listenButton.setOnClickListener(v -> toggleListening());
        testButton.setOnClickListener(v -> testAlarm());
        stopAlarmButton.setOnClickListener(v -> sendServiceAction(ListeningService.ACTION_STOP_RING));

        applyLanguageTexts();
        refreshUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override protected void onPause() {
        super.onPause();
        saveCurrentName();
    }

    private View buildUi() {
        int pad = dp(24);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(28), pad, pad);
        root.setBackgroundColor(Color.WHITE);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        titleText = text(30, Color.rgb(25, 25, 25), Gravity.CENTER);
        root.addView(titleText, lpMatchWrap(0, dp(8)));

        languageButton = button();
        root.addView(languageButton, lpMatch(dp(48), 0, dp(16)));

        subtitleText = text(17, Color.rgb(80, 80, 80), Gravity.CENTER);
        root.addView(subtitleText, lpMatchWrap(0, dp(24)));

        nameLabel = text(16, Color.rgb(45, 45, 45), Gravity.START);
        root.addView(nameLabel, lpMatchWrap(0, dp(8)));

        nameInput = new EditText(this);
        nameInput.setTextSize(22);
        nameInput.setSingleLine(true);
        nameInput.setGravity(Gravity.CENTER);
        nameInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(nameInput, lpMatch(dp(58), 0, dp(6)));

        saveInfoText = text(13, Color.rgb(90, 90, 90), Gravity.CENTER);
        root.addView(saveInfoText, lpMatchWrap(0, dp(12)));

        learnButton = button();
        root.addView(learnButton, lpMatch(dp(56), 0, dp(12)));

        statusText = text(17, Color.rgb(45, 45, 45), Gravity.CENTER);
        root.addView(statusText, lpMatchWrap(0, dp(16)));

        listenButton = button();
        root.addView(listenButton, lpMatch(dp(56), 0, dp(12)));

        testButton = button();
        root.addView(testButton, lpMatch(dp(56), 0, dp(12)));

        stopAlarmButton = button();
        root.addView(stopAlarmButton, lpMatch(dp(56), 0, dp(24)));

        noteText = text(13, Color.rgb(110, 110, 110), Gravity.CENTER);
        root.addView(noteText, lpMatchWrap(0, dp(24)));
        return scroll;
    }

    private TextView text(int size, int color, int gravity) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(gravity);
        return t;
    }

    private Button button() {
        Button b = new Button(this);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        return b;
    }

    private void applyLanguageTexts() {
        root.setLayoutDirection(hebrew ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        titleText.setText(hebrew ? "איפה הפלאפון?" : "Call My Phone");
        languageButton.setText(hebrew ? "English" : "עברית");
        subtitleText.setText(hebrew
                ? "תן לפלאפון שם, למד אותו פעם אחת איך אתה אומר את השם, ואז הוא יקשיב בשקט ברקע."
                : "Give your phone a name, teach it once how you say the name, then it listens silently in the background.");
        nameLabel.setText(hebrew ? "השם של הפלאפון" : "Phone name");
        nameInput.setHint(hebrew ? "לדוגמה: רובי" : "Example: Ruby");
        saveInfoText.setText(hebrew ? "השם נשמר אוטומטית" : "The name is saved automatically");
        learnButton.setText(hebrew ? "למד את השם בקול" : "Teach the spoken name");
        testButton.setText(hebrew ? "בדיקת צלצול" : "Test alarm");
        stopAlarmButton.setText(hebrew ? "עצור צלצול" : "Stop alarm");
        noteText.setText(hebrew
                ? "אין שימוש במנוע הדיבור של Android, ולכן ההאזנה עצמה שקטה."
                : "Android speech recognition is not used, so background listening itself is silent.");
        refreshUi();
    }

    private void toggleLanguage() {
        saveCurrentName();
        hebrew = !hebrew;
        prefs.edit().putString(KEY_LANG, hebrew ? "he" : "en").commit();
        applyLanguageTexts();
    }

    private void saveCurrentName() {
        if (nameInput == null) return;
        prefs.edit().putString(KEY_NAME, cleanName(nameInput.getText().toString())).commit();
    }

    private boolean hasProfileForCurrentName() {
        String name = cleanName(nameInput.getText().toString());
        String learned = prefs.getString(KEY_PROFILE_NAME, "");
        String profile = prefs.getString(KEY_PROFILE, "");
        return !name.isEmpty() && name.equals(learned) && profile != null && !profile.isEmpty();
    }

    private void hideKeyboard() {
        try {
            nameInput.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(nameInput.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    private void requestEnrollment() {
        if (enrolling) return;
        saveCurrentName();
        String name = prefs.getString(KEY_NAME, "");
        if (name == null || name.isEmpty()) {
            Toast.makeText(this,
                    hebrew ? "קודם כתוב שם לפלאפון" : "Enter a phone name first",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        hideKeyboard();
        sendServiceAction(ListeningService.ACTION_STOP_LISTENING);
        prefs.edit().putBoolean(KEY_LISTENING, false).commit();

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingEnroll = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSIONS);
            return;
        }
        beginEnrollment();
    }

    private void beginEnrollment() {
        if (enrolling) return;
        enrolling = true;
        learnButton.setEnabled(false);
        listenButton.setEnabled(false);
        String name = prefs.getString(KEY_NAME, "");
        statusText.setText(hebrew
                ? "🎙️ מקליט עכשיו — אמור: " + name
                : "🎙️ Recording now — say: " + name);
        Toast.makeText(this,
                hebrew ? "יש לך כ־3 שניות לומר את השם" : "You have about 3 seconds to say the name",
                Toast.LENGTH_LONG).show();

        new Thread(() -> {
            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
            short[] captured = captureTrainingWindow();
            double[][] features = VoiceMatcher.extract(captured);
            String encoded = VoiceMatcher.encode(features);
            String currentName = prefs.getString(KEY_NAME, "");
            boolean ok = features.length >= 4
                    && !encoded.isEmpty()
                    && currentName != null
                    && !currentName.isEmpty();

            if (ok) {
                prefs.edit()
                        .putString(KEY_PROFILE, encoded)
                        .putString(KEY_PROFILE_NAME, currentName)
                        .commit();
            }

            runOnUiThread(() -> {
                enrolling = false;
                learnButton.setEnabled(true);
                listenButton.setEnabled(true);
                refreshUi();
                Toast.makeText(this,
                        ok
                                ? (hebrew ? "✅ השם נלמד בהצלחה" : "✅ Voice name learned")
                                : (hebrew ? "לא קלטתי את הקול. נסה שוב ודבר קרוב לפלאפון." : "I did not capture your voice. Try again closer to the phone."),
                        Toast.LENGTH_LONG).show();
            });
        }, "voice-enrollment").start();
    }

    private short[] captureTrainingWindow() {
        int min = AudioRecord.getMinBufferSize(
                VoiceMatcher.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min, 4096);
        AudioRecord ar = null;
        try {
            ar = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    VoiceMatcher.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) return new short[0];

            short[] all = new short[VoiceMatcher.SAMPLE_RATE * 4];
            short[] block = new short[800];
            int used = 0;
            long endAt = System.currentTimeMillis() + 3200;
            ar.startRecording();

            while (System.currentTimeMillis() < endAt && used < all.length) {
                int maxRead = Math.min(block.length, all.length - used);
                int n = ar.read(block, 0, maxRead);
                if (n <= 0) continue;
                System.arraycopy(block, 0, all, used, n);
                used += n;
            }
            return Arrays.copyOf(all, used);
        } catch (Throwable t) {
            return new short[0];
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Throwable ignored) {}
                try { ar.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private void toggleListening() {
        boolean active = prefs.getBoolean(KEY_LISTENING, false);
        if (active) {
            sendServiceAction(ListeningService.ACTION_STOP_LISTENING);
            prefs.edit().putBoolean(KEY_LISTENING, false).commit();
            refreshUi();
            return;
        }

        saveCurrentName();
        if (!hasProfileForCurrentName()) {
            Toast.makeText(this,
                    hebrew ? "קודם לחץ על ‘למד את השם בקול’ ואמור את השם" : "First teach the spoken name",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasRequiredPermissions()) {
            pendingEnroll = false;
            requestNeededPermissions();
            return;
        }
        startListeningService();
    }

    private void testAlarm() {
        if (!hasRequiredPermissions()) {
            pendingEnroll = false;
            requestNeededPermissions();
            return;
        }
        Intent intent = new Intent(this, ListeningService.class);
        intent.setAction(ListeningService.ACTION_TEST_RING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void startListeningService() {
        Intent intent = new Intent(this, ListeningService.class);
        intent.setAction(ListeningService.ACTION_START_LISTENING);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            prefs.edit().putBoolean(KEY_LISTENING, true).commit();
            refreshUi();
            Toast.makeText(this,
                    hebrew ? "ההאזנה השקטה הופעלה" : "Silent listening started",
                    Toast.LENGTH_SHORT).show();
            nameInput.postDelayed(() -> moveTaskToBack(true), 500);
        } catch (Throwable t) {
            prefs.edit().putBoolean(KEY_LISTENING, false).commit();
            refreshUi();
        }
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, ListeningService.class);
        intent.setAction(action);
        try { startService(intent); } catch (Throwable ignored) {}
        refreshUi();
    }

    private boolean hasRequiredPermissions() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (permissions.isEmpty()) startListeningService();
        else requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this,
                    hebrew ? "חייבים לאשר מיקרופון" : "Microphone permission is required",
                    Toast.LENGTH_LONG).show();
            pendingEnroll = false;
            return;
        }

        if (pendingEnroll) {
            pendingEnroll = false;
            beginEnrollment();
        } else if (hasProfileForCurrentName()) {
            startListeningService();
        }
    }

    private String cleanName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    private void refreshUi() {
        if (prefs == null || listenButton == null || statusText == null || enrolling) return;
        boolean active = prefs.getBoolean(KEY_LISTENING, false);
        boolean learned = hasProfileForCurrentName();
        String name = prefs.getString(KEY_NAME, "");
        if (name == null) name = "";

        listenButton.setText(active
                ? (hebrew ? "כבה האזנה" : "Stop listening")
                : (hebrew ? "הפעל האזנה" : "Start listening"));

        if (active && !name.isEmpty()) {
            statusText.setText(hebrew
                    ? "מקשיב בשקט לשם: " + name
                    : "Silently listening for: " + name);
        } else if (learned) {
            statusText.setText(hebrew
                    ? "✅ השם נלמד — מוכן להפעלה"
                    : "✅ Voice name learned — ready");
        } else {
            statusText.setText(hebrew
                    ? "צריך ללמד את השם בקול פעם אחת"
                    : "Teach the spoken name once");
        }
    }

    private LinearLayout.LayoutParams lpMatch(int height, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private LinearLayout.LayoutParams lpMatchWrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
