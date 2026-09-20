package com.videomaker.ai;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 1001;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText serverUrl, apiKey, prompt;
    private Spinner duration, preset;
    private CheckBox consent;
    private Button choose, generate, save;
    private ImageView preview;
    private ProgressBar progress;
    private TextView status;
    private VideoView video;
    private Uri imageUri;
    private String jobId, videoUrl;

    private final String kissPrompt =
            "Two consenting adults slowly move closer and share a natural romantic kiss. " +
            "Preserve both people's facial identity and appearance from the reference image. " +
            "Natural blinking, subtle head movement, realistic body motion, stable background, " +
            "cinematic natural lighting, realistic skin, steady camera, no face warping, " +
            "no morphing, no extra limbs.";

    private final String hugPrompt =
            "Two consenting adults gently move closer and share a warm natural hug. " +
            "Preserve both people's facial identity and appearance from the reference image. " +
            "Natural blinking, subtle body motion, stable background, realistic skin, " +
            "steady camera, no face warping, no morphing, no extra limbs.";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(15,23,42));
        getWindow().setNavigationBarColor(Color.rgb(15,23,42));

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Color.rgb(11,16,32));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sc.addView(root);

        TextView title = text("🎬 VideoMaker AI", 30, Color.WHITE);
        root.addView(title);
        root.addView(text("תמונה → וידאו AI של 5 או 10 שניות", 14, Color.rgb(148,163,184)));

        root.addView(label("כתובת שרת AI"));
        serverUrl = edit("https://YOUR-GPU-SERVER");
        root.addView(serverUrl, lp());

        root.addView(label("API key (אופציונלי)"));
        apiKey = edit("");
        root.addView(apiKey, lp());

        choose = new Button(this);
        choose.setText("📷 בחר תמונה");
        choose.setOnClickListener(v -> pickImage());
        root.addView(choose, lpTop());

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(Color.rgb(30,41,59));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(220));
        pp.topMargin = dp(8);
        root.addView(preview, pp);

        root.addView(label("פעולה"));
        preset = new Spinner(this);
        preset.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"נשיקה רומנטית","חיבוק","Prompt חופשי"}));
        root.addView(preset, lp());

        root.addView(label("אורך"));
        duration = new Spinner(this);
        duration.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"10 שניות","5 שניות"}));
        root.addView(duration, lp());

        root.addView(label("Prompt"));
        prompt = edit(kissPrompt);
        prompt.setMinLines(5);
        prompt.setGravity(Gravity.TOP);
        root.addView(prompt, lp());

        preset.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos == 0) prompt.setText(kissPrompt);
                else if (pos == 1) prompt.setText(hugPrompt);
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        consent = new CheckBox(this);
        consent.setText("אני מאשר/ת שכל אדם אמיתי בתמונה הוא בגיר והסכים לשימוש בתמונה ליצירת הווידאו.");
        consent.setTextColor(Color.WHITE);
        root.addView(consent, lpTop());

        generate = new Button(this);
        generate.setText("✨ צור וידאו");
        generate.setOnClickListener(v -> startGeneration());
        root.addView(generate, lpTop());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, lpTop());

        status = text("מוכן", 14, Color.rgb(203,213,225));
        root.addView(status, lpTop());

        video = new VideoView(this);
        MediaController controls = new MediaController(this);
        controls.setAnchorView(video);
        video.setMediaController(controls);
        video.setVisibility(View.GONE);
        root.addView(video, new LinearLayout.LayoutParams(-1, dp(300)));

        save = new Button(this);
        save.setText("⬇ שמור MP4");
        save.setVisibility(View.GONE);
        save.setOnClickListener(v -> downloadVideo());
        root.addView(save, lpTop());

        setContentView(sc);
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, PICK_IMAGE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            imageUri = data.getData();
            if (imageUri != null) {
                preview.setImageURI(imageUri);
                status.setText("התמונה נבחרה ✅");
            }
        }
    }

    private void startGeneration() {
        if (imageUri == null) { status.setText("בחר תמונה קודם."); return; }
        if (!consent.isChecked()) { status.setText("צריך לאשר הסכמה."); return; }

        String base = baseUrl();
        if (base.contains("YOUR-GPU-SERVER") || base.isEmpty()) {
            status.setText("צריך להזין כתובת שרת AI.");
            return;
        }

        generate.setEnabled(false);
        save.setVisibility(View.GONE);
        video.setVisibility(View.GONE);
        progress.setProgress(0);
        status.setText("מעלה תמונה...");

        int seconds = duration.getSelectedItemPosition() == 0 ? 10 : 5;
        String promptText = prompt.getText().toString();

        executor.execute(() -> {
            try {
                JSONObject created = createJob(base, promptText, seconds);
                jobId = created.getString("id");
                poll(base);
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private JSONObject createJob(String base, String promptText, int seconds) throws Exception {
        String boundary = "----VideoMaker" + System.currentTimeMillis();
        HttpURLConnection c = connection(base + "/jobs", "POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream out = new DataOutputStream(c.getOutputStream())) {
            field(out, boundary, "prompt", promptText);
            field(out, boundary, "consent", "true");
            field(out, boundary, "duration", String.valueOf(seconds));

            String mime = getContentResolver().getType(imageUri);
            if (mime == null) mime = "image/jpeg";

            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"image\"; filename=\"input.jpg\"\r\n");
            out.writeBytes("Content-Type: " + mime + "\r\n\r\n");

            try (InputStream in = getContentResolver().openInputStream(imageUri)) {
                if (in == null) throw new IOException("Cannot open image.");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }

            out.writeBytes("\r\n--" + boundary + "--\r\n");
        }
        return new JSONObject(read(c));
    }

    private void field(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private void poll(String base) {
        try {
            while (true) {
                JSONObject j = new JSONObject(read(connection(base + "/jobs/" + jobId, "GET")));
                String s = j.optString("status");
                int p = j.optInt("progress", 0);

                handler.post(() -> {
                    progress.setProgress(p);
                    if ("queued".equals(s)) status.setText("ממתין בתור...");
                    else if ("running".equals(s)) status.setText("יוצר וידאו… " + p + "%");
                });

                if ("completed".equals(s)) {
                    videoUrl = base + "/jobs/" + jobId + "/video";
                    handler.post(this::showVideo);
                    return;
                }
                if ("failed".equals(s)) throw new IOException(j.optString("error","Generation failed"));
                Thread.sleep(1500);
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private void showVideo() {
        progress.setProgress(100);
        status.setText("מוכן ✅");
        generate.setEnabled(true);
        video.setVisibility(View.VISIBLE);
        save.setVisibility(View.VISIBLE);

        Map<String,String> headers = new HashMap<>();
        if (!apiKey.getText().toString().trim().isEmpty())
            headers.put("X-API-Key", apiKey.getText().toString().trim());

        video.setVideoURI(Uri.parse(videoUrl), headers);
        video.setOnPreparedListener(mp -> video.start());
    }

    private void downloadVideo() {
        if (videoUrl == null) return;
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(videoUrl));
        String key = apiKey.getText().toString().trim();
        if (!key.isEmpty()) r.addRequestHeader("X-API-Key", key);
        r.setMimeType("video/mp4");
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "VideoMakerAI-" + jobId + ".mp4");
        ((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
        status.setText("ההורדה התחילה ✅");
    }

    private HttpURLConnection connection(String u, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(u).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(30000);
        c.setReadTimeout(120000);
        String key = apiKey.getText().toString().trim();
        if (!key.isEmpty()) c.setRequestProperty("X-API-Key", key);
        return c;
    }

    private String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (in == null) throw new IOException("HTTP " + code);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) out.write(b,0,n);
        String body = out.toString(StandardCharsets.UTF_8.name());
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + body);
        return body;
    }

    private void fail(Exception e) {
        handler.post(() -> {
            generate.setEnabled(true);
            status.setText("שגיאה: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        });
    }

    private String baseUrl() {
        String s = serverUrl.getText().toString().trim();
        while (s.endsWith("/")) s = s.substring(0, s.length()-1);
        return s;
    }

    private TextView label(String s) {
        TextView t = text(s,15,Color.WHITE);
        t.setPadding(0,dp(14),0,dp(4));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private EditText edit(String s) {
        EditText e = new EditText(this);
        e.setText(s);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.GRAY);
        e.setBackgroundColor(Color.rgb(30,41,59));
        e.setPadding(dp(10),dp(10),dp(10),dp(10));
        return e;
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(-1,-2);
    }

    private LinearLayout.LayoutParams lpTop() {
        LinearLayout.LayoutParams p = lp();
        p.topMargin = dp(12);
        return p;
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
