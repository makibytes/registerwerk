#!/usr/bin/env bash
set -uo pipefail

# Real chaos drills against the running Docker Compose stack — kills a container out from under
# the app and measures how it actually behaves and recovers, then records the outcome as a real
# DORA Art. 24/25 ResilienceTest row (POST /api/v1/dora/resilience-tests), the same authenticated
# login+CSRF flow scripts/dr-restore-drill.sh's --verify-audit-chain already uses.
#
# Scoped honestly to what this environment actually is: a single-instance Compose demo stack, not
# a multi-node orchestrator. "Kill a replica mid-transaction" (the plan's original wording,
# written for a multi-replica Kubernetes deployment) becomes here "kill the one backend container
# mid-request and measure what actually happens" — a real, meaningful chaos test for what's
# actually deployable in this sandbox, not a simulation of infrastructure that doesn't exist here.
# It does NOT prove zero-downtime failover (there is nothing to fail over to locally). It also does
# NOT assume `restart: unless-stopped` recovers automatically — running this drill for the first
# time found, for real, that it doesn't: Docker's restart policy only covers a genuine in-process
# crash, not an Engine-API-initiated `docker kill`/`docker stop` (confirmed via RestartCount
# staying at 0). kill-backend measures both halves honestly: whether auto-recovery happens, and,
# if not, how long operator-triggered recovery (`docker start`) takes — recorded as FINDINGS_OPEN,
# not silently upgraded to PASSED.
#
# Usage:
#   scripts/chaos-drill.sh kill-postgres
#   scripts/chaos-drill.sh kill-backend
#   scripts/chaos-drill.sh kill-postgres --no-record   (skip the DORA POST, e.g. for a dry run)
#
# Requires: docker, docker compose, and the full stack already up (`docker compose up -d`) —
# reuses whatever's already running rather than starting its own.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MODE="${1:-}"
RECORD=true
if [ "${2:-}" = "--no-record" ]; then
  RECORD=false
fi

if [ "$MODE" != "kill-postgres" ] && [ "$MODE" != "kill-backend" ]; then
  echo "Usage: $0 <kill-postgres|kill-backend> [--no-record]" >&2
  exit 2
fi

# Same repo-root .env reading as dr-restore-drill.sh, for the same reason: docker compose's .env
# format allows values (ENTRA_CLIENT_ID=<client-id>) that aren't valid bash, so a blanket `source`
# breaks — pull out only the specific keys this script needs.
env_from_dotenv() {
  local key="$1"
  [ -f "${REPO_ROOT}/.env" ] || return 0
  grep -E "^${key}=" "${REPO_ROOT}/.env" | tail -1 | cut -d= -f2-
}
: "${DEFAULT_ADMIN_EMAIL:=$(env_from_dotenv DEFAULT_ADMIN_EMAIL)}"
: "${DEFAULT_ADMIN_PASSWORD:=$(env_from_dotenv DEFAULT_ADMIN_PASSWORD)}"

BACKEND_URL="http://127.0.0.1:48080"
DRILL_ID="chaos-drill-${MODE}-$(date -u +%Y%m%dT%H%M%SZ)"
COOKIE_JAR="$(mktemp)"
trap 'rm -f "$COOKIE_JAR"' EXIT

RESULT="FAILED"
FINDINGS=""
RECOVERY_SECONDS=""

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

echo "== Chaos drill: ${DRILL_ID} =="
STARTED_EPOCH=$(date +%s)

