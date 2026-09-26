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
