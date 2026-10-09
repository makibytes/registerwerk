#!/usr/bin/env bash
# `docker build` for CI: pulls Docker Hub images through a mirror, and retries.
#
# Anonymous Docker Hub pulls from shared GitHub-hosted runners are rate limited per egress IP
# ("429 Too Many Requests" while resolving the base-image manifest), which fails the step before a
# single Dockerfile instruction runs. Two measures:
#   1. On a GitHub-hosted runner the Docker daemon is pointed at Google's public pull-through cache
#      of Docker Hub (mirror.gcr.io); images it does not hold are still fetched from Docker Hub.
#   2. A failed build is retried with a growing pause. A genuine Dockerfile or build error fails
#      every attempt and still fails the job, only later.
set -uo pipefail

if [ "${GITHUB_ACTIONS:-}" = "true" ] && ! grep -qs "mirror.gcr.io" /etc/docker/daemon.json; then
  echo "Configuring the Docker daemon to pull Docker Hub images through mirror.gcr.io"
  existing='{}'
  [ -s /etc/docker/daemon.json ] && existing=$(cat /etc/docker/daemon.json)
  echo "$existing" | jq '. + {"registry-mirrors": ["https://mirror.gcr.io"]}' | sudo tee /etc/docker/daemon.json >/dev/null \
    && sudo systemctl restart docker \
    || echo "::warning::could not configure the Docker Hub mirror; pulling from Docker Hub directly"
fi

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