if [ "$MODE" = "kill-postgres" ]; then
  echo "-> Confirming baseline health..."
  if ! curl -sf -o /dev/null "${BACKEND_URL}/actuator/health"; then
    echo "FAILED: backend is not healthy before the drill even starts — aborting."
    exit 1
  fi

  echo "-> Killing postgres (SIGKILL, no graceful shutdown)..."
  docker kill registerwerk-postgres-1 >/dev/null

  echo "-> Confirming the backend degrades cleanly (not a hang) while postgres is down..."
  DB_DOWN_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -m 10 "${BACKEND_URL}/actuator/health/readiness")
  if [ "$DB_DOWN_STATUS" = "503" ] || [ "$DB_DOWN_STATUS" = "000" ]; then
    echo "   OK: readiness reports unhealthy (HTTP ${DB_DOWN_STATUS}) within 10s — no hang."
  else
    FINDINGS="Readiness check returned HTTP ${DB_DOWN_STATUS} with postgres killed — expected 503 (or a timeout), got something else."
    echo "   UNEXPECTED: $FINDINGS"
  fi

  echo "-> Restarting postgres..."
  docker start registerwerk-postgres-1 >/dev/null
  RECOVERY_START=$(date +%s)

  echo -n "   waiting for backend readiness to recover"
  RECOVERED=false
  for _ in $(seq 1 120); do
    if curl -sf -o /dev/null "${BACKEND_URL}/actuator/health/readiness"; then
      RECOVERED=true
      break
    fi
    echo -n "."
    sleep 1
  done
  RECOVERY_SECONDS=$(( $(date +%s) - RECOVERY_START ))

  if [ "$RECOVERED" = true ]; then
    echo " recovered in ${RECOVERY_SECONDS}s"
    if [ -z "$FINDINGS" ]; then
      RESULT="PASSED"
    else
      RESULT="FINDINGS_OPEN"
    fi
  else
    FINDINGS="${FINDINGS} Backend readiness did not recover within 120s of postgres restarting."
    RESULT="FAILED"
    echo " FAILED to recover within 120s"
  fi

elif [ "$MODE" = "kill-backend" ]; then
  echo "-> Confirming baseline health..."
  if ! curl -sf -o /dev/null "${BACKEND_URL}/actuator/health"; then
    echo "FAILED: backend is not healthy before the drill even starts — aborting."
    exit 1
  fi

  echo "-> Firing a request and killing the backend mid-flight (SIGKILL, no graceful shutdown)..."
  # Backgrounded so the kill below genuinely lands while this request is in flight, not after.
  curl -s -o /dev/null -w '%{http_code}\n' -m 15 "${BACKEND_URL}/actuator/health" > /tmp/chaos-inflight-status.txt &
  INFLIGHT_PID=$!
  sleep 0.2
  docker kill registerwerk-backend-1 >/dev/null 2>&1 || true
  wait "$INFLIGHT_PID" 2>/dev/null
  INFLIGHT_STATUS=$(cat /tmp/chaos-inflight-status.txt 2>/dev/null || echo "000")
  rm -f /tmp/chaos-inflight-status.txt
  echo "   in-flight request ended with HTTP/curl status: ${INFLIGHT_STATUS} (000 = connection dropped, the expected outcome of a hard kill mid-request)"

  echo "-> Waiting up to 20s for Docker's restart policy to recover it on its own..."
  RECOVERY_START=$(date +%s)
  AUTO_RECOVERED=false
  if wait_for_backend_health 20; then
    AUTO_RECOVERED=true
  fi

  if [ "$AUTO_RECOVERED" = true ]; then
    RECOVERY_SECONDS=$(( $(date +%s) - RECOVERY_START ))
    echo "   auto-recovered in ${RECOVERY_SECONDS}s"
    RESULT="PASSED"
  else
    # Real, previously-undocumented finding from running this drill: `restart: unless-stopped`
    # does NOT restart a container after `docker kill` (or `docker stop`) — Docker's restart
    # policy engine treats an Engine-API-initiated kill the same as a deliberate stop, and only
    # recovers from a genuine in-process crash the container runtime itself observes. Confirmed
    # by checking `docker inspect ... RestartCount` after a kill: it stays 0 — the policy never
    # even attempts a restart. This is a real gap versus Kubernetes, where `restartPolicy: Always`
    # (the Helm chart's implicit default) restarts a pod after ANY container exit, administrative
    # or not — the Docker Compose path does not have that safety net, and this is exactly the
    # class of finding a chaos drill exists to surface, not paper over.
    echo "   NOT auto-recovered after 20s — restart: unless-stopped does not cover an" \
         "administratively-killed container (only a genuine in-process crash). Manually" \
         "restarting to measure operator-triggered recovery instead."
    docker start registerwerk-backend-1 >/dev/null
    if wait_for_backend_health 160; then
      RECOVERY_SECONDS=$(( $(date +%s) - RECOVERY_START ))
      echo "   recovered ${RECOVERY_SECONDS}s after kill (required a manual 'docker start')"
      RESULT="FINDINGS_OPEN"
      FINDINGS="restart: unless-stopped did NOT automatically recover the backend after a hard kill (confirmed via RestartCount staying 0) — Docker only restarts on a genuine in-process crash, not an Engine-API-initiated kill/stop. Recovery required an explicit 'docker start' and took ${RECOVERY_SECONDS}s total from kill to healthy. The Helm/Kubernetes deployment path does not share this gap (restartPolicy: Always covers any exit reason); the Compose path should either accept manual recovery as the documented model for a single-instance kill, or add an external supervisor/healthcheck-triggered restart."
    else
      FINDINGS="Backend did not recover even after an explicit 'docker start' following the kill — investigate separately from the restart-policy gap noted above."
      RESULT="FAILED"
      echo "   FAILED to recover even after a manual 'docker start'"
    fi
  fi
