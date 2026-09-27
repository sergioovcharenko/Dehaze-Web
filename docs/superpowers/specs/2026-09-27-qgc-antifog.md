# QGC antifog integration

User approved an offline ANTI-FOG toggle immediately after RSSI in the uploaded
QGC 4.4 APK, with full-frame processing and a measured timing value. OFF restores
the original video. Previous APKs must remain installable. Source APK SHA-256:
bf45356100562e266ee99fd6b3bc1212901db83c52868a3ecd29ed9b0a0d42f2.

Use the APK's existing Qt 5.15.2/GStreamer video item. Add an asynchronous
192x108 CLASSIC analysis worker (DCP, guided refinement, CLAHE, temporal maps)
ported from the delivered LAB4 engine and apply maps to the current full-size
video texture. Keep overlays outside the effect. AUTO adjusts CLASSIC strength;
it does not select the five still-image algorithms. One outstanding analysis,
bounded cadence and stale-result rejection avoid a queued stream of old frames.
The timing label explicitly measures map computation, not end-to-end video latency.

Patch only the required embedded QML resources, invalidate only their compiled
QML caches, add the filter assets and change the package identity for side-by-side
installation. Retain all other original native/DEX contents. Preserve the input.
If the original video cannot be captured or the shader fails, show original video
and an error state; do not cover it with black or hold an old image.

Verify Java/JS map parity, actual Qt 5 QML/GL rendering, OFF and stale-worker
behavior, original-resource integrity, package identity and APK signature.
Android ARM64/Active 10 Pro live-stream performance cannot be inferred from a
desktop test. Explicitly report any device validation not performed.
