# LAB 4 live regression correction

User evidence: 12182.mp4 (10.41 s) and four screenshots. LAB 4's launcher is a
snapshot workbench, captures at >=750 ms and probes five serial algorithms. The
video shows >2 s snapshot age while the tiny source preview moves. VIDEO MAX used
a full-screen OES texture plus asynchronous small DCP maps. No evidence supports
claiming the tablet's full GPU latency was 0.5 ms.

Scope approved by user's request to fix the regression against previous APK UI:
1. Restore the existing MainActivity/SplitRenderer video path as launcher. Compact
   top bar, large video, four comparison layouts and drawer from VIDEO MAX.
2. Immediate ANTI-FOG off/on (including frozen overlay), no CPU map jobs while off,
   invalidate maps on pause/source/orientation changes, reject stale camera opens.
3. Distinguish consumed-frame FPS, camera timestamp age (if comparable), CPU map
   calculation and age. Do not present submission time as end-to-end latency.
4. Keep all five algorithms and AUTO selection in the secondary comparison lab;
   LIVE AUTO adjusts CLASSIC strength, does not run the neural model. No new live
   algorithms in this regression fix. Preserve original apps with separate ID.
5. Regression tests: launcher geometry at tablet and small landscape sizes; moving
   decoded video, same-frame OFF pixels, blocked map worker must not stop video,
   pause/resume, freeze/off, menu screenshot. Inspect emulator screenshots. Build,
   verify artifact and save downloadable APK plus short plain-text instructions.

Device limit: emulator cannot establish Active 10 Pro speed, heat or dehaze quality.

Review pass: no critical findings; corrected stale package assertion, old-frame
validity on source replacement, fallback mode label, and FPS interval reset.
Readback captures the epoch before sampling and checks after it. Added missing
replacement-file regression. Android 35 full suite and visual inspection pending.

Android 35 run 36259085347: 22 existing Android tests passed; first LIVE test
exposed PhoneWindow.getInsetsController() dereferencing a missing decor before
setContentView. Initialize decor and query View's nullable controller instead.
The existing 2-second fixture has 20 identical decoded frames (framemd5); retain
it for previous tests, add a separate moving 4-second 24fps testsrc2 fixture
with a fixed gray veil for the LIVE motion regression. Collect screenshots
on failed test runs as well as successful ones.

Run 36259509538: 50 unit tests; 26/27 Android tests passed. Launch, large
layout, pixel effect, OFF and failed-source invalidation passed. Resume failed
at LiveRegressionTest:95: decoder restarted, but renderer only received the
1-second status pulse and no source frames. Recreate GL/OES consumer on resume
and keep Activity's cameraTexture null while paused; start producer only from
the new texture callback. Same resume regression retained without relaxing it.
