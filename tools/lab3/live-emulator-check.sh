#!/usr/bin/env bash
# Keep screenshots even when an assertion fails; propagate the test exit code.
set -u
adb shell wm size 1200x1920
adb shell wm density 240
gradle -p android-lab3 -PlabTestAbi=x86_64 --no-daemon connectedDebugAndroidTest
test_status=$?
mkdir -p live-screenshots
adb pull /sdcard/Pictures/Meti-Live-QA/ live-screenshots/ || true
exit "$test_status"
