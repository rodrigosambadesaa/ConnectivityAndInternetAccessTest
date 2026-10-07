#!/usr/bin/env bash
set -euo pipefail

chmod +x gradlew

classes=(
  "net.i2p.android.router.util.StrictCaptivePortalProbeTest"
  "net.i2p.android.router.util.ReachabilityEngineMatrixTest"
  "net.i2p.android.router.util.PassiveNetworkAndVpnTest"
  "net.i2p.android.router.util.LocalProtocolProbeTest"
)

for test_class in "${classes[@]}"; do
  echo "===== UNIT TEST CLASS: $test_class ====="
  timeout --signal=TERM --kill-after=15s 240s \
    ./gradlew --no-daemon testDebugUnitTest --tests "$test_class"
done

echo "All deterministic JVM/Robolectric test classes completed successfully."
