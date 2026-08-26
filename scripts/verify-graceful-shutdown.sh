#!/usr/bin/env bash
set -uo pipefail

# Proves server.shutdown: graceful (application.yml) actually behaves differently from a hard
# kill — not just that the config key is set. Fires a burst of concurrent requests at the backend,
# issues a real `docker stop` (SIGTERM, the same signal Kubernetes/Compose send on a normal
# recreate or scale-down) mid-burst, and checks how many of those in-flight requests complete
# cleanly versus get reset. Contrast this against scripts/chaos-drill.sh kill-backend, which uses
# `docker kill` (SIGKILL, no drain at all, no signal handler even runs) — this script exercises
# the OTHER half of the lifecycle story: an orderly stop, not a crash.
#
# Requires the full stack already running (`docker compose up -d`) — restarts the backend
# container itself as part of the drill, so run this somewhere that's fine with a brief backend
# restart (not against a shared demo everyone else is actively using).

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

BACKEND_URL="http://127.0.0.1:48080"
CONCURRENCY=25
RESULTS_DIR="$(mktemp -d)"
trap 'rm -rf "$RESULTS_DIR"' EXIT

wait_for_backend_health() {
  local timeout="$1" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if curl -sf -o /dev/null "${BACKEND_URL}/actuator/health"; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

echo "== Graceful shutdown verification =="
echo "-> Confirming baseline health..."
if ! curl -sf -o /dev/null "${BACKEND_URL}/actuator/health"; then
  echo "FAILED: backend is not healthy before the drill even starts — aborting."
  exit 1
fi

echo "-> Firing ${CONCURRENCY} concurrent requests and issuing 'docker stop' immediately after..."
for i in $(seq 1 "$CONCURRENCY"); do
  curl -s -o /dev/null -w '%{http_code}\n' -m 40 "${BACKEND_URL}/actuator/health" > "${RESULTS_DIR}/${i}.status" &
done

# No sleep: the stop is issued while curl is still connecting/sending the burst above, the same
# "shutdown lands mid-traffic" scenario as the hard-kill chaos drill, just via SIGTERM this time.
# -t 35 matches the container's own stop_grace_period (docker-compose.yml) — this is the timeout
# `docker stop` itself waits before escalating to SIGKILL, not a value this script invents.
STOP_START=$(date +%s)
docker stop -t 35 registerwerk-backend-1 >/dev/null
STOP_DURATION=$(( $(date +%s) - STOP_START ))

wait
echo "   container reached 'stopped' after ${STOP_DURATION}s (graceful budget was 35s)"

SUCCESS=0
FAILED_REQ=0
for i in $(seq 1 "$CONCURRENCY"); do
  STATUS=$(cat "${RESULTS_DIR}/${i}.status" 2>/dev/null || echo "000")
  if [ "$STATUS" = "200" ]; then
    SUCCESS=$((SUCCESS + 1))
  else
    FAILED_REQ=$((FAILED_REQ + 1))
  fi
done
echo "   in-flight burst: ${SUCCESS}/${CONCURRENCY} completed with HTTP 200, ${FAILED_REQ} did not (reset/refused)."

echo "-> Restarting the backend to leave the stack in its normal state..."
docker start registerwerk-backend-1 >/dev/null
if wait_for_backend_health 90; then
  echo "   backend healthy again."
else
  echo "   WARNING: backend did not become healthy again within 90s after restart — check manually."
fi

echo
if [ "$STOP_DURATION" -lt 35 ] && [ "$SUCCESS" -ge $(( CONCURRENCY * 8 / 10 )) ]; then
  echo "RESULT: PASSED — container stopped in ${STOP_DURATION}s (under the 35s SIGKILL escalation," \
       "so it exited on its own rather than being forced) and ${SUCCESS}/${CONCURRENCY} in-flight" \
       "requests completed cleanly rather than being reset."
  exit 0
elif [ "$STOP_DURATION" -ge 35 ]; then
  echo "RESULT: FINDINGS_OPEN — the container did not exit on its own within the 35s grace period" \
       "and Docker had to escalate to SIGKILL. Either shutdown is hanging on something (check" \
       "'docker logs' from this run), or the grace period needs to be longer for real traffic."
  exit 1
else
  echo "RESULT: FINDINGS_OPEN — the container exited gracefully within budget, but only" \
       "${SUCCESS}/${CONCURRENCY} in-flight requests completed cleanly. Expected most/all of a" \
       "burst fired immediately before SIGTERM to finish, not be reset."
  exit 1
fi
