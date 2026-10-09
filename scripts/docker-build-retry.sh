#!/usr/bin/env bash
# `docker build` for CI: pulls Docker Hub images through a mirror, and retries.
#
# Anonymous Docker Hub pulls from shared GitHub-hosted runners are rate limited per egress IP
# ("429 Too Many Requests" while resolving the base-image manifest), which fails the step before a
# single Dockerfile instruction runs. Two measures:
#   1. On a GitHub-hosted runner the Docker daemon is pointed at Google's public pull-through cache
#      of Docker Hub (mirror.gcr.io), see docker-hub-mirror.sh.
#   2. A failed build is retried with a growing pause. A genuine Dockerfile or build error fails
#      every attempt and still fails the job, only later.
set -uo pipefail

"$(dirname "$0")/docker-hub-mirror.sh"

attempts=4
for attempt in $(seq 1 "$attempts"); do
  if docker build "$@"; then
    exit 0
  fi
  if [ "$attempt" -lt "$attempts" ]; then
    pause=$((attempt * 30))
    echo "::warning::docker build failed (attempt $attempt/$attempts); retrying in ${pause}s"
    sleep "$pause"
  fi
done
echo "::error::docker build failed after $attempts attempts"
exit 1
