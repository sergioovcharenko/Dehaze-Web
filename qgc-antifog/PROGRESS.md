# QGC antifog work record — 2026-09-27

Branch feat/qgc-antifog, isolated worktree Dehaze-Web-live-fix; base 991ee0d.
User approved full-frame QGC integration, no split, original APK only.
Spec and plan: docs/superpowers/{specs,plans}/2026-09-27-qgc-antifog.md.

Current work: ClassicCore.js port and WorkerScript/QML overlay authored.
Node core behavior tests first failed (missing implementation), now 2/2 pass.
Java oracle parity on textured fog fixture passes: max map/LUT byte delta 1;
native Node JS execution 75 ms (not a tablet benchmark). Original engine unchanged.
RCC tests first failed (missing implementation), now 3/3 pass, including actual Qt
resource registration/read. All 921 entries in original asset RCC round-trip exactly.
Qt rendering test first failed missing component; now actual rendering checks pending.

Implementation constraints: Android original includes native compiled QML caches.
Qt 5.15.2 qqmlmetatype.cpp verifies their header and falls back to source on version
mismatch. Invalidate only the three changed units' version fields; no native machine
code changes. Native QML payloads must remain inside existing compressed allocations.
Embedded root resource table: tree 0x1a9d7c0, names 0x1a9f734, data 0x1aa3aa2, v3.
Root node name must be ignored. 351 resources. FlightDisplayViewVideo capacity2992,
MainRootWindow capacity5653, MainToolBarIndicators capacity628 compressed bytes.
Use external modified toolbar in new RCC folder, tiny original toolbar wrapper.
Other two retain same native resource paths and use compact inserted code.

Environment: tools under ../antifog_review/qgc_tools. PyQt5 binding5.15.14 + actual
Qt5.15.2 works; build-tools35 checksum verified, apktool2.11.1 downloaded official.
APK decoded with -s into ../antifog_review/qgc-build/decoded (original DEX retained).
No Android emulator/ARM64 device available yet. Xvfb installed in private tools
directory; xkbcomp symlink in /usr/bin. Unix sockets unavailable; use authenticated
TCP display. Each exec has a separate network namespace, so run_qt.py starts Xvfb
and Qt test in one process tree with unique display and terminates it afterward.
Old Xvfb exec session67583 on display91 may still exist; do not reuse display91.

Remaining: fix any actual QML/GL test failures; test pending OFF/source restart;
write guarded APK patcher and toolbar adapter; verify all unchanged bytes; rebuild
and sign separate package; review; save APK/TXT. No integrated APK exists yet.


Final checkpoint 2026-09-27:
- Qt5.15.2 rendering 4/4, toolbar 1/1, RCC 3/3, patch/package 6/6,
  Node core2/2; Java oracle max byte delta1. Final suite green.
- Worker namespace RED→bundle GREEN; image capture consumes synchronous
  itemgrabber cache. Explicit vertex shader avoids Qt batch shader false Error.
- Source reset RED→Canvas onPaint prepares map/LUT; current-frame passthrough
  keeps providers active. GPU failures latch and reveal original video.
- Fresh reviewer qgc_final_review: no Critical; two Important fixed:
  grid now outside processed subtree (actual original-resource test RED→GREEN),
  copy ZipInfo during recomposition (different-size manifest test RED→GREEN).
- Source native bytes unchanged except three bounded QML payload allocations and
  three cache version words. All other351 resource entries checked; original921
  RCC entries preserved. Final signed ZIP has114 original entries byte-identical.
- Binary manifest diff only separate package and app/activity labels; resource
  table changes only package-name header. Original DEX and native machine code preserved.
- APK58126102 bytes, SHA25649feaed3162c94a3ae4f93079803aa417eee3bfe355db7b367e96c2ec1a6296b.
  v1/v2/v3 signature verified, zipalign verified, CRC OK.
- Ruling: deliver separate TEST package, not stable — ARM64 Android/GStreamer
  device unavailable; desktop Qt checks cannot guarantee tablet launch/FPS.
- Ruling: preserve original binary resources and recompose four changed ZIP
  entries rather than adopting all apktool recompilations — minimizes unrelated changes.
- Deferred validation: actual source-object replacement, GL context recreation,
  injected readback error and physical device behavior. No concrete remaining defect.
- Test signing key retained privately outside repository; no APK/key in public git.
