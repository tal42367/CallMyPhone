package com.openai.callmyphone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextWatcher;
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
    private TextView titleText;
    private TextView subtitleText;
    private TextView nameLabel;
    private TextView saveInfoText;
    private TextView statusText;
    private TextView noteText;
    private LinearLayout root;
    private SharedPreferences prefs;
    private boolean hebrew;

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
                refreshUi();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        languageButton.setOnClickListener(v -> toggleLanguage());
        listenButton.setOnClickListener(v -> toggleListening());
        testButton.setOnClickListener(v -> testAlarm());
        stopAlarmButton.setOnClickListener(v -> sendServiceAction(ListeningService.ACTION_STOP_RING));

        applyLanguageTexts();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    protected void onPause() {
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
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        root.addView(saveInfoText, lpMatchWrap(0, dp(16)));

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
                ? "תן לפלאפון שם. תקרא בשם שלו והוא יענה בצלצול חזק."
                : "Give your phone a name. Call that name and it will answer with an alarm.");
        nameLabel.setText(hebrew ? "השם של הפלאפון" : "Phone name");
        nameInput.setHint(hebrew ? "לדוגמה: רובי" : "Example: Ruby");
        saveInfoText.setText(hebrew ? "השם נשמר אוטומטית" : "The name is saved automatically");
        testButton.setText(hebrew ? "בדיקת צלצול" : "Test alarm");
        stopAlarmButton.setText(hebrew ? "עצור צלצול" : "Stop alarm");
        noteText.setText(hebrew
                ? "כשההאזנה פעילה, אפשר לצאת מהאפליקציה ולקרוא לפלאפון בשם ששמרת."
                : "When listening is active, you can leave the app and call the phone by its saved name.");
        refreshUi();
    }

    private void toggleLanguage() {
        saveCurrentName();
        hebrew = !hebrew;
        prefs.edit().putString(KEY_LANG, hebrew ? "he" : "en").commit();
        applyLanguageTexts();
        Toast.makeText(this, hebrew ? "עברית הופעלה" : "English enabled", Toast.LENGTH_SHORT).show();
    }

    private void saveCurrentName() {
        if (nameInput == null) return;
        String name = cleanName(nameInput.getText().toString());
        prefs.edit().putString(KEY_NAME, name).commit();
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
        String name = prefs.getString(KEY_NAME, "");
        if (name == null || name.isEmpty()) {
            Toast.makeText(this, hebrew ? "קודם צריך לכתוב שם לפלאפון" : "Enter a phone name first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasRequiredPermissions()) {
            requestNeededPermissions();
            return;
        }
        startListeningService();
    }

    private void testAlarm() {
        saveCurrentName();
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
            prefs.edit().putBoolean(KEY_LISTENING, false).commit();
            refreshUi();
            Toast.makeText(this,
                    hebrew ? "לא נמצא שירות זיהוי דיבור פעיל במכשיר" : "No active speech-recognition service was found",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(this, ListeningService.class);
        intent.setAction(ListeningService.ACTION_START_LISTENING);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            prefs.edit().putBoolean(KEY_LISTENING, true).commit();
            refreshUi();
            Toast.makeText(this,
                    hebrew ? "ההאזנה הופעלה" : "Listening started",
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            prefs.edit().putBoolean(KEY_LISTENING, false).commit();
            refreshUi();
            Toast.makeText(this,
                    hebrew ? "לא הצלחתי להפעיל את ההאזנה" : "Could not start listening",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, ListeningService.class);
        intent.setAction(action);
        try {
            startService(intent);
        } catch (Throwable ignored) {}
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
        if (permissions.isEmpty()) startListeningService();
        else requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            saveCurrentName();
            if (!prefs.getString(KEY_NAME, "").isEmpty()) startListeningService();
        } else {
            Toast.makeText(this,
                    hebrew ? "חייבים לאשר גישה למיקרופון" : "Microphone permission is required",
                    Toast.LENGTH_LONG).show();
        }
    }

    private String cleanName(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    private void refreshUi() {
        if (prefs == null || listenButton == null || statusText == null) return;
        boolean active = prefs.getBoolean(KEY_LISTENING, false);
        String name = prefs.getString(KEY_NAME, "");
        if (name == null) name = "";
        listenButton.setText(active
                ? (hebrew ? "כבה האזנה" : "Stop listening")
                : (hebrew ? "הפעל האזנה" : "Start listening"));
        if (active && !name.isEmpty()) {
            statusText.setText(hebrew ? "מקשיב לשם: " + name : "Listening for: " + name);
        } else {
            statusText.setText(hebrew ? "ההאזנה כבויה" : "Listening is off");
        }
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
