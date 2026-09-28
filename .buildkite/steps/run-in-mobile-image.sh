#!/usr/bin/env bash
set -euo pipefail

: "${CI_IMAGE:?pipeline.yml must set CI_IMAGE}"
script="${1:?in-container script path required}"

docker_args=(
  --rm
  --user 0:0
  --volume "$PWD:/work"
  --workdir /work
  --volume "tokito-cargo-registry:/usr/local/cargo/registry"
  --volume "tokito-cargo-git:/usr/local/cargo/git"
  --volume "tokito-target-${BUILDKITE_PIPELINE_SLUG:-tokito-app}:/work/target"
  --volume "tokito-gradle-${BUILDKITE_PIPELINE_SLUG:-tokito-app}:/opt/gradle"
  --volume "tokito-npm-${BUILDKITE_PIPELINE_SLUG:-tokito-app}:/root/.npm"
  --env CARGO_TERM_COLOR=always
  --env RUST_BACKTRACE=1
)

for name in ${CI_PASS_ENV:-}; do
  docker_args+=(--env "$name")
done

if [[ "$CI_IMAGE" == ghcr.io/* ]]; then
  if [ ! -x /usr/local/bin/tokito-gh-token ]; then
    echo "private GHCR image requires /usr/local/bin/tokito-gh-token" >&2
    exit 1
  fi
  registry_token="$(/usr/local/bin/tokito-gh-token)"
  printf '%s' "$registry_token" | docker login ghcr.io --username x-access-token --password-stdin >/dev/null
  unset registry_token
  trap 'docker logout ghcr.io >/dev/null 2>&1 || true' EXIT
  docker pull "$CI_IMAGE" >/dev/null
fi

docker run "${docker_args[@]}" "$CI_IMAGE" "$script"
