#!/usr/bin/env bash
# Tests and their screenshots must be collected before emulator-runner stops the device.
set -uo pipefail
./gradlew :app:connectedGithubDebugAndroidTest --continue
result=$?
mkdir -p model-ui-screenshots
adb root >/dev/null
adb wait-for-device
adb exec-out 'cd /data/local/tmp/rk-r2-model-ui && tar cf - model-runtime-*.png' > model-ui-screenshots/screenshots.tar
if tar -tf model-ui-screenshots/screenshots.tar >/dev/null 2>&1; then
  tar -xf model-ui-screenshots/screenshots.tar -C model-ui-screenshots
  rm model-ui-screenshots/screenshots.tar
else
  echo 'No model runtime UI screenshots were captured.'
  result=1
fi
exit "$result"
