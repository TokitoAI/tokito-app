#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/run-in-mobile-image.sh" .buildkite/in-image/unified-android.sh
