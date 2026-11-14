#!/usr/bin/env bash
set -euo pipefail
result=0
run_tests() {
  ./gradlew --no-daemon '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' connectedDebugAndroidTest "$@"
}
collect_diagnostics() {
  local target="app/build/reports/device/$1"
  mkdir -p "$target"
  adb pull /sdcard/Pictures/EasyAudioTests "$target/screenshots" || true
  adb logcat -d -s AndroidRuntime:E > "$target/android-runtime.txt" || true
  if [ -d app/build/outputs/androidTest-results ]; then
    cp -R app/build/outputs/androidTest-results "$target/"
  fi
  if [ -d app/build/reports/androidTests ]; then
    cp -R app/build/reports/androidTests "$target/"
  fi
}
# Verify basic recording, codecs, playback and UI before the long endurance test.
run_tests -Pandroid.testInstrumentationRunnerArguments.longRecording=false || result=$?
collect_diagnostics short
if [ "$result" -eq 0 ] && [ "${1:-false}" = true ]; then
  run_tests -Pandroid.testInstrumentationRunnerArguments.longRecording=true '-Pandroid.testInstrumentationRunnerArguments.class=com.phocorin.easyaudio.AudioIntegrationTest#backgroundAndLockedRecordingForTenMinutes' || result=$?
  collect_diagnostics long
fi
printf 'CI_DEVICE_TEST_EXIT=%s\n' "$result"
exit "$result"