#!/usr/bin/env bash
set -euo pipefail
cd physical-runtime
chmod +x gradlew
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --stacktrace
