#!/usr/bin/env bash
# The Android build, previously .github/workflows/android.yml.
#
# Runs in the Android image (JDK 17 + SDK 35 + NDK 27.1 + cmake 3.22.1) rather
# than the Rust one — see tokito-infra/ci/runner/Dockerfile.android. Versions
# are pinned to exactly what the workflow installed, so this changes where the
# build runs and nothing else.
set -euo pipefail

: "${CI_IMAGE:?pipeline.yml must set CI_IMAGE}"

# Gradle's caches persist across builds in a named volume, the same way the
# cargo registry does for the Rust repos. Without it every build re-downloads
# the whole dependency graph and the Gradle distribution itself.
export CI_GRADLE_CACHE=1

# Gradle writes `.gradle/` and `build/` directories inside the project tree, so
# the build stays as container root rather than dropping to an unprivileged uid.
# Under rootless Docker that root is the host agent user, so the checkout stays
# agent-owned and writable. The privilege drop exists only for pg-embed's
# PostgreSQL, which this build does not use.
export CI_RUN_AS_ROOT=1

exec "$(dirname "$0")/run-in-image.sh" android-verify
