package com.luminacam.app;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.params.TonemapCurve;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything that talks to the camera hardware through Camera2: preview, focus, zoom, flash,
 * manual controls, still capture (single and multi-shot sequences for HDR / Night), and video.
 * Every camera call runs on one background thread. The phone's real capabilities are read
 * first (see Caps) and anything it can't do is simply reported as unsupported, never faked.
 */
public class CameraController {

    public static final int MODE_PHOTO = 0, MODE_PORTRAIT = 1, MODE_NIGHT = 2, MODE_HDR = 3,
            MODE_PRO = 4, MODE_VIDEO = 5, MODE_TIMELAPSE = 6;
    public static final int FLASH_OFF = 0, FLASH_AUTO = 1, FLASH_ON = 2, FLASH_TORCH = 3;

    private static final int ST_PREVIEW = 0, ST_WAIT_LOCK = 1, ST_WAIT_PRECAP = 2,
            ST_WAIT_NONPRECAP = 3, ST_CAPTURING = 4;

    // ---------- public data types ----------

    public static class VideoOption {
        public Size size;
        public int maxFps;
        public String label;
    }

    public static class Caps {
        public boolean front, hasFlash, afAvailable, manualSensor, videoStab, ois, contrastCurve, aeLockAvailable;
        public float maxZoom = 1f;
        public int evMin, evMax;
        public float evStep = 1f;
        public int isoMin, isoMax;
        public long expMin, expMax;
        public float minFocusDiopters;
        public int hwLevel;
        public int sensorOrientation;
        public int maxAfRegions, maxAeRegions;
        public boolean[] awbSupported = new boolean[9];
        public List<VideoOption> videoOptions = new ArrayList<>();
        public String summary = "";
    }

    public static class ProSettings {
        public boolean manualExposure = false;
        public long exposureNs = 10_000_000L;
        public int iso = 100;
        public int evIndex = 0;
        public boolean manualFocus = false;
        public float focusDiopters = 0f;
        public int awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
    }

    public static class ShotSpec {
        public final double ev;
        public final boolean lockAe;
        public final long settleMs;

        public ShotSpec(double ev, boolean lockAe, long settleMs) {
            this.ev = ev;
            this.lockAe = lockAe;
            this.settleMs = settleMs;
        }
    }

    public static class VideoConfig {
        public VideoOption option;
        public int fps = 30;
        public int bitrate = 12_000_000;
        public boolean audio = true;
        public boolean stabilize = true;
        public boolean cinematicCurve = false;
    }

    public interface Listener {
        void onOpened(Caps caps);
        void onError(String message);
        void onVideoStarted();
        void onVideoFinished(File file, String error);
    }

    public interface FramesCallback {
        void onFrames(List<byte[]> jpegs);
        void onFailed(String message);
    }

    private interface JpegSink {
        void onJpeg(byte[] data);
    }

    // ---------- state ----------

    private final Context ctx;
    private final CameraManager mgr;
    private final AutoFitTextureView tv;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HandlerThread bgThread;
    private Handler bg;

    private boolean front = false;
    private String cameraId;
    private CameraCharacteristics chars;
    private Caps caps = new Caps();
    private Set<CaptureRequest.Key<?>> availableKeys = new HashSet<>();
    private Rect activeArray = new Rect(0, 0, 4000, 3000);

    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewBuilder;
    private CaptureRequest.Builder recordBuilder;
    private ImageReader jpegReader;
    private Surface previewSurface;
    private Size previewSize = new Size(1280, 960);
    private Size stillSize = new Size(1280, 960);

    private int mode = MODE_PHOTO;
    private boolean photoWide = false;
    private int flashMode = FLASH_OFF;
    private float zoom = 1f;
    public final ProSettings pro = new ProSettings();

    private volatile int state = ST_PREVIEW;
    private Runnable afterLock;
    private JpegSink jpegSink;
    private Runnable watchdog;
    private boolean opening = false;

    private MediaRecorder recorder;
    private File videoFile;
    private volatile boolean recording = false;