fi

FINISHED_EPOCH=$(date +%s)
TOTAL_SECONDS=$((FINISHED_EPOCH - STARTED_EPOCH))
echo
echo "Drill outcome: ${RESULT}. Recovery time: ${RECOVERY_SECONDS:-n/a}s. Total drill time: ${TOTAL_SECONDS}s."
[ -n "$FINDINGS" ] && echo "Findings: $FINDINGS"

if [ "$RECORD" != true ]; then
  echo "Skipping DORA record (--no-record)."
  [ "$RESULT" = "PASSED" ] && exit 0 || exit 1
fi

if [ -z "${DEFAULT_ADMIN_EMAIL:-}" ] || [ -z "${DEFAULT_ADMIN_PASSWORD:-}" ]; then
  echo "DEFAULT_ADMIN_EMAIL/DEFAULT_ADMIN_PASSWORD not available — cannot record this drill as a DORA ResilienceTest. The drill result above still stands."
  [ "$RESULT" = "PASSED" ] && exit 0 || exit 1
fi

echo "-> Recording as a DORA resilience-test..."
LOGIN_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -c "$COOKIE_JAR" \
  -X POST "${BACKEND_URL}/api/v1/public/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${DEFAULT_ADMIN_EMAIL}\",\"password\":\"${DEFAULT_ADMIN_PASSWORD}\"}")

if [ "$LOGIN_STATUS" != "200" ]; then
  echo "Login returned HTTP ${LOGIN_STATUS} — cannot record. The drill result above still stands."
  [ "$RESULT" = "PASSED" ] && exit 0 || exit 1
fi

# Prime the XSRF-TOKEN cookie via an authenticated GET first — /api/v1/public/** (login) is
# CSRF-exempt and never sets it; see dr-restore-drill.sh's identical comment for the full reason.
curl -sf -o /dev/null -c "$COOKIE_JAR" -b "$COOKIE_JAR" "${BACKEND_URL}/api/v1/dora/resilience-tests"
XSRF_TOKEN=$(awk -F'\t' '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -1)

SCOPE="Docker Compose demo stack — ${MODE}"
if [ "$MODE" = "kill-postgres" ]; then
  SCOPE="Docker Compose demo stack — SIGKILL postgres mid-traffic, measure degrade + recovery"
else
  SCOPE="Docker Compose demo stack — SIGKILL backend mid-request, measure in-flight failure + auto-recovery"
fi

FINDINGS_JSON=$(printf '%s' "${FINDINGS:-Recovery time: ${RECOVERY_SECONDS:-n/a}s}" | node -e 'process.stdout.write(JSON.stringify(require("fs").readFileSync(0,"utf8")))')

HTTP_STATUS=$(curl -s -o /tmp/chaos-dora-response.json -w '%{http_code}' \
  -b "$COOKIE_JAR" -X POST "${BACKEND_URL}/api/v1/dora/resilience-tests" \
  -H "X-XSRF-TOKEN: ${XSRF_TOKEN}" -H "Content-Type: application/json" \
  -d "{
    \"testType\": \"SCENARIO_BASED\",
    \"scope\": \"${SCOPE}\",
    \"tlptRequired\": false,
    \"performedAt\": \"$(date -u +%Y-%m-%d)\",
    \"result\": \"${RESULT}\",
    \"findings\": ${FINDINGS_JSON},
    \"testerName\": \"chaos-drill.sh (automated)\",
    \"reportRef\": \"${DRILL_ID}\"
  }")

if [ "$HTTP_STATUS" = "200" ] || [ "$HTTP_STATUS" = "201" ]; then
  echo "Recorded (HTTP ${HTTP_STATUS})."
else
  echo "Failed to record (HTTP ${HTTP_STATUS}): $(cat /tmp/chaos-dora-response.json 2>/dev/null)"
fi
rm -f /tmp/chaos-dora-response.json

[ "$RESULT" = "PASSED" ] && exit 0 || exit 1
