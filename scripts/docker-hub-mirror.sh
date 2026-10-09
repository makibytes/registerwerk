#!/usr/bin/env bash
# Point the Docker daemon of a GitHub-hosted runner at Google's public pull-through cache of Docker
# Hub (mirror.gcr.io), so image pulls do not count against Docker Hub's anonymous rate limit
# ("429 Too Many Requests" / "toomanyrequests", which hits shared runner IPs). Images the mirror
# does not hold are still fetched from Docker Hub. No-op outside GitHub Actions or when already set.
#
# `registry-mirrors` is applied with a daemon *reload* (SIGHUP), not a restart: a restart recreates
# the docker0 bridge, which Chrome reports as net::ERR_NETWORK_CHANGED in the docs browser checks.
set -uo pipefail

[ "${GITHUB_ACTIONS:-}" = "true" ] || exit 0
grep -qs "mirror.gcr.io" /etc/docker/daemon.json && exit 0

echo "Configuring the Docker daemon to pull Docker Hub images through mirror.gcr.io"
existing='{}'
[ -s /etc/docker/daemon.json ] && existing=$(cat /etc/docker/daemon.json)
if echo "$existing" | jq '. + {"registry-mirrors": ["https://mirror.gcr.io"]}' | sudo tee /etc/docker/daemon.json >/dev/null \
   && sudo systemctl reload docker; then
  docker info --format 'Registry mirrors: {{.RegistryConfig.Mirrors}}' || true
else
  echo "::warning::could not configure the Docker Hub mirror; pulling from Docker Hub directly"
fi
exit 0
