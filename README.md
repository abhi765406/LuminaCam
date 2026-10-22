# Lumina Camera

A pro-style camera app for Android (built and tuned for the Vivo Y91i, works on any
Android 7.0+ phone). No third-party libraries - only Android's own Camera2, MediaRecorder,
MediaCodec and graphics APIs.

## What it does

**Photo modes**
- **Photo** - normal shots with a colour Look baked in.
- **Portrait** - simulated background blur (finds the face with Android's built-in face
  detector, blurs everything outside a head-and-shoulders mask). No depth sensor on this
  phone, so it's a simulation: best on one person against a plain background.
- **Night** - shoots 5 frames, aligns them, and merges them to reduce noise, then lifts
  shadows. Hold still for ~2 seconds.
- **HDR** - shoots under/normal/over-exposed frames and blends the best-exposed parts.
  If the phone can't bracket exposure, it falls back to single-frame tone mapping.
- **Pro** - manual EV, ISO, shutter speed, focus distance, white balance, plus histogram,
  zebra (blown highlights) and focus peaking. **Each control only appears if your phone's
  camera hardware actually supports it.**

**Video modes**
- **Video** - 4K/1440p/1080p/720p (whatever the phone offers), 24/30/60 fps, stabilisation,
  pause/resume, torch, audio on/off, optional "cinematic" profile (24 fps + contrast
  curve, where the hardware allows).
- **Time-lapse** - samples the viewfinder every 1-30 s and encodes an MP4.

**Looks** (applied live in the viewfinder and baked into saved photos):
Natural (balanced, true-to-life), Vivid (punchy, high saturation/sharpness), Cinematic
(teal-orange grade, vignette, grain), Film, Matte, Warm, Cool, Mono.

**Everything else:** pinch zoom, tap to focus/expose, flash (and screen-flash for selfies),
self-timer, aspect ratios 4:3 / 16:9 / 1:1 / 21:9, grid + level, volume-key shutter,
selfie mirroring, date stamp, saves into **Gallery > LuminaCam**.

## Build it

1. Unzip, upload the contents to a new GitHub repo (check `.github/workflows/build.yml`
   made it in - mobile uploads sometimes skip hidden folders).
2. Actions tab -> let it run -> download the `LuminaCam-app` artifact -> install `app-debug.apk`.
3. If the build fails, open the "Build debug APK" step, search the log for `error:` (or
   `FAILURE`) and send me that block.

## If something goes wrong on the phone

- **If the app crashes**, reopen it - it shows the crash report with a **Copy** button.
- **Settings -> Camera info & diagnostics** shows what your camera hardware supports
  (Camera2 level, manual controls, video sizes) plus a recent log, with a **Copy** button.
Paste either one to me and I can fix it precisely.

## Honest limits

- It can't change your phone's sensor or lens. The software features reduce noise and
  improve colour/contrast, but won't match flagship optics.
- No true depth/LiDAR portrait, no panorama, no RAW/DNG, no slow-motion, no 8K.
- Looks are baked into photos, **not** into video (video comes straight from the camera
  hardware's processing, apart from the optional contrast curve).
- Night/HDR use a ~5 MP capture size to stay within a 2 GB phone's memory.
- Written without being able to run it on your phone - expect to report a few issues.
