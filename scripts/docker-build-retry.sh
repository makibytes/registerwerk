#!/usr/bin/env bash
# `docker build` for CI: pulls Docker Hub images through a mirror, and retries registry hiccups.
#
# Anonymous Docker Hub pulls from shared GitHub-hosted runners are rate limited per egress IP
# ("429 Too Many Requests" while resolving the base-image manifest), which fails the step before a
# single Dockerfile instruction runs. Two measures:
#   1. On a GitHub-hosted runner the Docker daemon is pointed at Google's public pull-through cache
#      of Docker Hub (mirror.gcr.io), see docker-hub-mirror.sh.
#   2. A build that failed with a registry/network error is retried with a growing pause. Any other
#      failure (a Dockerfile, compile or test error) is final on the first attempt: retrying it would
#      only delay the red job by minutes.
#
# DOCKER_BUILD_ATTEMPTS (default 4) and DOCKER_BUILD_PAUSE (seconds, default 30, multiplied by the
# attempt number) exist for scripts/test-docker-build-retry.sh.
set -uo pipefail

"$(dirname "$0")/docker-hub-mirror.sh"

# What a rate limit, an overloaded registry or a dropped connection looks like in BuildKit's output.
transient='toomanyrequests|429 Too Many Requests|TLS handshake timeout|i/o timeout|connection reset|connection refused|unexpected EOF|: EOF$|50[234] (Bad Gateway|Service Unavailable|Gateway Time-?out)|unexpected status.*50[234]|temporary failure in name resolution|failed to do request'

attempts=${DOCKER_BUILD_ATTEMPTS:-4}
pause_step=${DOCKER_BUILD_PAUSE:-30}
log=$(mktemp)
trap 'rm -f "$log"' EXIT

for attempt in $(seq 1 "$attempts"); do
  docker build "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if [ "$status" -eq 0 ]; then
    exit 0
  fi
  if ! grep -qiE "$transient" "$log"; then
    echo "::error::docker build failed (exit $status) and the output shows no registry or network error; not retrying"
    exit "$status"
  fi
  if [ "$attempt" -lt "$attempts" ]; then
    pause=$((attempt * pause_step))
    echo "::warning::docker build hit a registry/network error (attempt $attempt/$attempts); retrying in ${pause}s"
    sleep "$pause"
  fi
done
echo "::error::docker build still failing with registry/network errors after $attempts attempts"
exit 1
