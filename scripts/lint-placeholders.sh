#!/usr/bin/env bash
# Fails when a file still contains template placeholders (changeme / CHANGEME / "EDIT ME").
# Run it against the files you actually DEPLOY (rendered Helm/production values, the
# alertmanager.yml you mount in production) — NOT against monitoring/alertmanager.yml in the
# repo, whose placeholders are intentional for the local observability demo (7B-06).
set -euo pipefail
status=0
for f in "$@"; do
  if grep -nEi 'changeme|EDIT ME' "$f"; then
    echo "placeholder found in $f" >&2
    status=1
  fi
done
exit $status