    public CameraController(Context ctx, AutoFitTextureView tv, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.tv = tv;
        this.listener = listener;
        this.mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    /** Sets the starting mode before the camera opens, so launch doesn't open the preview twice. */
    public void initState(int startMode, boolean wide, int flash) {
        this.mode = startMode;
        this.photoWide = wide;
        this.flashMode = flash;
    }

    // ---------- lifecycle ----------

    public synchronized void start() {
        if (bgThread == null) {
            bgThread = new HandlerThread("LuminaCamera");
            bgThread.start();
            bg = new Handler(bgThread.getLooper());
        }
        if (tv.isAvailable()) {
            bg.post(this::openCamera);
        } else {
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) {
                    Handler h2 = bg;
                    if (h2 != null) h2.post(CameraController.this::openCamera);
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
            });
        }
    }

    public synchronized void stop() {
        if (bg == null) return;
        bg.post(this::closeAll);
        bgThread.quitSafely();
        try {
            bgThread.join(2000);
        } catch (InterruptedException ignored) {
        }
        bgThread = null;
        bg = null;
    }

    private void closeAll() {
        recording = false;
        cleanupRecorder();
        closeSession();
        if (device != null) {
            try {
                device.close();
            } catch (Exception ignored) {
            }
            device = null;
        }
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        opening = false;
    }

    private void closeSession() {
        state = ST_PREVIEW;
        afterLock = null;
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void fail(String msg) {
        Diag.log("ERROR: " + msg);
        main.post(() -> listener.onError(msg));
    }

    private void post(Runnable r) {
        Handler h = bg;
        if (h != null) h.post(r);
    }

    // ---------- opening ----------

    private void openCamera() {
        if (opening || device != null) return;
        try {
            pickCamera();
            setupSizesAndReader();
            opening = true;
            mgr.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice cd) {
                    device = cd;
                    opening = false;
                    main.post(() -> listener.onOpened(caps));
                    createPreviewSession();
                }
                @Override public void onDisconnected(CameraDevice cd) {
                    cd.close();
                    device = null;
                    opening = false;
                    fail("Camera disconnected");
                }
                @Override public void onError(CameraDevice cd, int error) {
                    cd.close();
                    device = null;
                    opening = false;
                    fail("Camera error " + error);
                }
            }, bg);
        } catch (SecurityException e) {
            opening = false;
            fail("Camera permission missing");
        } catch (Exception e) {
            opening = false;
            Diag.log("openCamera", e);
            fail("Could not open camera: " + e.getMessage());
        }
    }

    private void pickCamera() throws CameraAccessException {
        int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        String found = null;
        for (String id : mgr.getCameraIdList()) {
            CameraCharacteristics c = mgr.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == want && c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null) {
                found = id;
                break;
            }
        }
        if (found == null) {
            String[] ids = mgr.getCameraIdList();
            if (ids.length == 0) throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED, "No camera");
            found = ids[0];
        }
        cameraId = found;
        chars = mgr.getCameraCharacteristics(cameraId);
        buildCaps();
    }

    private static boolean has(int[] arr, int v) {
        if (arr == null) return false;
        for (int a : arr) if (a == v) return true;
        return false;
    }

    private void buildCaps() {
        Caps c = new Caps();
        Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
        c.front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        Boolean flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        c.hasFlash = flash != null && flash;
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        c.sensorOrientation = so == null ? 90 : so;
        Float mz = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        c.maxZoom = mz == null ? 1f : Math.min(mz, 8f);
        Rect aa = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (aa != null) activeArray = aa;

        Range<Integer> ev = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (ev != null) {
            c.evMin = ev.getLower();
            c.evMax = ev.getUpper();
        }
        android.util.Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        c.evStep = step == null ? 1f : step.floatValue();
        if (c.evStep <= 0f) c.evStep = 1f;

        int[] caplist = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        c.manualSensor = has(caplist, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        Range<Integer> isoR = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> expR = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (isoR != null) {
            c.isoMin = isoR.getLower();
            c.isoMax = isoR.getUpper();
        }
        if (expR != null) {
            c.expMin = expR.getLower();
            c.expMax = expR.getUpper();
        }
        if (isoR == null || expR == null) c.manualSensor = false;

        Float mfd = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        c.minFocusDiopters = mfd == null ? 0f : mfd;
        Integer hw = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        c.hwLevel = hw == null ? -1 : hw;
        Integer maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        c.maxAfRegions = maxAf == null ? 0 : maxAf;
        c.maxAeRegions = maxAe == null ? 0 : maxAe;
        Boolean aeLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
        c.aeLockAvailable = aeLock != null && aeLock;
        c.videoStab = has(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        c.ois = has(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        c.contrastCurve = has(chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES),
                CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE);
        int[] awb = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (awb != null) for (int m : awb) if (m >= 0 && m < c.awbSupported.length) c.awbSupported[m] = true;
        c.afAvailable = chooseAfMode(false) != CameraMetadata.CONTROL_AF_MODE_OFF;

        availableKeys = new HashSet<>();
        try {
            List<CaptureRequest.Key<?>> keys = chars.getAvailableCaptureRequestKeys();
            if (keys != null) availableKeys.addAll(keys);
        } catch (Throwable ignored) {
        }

        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] rec = map.getOutputSizes(MediaRecorder.class);
            int[][] want = {{3840, 2160}, {2560, 1440}, {1920, 1080}, {1280, 720}};
            String[] label = {"4K", "1440p", "1080p", "720p"};
            if (rec != null) {
                for (int i = 0; i < want.length; i++) {
                    for (Size s : rec) {
                        if (s.getWidth() == want[i][0] && s.getHeight() == want[i][1]) {
                            VideoOption vo = new VideoOption();
                            vo.size = s;
                            vo.label = label[i];
                            long minDur = map.getOutputMinFrameDuration(MediaRecorder.class, s);
                            int maxFps = minDur > 0 ? (int) Math.round(1e9 / minDur) : 30;
                            vo.maxFps = Math.max(24, Math.min(maxFps, 60));
                            c.videoOptions.add(vo);
                            break;
                        }
                    }
                }
            }
        }

        String[] levels = {"LIMITED", "FULL", "LEGACY", "LEVEL_3", "EXTERNAL"};
        StringBuilder sb = new StringBuilder();
        sb.append("Camera id ").append(cameraId).append(c.front ? " (front)" : " (back)").append('\n');
        sb.append("Camera2 hardware level: ")
                .append(c.hwLevel >= 0 && c.hwLevel < levels.length ? levels[c.hwLevel] : "unknown").append('\n');
        sb.append("Sensor orientation: ").append(c.sensorOrientation).append('\n');
        sb.append("Flash: ").append(c.hasFlash).append("   Autofocus: ").append(c.afAvailable).append('\n');
        sb.append("Max digital zoom: ").append(c.maxZoom).append("x\n");
        sb.append("Exposure compensation: ").append(c.evMin).append("..").append(c.evMax)
                .append(" (step ").append(c.evStep).append(")\n");
        sb.append("Manual ISO/shutter: ").append(c.manualSensor);
        if (c.manualSensor) sb.append("  ISO ").append(c.isoMin).append("-").append(c.isoMax);
        sb.append('\n');
        sb.append("Manual focus: ").append(c.minFocusDiopters > 0f).append('\n');
        sb.append("Video stabilisation: ").append(c.videoStab).append("   Optical stabilisation: ").append(c.ois).append('\n');
        sb.append("Tone-curve control: ").append(c.contrastCurve).append('\n');
        sb.append("Video options: ");
        for (VideoOption vo : c.videoOptions) sb.append(vo.label).append("@").append(vo.maxFps).append(" ");
        sb.append('\n');
        c.summary = sb.toString();
        caps = c;
        Diag.log(c.summary.replace('\n', ' '));
    }

    private int chooseAfMode(boolean video) {
        int[] modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes == null) return CameraMetadata.CONTROL_AF_MODE_OFF;
        if (video && has(modes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO))
            return CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
        if (has(modes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
            return CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        if (has(modes, CameraMetadata.CONTROL_AF_MODE_AUTO)) return CameraMetadata.CONTROL_AF_MODE_AUTO;
        return CameraMetadata.CONTROL_AF_MODE_OFF;
    }

    // ---------- sizes ----------

    private static long area(Size s) {
        return (long) s.getWidth() * s.getHeight();
    }

    private static Size chooseSize(Size[] sizes, boolean wide, long maxPixels, int maxLong) {
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        double target = wide ? 16.0 / 9.0 : 4.0 / 3.0;
        Size best = null;
        for (Size s : sizes) {
            int lw = Math.max(s.getWidth(), s.getHeight());
            int sh = Math.min(s.getWidth(), s.getHeight());
            if (Math.abs((double) lw / sh - target) > 0.03) continue;
            if (area(s) > maxPixels || lw > maxLong) continue;
            if (best == null || area(s) > area(best)) best = s;
        }
        if (best == null) {
            double bd = 1e9;
            for (Size s : sizes) {
                int lw = Math.max(s.getWidth(), s.getHeight());
                int sh = Math.min(s.getWidth(), s.getHeight());
                double d = Math.abs((double) lw / sh - target);
                if (area(s) <= maxPixels && (best == null || d < bd || (d == bd && area(s) > area(best)))) {
                    best = s;
                    bd = d;
                }
            }
        }
        if (best == null) {
            best = sizes[0];
            for (Size s : sizes) if (area(s) < area(best)) best = s;
        }
        return best;
    }

    private boolean wantWide() {
        return mode == MODE_VIDEO || mode == MODE_TIMELAPSE || photoWide;
    }

    private void setupSizesAndReader() {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        boolean wide = wantWide();
        boolean video = mode == MODE_VIDEO || mode == MODE_TIMELAPSE;
        long maxStill = video ? 2_200_000L : ((mode == MODE_NIGHT || mode == MODE_HDR) ? 5_200_000L : 15_000_000L);
        stillSize = chooseSize(map.getOutputSizes(ImageFormat.JPEG), wide, maxStill, 6000);
        previewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class), wide, wide ? 2_100_000L : 1_950_000L, 2000);
        if (jpegReader != null) jpegReader.close();
        jpegReader = ImageReader.newInstance(stillSize.getWidth(), stillSize.getHeight(), ImageFormat.JPEG, 3);
        jpegReader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireNextImage();
                if (img == null) return;
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] data = new byte[buf.remaining()];
                buf.get(data);
                JpegSink sink = jpegSink;
                if (sink != null) sink.onJpeg(data);
            } catch (Exception e) {
                Diag.log("Image read failed", e);
            } finally {
                if (img != null) img.close();
            }
        }, bg);
        Diag.log("Preview " + previewSize + "  Still " + stillSize);
        final boolean swap = caps.sensorOrientation == 90 || caps.sensorOrientation == 270;
        final int pw = swap ? previewSize.getHeight() : previewSize.getWidth();
        final int ph = swap ? previewSize.getWidth() : previewSize.getHeight();
        main.post(() -> tv.setAspectRatio(pw, ph));
    }

    // ---------- public setters (all hop onto the camera thread) ----------

    public void setMode(final int newMode) {
        post(() -> {
            boolean wasWide = wantWide();
            boolean wasBig = mode == MODE_NIGHT || mode == MODE_HDR;
            boolean wasVideo = mode == MODE_VIDEO || mode == MODE_TIMELAPSE;
            mode = newMode;
            boolean isBig = mode == MODE_NIGHT || mode == MODE_HDR;
            boolean isVideo = mode == MODE_VIDEO || mode == MODE_TIMELAPSE;
            if (device != null && (wasWide != wantWide() || wasBig != isBig || wasVideo != isVideo)) reconfigure();
            else refreshParams();
        });
    }

    public void setPhotoWide(final boolean wide) {
        post(() -> {
            boolean before = wantWide();
            photoWide = wide;
            if (device != null && before != wantWide()) reconfigure();
        });
    }

    private void reconfigure() {
        closeSession();
        setupSizesAndReader();
        createPreviewSession();
    }

    public void setFlashMode(final int m) {
        post(() -> {
            flashMode = m;
            refreshParams();
        });
    }

    public void setZoom(final float z) {
        post(() -> {
            zoom = Math.max(1f, Math.min(z, caps.maxZoom));
            refreshParams();
        });
    }

    public void setPro(final boolean manualExposure, final long exposureNs, final int iso, final int evIndex,
                       final boolean manualFocus, final float focusDiopters, final int awbMode) {
        post(() -> {
            pro.manualExposure = manualExposure && caps.manualSensor;
            pro.exposureNs = exposureNs;
            pro.iso = iso;
            pro.evIndex = evIndex;
            pro.manualFocus = manualFocus && caps.minFocusDiopters > 0f;
            pro.focusDiopters = focusDiopters;
            pro.awbMode = awbMode;
            refreshParams();
        });
    }

    public void switchFacing() {
        post(() -> {
            front = !front;
            closeAll();
            openCamera();
        });
    }

    public boolean isFront() {
        return front;
    }

    public boolean isRecording() {
        return recording;
    }

    public Caps getCaps() {
        return caps;
    }

    public Size getStillSize() {
        return stillSize;
    }

    // ---------- request parameters ----------

    private boolean keyOk(CaptureRequest.Key<?> k) {
        return availableKeys.isEmpty() || availableKeys.contains(k);
    }

    private Rect cropFor(float z) {
        Rect a = activeArray;
        int cw = (int) (a.width() / z);
        int ch = (int) (a.height() / z);
        int cx = a.centerX();
        int cy = a.centerY();
        return new Rect(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
    }

    private void applyCommon(CaptureRequest.Builder b, boolean forStill, boolean forVideo) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        // focus
        if (pro.manualFocus && caps.minFocusDiopters > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.max(0f, Math.min(pro.focusDiopters, caps.minFocusDiopters)));
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, chooseAfMode(forVideo));
        }

        // exposure + flash
        if (pro.manualExposure && caps.manualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Math.max(caps.expMin, Math.min(pro.exposureNs, caps.expMax)));
            b.set(CaptureRequest.SENSOR_SENSITIVITY, Math.max(caps.isoMin, Math.min(pro.iso, caps.isoMax)));
        } else {
            int ae = CameraMetadata.CONTROL_AE_MODE_ON;
            if (caps.hasFlash && !forVideo) {
                if (flashMode == FLASH_AUTO) ae = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
                else if (flashMode == FLASH_ON) ae = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, ae);
            int idx = Math.max(caps.evMin, Math.min(pro.evIndex, caps.evMax));
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx);
        }
        if (caps.hasFlash && (ae(b) != CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
                && (ae(b) != CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)) {
            boolean torch = flashMode == FLASH_TORCH;
            b.set(CaptureRequest.FLASH_MODE, torch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
        }

        // white balance
        if (pro.awbMode != CameraMetadata.CONTROL_AWB_MODE_AUTO && pro.awbMode >= 0
                && pro.awbMode < caps.awbSupported.length && caps.awbSupported[pro.awbMode]) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, pro.awbMode);
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        }

        if (zoom > 1.001f) b.set(CaptureRequest.SCALER_CROP_REGION, cropFor(zoom));

        if (forStill) {
            if (keyOk(CaptureRequest.NOISE_REDUCTION_MODE)
                    && has(chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                    CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            }
            if (keyOk(CaptureRequest.EDGE_MODE)
                    && has(chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                    CameraMetadata.EDGE_MODE_HIGH_QUALITY)) {
                b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY);
            }
        }
    }

    private int ae(CaptureRequest.Builder b) {
        Integer v = b.get(CaptureRequest.CONTROL_AE_MODE);
        return v == null ? CameraMetadata.CONTROL_AE_MODE_ON : v;
    }

    private void applyVideoParams(CaptureRequest.Builder b, VideoConfig cfg) {
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            Range<Integer> pick = null;
            for (Range<Integer> r : ranges) {
                if (r.getUpper() == cfg.fps && r.getLower() == cfg.fps) {
                    pick = r;
                    break;
                }
            }
            if (pick == null) {
                for (Range<Integer> r : ranges) {
                    if (r.getUpper() == cfg.fps && (pick == null || r.getLower() > pick.getLower())) pick = r;
                }
            }
            if (pick != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, pick);
        }
        if (cfg.stabilize && caps.videoStab) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        }
        if (cfg.stabilize && caps.ois && keyOk(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
        if (cfg.cinematicCurve && caps.contrastCurve) {
            try {
                float[] curve = {0f, 0f, 0.20f, 0.14f, 0.45f, 0.40f, 0.60f, 0.64f, 0.80f, 0.86f, 1f, 1f};
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE);
                b.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(curve, curve, curve));
            } catch (Exception e) {
                Diag.log("Tone curve not applied", e);
            }
        }
    }

    private void refreshParams() {
        if (session == null || device == null) return;
        try {
            CaptureRequest.Builder b = recording ? recordBuilder : previewBuilder;
            if (b == null) return;
            applyCommon(b, false, recording);
            session.setRepeatingRequest(b.build(), previewCb, bg);
        } catch (Exception e) {
            Diag.log("refreshParams", e);
        }
    }

    // ---------- preview session ----------

    private void createPreviewSession() {
        if (device == null || jpegReader == null) return;
        closeSession();
        SurfaceTexture st = tv.getSurfaceTexture();
        if (st == null) {
            Diag.log("No SurfaceTexture yet");
            return;
        }
        st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        final Surface ps = new Surface(st);
        previewSurface = ps;
        try {
            previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(ps);
            applyCommon(previewBuilder, false, false);
            device.createCaptureSession(Arrays.asList(ps, jpegReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (device == null || previewSurface != ps) {
                                try { s.close(); } catch (Exception ignored) { }
                                return;
                            }
                            session = s;
                            try {
                                s.setRepeatingRequest(previewBuilder.build(), previewCb, bg);
                            } catch (Exception e) {
                                Diag.log("setRepeatingRequest", e);
                                fail("Preview failed: " + e.getMessage());
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            if (previewSurface != ps) return;   // an older session that was replaced - ignore
                            fail("Camera session could not be configured");
                        }
                    }, bg);
        } catch (Exception e) {
            Diag.log("createPreviewSession", e);
            fail("Preview failed: " + e.getMessage());
        }
    }

    private final CameraCaptureSession.CaptureCallback previewCb = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureProgressed(CameraCaptureSession s, CaptureRequest r, CaptureResult res) {
            process(res);
        }
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
            process(res);
        }
    };

    // ---------- tap to focus ----------

    public void focusAt(final float u, final float v) {
        post(() -> {
            if (session == null || device == null || previewBuilder == null || recording) return;
            if (caps.maxAfRegions == 0 && caps.maxAeRegions == 0) return;
            Rect crop = zoom > 1.001f ? cropFor(zoom) : activeArray;
            float a;
            float bb;
            switch (caps.sensorOrientation) {
                case 90: a = v; bb = 1f - u; break;
                case 180: a = 1f - u; bb = 1f - v; break;
                case 270: a = 1f - v; bb = u; break;
                default: a = u; bb = v; break;
            }
            int cx = crop.left + (int) (a * crop.width());
            int cy = crop.top + (int) (bb * crop.height());
            int half = (int) (Math.min(crop.width(), crop.height()) * 0.07f);
            Rect r = new Rect(Math.max(activeArray.left, cx - half), Math.max(activeArray.top, cy - half),
                    Math.min(activeArray.right - 1, cx + half), Math.min(activeArray.bottom - 1, cy + half));
            MeteringRectangle[] regions = {new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1)};
            try {
                if (caps.maxAfRegions > 0) previewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, regions);
                if (caps.maxAeRegions > 0) previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
                if (!pro.manualFocus && has(chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                        CameraMetadata.CONTROL_AF_MODE_AUTO)) {
                    previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                    session.capture(previewBuilder.build(), previewCb, bg);
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                }
                session.setRepeatingRequest(previewBuilder.build(), previewCb, bg);
                bg.removeCallbacks(clearFocusRunnable);
                bg.postDelayed(clearFocusRunnable, 5000);
            } catch (Exception e) {
                Diag.log("focusAt", e);
            }
        });
    }

    private final Runnable clearFocusRunnable = () -> {
        if (session == null || previewBuilder == null || recording) return;
        try {
            MeteringRectangle[] none = {new MeteringRectangle(activeArray, 0)};
            if (caps.maxAfRegions > 0) previewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, none);
            if (caps.maxAeRegions > 0) previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, none);
            applyCommon(previewBuilder, false, false);
            session.setRepeatingRequest(previewBuilder.build(), previewCb, bg);
        } catch (Exception ignored) {
        }
    };

    // ---------- still capture ----------

    private int jpegOrientation(int deviceOrientation) {
        if (deviceOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) deviceOrientation = 0;
        deviceOrientation = (deviceOrientation + 45) / 90 * 90;
        if (caps.front) deviceOrientation = -deviceOrientation;
        return (caps.sensorOrientation + deviceOrientation + 360) % 360;
    }

    /** Takes one or more stills. The first shot waits for focus (and flash metering) to settle. */
    public void captureSequence(final List<ShotSpec> specs, final int deviceOrientation, final FramesCallback cb) {
        post(() -> {
            if (session == null || device == null || jpegReader == null) {
                main.post(() -> cb.onFailed("Camera isn't ready yet"));
                return;
            }
            if (state != ST_PREVIEW) {
                main.post(() -> cb.onFailed("Still busy with the last shot"));
                return;
            }
            final List<byte[]> out = new ArrayList<>();
            beginLock(() -> runShot(specs, 0, out, deviceOrientation, cb));
        });
    }

    private void beginLock(Runnable ready) {
        afterLock = ready;
        boolean useAf = caps.afAvailable && !pro.manualFocus;
        if (!useAf) {
            state = ST_CAPTURING;
            runAfterLock();
            return;
        }
        try {
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            state = ST_WAIT_LOCK;
            session.capture(previewBuilder.build(), previewCb, bg);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            armWatchdog();
        } catch (Exception e) {
            Diag.log("beginLock", e);
            state = ST_CAPTURING;
            runAfterLock();
        }
    }

    private void armWatchdog() {
        if (watchdog != null) bg.removeCallbacks(watchdog);
        watchdog = () -> {
            if (state == ST_WAIT_LOCK || state == ST_WAIT_PRECAP || state == ST_WAIT_NONPRECAP) {
                Diag.log("Focus/AE wait timed out - capturing anyway");
                state = ST_CAPTURING;
                runAfterLock();
            }
        };
        bg.postDelayed(watchdog, 2800);
    }

    private void runAfterLock() {
        if (watchdog != null) bg.removeCallbacks(watchdog);
        Runnable r = afterLock;
        afterLock = null;
        if (r != null) r.run();
    }

    private void runPrecapture() {
        try {
            previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            state = ST_WAIT_PRECAP;
            session.capture(previewBuilder.build(), previewCb, bg);
            previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        } catch (Exception e) {
            Diag.log("runPrecapture", e);
            state = ST_CAPTURING;
            runAfterLock();
        }
    }

    private void process(CaptureResult result) {
        int st = state;
        if (st == ST_PREVIEW || st == ST_CAPTURING) return;
        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
        if (st == ST_WAIT_LOCK) {
            Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
            if (af == null) {
                state = ST_CAPTURING;
                runAfterLock();
            } else if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                        || pro.manualExposure) {
                    state = ST_CAPTURING;
                    runAfterLock();
                } else {
                    runPrecapture();
                }
            }
        } else if (st == ST_WAIT_PRECAP) {
            if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                    || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                    || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                state = ST_WAIT_NONPRECAP;
            }
        } else if (st == ST_WAIT_NONPRECAP) {
            if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                state = ST_CAPTURING;
                runAfterLock();
            }
        }
    }

    private void runShot(final List<ShotSpec> specs, final int idx, final List<byte[]> out,
                         final int orient, final FramesCallback cb) {
        if (session == null || device == null) {
            finishSequence(false, out, cb, "Camera closed during capture");
            return;
        }
        final ShotSpec spec = specs.get(idx);
        try {
            applyCommon(previewBuilder, false, false);
            applySpec(previewBuilder, spec);
            session.setRepeatingRequest(previewBuilder.build(), previewCb, bg);
        } catch (Exception e) {
            Diag.log("runShot preview", e);
        }
        final Runnable timeout = () -> {
            jpegSink = null;
            finishSequence(false, out, cb, "The photo timed out");
        };
        bg.postDelayed(() -> {
            if (session == null || device == null) return;
            jpegSink = data -> {
                bg.removeCallbacks(timeout);
                jpegSink = null;
                out.add(data);
                if (idx + 1 < specs.size()) runShot(specs, idx + 1, out, orient, cb);
                else finishSequence(true, out, cb, null);
            };
            try {
                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(jpegReader.getSurface());
                applyCommon(b, true, false);
                applySpec(b, spec);
                b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(orient));
                b.set(CaptureRequest.JPEG_QUALITY, (byte) 97);
                session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r,
                                                          android.hardware.camera2.CaptureFailure f) {
                        bg.removeCallbacks(timeout);
                        jpegSink = null;
                        finishSequence(false, out, cb, "The camera could not take the photo");
                    }
                }, bg);
                bg.postDelayed(timeout, 8000);
            } catch (Exception e) {
                Diag.log("runShot capture", e);
                jpegSink = null;
                finishSequence(false, out, cb, "Capture failed: " + e.getMessage());
            }
        }, spec.settleMs);
    }

    private void applySpec(CaptureRequest.Builder b, ShotSpec spec) {
        if (pro.manualExposure && caps.manualSensor) {
            if (spec.ev != 0) {
                double scaled = pro.exposureNs * Math.pow(2.0, spec.ev);
                long ns = (long) Math.max(caps.expMin, Math.min(scaled, caps.expMax));
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns);
            }
            return;
        }
        int idx = pro.evIndex + (int) Math.round(spec.ev / caps.evStep);
        idx = Math.max(caps.evMin, Math.min(idx, caps.evMax));
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx);
        if (spec.lockAe && caps.aeLockAvailable) b.set(CaptureRequest.CONTROL_AE_LOCK, true);
    }

    private void finishSequence(boolean ok, List<byte[]> out, FramesCallback cb, String error) {
        try {
            if (previewBuilder != null && session != null) {
                previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(previewBuilder.build(), null, bg);
                previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                previewBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                if (Build.VERSION.SDK_INT >= 23) {
                    previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
                    session.capture(previewBuilder.build(), null, bg);
                    previewBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
                }
                applyCommon(previewBuilder, false, false);
                session.setRepeatingRequest(previewBuilder.build(), previewCb, bg);
            }
        } catch (Exception e) {
            Diag.log("finishSequence restore", e);
        }
        state = ST_PREVIEW;
        final List<byte[]> result = new ArrayList<>(out);
        main.post(() -> {
            if (ok && !result.isEmpty()) cb.onFrames(result);
            else cb.onFailed(error == null ? "No image was produced" : error);
        });
    }

    // ---------- video ----------

    public void startVideo(final VideoConfig cfg, final int deviceOrientation) {
        post(() -> {
            if (device == null || recording || cfg == null || cfg.option == null) return;
            try {
                closeSession();
                File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (dir == null) dir = ctx.getCacheDir();
                videoFile = new File(dir, "rec_" + System.currentTimeMillis() + ".mp4");
                setupRecorder(cfg, deviceOrientation);

                SurfaceTexture st = tv.getSurfaceTexture();
                if (st == null) throw new IllegalStateException("No preview surface");
                st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                final Surface ps = new Surface(st);
                previewSurface = ps;
                final Surface rs = recorder.getSurface();
                final CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                b.addTarget(ps);
                b.addTarget(rs);
                recordBuilder = b;
                applyCommon(b, false, true);
                applyVideoParams(b, cfg);
                device.createCaptureSession(Arrays.asList(ps, rs), new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        if (device == null || previewSurface != ps) {
                            try { s.close(); } catch (Exception ignored) { }
                            return;
                        }
                        session = s;
                        try {
                            s.setRepeatingRequest(b.build(), previewCb, bg);
                            recorder.start();
                            recording = true;
                            main.post(listener::onVideoStarted);
                        } catch (Exception e) {
                            Diag.log("video start", e);
                            abortRecording("Could not start recording: " + e.getMessage());
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        if (previewSurface != ps) return;
                        abortRecording("This video size/frame-rate isn't supported by the camera");
                    }
                }, bg);
            } catch (Exception e) {
                Diag.log("startVideo", e);
                abortRecording("Video setup failed: " + e.getMessage());
            }
        });
    }

    private void abortRecording(String msg) {
        recording = false;
        cleanupRecorder();
        if (videoFile != null) {
            //noinspection ResultOfMethodCallIgnored
            videoFile.delete();
            videoFile = null;
        }
        fail(msg);
        createPreviewSession();
    }

    private void setupRecorder(VideoConfig cfg, int deviceOrientation) throws Exception {
        recorder = new MediaRecorder();
        if (cfg.audio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(videoFile.getAbsolutePath());
        recorder.setVideoEncodingBitRate(cfg.bitrate);
        recorder.setVideoFrameRate(cfg.fps);
        recorder.setVideoSize(cfg.option.size.getWidth(), cfg.option.size.getHeight());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        if (cfg.audio) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(44_100);
        }
        recorder.setOrientationHint(jpegOrientation(deviceOrientation));
        recorder.prepare();
    }

    private void cleanupRecorder() {
        if (recorder != null) {
            try {
                recorder.reset();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
    }

    public void stopVideo() {
        post(() -> {
            if (!recording) return;
            recording = false;
            String err = null;
            try {
                if (session != null) {
                    session.stopRepeating();
                    session.abortCaptures();
                }
            } catch (Exception ignored) {
            }
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                err = "The recording was too short to save";
            }
            cleanupRecorder();
            final File f = videoFile;
            videoFile = null;
            if (err != null && f != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            final String fe = err;
            createPreviewSession();
            main.post(() -> listener.onVideoFinished(fe == null ? f : null, fe));
        });
    }

    public void pauseVideo(final boolean pause) {
        post(() -> {
            if (!recording || recorder == null) return;
            try {
                if (pause) recorder.pause();
                else recorder.resume();
            } catch (Exception e) {
                Diag.log("pause/resume", e);
            }
        });
    }
}
