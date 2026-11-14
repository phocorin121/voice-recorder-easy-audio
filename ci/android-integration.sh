#!/usr/bin/env bash
set -euo pipefail
result=0
./gradlew --no-daemon '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.longRecording=${1:-false}" || result=$?
mkdir -p app/build/reports/device
adb exec-out run-as com.phocorin.easyaudio tar -C files -cf - test-screenshots > app/build/reports/device/screenshots.tar || true
adb logcat -d -s AndroidRuntime:E > app/build/reports/device/android-runtime.txt || true
printf 'CI_DEVICE_TEST_EXIT=%s\n' "$result"
exit "$result"