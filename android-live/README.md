# Dehaze LIVE — native Android prototype

Native offline Android app with **Camera2 + SurfaceTexture + OpenGL ES 2.0**.
The same live camera frame is drawn in two viewports: **left = original**, **right = processed** with a fast one-pass GPU haze/contrast shader and sky protection. Camera images are not uploaded; the APK requests CAMERA but no INTERNET permission.

**HUD**: FPS; approximate frame age before draw (only when Camera2 reports REALTIME timestamps); time spent submitting OpenGL draw commands (CPU→GPU). Local camera has no network ping, so this is shown as not applicable. These values do not equal measured end-to-end camera-to-screen latency: that requires an external time/LED test. Slider and toggle are provided.

This is a real-time video-filter prototype, **not** full photographic Multi-scale Fusion or a Topotek/QGroundControl integration. Designed for landscape Android tablets, min SDK 26. Uses the **onboard Android camera**, not a network camera. Open the project directory `android-live` in Android Studio and build `app-debug.apk`, or use the repository's *Build Android LIVE APK* GitHub Actions workflow.

### Build
- Android Studio with JDK 17, SDK 35 and AGP 8.5.2
- or Gradle 8.7: `gradle -p android-live assembleDebug`
- GitHub workflow: `.github/workflows/build-android-live.yml`, output artifact `Dehaze-Live-debug`

### Constraints
- Camera2 preview support, hardware OES external texture and OpenGL ES 2.0 are required.
- Performance and orientation must be validated on the actual tablet; GPU queue latency is not included in the CPU→GPU timing.
- Android CAMERA permission required on first launch. No internet needed even on first native app launch.
