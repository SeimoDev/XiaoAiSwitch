package io.github.xiaoaiswitch;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Local interaction fixture, excluded from release builds. */
public final class ControlFixtureActivity extends Activity {
    private int count;
    private MediaPlayer player;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private long audioWait;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getSharedPreferences("control_fixture", 0).edit().clear().putInt("count", 0).putInt("scroll", 0).apply();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 100, 32, 80);
        TextView title = new TextView(this);
        title.setText("CONTROL_LOCAL_FIXTURE");
        title.setTextSize(24);
        layout.addView(title);
        TextView value = new TextView(this);
        value.setText("Count: 0");
        value.setTextSize(22);
        layout.addView(value);
        Button increment = new Button(this);
        increment.setText("Increment");
        increment.setContentDescription("fixture increment");
        increment.setOnClickListener(view -> {
            count++;
            value.setText("Count: " + count);
            getSharedPreferences("control_fixture", 0).edit().putInt("count", count).apply();
        });
        layout.addView(increment, new LinearLayout.LayoutParams(-1, 160));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Fixture text input");
        input.setContentDescription("fixture input");
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                getSharedPreferences("control_fixture", 0).edit().putString("text", text.toString()).apply();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        layout.addView(input, new LinearLayout.LayoutParams(-1, 140));
        ScrollView scroll = new ScrollView(this);
        scroll.setContentDescription("fixture scroll");
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int index = 0; index < 50; index++) {
            TextView row = new TextView(this);
            row.setText("ROW " + index);
            row.setTextSize(22);
            row.setPadding(20, 36, 20, 36);
            rows.addView(row);
        }
        scroll.addView(rows);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) ->
                getSharedPreferences("control_fixture", 0).edit().putInt("scroll", y).apply());
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        if (getIntent().getBooleanExtra("play_audio", false)) {
            getSharedPreferences("voice_fixture", 0).edit().clear().apply();
            audioWait = System.currentTimeMillis();
            timer.postDelayed(this::awaitAsr, 100);
        }
    }

    private void awaitAsr() {
        if (getSharedPreferences("switch", 0).getLong("asrStarted", 0) >= audioWait) {
            timer.postDelayed(this::playAudio, 350);
            return;
        }
        if (System.currentTimeMillis() - audioWait < 30000) timer.postDelayed(this::awaitAsr, 100);
    }

    private void playAudio() {
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            player.setDataSource(new java.io.File(getFilesDir(), "voice-fixture.wav").getAbsolutePath());
            player.setOnCompletionListener(value -> {
                getSharedPreferences("voice_fixture", 0).edit().putBoolean("completed", true).apply();
                value.release();
                player = null;
            });
            player.prepare();
            player.start();
            getSharedPreferences("voice_fixture", 0).edit().putBoolean("started", true).apply();
        } catch (Exception error) {
            getSharedPreferences("voice_fixture", 0).edit().putString("error", error.getClass().getSimpleName()).apply();
        }
    }

    @Override public void onDestroy() {
        timer.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        super.onDestroy();
    }
}
