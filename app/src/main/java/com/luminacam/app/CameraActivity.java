package com.luminacam.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraActivity extends Activity implements CameraController.Listener {

    private static final int REQ_PERMS = 7;
    private static final String[] MODE_NAMES = {"PHOTO", "PORTRAIT", "NIGHT", "HDR", "PRO", "VIDEO", "TIME-LAPSE"};
    private static final double[] ASPECTS = {4.0 / 3.0, 16.0 / 9.0, 1.0, 21.0 / 9.0};
    private static final String[] ASPECT_LABELS = {"4:3", "16:9", "1:1", "21:9"};
    private static final String[] FLASH_LABELS = {"⚡ Off", "⚡ Auto", "⚡ On", "🔦 Torch"};
    private static final int[] TIMERS = {0, 3, 10};
    private static final int[] TL_INTERVALS = {1, 2, 5, 10, 30};
    private static final int[] QUALITY_VALUES = {95, 88, 80};
    private static final int[] AWB_MODES = {1, 5, 6, 2, 3};
    private static final String[] AWB_LABELS = {"Auto WB", "Daylight", "Cloudy", "Tungsten", "Fluorescent"};

    // views
    private AutoFitTextureView textureView;
    private OverlayView overlay;
    private View touchLayer, flashOverlay;
    private TextView btnFlash, btnTimer, btnAspect, btnSettings, recTimer, countdownText, hintText;
    private TextView zoomLabel, btnFlip, btnPause;
    private TextView proEvLabel, proIsoLabel, proShutterLabel, proFocusLabel;
    private SeekBar zoomSeek, proEv, proIso, proShutter, proFocus;
    private LinearLayout proPanel, lookStrip, modeStrip, proChips, evRow, isoRow, shutterRow, focusRow;
    private HorizontalScrollView lookScroll;
    private ImageView thumb;
    private ShutterButton shutter;

    // state
    private CameraController controller;
    private CameraController.Caps caps;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService proc = Executors.newSingleThreadExecutor();
    private final ExecutorService tlExec = Executors.newSingleThreadExecutor();
    private final ExecutorService analysisExec = Executors.newSingleThreadExecutor();

    private int mode = CameraController.MODE_PHOTO;
    private int look = 0;
    private int aspectIdx = 0;
    private int flashIdx = 0;
    private int timerIdx = 0;
    private float zoom = 1f;
    private int deviceOrientation = 0;
    private boolean processing = false;
    private boolean permissionsAsked = false;

    // pro state
    private boolean proManualExp = false;
    private boolean proManualFocus = false;
    private int proAwbIdx = 0;
    private TextView chipManualExp, chipManualFocus;
    private final List<TextView> wbChips = new ArrayList<>();

    // video state
    private boolean videoRunning = false;
    private boolean videoPaused = false;
    private long videoStartMs = 0;
    private long pausedAccumMs = 0;
    private long pauseStartMs = 0;

    // time-lapse state
    private boolean tlRunning = false;
    private TimelapseEncoder tlEncoder;
    private File tlFile;
    private int tlFrames = 0;
    private long tlStartMs = 0;

    private Uri lastUri;
    private String lastMime = "image/jpeg";
    private MediaActionSound sound;
    private OrientationEventListener orientationListener;
    private SensorManager sensorManager;
    private boolean analyzing = false;

    // ============================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashHandler.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_camera);
        bindViews();

        look = Prefs.getInt(this, Prefs.LOOK, 0);
        aspectIdx = Prefs.getInt(this, Prefs.ASPECT, 0);
        flashIdx = Prefs.getInt(this, Prefs.FLASH, 0);
        timerIdx = Prefs.getInt(this, Prefs.TIMER, 0);
        mode = Prefs.getInt(this, Prefs.MODE, 0);

        sound = new MediaActionSound();
        sound.load(MediaActionSound.SHUTTER_CLICK);
        sound.load(MediaActionSound.START_VIDEO_RECORDING);
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING);

        controller = new CameraController(this, textureView, this);
        controller.initState(mode, aspectIdx == 1 || aspectIdx == 3, flashIdx);

        buildModeStrip();
        buildLookStrip();
        setupListeners();
        updateTopBar();
        setupOrientationAndSensors();

        textureView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                overlay.setPreviewRect(l, t, r, b));

        showCrashReportIfAny();
    }

    private void bindViews() {
        textureView = findViewById(R.id.textureView);
        overlay = findViewById(R.id.overlay);
        touchLayer = findViewById(R.id.touchLayer);
        flashOverlay = findViewById(R.id.flashOverlay);
        btnFlash = findViewById(R.id.btnFlash);
        btnTimer = findViewById(R.id.btnTimer);
        btnAspect = findViewById(R.id.btnAspect);
        btnSettings = findViewById(R.id.btnSettings);
        recTimer = findViewById(R.id.recTimer);
        countdownText = findViewById(R.id.countdownText);
        hintText = findViewById(R.id.hintText);
        zoomLabel = findViewById(R.id.zoomLabel);
        btnFlip = findViewById(R.id.btnFlip);
        btnPause = findViewById(R.id.btnPause);
        proEvLabel = findViewById(R.id.proEvLabel);
        proIsoLabel = findViewById(R.id.proIsoLabel);
        proShutterLabel = findViewById(R.id.proShutterLabel);
        proFocusLabel = findViewById(R.id.proFocusLabel);
        zoomSeek = findViewById(R.id.zoomSeek);
        proEv = findViewById(R.id.proEv);
        proIso = findViewById(R.id.proIso);
        proShutter = findViewById(R.id.proShutter);
        proFocus = findViewById(R.id.proFocus);
        proPanel = findViewById(R.id.proPanel);
        lookStrip = findViewById(R.id.lookStrip);
        modeStrip = findViewById(R.id.modeStrip);
        proChips = findViewById(R.id.proChips);
        evRow = findViewById(R.id.evRow);
        isoRow = findViewById(R.id.isoRow);
        shutterRow = findViewById(R.id.shutterRow);
        focusRow = findViewById(R.id.focusRow);
        lookScroll = findViewById(R.id.lookScroll);
        thumb = findViewById(R.id.thumb);
        shutter = findViewById(R.id.shutter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasCameraPermission()) {
            if (!permissionsAsked) {
                permissionsAsked = true;
                requestNeededPermissions();
            }
            return;
        }
        controller.start();
        if (orientationListener != null && orientationListener.canDetectOrientation()) orientationListener.enable();
        Sensor acc = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sensorManager.registerListener(levelListener, acc, SensorManager.SENSOR_DELAY_UI);
        main.postDelayed(analysisTick, 400);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(analysisTick);
        if (videoRunning) controller.stopVideo();
        if (tlRunning) stopTimelapse();
        countdownCancel();
        if (orientationListener != null) orientationListener.disable();
        if (sensorManager != null) sensorManager.unregisterListener(levelListener);
        controller.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        proc.shutdown();
        tlExec.shutdown();
        analysisExec.shutdown();
        if (sound != null) sound.release();
        super.onDestroy();
    }

    // ============================================================ permissions

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        if (hasCameraPermission()) {
            controller.start();
            if (orientationListener != null && orientationListener.canDetectOrientation()) orientationListener.enable();
        } else {
            Toast.makeText(this, "Camera permission is required. Allow it in Settings > Apps > Lumina Camera.",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ============================================================ orientation + sensors

    private void setupOrientationAndSensors() {
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o != ORIENTATION_UNKNOWN) deviceOrientation = o;
            }
        };
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }

    private final SensorEventListener levelListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            float ax = e.values[0];
            float ay = e.values[1];
            float az = e.values[2];
            boolean valid = Math.abs(az) < 7.5f;
            float roll = (float) Math.toDegrees(Math.atan2(-ax, ay));
            overlay.setRoll(roll, valid);
        }
        @Override public void onAccuracyChanged(Sensor s, int a) {}
    };

    // ============================================================ UI construction

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView makeChip(String text, boolean selected, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        styleChip(t, selected);
        return t;
    }

    private void styleChip(TextView t, boolean selected) {
        t.setTextColor(selected ? Color.BLACK : Color.WHITE);
        t.setBackgroundResource(selected ? R.drawable.chip_selected_bg : R.drawable.chip_bg);
    }

    private void restyle(LinearLayout strip, int selected) {
        for (int i = 0; i < strip.getChildCount(); i++) styleChip((TextView) strip.getChildAt(i), i == selected);
    }

    private void buildModeStrip() {
        modeStrip.removeAllViews();
        for (int i = 0; i < MODE_NAMES.length; i++) {
            final int m = i;
            modeStrip.addView(makeChip(MODE_NAMES[i], i == mode, v -> {
                if (videoRunning || tlRunning || processing) return;
                applyMode(m);
            }));
        }
    }

    private void buildLookStrip() {
        lookStrip.removeAllViews();
        for (int i = 0; i < Looks.NAMES.length; i++) {
            final int l = i;
            lookStrip.addView(makeChip(Looks.NAMES[i], i == look, v -> {
                look = l;
                Prefs.putInt(this, Prefs.LOOK, look);
                restyle(lookStrip, look);
                applyLookPreview();
            }));
        }
    }

    private void applyLookPreview() {
        boolean video = mode == CameraController.MODE_VIDEO || mode == CameraController.MODE_TIMELAPSE;
        if (video) {
            textureView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            return;
        }
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(Looks.previewMatrix(look)));
        textureView.setLayerType(View.LAYER_TYPE_HARDWARE, p);
    }

    private void applyMode(int m) {
        mode = m;
        Prefs.putInt(this, Prefs.MODE, m);
        restyle(modeStrip, m);
        boolean video = m == CameraController.MODE_VIDEO || m == CameraController.MODE_TIMELAPSE;
        shutter.setStyle(video ? ShutterButton.STYLE_VIDEO : ShutterButton.STYLE_PHOTO);
        lookScroll.setVisibility(video ? View.GONE : View.VISIBLE);
        btnAspect.setVisibility(video ? View.GONE : View.VISIBLE);
        proPanel.setVisibility(m == CameraController.MODE_PRO ? View.VISIBLE : View.GONE);
        if (m != CameraController.MODE_PRO) {
            overlay.setHistogram(null);
            overlay.setAnalysis(null);
        }
        controller.setMode(m);
        updateCropGuide();
        applyLookPreview();
        if (m == CameraController.MODE_NIGHT) hint("Night: hold the phone steady while shooting", 2200);
        else if (m == CameraController.MODE_PORTRAIT) hint("Portrait: frame one person's face", 2200);
    }

    private void updateCropGuide() {
        boolean video = mode == CameraController.MODE_VIDEO || mode == CameraController.MODE_TIMELAPSE;
        overlay.setCropTarget(video ? 0f : (float) (1.0 / ASPECTS[aspectIdx]));
    }

    private void updateTopBar() {
        btnFlash.setText(FLASH_LABELS[flashIdx]);
        btnTimer.setText(TIMERS[timerIdx] == 0 ? "⏱ Off" : "⏱ " + TIMERS[timerIdx] + "s");
        btnAspect.setText(ASPECT_LABELS[aspectIdx]);
        overlay.setGridType(Prefs.getInt(this, Prefs.GRID, 1));
    }

    private void hint(String text, long ms) {
        hintText.setText(text);
        hintText.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideHint);
        if (ms > 0) main.postDelayed(hideHint, ms);
    }

    private final Runnable hideHint = () -> hintText.setVisibility(View.GONE);

    // ============================================================ listeners

    private void setupListeners() {
        shutter.setOnClickListener(v -> onShutter());

        btnFlash.setOnClickListener(v -> {
            flashIdx = (flashIdx + 1) % FLASH_LABELS.length;
            Prefs.putInt(this, Prefs.FLASH, flashIdx);
            updateTopBar();
            controller.setFlashMode(flashIdx);
        });
        btnTimer.setOnClickListener(v -> {
            timerIdx = (timerIdx + 1) % TIMERS.length;
            Prefs.putInt(this, Prefs.TIMER, timerIdx);
            updateTopBar();
        });
        btnAspect.setOnClickListener(v -> {
            aspectIdx = (aspectIdx + 1) % ASPECTS.length;
            Prefs.putInt(this, Prefs.ASPECT, aspectIdx);
            updateTopBar();
            controller.setPhotoWide(aspectIdx == 1 || aspectIdx == 3);
            updateCropGuide();
        });
        btnSettings.setOnClickListener(v -> showSettings());
        btnFlip.setOnClickListener(v -> {
            if (videoRunning || tlRunning || processing) return;
            controller.switchFacing();
        });
        btnPause.setOnClickListener(v -> {
            if (!videoRunning) return;
            videoPaused = !videoPaused;
            controller.pauseVideo(videoPaused);
            btnPause.setText(videoPaused ? "▶" : "⏸");
            if (videoPaused) pauseStartMs = System.currentTimeMillis();
            else pausedAccumMs += System.currentTimeMillis() - pauseStartMs;
        });
        thumb.setOnClickListener(v -> openLast());

        zoomSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser || caps == null) return;
                zoom = 1f + (caps.maxZoom - 1f) * p / 100f;
                applyZoom();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        final ScaleGestureDetector scale = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        if (caps == null) return true;
                        zoom = Math.max(1f, Math.min(zoom * d.getScaleFactor(), caps.maxZoom));
                        zoomSeek.setProgress(caps.maxZoom > 1f
                                ? Math.round((zoom - 1f) / (caps.maxZoom - 1f) * 100f) : 0);
                        applyZoom();
                        return true;
                    }
                });
        final GestureDetector tap = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                onPreviewTap(e.getX(), e.getY());
                return true;
            }
        });
        touchLayer.setOnTouchListener((v, e) -> {
            scale.onTouchEvent(e);
            if (!scale.isInProgress()) tap.onTouchEvent(e);
            return true;
        });

        // Pro sliders
        SeekBar.OnSeekBarChangeListener proListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) pushPro();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        };
        proEv.setOnSeekBarChangeListener(proListener);
        proIso.setOnSeekBarChangeListener(proListener);
        proShutter.setOnSeekBarChangeListener(proListener);
        proFocus.setOnSeekBarChangeListener(proListener);
    }

    private void applyZoom() {
        zoomLabel.setText(String.format(Locale.US, "%.1fx", zoom));
        controller.setZoom(zoom);
    }

    private void onPreviewTap(float x, float y) {
        float l = textureView.getLeft();
        float t = textureView.getTop();
        float w = textureView.getWidth();
        float h = textureView.getHeight();
        if (w <= 0 || h <= 0 || x < l || x > l + w || y < t || y > t + h) return;
        float u = (x - l) / w;
        float v = (y - t) / h;
        if (textureView.getScaleX() < 0) u = 1f - u;
        overlay.showFocus(x, y);
        controller.focusAt(u, v);
    }

    // ============================================================ controller callbacks

    @Override
    public void onOpened(CameraController.Caps c) {
        caps = c;
        textureView.setScaleX(c.front ? -1f : 1f);
        btnFlash.setVisibility(c.hasFlash || c.front ? View.VISIBLE : View.GONE);
        zoomSeek.setEnabled(c.maxZoom > 1.05f);
        zoom = 1f;
        zoomSeek.setProgress(0);
        zoomLabel.setText("1.0x");
        configureProPanel();
        controller.setFlashMode(flashIdx);
        controller.setPhotoWide(aspectIdx == 1 || aspectIdx == 3);
        applyMode(mode);
        if (lastUri == null) thumb.setBackgroundResource(R.drawable.thumb_bg);
    }

    @Override
    public void onError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        shutter.setBusy(false);
        processing = false;
        hintText.setVisibility(View.GONE);
    }

    @Override
    public void onVideoStarted() {
        videoRunning = true;
        videoPaused = false;
        pausedAccumMs = 0;
        videoStartMs = System.currentTimeMillis();
        shutter.setRecording(true);
        recTimer.setVisibility(View.VISIBLE);
        btnPause.setText("⏸");
        btnPause.setVisibility(View.VISIBLE);
        btnFlip.setVisibility(View.GONE);
        sound.play(MediaActionSound.START_VIDEO_RECORDING);
        main.post(recTick);
    }

    @Override
    public void onVideoFinished(final File file, String error) {
        videoRunning = false;
        shutter.setRecording(false);
        recTimer.setVisibility(View.INVISIBLE);
        btnPause.setVisibility(View.GONE);
        btnFlip.setVisibility(View.VISIBLE);
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
        main.removeCallbacks(recTick);
        if (file == null) {
            Toast.makeText(this, error == null ? "Recording failed" : error, Toast.LENGTH_LONG).show();
            return;
        }
        hint("Saving video…", 0);
        proc.execute(() -> {
            try {
                String name = "VID_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new java.util.Date()) + ".mp4";
                final Uri uri = MediaSaver.saveVideo(this, file, name);
                main.post(() -> {
                    lastUri = uri;
                    lastMime = "video/mp4";
                    hintText.setVisibility(View.GONE);
                    Toast.makeText(this, "Video saved to Gallery > LuminaCam", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Diag.log("save video", e);
                main.post(() -> {
                    hintText.setVisibility(View.GONE);
                    Toast.makeText(this, "Could not save video: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (!videoRunning) return;
            long now = System.currentTimeMillis();
            long el = (videoPaused ? pauseStartMs : now) - videoStartMs - pausedAccumMs;
            long s = Math.max(0, el / 1000);
            recTimer.setText(String.format(Locale.US, "● %02d:%02d", s / 60, s % 60));
            main.postDelayed(this, 500);
        }
    };

    // ============================================================ shutter

    private void onShutter() {
        if (caps == null) return;
        if (mode == CameraController.MODE_VIDEO) {
            toggleVideo();
        } else if (mode == CameraController.MODE_TIMELAPSE) {
            if (tlRunning) stopTimelapse();
            else startTimelapse();
        } else {
            if (processing) return;
            int secs = TIMERS[timerIdx];
            if (secs > 0) startCountdown(secs);
            else doCapture();
        }
    }

    private int countdownLeft = 0;
    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            if (countdownLeft <= 0) {
                countdownText.setVisibility(View.GONE);
                doCapture();
                return;
            }
            countdownText.setText(String.valueOf(countdownLeft));
            countdownText.setVisibility(View.VISIBLE);
            countdownLeft--;
            main.postDelayed(this, 1000);
        }
    };

    private void startCountdown(int secs) {
        countdownLeft = secs;
        shutter.setBusy(true);
        main.post(countdownTick);
    }

    private void countdownCancel() {
        main.removeCallbacks(countdownTick);
        countdownLeft = 0;
        countdownText.setVisibility(View.GONE);
    }

    private void screenFlash(boolean on) {
        boolean needs = caps != null && caps.front && flashIdx != 0 && flashIdx != 3;
        if (!needs) return;
        flashOverlay.setVisibility(on ? View.VISIBLE : View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    private void doCapture() {
        if (caps == null || controller == null) return;
        processing = true;
        shutter.setBusy(true);

        final PhotoProcessor.Job job = new PhotoProcessor.Job();
        job.mode = mode;
        job.look = look;
        job.aspect = ASPECTS[aspectIdx];
        job.stamp = Prefs.getBool(this, Prefs.STAMP, false);
        job.mirrorSave = Prefs.getBool(this, Prefs.MIRROR_SAVE, false);
        job.front = caps.front;
        final int quality = QUALITY_VALUES[Math.max(0, Math.min(2, Prefs.getInt(this, Prefs.QUALITY, 0)))];

        List<CameraController.ShotSpec> specs = new ArrayList<>();
        if (mode == CameraController.MODE_NIGHT) {
            for (int i = 0; i < 5; i++) specs.add(new CameraController.ShotSpec(1.0, true, i == 0 ? 600 : 0));
            hint("Hold still…", 0);
        } else if (mode == CameraController.MODE_HDR && caps.evMax > caps.evMin) {
            specs.add(new CameraController.ShotSpec(-2.0, false, 350));
            specs.add(new CameraController.ShotSpec(0.0, false, 350));
            specs.add(new CameraController.ShotSpec(2.0, false, 350));
            hint("Hold still…", 0);
        } else {
            specs.add(new CameraController.ShotSpec(0.0, false, 0));
            if (mode == CameraController.MODE_HDR) {
                Toast.makeText(this, "This camera can't bracket exposures - using single-frame HDR tone mapping",
                        Toast.LENGTH_SHORT).show();
            }
        }

        screenFlash(true);
        if (Prefs.getBool(this, Prefs.SOUND, true)) sound.play(MediaActionSound.SHUTTER_CLICK);

        controller.captureSequence(specs, deviceOrientation, new CameraController.FramesCallback() {
            @Override public void onFrames(final List<byte[]> jpegs) {
                screenFlash(false);
                hint("Processing…", 0);
                proc.execute(() -> {
                    try {
                        PhotoProcessor.Output out = PhotoProcessor.process(jpegs, job);
                        if (out == null || out.bitmap == null) throw new Exception("Could not decode the photo");
                        String name = "IMG_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(new java.util.Date()) + ".jpg";
                        final Uri uri = MediaSaver.savePhoto(CameraActivity.this, out.bitmap, quality, name);
                        final Bitmap th = ImagePipeline.thumbnail(out.bitmap, 160);
                        final String note = out.note;
                        out.bitmap.recycle();
                        main.post(() -> {
                            lastUri = uri;
                            lastMime = "image/jpeg";
                            thumb.setImageBitmap(th);
                            finishCapture();
                            if (note != null) Toast.makeText(CameraActivity.this, note, Toast.LENGTH_LONG).show();
                        });
                    } catch (final Throwable t) {
                        Diag.log("process/save", t);
                        main.post(() -> {
                            finishCapture();
                            Toast.makeText(CameraActivity.this, "Could not save photo: " + t.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
            }

            @Override public void onFailed(String message) {
                screenFlash(false);
                finishCapture();
                Toast.makeText(CameraActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void finishCapture() {
        processing = false;
        shutter.setBusy(false);
        hintText.setVisibility(View.GONE);
    }

    private void openLast() {
        if (lastUri == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, lastMime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No gallery app found to open it", Toast.LENGTH_SHORT).show();
        }
    }

    // ============================================================ video

    private CameraController.VideoConfig buildVideoConfig() {
        if (caps.videoOptions.isEmpty()) return null;
        int idx = Prefs.getInt(this, Prefs.VRES, -1);
        if (idx < 0 || idx >= caps.videoOptions.size()) {
            idx = 0;
            for (int i = 0; i < caps.videoOptions.size(); i++) {
                if ("1080p".equals(caps.videoOptions.get(i).label)) idx = i;
            }
        }
        CameraController.VideoOption opt = caps.videoOptions.get(idx);
        int[] fpsList = {24, 30, 60};
        int fi = Math.max(0, Math.min(2, Prefs.getInt(this, Prefs.VFPS, 1)));
        int fps = Math.min(fpsList[fi], opt.maxFps);
        boolean cine = Prefs.getBool(this, Prefs.VCINE, false);
        if (cine && opt.maxFps >= 24) fps = 24;
        boolean high = Prefs.getInt(this, Prefs.VQUALITY, 1) == 1;
        double bpp = high ? 0.14 : 0.09;
        long bitrate = (long) (opt.size.getWidth() * (double) opt.size.getHeight() * fps * bpp);
        bitrate = Math.max(2_000_000L, Math.min(bitrate, 60_000_000L));

        CameraController.VideoConfig cfg = new CameraController.VideoConfig();
        cfg.option = opt;
        cfg.fps = fps;
        cfg.bitrate = (int) bitrate;
        cfg.audio = Prefs.getBool(this, Prefs.VAUDIO, true)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        cfg.stabilize = Prefs.getBool(this, Prefs.VSTAB, true);
        cfg.cinematicCurve = cine;
        return cfg;
    }

    private void toggleVideo() {
        if (!controller.isRecording() && !videoRunning) {
            CameraController.VideoConfig cfg = buildVideoConfig();
            if (cfg == null) {
                Toast.makeText(this, "This camera reports no usable video sizes", Toast.LENGTH_LONG).show();
                return;
            }
            controller.startVideo(cfg, deviceOrientation);
        } else {
            controller.stopVideo();
        }
    }

    // ============================================================ time-lapse

    private void startTimelapse() {
        try {
            File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
            if (dir == null) dir = getCacheDir();
            tlFile = new File(dir, "tl_" + System.currentTimeMillis() + ".mp4");
            final TimelapseEncoder enc = new TimelapseEncoder(tlFile, 720, 1280, 30);
            tlEncoder = enc;
            tlExec.execute(() -> {
                try {
                    enc.start();
                } catch (Exception e) {
                    Diag.log("timelapse start", e);
                    main.post(() -> {
                        Toast.makeText(this, "Time-lapse video encoding isn't supported on this phone",
                                Toast.LENGTH_LONG).show();
                        tlRunning = false;
                        shutter.setRecording(false);
                    });
                }
            });
            tlRunning = true;
            tlFrames = 0;
            tlStartMs = System.currentTimeMillis();
            shutter.setRecording(true);
            recTimer.setVisibility(View.VISIBLE);
            btnFlip.setVisibility(View.GONE);
            main.post(tlTick);
            main.post(tlClock);
        } catch (Exception e) {
            Diag.log("startTimelapse", e);
            Toast.makeText(this, "Could not start time-lapse: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private final Runnable tlTick = new Runnable() {
        @Override public void run() {
            if (!tlRunning) return;
            Bitmap b = textureView.getBitmap(720, 1280);
            if (b != null && tlEncoder != null) {
                final Bitmap fb = b;
                final TimelapseEncoder enc = tlEncoder;
                tlExec.execute(() -> {
                    try {
                        enc.addFrame(fb);
                    } catch (Throwable t) {
                        Diag.log("timelapse frame", t);
                    } finally {
                        fb.recycle();
                    }
                });
                tlFrames++;
            }
            int sec = TL_INTERVALS[Math.max(0, Math.min(TL_INTERVALS.length - 1, Prefs.getInt(CameraActivity.this, Prefs.TL_INT, 2)))];
            main.postDelayed(this, sec * 1000L);
        }
    };

    private final Runnable tlClock = new Runnable() {
        @Override public void run() {
            if (!tlRunning) return;
            long s = (System.currentTimeMillis() - tlStartMs) / 1000;
            recTimer.setText(String.format(Locale.US, "● %02d:%02d  %d frames", s / 60, s % 60, tlFrames));
            main.postDelayed(this, 500);
        }
    };

    private void stopTimelapse() {
        if (!tlRunning) return;
        tlRunning = false;
        main.removeCallbacks(tlTick);
        main.removeCallbacks(tlClock);
        shutter.setRecording(false);
        recTimer.setVisibility(View.INVISIBLE);
        btnFlip.setVisibility(View.VISIBLE);
        final TimelapseEncoder enc = tlEncoder;
        final File f = tlFile;
        final int frames = tlFrames;
        tlEncoder = null;
        if (frames < 2) {
            Toast.makeText(this, "Time-lapse needs at least 2 frames", Toast.LENGTH_SHORT).show();
            tlExec.execute(() -> {
                try { enc.finish(); } catch (Throwable ignored) { }
                if (f != null) f.delete();
            });
            return;
        }
        hint("Building time-lapse video…", 0);
        tlExec.execute(() -> {
            try {
                enc.finish();
                String name = "TL_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new java.util.Date()) + ".mp4";
                final Uri uri = MediaSaver.saveVideo(this, f, name);
                main.post(() -> {
                    lastUri = uri;
                    lastMime = "video/mp4";
                    hintText.setVisibility(View.GONE);
                    Toast.makeText(this, "Time-lapse saved (" + frames + " frames)", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                Diag.log("timelapse save", t);
                main.post(() -> {
                    hintText.setVisibility(View.GONE);
                    Toast.makeText(this, "Could not save time-lapse: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ============================================================ pro panel

    private void configureProPanel() {
        boolean ev = caps.evMax > caps.evMin;
        evRow.setVisibility(ev ? View.VISIBLE : View.GONE);
        proEv.setMax(Math.max(1, caps.evMax - caps.evMin));
        proEv.setProgress(-caps.evMin);

        isoRow.setVisibility(caps.manualSensor ? View.VISIBLE : View.GONE);
        shutterRow.setVisibility(caps.manualSensor ? View.VISIBLE : View.GONE);
        proIso.setProgress(30);
        proShutter.setProgress(55);

        boolean mf = caps.minFocusDiopters > 0f;
        focusRow.setVisibility(mf ? View.VISIBLE : View.GONE);
        proFocus.setProgress(0);

        proChips.removeAllViews();
        wbChips.clear();
        proManualExp = false;
        proManualFocus = false;
        proAwbIdx = 0;
        chipManualExp = null;
        chipManualFocus = null;
        if (caps.manualSensor) {
            chipManualExp = makeChip("Manual exposure", false, v -> {
                proManualExp = !proManualExp;
                styleChip(chipManualExp, proManualExp);
                pushPro();
            });
            proChips.addView(chipManualExp);
        }
        if (mf) {
            chipManualFocus = makeChip("Manual focus", false, v -> {
                proManualFocus = !proManualFocus;
                styleChip(chipManualFocus, proManualFocus);
                pushPro();
            });
            proChips.addView(chipManualFocus);
        }
        for (int i = 0; i < AWB_MODES.length; i++) {
            if (!caps.awbSupported[AWB_MODES[i]]) continue;
            final int idx = i;
            TextView c = makeChip(AWB_LABELS[i], i == 0, v -> {
                proAwbIdx = idx;
                for (int k = 0; k < wbChips.size(); k++) {
                    styleChip(wbChips.get(k), (Integer) wbChips.get(k).getTag() == idx);
                }
                pushPro();
            });
            c.setTag(i);
            wbChips.add(c);
            proChips.addView(c);
        }
        pushPro();
    }

    private long shutterFromProgress(int p) {
        long lo = Math.max(caps.expMin, 125_000L);          // 1/8000 s
        long hi = Math.min(caps.expMax, 250_000_000L);      // 1/4 s
        if (hi <= lo) return lo;
        return (long) (lo * Math.pow((double) hi / lo, p / 100.0));
    }

    private int isoFromProgress(int p) {
        int lo = Math.max(caps.isoMin, 50);
        int hi = Math.min(caps.isoMax, 3200);
        if (hi <= lo) return lo;
        return (int) (lo * Math.pow((double) hi / lo, p / 100.0));
    }

    private void pushPro() {
        if (caps == null) return;
        int evIdx = caps.evMin + proEv.getProgress();
        proEvLabel.setText(String.format(Locale.US, "EV %+.1f", evIdx * caps.evStep));
        long exp = caps.manualSensor ? shutterFromProgress(proShutter.getProgress()) : 10_000_000L;
        int iso = caps.manualSensor ? isoFromProgress(proIso.getProgress()) : 100;
        proIsoLabel.setText("ISO " + iso);
        proShutterLabel.setText(exp >= 500_000_000L ? String.format(Locale.US, "%.1fs", exp / 1e9)
                : "1/" + Math.max(1, Math.round(1e9 / exp)));
        float diopters = caps.minFocusDiopters * proFocus.getProgress() / 100f;
        proFocusLabel.setText(diopters < 0.05f ? "Focus ∞" : String.format(Locale.US, "Focus %dcm", Math.round(100f / diopters)));
        float alpha = proManualExp ? 1f : 0.45f;
        isoRow.setAlpha(alpha);
        shutterRow.setAlpha(alpha);
        focusRow.setAlpha(proManualFocus ? 1f : 0.45f);
        int awb = proAwbIdx < AWB_MODES.length ? AWB_MODES[proAwbIdx] : 1;
        controller.setPro(proManualExp, exp, iso, evIdx, proManualFocus, diopters, awb);
    }

    // ============================================================ analyzers

    private final Runnable analysisTick = new Runnable() {
        @Override public void run() {
            try {
                boolean hist = Prefs.getBool(CameraActivity.this, Prefs.HIST, true);
                boolean zebra = Prefs.getBool(CameraActivity.this, Prefs.ZEBRA, false);
                boolean peak = Prefs.getBool(CameraActivity.this, Prefs.PEAK, false);
                if (mode == CameraController.MODE_PRO && (hist || zebra || peak) && !analyzing
                        && textureView.isAvailable() && textureView.getWidth() > 0) {
                    int bw = 240;
                    int bh = Math.max(2, Math.round(bw * textureView.getHeight() / (float) textureView.getWidth()));
                    final Bitmap b = textureView.getBitmap(bw, bh);
                    if (b != null) {
                        analyzing = true;
                        final boolean fz = zebra;
                        final boolean fp = peak;
                        final boolean fh = hist;
                        analysisExec.execute(() -> {
                            try {
                                final Analyzer.Result r = Analyzer.analyze(b, fz, fp);
                                b.recycle();
                                main.post(() -> {
                                    overlay.setHistogram(fh ? r.hist : null);
                                    overlay.setAnalysis(r.overlay);
                                });
                            } catch (Throwable t) {
                                Diag.log("analyzer", t);
                            } finally {
                                analyzing = false;
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                Diag.log("analysisTick", t);
            }
            main.postDelayed(this, 250);
        }
    };

    // ============================================================ keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            int action = Prefs.getInt(this, Prefs.VOLKEY, 0);
            if (action == 0) {
                if (event.getRepeatCount() == 0) onShutter();
                return true;
            } else if (action == 1 && caps != null) {
                float step = (caps.maxZoom - 1f) / 10f;
                zoom = Math.max(1f, Math.min(caps.maxZoom, zoom + (keyCode == KeyEvent.KEYCODE_VOLUME_UP ? step : -step)));
                zoomSeek.setProgress(caps.maxZoom > 1f ? Math.round((zoom - 1f) / (caps.maxZoom - 1f) * 100f) : 0);
                applyZoom();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ============================================================ settings + diagnostics

    private void showSettings() {
        final String[] sQuality = {"High", "Medium", "Standard"};
        final String[] sGrid = {"Off", "Thirds", "Thirds + level"};
        final String[] sVol = {"Shutter", "Zoom", "Nothing"};
        final String[] sFps = {"24", "30", "60"};
        final String[] sVq = {"Standard", "High"};
        final String[] sTl = {"1 s", "2 s", "5 s", "10 s", "30 s"};

        int vres = Prefs.getInt(this, Prefs.VRES, -1);
        String resLabel = "Auto (1080p)";
        if (caps != null && vres >= 0 && vres < caps.videoOptions.size()) resLabel = caps.videoOptions.get(vres).label;

        final String[] items = {
                "Photo quality: " + sQuality[Prefs.getInt(this, Prefs.QUALITY, 0)],
                "Grid: " + sGrid[Prefs.getInt(this, Prefs.GRID, 1)],
                "Shutter sound: " + onOff(Prefs.getBool(this, Prefs.SOUND, true)),
                "Volume keys: " + sVol[Prefs.getInt(this, Prefs.VOLKEY, 0)],
                "Date stamp on photos: " + onOff(Prefs.getBool(this, Prefs.STAMP, false)),
                "Save selfies mirrored: " + onOff(Prefs.getBool(this, Prefs.MIRROR_SAVE, false)),
                "Pro: histogram: " + onOff(Prefs.getBool(this, Prefs.HIST, true)),
                "Pro: zebra (blown highlights): " + onOff(Prefs.getBool(this, Prefs.ZEBRA, false)),
                "Pro: focus peaking: " + onOff(Prefs.getBool(this, Prefs.PEAK, false)),
                "Video resolution: " + resLabel,
                "Video frame rate: " + sFps[Prefs.getInt(this, Prefs.VFPS, 1)],
                "Video quality: " + sVq[Prefs.getInt(this, Prefs.VQUALITY, 1)],
                "Video stabilisation: " + onOff(Prefs.getBool(this, Prefs.VSTAB, true)),
                "Record audio: " + onOff(Prefs.getBool(this, Prefs.VAUDIO, true)),
                "Cinematic video (24 fps + tone curve): " + onOff(Prefs.getBool(this, Prefs.VCINE, false)),
                "Time-lapse interval: " + sTl[Prefs.getInt(this, Prefs.TL_INT, 2)],
                "Camera info & diagnostics"
        };

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: cycle(Prefs.QUALITY, 3, 0); break;
                        case 1: cycle(Prefs.GRID, 3, 1); updateTopBar(); break;
                        case 2: toggle(Prefs.SOUND, true); break;
                        case 3: cycle(Prefs.VOLKEY, 3, 0); break;
                        case 4: toggle(Prefs.STAMP, false); break;
                        case 5: toggle(Prefs.MIRROR_SAVE, false); break;
                        case 6: toggle(Prefs.HIST, true); break;
                        case 7: toggle(Prefs.ZEBRA, false); break;
                        case 8: toggle(Prefs.PEAK, false); break;
                        case 9: {
                            int n = caps == null ? 0 : caps.videoOptions.size();
                            if (n > 0) Prefs.putInt(this, Prefs.VRES, (Prefs.getInt(this, Prefs.VRES, -1) + 1) % n);
                            break;
                        }
                        case 10: cycle(Prefs.VFPS, 3, 1); break;
                        case 11: cycle(Prefs.VQUALITY, 2, 1); break;
                        case 12: toggle(Prefs.VSTAB, true); break;
                        case 13: toggle(Prefs.VAUDIO, true); break;
                        case 14: toggle(Prefs.VCINE, false); break;
                        case 15: cycle(Prefs.TL_INT, 5, 2); break;
                        default: showDiagnostics(); return;
                    }
                    showSettings();
                })
                .setPositiveButton("Close", null)
                .show();
    }

    private static String onOff(boolean b) {
        return b ? "On" : "Off";
    }

    private void toggle(String key, boolean def) {
        Prefs.putBool(this, key, !Prefs.getBool(this, key, def));
    }

    private void cycle(String key, int count, int def) {
        Prefs.putInt(this, key, (Prefs.getInt(this, key, def) + 1) % count);
    }

    private void showDiagnostics() {
        final String text = "Lumina Camera diagnostics\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")  "
                + Build.MANUFACTURER + " " + Build.MODEL + "\n\n"
                + (caps == null ? "Camera not opened yet.\n" : caps.summary)
                + "\n--- recent log ---\n" + Diag.dump();
        showTextDialog("Camera info & diagnostics", text, false);
    }

    private void showTextDialog(String title, final String text, final boolean clearCrashOnClose) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("LuminaCam", text));
                    Toast.makeText(this, "Copied - paste it to me", Toast.LENGTH_SHORT).show();
                    if (clearCrashOnClose) CrashHandler.clear(this);
                })
                .setNegativeButton("Close", (d, w) -> {
                    if (clearCrashOnClose) CrashHandler.clear(this);
                })
                .show();
    }

    private void showCrashReportIfAny() {
        String crash = CrashHandler.pending(this);
        if (crash != null) {
            showTextDialog("The app crashed last time", "Tap Copy and send this to me so I can fix it:\n\n" + crash, true);
        }
    }
}
