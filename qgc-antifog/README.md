# QGC full-frame antifog test integration

Based on the exact user-supplied ARM64 QGC4.4 APK, pinned in patch_apk.py.
No original APK, signing key, or generated binaries are committed.
The UI adds a full-frame CLASSIC AUTO video filter; CPU analysis time is labelled
as map time and does not claim end-to-end latency. See PROGRESS.md for validation.

Build: decode the supplied APK with apktool2.11.1 using `d -s`; run
`patch_apk.py SOURCE_APK DECODED_DIR PATCH_REPORT`; rebuild with apktool to compile
the manifest; run `package_apk.py SOURCE_APK REBUILT_APK DECODED_DIR UNSIGNED_APK
INTEGRITY_REPORT`; zipalign, then sign with a private test key using Android
build-tools35. Python patching needs pyelftools; RCC creation uses the standard library.
Worker assets are generated automatically. Base APK stays unchanged.

Tests: Node `tests/core.test.cjs` and Java parity fixture; Python test_rcc.py,
test_patch.py, test_package.py. Set QGC_SOURCE_APK for the exact embedded-resource
regression. Actual rendering tests need PyQt5 with Qt5.15.2 and an OpenGL display;
test_qml.py and test_toolbar.py use QQuickView. run_qt.py is the current workspace's
private Xvfb launcher, not a portable installer.

The signed artifact remains a test build until Android ARM64/GStreamer and tablet
performance are checked. No main-branch merge or production deployment was performed.
