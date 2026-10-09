#!/usr/bin/env bash
# `docker build` with retries, for CI.
#
# Anonymous Docker Hub pulls from shared GitHub-hosted runners are rate limited per egress IP
# ("429 Too Many Requests" while resolving the base-image manifest). That fails the step before a
# single Dockerfile instruction runs and clears within a minute or two, so a failed build is retried
# with a growing pause. A genuine Dockerfile or build error fails every attempt and still fails the
# job, only later.
set -uo pipefail

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
