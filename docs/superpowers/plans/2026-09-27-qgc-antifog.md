# QGC antifog implementation plan

> Implement inline with superpowers:executing-plans. User already requested the
> agreed integration and ready APK; no additional permission handoff is needed.

**Goal:** Full-frame offline CLASSIC antifog in the user's original QGC APK.
**Architecture:** Qt video texture plus asynchronous low-resolution JS analysis,
ported from the existing Java CLASSIC engine; separate Qt component assets.
**Tech Stack:** Qt 5.15 QML/WorkerScript/GLSL, Python APK patching, Android build tools.
**Spec:** ../specs/2026-09-27-qgc-antifog.md

## Global constraints
Use only the supplied QGC APK as application base. No flight/control changes.
OFF by default, one outstanding analysis, fresh-map epoch checks, full-frame view.
Separate application ID; timing describes analysis CPU time, not video-link delay.

## Review focus
Off during pending work; source replacement; app pause/resume; missing video;
shader or image readback failure. All must leave current original video available.

## Tasks
- [x] Write failing core behavior tests, port `ClassicCore.js`, compare map/LUT
  output with the existing Java processor on identical image fixtures.
- [x] Implement `AntiFogOverlay.qml`, `ClassicWorker.js`, `AntiFogButton.qml`;
  exercise actual Qt GL output, OFF, epoch invalidation and failed-frame recovery.
- [x] Implement `patch_apk.py`: bound embedded-resource writes, bypass only
  altered compiled units, preserve original entries; validate patched QML syntax.
- [x] Rebuild/sign isolated APK; verify package/signature and changed-file set;
  save APK and brief installation instructions. Report physical-device limits.
