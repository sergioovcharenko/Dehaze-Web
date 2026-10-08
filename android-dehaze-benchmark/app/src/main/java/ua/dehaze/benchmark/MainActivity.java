package ua.dehaze.benchmark;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.opengl.GLSurfaceView;

import java.util.Locale;

public final class MainActivity extends Activity implements BenchmarkRenderer.Callback {
    private static final int PICK_VIDEO = 42;
    private static final String[] NAMES = {
        "ORIGINAL",
        "CAP 25%",
        "CAP 45%",
        "CAP 65%",
        "CAP 85%",
        "CAP 100%"
    };

    private FrameLayout root;
    private FrameLayout videoFrame;
    private GLSurfaceView glView;
    private BenchmarkRenderer renderer;
    private GridLayout labelsGrid;
    private View soloTap;
    private TextView soloBadge;
    private TextView statusView;
    private TextView playButton;
    private TextView timeView;
    private SeekBar seek;
    private Handler ui = new Handler();
    private Runnable progressTask;
    private SurfaceTexture videoTexture;
    private Surface playerSurface;
    private MediaPlayer player;
    private Uri pendingUri;
    private int soloIndex = -1;
    private boolean userSeeking = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        buildUi();
        progressTask = new Runnable() {
            @Override public void run() {
                updateProgress();
                ui.postDelayed(this, 250);
            }
        };
        ui.post(progressTask);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView button(String text, View.OnClickListener click) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(13);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(48, 52, 58));
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Color.rgb(90, 96, 104));
        v.setBackground(bg);
        v.setOnClickListener(click);
        return v;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        videoFrame = new FrameLayout(this);
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(-1, -1);
        vp.topMargin = dp(46);
        vp.bottomMargin = dp(70);
        root.addView(videoFrame, vp);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        renderer = new BenchmarkRenderer(glView, this);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        videoFrame.addView(glView, new FrameLayout.LayoutParams(-1, -1));

        buildLabels();
        buildTopBar();
        buildBottomBar();
    }

    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(4), dp(10), dp(4));
        bar.setBackgroundColor(Color.rgb(35, 38, 43));

        TextView title = new TextView(this);
        title.setText("PURE CAP VIDEO TEST 2×3");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(title);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        statusView = new TextView(this);
        statusView.setText("Відкрий відео • офлайн");
        statusView.setTextColor(Color.rgb(190, 196, 204));
        statusView.setTextSize(11);
        statusView.setSingleLine(true);
        bar.addView(statusView);

        TextView grid = button("2×3", v -> showGrid());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(62), dp(36));
        gp.leftMargin = dp(8);
        bar.addView(grid, gp);

        TextView open = button("ВІДКРИТИ ВІДЕО", v -> pickVideo());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(dp(150), dp(36));
        op.leftMargin = dp(8);
        bar.addView(open, op);

        root.addView(bar, new FrameLayout.LayoutParams(-1, dp(46), Gravity.TOP));
    }

    private void buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackgroundColor(Color.rgb(31, 34, 39));

        TextView back = button("−10 c", v -> seekBy(-10_000));
        bar.addView(back, new LinearLayout.LayoutParams(dp(72), dp(42)));

        playButton = button("▶", v -> togglePlay());
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(62), dp(42));
        pp.leftMargin = dp(6);
        bar.addView(playButton, pp);

        TextView freeze = button("Ⅱ FREEZE", v -> freeze());
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(100), dp(42));
        fp.leftMargin = dp(6);
        bar.addView(freeze, fp);

        TextView fwd = button("+10 c", v -> seekBy(10_000));
        LinearLayout.LayoutParams fwp = new LinearLayout.LayoutParams(dp(72), dp(42));
        fwp.leftMargin = dp(6);
        bar.addView(fwd, fwp);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                MediaPlayer p = player;
                if (p != null) {
                    int d = p.getDuration();
                    if (d > 0) p.seekTo((int)((long)d * s.getProgress() / 1000L));
                }
                userSeeking = false;
            }
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {}
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        sp.leftMargin = dp(12);
        sp.rightMargin = dp(10);
        bar.addView(seek, sp);

        timeView = new TextView(this);
        timeView.setText("00:00 / 00:00");
        timeView.setTextColor(Color.WHITE);
        timeView.setTextSize(12);
        timeView.setGravity(Gravity.CENTER);
        bar.addView(timeView, new LinearLayout.LayoutParams(dp(112), dp(42)));

        root.addView(bar, new FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM));
    }

    private void buildLabels() {
        labelsGrid = new GridLayout(this);
        labelsGrid.setColumnCount(3);
        labelsGrid.setRowCount(2);

        for (int i = 0; i < 6; i++) {
            final int index = i;
            FrameLayout cell = new FrameLayout(this);
            GradientDrawable border = new GradientDrawable();
            border.setColor(Color.TRANSPARENT);
            border.setStroke(dp(1), Color.argb(95, 255, 255, 255));
            cell.setBackground(border);
            cell.setOnClickListener(v -> showSolo(index));

            TextView label = new TextView(this);
            label.setText(NAMES[i]);
            label.setTextColor(Color.WHITE);
            label.setTextSize(11);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            label.setPadding(dp(8), dp(5), dp(8), dp(5));
            GradientDrawable badge = new GradientDrawable();
            badge.setColor(Color.argb(205, 18, 20, 24));
            badge.setCornerRadius(dp(7));
            label.setBackground(badge);
            FrameLayout.LayoutParams lpLabel = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
            lpLabel.leftMargin = dp(7);
            lpLabel.topMargin = dp(7);
            cell.addView(label, lpLabel);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(i / 3, 1f),
                GridLayout.spec(i % 3, 1f)
            );
            lp.width = 0;
            lp.height = 0;
            labelsGrid.addView(cell, lp);
        }

        videoFrame.addView(labelsGrid, new FrameLayout.LayoutParams(-1, -1));

        soloTap = new View(this);
        soloTap.setBackgroundColor(Color.TRANSPARENT);
        soloTap.setVisibility(View.GONE);
        soloTap.setOnClickListener(v -> showGrid());
        videoFrame.addView(soloTap, new FrameLayout.LayoutParams(-1, -1));

        soloBadge = new TextView(this);
        soloBadge.setTextColor(Color.WHITE);
        soloBadge.setTextSize(12);
        soloBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        soloBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(220, 18, 20, 24));
        bg.setCornerRadius(dp(8));
        soloBadge.setBackground(bg);
        soloBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams sb = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        sb.leftMargin = dp(10);
        sb.topMargin = dp(10);
        videoFrame.addView(soloBadge, sb);
    }

    private void showSolo(int index) {
        soloIndex = index;
        renderer.setSolo(index);
        labelsGrid.setVisibility(View.GONE);
        soloTap.setVisibility(View.VISIBLE);
        soloBadge.setText(NAMES[index] + "  •  натисни для 2×3");
        soloBadge.setVisibility(View.VISIBLE);
    }

    private void showGrid() {
        soloIndex = -1;
        renderer.setSolo(-1);
        labelsGrid.setVisibility(View.VISIBLE);
        soloTap.setVisibility(View.GONE);
        soloBadge.setVisibility(View.GONE);
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        startActivityForResult(i, PICK_VIDEO);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_VIDEO && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            pendingUri = uri;
            if (videoTexture != null) loadVideo(uri);
        }
    }

    @Override public void onVideoSurfaceReady(SurfaceTexture texture) {
        runOnUiThread(() -> {
            videoTexture = texture;
            if (pendingUri != null) loadVideo(pendingUri);
        });
    }

    private void loadVideo(Uri uri) {
        stopPlayer();
        try {
            playerSurface = new Surface(videoTexture);
            MediaPlayer p = new MediaPlayer();
            player = p;
            p.setDataSource(this, uri);
            p.setSurface(playerSurface);
            p.setLooping(true);
            p.setOnVideoSizeChangedListener((mp, w, h) -> {
                renderer.setVideoSize(w, h);
                statusView.setText(w + "×" + h + " • підготовка");
            });
            p.setOnPreparedListener(mp -> {
                renderer.setVideoSize(mp.getVideoWidth(), mp.getVideoHeight());
                mp.start();
                playButton.setText("Ⅱ");
                statusView.setText(mp.getVideoWidth() + "×" + mp.getVideoHeight() + " • 2×3 • офлайн");
            });
            p.setOnCompletionListener(mp -> playButton.setText("▶"));
            p.setOnErrorListener((mp, what, extra) -> {
                statusView.setText("Помилка відео " + what + "/" + extra);
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            statusView.setText("Не вдалося відкрити відео: " + e.getMessage());
        }
    }

    private void togglePlay() {
        MediaPlayer p = player;
        if (p == null) return;
        if (p.isPlaying()) {
            p.pause();
            playButton.setText("▶");
        } else {
            p.start();
            playButton.setText("Ⅱ");
        }
    }

    private void freeze() {
        MediaPlayer p = player;
        if (p == null) return;
        if (p.isPlaying()) {
            p.pause();
            playButton.setText("▶");
            statusView.setText("FREEZE • один кадр у всіх 6 вікнах");
        }
    }

    private void seekBy(int delta) {
        MediaPlayer p = player;
        if (p == null) return;
        int d = p.getDuration();
        if (d <= 0) return;
        int next = Math.max(0, Math.min(d, p.getCurrentPosition() + delta));
        p.seekTo(next);
    }

    private void updateProgress() {
        MediaPlayer p = player;
        if (p == null) return;
        try {
            int d = p.getDuration();
            int c = p.getCurrentPosition();
            if (!userSeeking && d > 0) seek.setProgress((int)((long)c * 1000L / d));
            timeView.setText(formatMs(c) + " / " + formatMs(d));
        } catch (IllegalStateException ignored) {}
    }

    private static String formatMs(int ms) {
        int total = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", total / 60, total % 60);
    }

    @Override public void onStats(String text) {
        runOnUiThread(() -> {
            if (player != null && soloIndex < 0) statusView.setText(text + " • 2×3 • офлайн");
            else if (player != null) statusView.setText(text + " • " + NAMES[Math.max(0, soloIndex)]);
        });
    }

    private void stopPlayer() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) {}
            p.reset();
            p.release();
        }
        if (playerSurface != null) {
            playerSurface.release();
            playerSurface = null;
        }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override protected void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
        immersive();
    }

    @Override protected void onPause() {
        if (player != null && player.isPlaying()) {
            player.pause();
            playButton.setText("▶");
        }
        if (glView != null) glView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        stopPlayer();
        if (renderer != null) renderer.release();
        super.onDestroy();
    }
}
