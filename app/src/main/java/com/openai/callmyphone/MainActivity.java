package com.openai.callmyphone;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 1001;
    private static final String PREFS = "call_my_phone_prefs";
    private static final String KEY_NAME = "phone_name";
    private static final String KEY_LISTENING = "listening_active";
    private static final String KEY_LANG = "app_language";

    private EditText nameInput;
    private Button listenButton;
    private Button testButton;
    private Button stopAlarmButton;
    private Button languageButton;
    private TextView statusText;
    private SharedPreferences prefs;

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences p = newBase.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String lang = p.getString(KEY_LANG, "");
        if (lang != null && !lang.isEmpty()) {
            Locale locale = Locale.forLanguageTag(lang);
            Locale.setDefault(locale);
            Configuration config = new Configuration(newBase.getResources().getConfiguration());
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            newBase = newBase.createConfigurationContext(config);
        }
        super.attachBaseContext(newBase);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        nameInput.setText(prefs.getString(KEY_NAME, ""));
        languageButton.setOnClickListener(v -> toggleLanguage());
        listenButton.setOnClickListener(v -> toggleListening());
        testButton.setOnClickListener(v -> testAlarm());
        stopAlarmButton.setOnClickListener(v -> sendServiceAction(ListeningService.ACTION_STOP_RING));
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private View buildUi() {
        int pad = dp(24);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(30), pad, pad);
        root.setBackgroundColor(Color.WHITE);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.title);
        title.setTextSize(30);
        title.setTextColor(Color.rgb(25, 25, 25));
        title.setGravity(Gravity.CENTER);
        root.addView(title, lpMatchWrap(0, dp(8)));

        languageButton = button();
        languageButton.setText(R.string.switch_language);
        root.addView(languageButton, lpMatch(dp(48), 0, dp(16)));

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.subtitle);
        subtitle.setTextSize(17);
        subtitle.setTextColor(Color.rgb(80, 80, 80));
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, lpMatchWrap(0, dp(28)));

        TextView label = new TextView(this);
        label.setText(R.string.phone_name_label);
        label.setTextSize(16);
        label.setTextColor(Color.rgb(45, 45, 45));
        label.setGravity(Gravity.START);
        root.addView(label, lpMatchWrap(0, dp(8)));

        nameInput = new EditText(this);
        nameInput.setHint(R.string.phone_name_hint);
        nameInput.setTextSize(22);
        nameInput.setSingleLine(true);
        nameInput.setGravity(Gravity.CENTER);
        nameInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(nameInput, lpMatch(dp(58), 0, dp(20)));

        statusText = new TextView(this);
        statusText.setTextSize(17);
        statusText.setGravity(Gravity.CENTER);
        statusText.setTextColor(Color.rgb(45, 45, 45));
        root.addView(statusText, lpMatchWrap(0, dp(18)));

        listenButton = button();
        root.addView(listenButton, lpMatch(dp(56), 0, dp(12)));

        testButton = button();
        testButton.setText(R.string.test_alarm);
        root.addView(testButton, lpMatch(dp(56), 0, dp(12)));

        stopAlarmButton = button();
        stopAlarmButton.setText(R.string.stop_alarm);
        root.addView(stopAlarmButton, lpMatch(dp(56), 0, dp(26)));

        TextView note = new TextView(this);
        note.setText(R.string.privacy_note);
        note.setTextSize(13);
        note.setTextColor(Color.rgb(110, 110, 110));
        note.setGravity(Gravity.CENTER);
        root.addView(note, lpMatchWrap(0, dp(24)));

        return scroll;
    }

    private Button button() {
        Button b = new Button(this);
        b.setTextSize(17);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout.LayoutParams lpMatch(int height, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private LinearLayout.LayoutParams lpMatchWrap(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private void toggleLanguage() {
        String current = prefs.getString(KEY_LANG, "");
        if (current == null || current.isEmpty()) {
            current = getResources().getConfiguration().getLocales().get(0).getLanguage();
        }
        String next = "he".equalsIgnoreCase(current) ? "en" : "he";
        prefs.edit().putString(KEY_LANG, next).apply();
        recreate();
    }

    private void toggleListening() {
        boolean active = prefs.getBoolean(KEY_LISTENING, false);
        if (active) {
            sendServiceAction(ListeningService.ACTION_STOP_LISTENING);
            prefs.edit().putBoolean(KEY_LISTENING, false).apply();
            refreshUi();
            return;
        }

        String name = cleanName(nameInput.getText().toString());
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.enter_name, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString(KEY_NAME, name).apply();

        if (!hasRequiredPermissions()) {
            requestNeededPermissions();
            return;
        }
        startListeningService();
    }

    private void testAlarm() {
        String name = cleanName(nameInput.getText().toString());
        if (!name.isEmpty()) prefs.edit().putString(KEY_NAME, name).apply();
        if (!hasRequiredPermissions()) {
            requestNeededPermissions();
            return;
        }
        Intent intent = new Intent(this, ListeningService.class);
        intent.setAction(ListeningService.ACTION_TEST_RING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void startListeningService() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            prefs.edit().putBoolean(KEY_LISTENING, false).apply();
            refreshUi();
            Toast.makeText(this, R.string.speech_recognition_unavailable, Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, ListeningService.class);
        intent.setAction(ListeningService.ACTION_START_LISTENING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        prefs.edit().putBoolean(KEY_LISTENING, true).apply();
        refreshUi();
        Toast.makeText(this, R.string.listening_started, Toast.LENGTH_LONG).show();
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, ListeningService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ListeningService.ACTION_STOP_RING.equals(action)) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (permissions.isEmpty()) {
            startListeningService();
        } else {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            String name = cleanName(nameInput.getText().toString());
            if (!name.isEmpty()) {
                prefs.edit().putString(KEY_NAME, name).apply();
                startListeningService();
            }
        } else {
            Toast.makeText(this, R.string.mic_permission_needed, Toast.LENGTH_LONG).show();
        }
    }

    private String cleanName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    private void refreshUi() {
        if (prefs == null || listenButton == null) return;
        boolean active = prefs.getBoolean(KEY_LISTENING, false);
        String name = prefs.getString(KEY_NAME, "");
        listenButton.setText(active ? R.string.stop_listening : R.string.start_listening);
        statusText.setText(active && !name.isEmpty() ? getString(R.string.status_on, name) : getString(R.string.status_off));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
