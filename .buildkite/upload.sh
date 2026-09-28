#!/usr/bin/env bash
set -euo pipefail

buildkite-agent pipeline upload .buildkite/pipeline.yml

if [ -n "${BUILDKITE_TAG:-}" ]; then
  echo "--- :label: ${BUILDKITE_TAG} — uploading Android release pipeline"
  buildkite-agent pipeline upload .buildkite/release.yml
else
  echo "--- :label: CI build — release pipeline not uploaded"
fi
