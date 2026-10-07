#!/usr/bin/env bash
set -euo pipefail

chmod +x gradlew
./gradlew --no-daemon assembleDebug assembleDebugAndroidTest

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

RUNNER="com.example.connectivityandinternetaccesstest.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.example.connectivityandinternetaccesstest.ConnectivityRuntimeInstrumentedTest"

run_profile() {
  local profile="$1"
  local speed="$2"
  local delay="$3"

  echo "===== NETWORK PROFILE: $profile speed=$speed delay=$delay ====="
  adb emu network speed "$speed"
  adb emu network delay "$delay"
  sleep 3
  adb logcat -c

  local output
  output="$(adb shell am instrument -w -r -e class "$TEST_CLASS" "$RUNNER")"
  printf '%s\n' "$output"
  adb logcat -d -s ConnectivityCI:I '*:S' || true

  if printf '%s\n' "$output" | grep -qE 'FAILURES|INSTRUMENTATION_FAILED|Process crashed'; then
    echo "Instrumentation failed under network profile $profile" >&2
    return 1
  fi
  printf '%s\n' "$output" | grep -q 'OK ('
}

run_profile full full none
run_profile gprs gprs gprs
run_profile edge edge edge
run_profile umts umts umts
run_profile high-latency-jitter 256:512 400:1200

adb emu network speed full
adb emu network delay none
adb shell settings put global private_dns_mode opportunistic
sleep 3
run_profile private-dns-opportunistic full none
adb shell settings delete global private_dns_mode || true

echo "All Android emulator network profiles completed successfully."
