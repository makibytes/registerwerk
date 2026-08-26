#!/usr/bin/env bash
set -euo pipefail

# Automates docs/operator/dr/runbook.md §2b (pg_dump/pg_restore fallback) + the row-count/
# freshness half of §4/§7's post-recovery checklist, against a disposable target container.
# With --verify-audit-chain, also automates the audit-hash-chain half of §4 for real (see below).
#
# Deliberately scoped: this exercises the pg_dump/pg_restore fallback path (§2b), not the
# primary wal-g/S3 path (§2a) — that needs real backup-bucket credentials this environment
# doesn't have, and simulating it would produce a result nobody could trust.
#
# What it DOES prove, for real, every time it's run: the documented pg_dump/pg_restore path
# works end to end, RTO is measurable, and every table's row count survives the round trip —
# closing "no restore automation" and (with --record-dora) "ResilienceTest is never populated
# outside demo data."
#
# --verify-audit-chain boots a real, throwaway backend container against the restored copy and
# calls its own POST /api/v1/audit/chain/verify — rather than reimplementing
# AuditChainVerificationService's SHA-256 canonicalization here in bash, which would risk
# silently diverging from it and giving false confidence, this reuses the app's actual
# verification logic. It requires no HSM/RPC/relayer machinery: REGISTERWERK_HSM_ENABLED is
# overridden to false for this throwaway container only (the same EnvVarKekProvider dev/demo
# fallback this repo already uses whenever HSM is off — see EnvVarKekProvider's own Javadoc —
# not a new insecure path), so the drill needs only the `postgres` service to already be
# running, not the full demo stack (softhsm/anvil/zama-relayer). It needs DEFAULT_ADMIN_EMAIL
# and DEFAULT_ADMIN_PASSWORD available in this shell (or in a repo-root .env, which this script
# will source) matching the credentials the SOURCE database was actually seeded with — without
# them it prints why and continues, reporting the audit-chain step as SKIPPED rather than
# failing the whole drill over a step that never got real credentials to run with.
#
# Usage:
#   scripts/dr-restore-drill.sh
#   scripts/dr-restore-drill.sh --verify-audit-chain
#   scripts/dr-restore-drill.sh --record-dora <backend-base-url> <bearer-token>
#   scripts/dr-restore-drill.sh --verify-audit-chain --record-dora <backend-base-url> <bearer-token>
#
# Requires: docker, docker compose, and a running `postgres` service (docker-compose.yml) —
# run from the repo root, or from anywhere with COMPOSE_FILE pointed at it. Only ever reads
# from the source database (pg_dump); every write goes to the disposable target container(s).

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Reads (only) the handful of vars this script itself needs straight out of a repo-root .env,
# without a blanket `source`: docker compose's .env format allows values like
# ENTRA_CLIENT_ID=<client-id> that are perfectly fine for compose's own KEY=VALUE parser but
# are not valid bash (`<` is a redirection operator) — `source`-ing the file outright breaks on
# exactly that. An already-exported shell value always wins over the .env file's value, same
# precedence docker compose itself uses.
env_from_dotenv() {
  local key="$1"
  [ -f "${REPO_ROOT}/.env" ] || return 0
  grep -E "^${key}=" "${REPO_ROOT}/.env" | tail -1 | cut -d= -f2-
}
: "${DEFAULT_ADMIN_EMAIL:=$(env_from_dotenv DEFAULT_ADMIN_EMAIL)}"
: "${DEFAULT_ADMIN_PASSWORD:=$(env_from_dotenv DEFAULT_ADMIN_PASSWORD)}"
: "${DB_USER:=$(env_from_dotenv DB_USER)}"

VERIFY_AUDIT_CHAIN=false
RECORD_DORA=false
DORA_BASE_URL=""
DORA_TOKEN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --verify-audit-chain)
      VERIFY_AUDIT_CHAIN=true
      shift
      ;;
    --record-dora)
      RECORD_DORA=true
      DORA_BASE_URL="${2:?--record-dora requires a backend base URL}"
      DORA_TOKEN="${3:?--record-dora requires a bearer token}"
      shift 3
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

DB_USER="${DB_USER:-registerwerk}"
DB_NAME="registerwerk"
DRILL_ID="dr-drill-$(date -u +%Y%m%dT%H%M%SZ)"
DUMP_FILE="$(mktemp -t "${DRILL_ID}.sql.XXXXXX")"
RESTORE_LOG="$(mktemp -t "${DRILL_ID}.restore-log.XXXXXX")"
TARGET_CONTAINER="registerwerk-${DRILL_ID}"
TARGET_PORT="${TARGET_PORT:-55432}"
AUDIT_BACKEND_CONTAINER="registerwerk-${DRILL_ID}-audit-backend"
AUDIT_BACKEND_PORT="${AUDIT_BACKEND_PORT:-58080}"
COOKIE_JAR="$(mktemp -t "${DRILL_ID}.cookies.XXXXXX")"
REPORT_FILE="${REPO_ROOT}/dr-drill-report-${DRILL_ID}.json"

RESULT="FAILED"
FINDINGS=""
AUDIT_CHAIN_RESULT="SKIPPED"
AUDIT_CHAIN_DETAIL=""

cleanup() {
  docker rm -f "$TARGET_CONTAINER" >/dev/null 2>&1 || true
  docker rm -f "$AUDIT_BACKEND_CONTAINER" >/dev/null 2>&1 || true
  rm -f "$DUMP_FILE" "$RESTORE_LOG" "$COOKIE_JAR"
}
trap cleanup EXIT

write_report() {
  cat > "$REPORT_FILE" <<EOF
{
  "drillId": "$DRILL_ID",
  "startedAt": "$STARTED_AT_ISO",
  "finishedAt": "$FINISHED_AT_ISO",
  "rtoSeconds": $RTO_SECONDS,
  "result": "$RESULT",
  "sourceDumpBytes": ${SOURCE_BYTES:-0},
  "tableRowCountsMatch": ${TABLES_MATCH:-false},
  "auditChainResult": "$AUDIT_CHAIN_RESULT",
  "auditChainDetail": $(printf '%s' "$AUDIT_CHAIN_DETAIL" | node -e 'process.stdout.write(JSON.stringify(require("fs").readFileSync(0,"utf8")))'),
  "findings": $(printf '%s' "$FINDINGS" | node -e 'process.stdout.write(JSON.stringify(require("fs").readFileSync(0,"utf8")))')
}
EOF
  echo "Report written: $REPORT_FILE"
}

STARTED_AT_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
STARTED_AT_EPOCH=$(date +%s)

echo "== DR restore drill: $DRILL_ID =="

echo "-> 1/5 Dumping source database (read-only) via 'docker compose exec postgres pg_dump'..."
docker compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" --no-owner --no-privileges > "$DUMP_FILE"
SOURCE_BYTES=$(wc -c < "$DUMP_FILE" | tr -d ' ')
echo "   dumped ${SOURCE_BYTES} bytes"

# Same compose network the running `postgres` service is on — resolved from that container
# rather than assumed from the compose project name, so this doesn't break if the project is
# ever run under a different name/directory. Needed so a --verify-audit-chain backend
# container (which `docker compose run` attaches to this same network) can reach the target by
# container name; harmless to attach unconditionally even when that flag is off.
POSTGRES_CID=$(docker compose ps -q postgres)
COMPOSE_NETWORK=$(docker inspect "$POSTGRES_CID" --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')

echo "-> 2/5 Starting disposable Postgres 18.6 target on 127.0.0.1:${TARGET_PORT}..."
docker run -d --name "$TARGET_CONTAINER" \
  --network "$COMPOSE_NETWORK" \
  -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD=drill \
  -p "127.0.0.1:${TARGET_PORT}:5432" \
  postgres:18.6-alpine >/dev/null

echo -n "   waiting for target readiness"
# The official postgres image briefly starts a throwaway internal server to run its init
# scripts (create the app DB/user), stops it, then starts the real one — pg_isready alone can
# report "accepting connections" against that transient first instance, before POSTGRES_DB
# actually exists. Polling `SELECT 1` against the real target database is what actually proves
# it's ready, not just that *some* Postgres process is listening.
READY=false
for _ in $(seq 1 90); do
  if docker exec "$TARGET_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT 1" >/dev/null 2>&1; then
    READY=true
    break
  fi
  echo -n "."
  sleep 1
done
if [ "$READY" != true ]; then
  FINDINGS="Target Postgres did not become ready within 90s"
  echo; echo "FAILED: $FINDINGS"
  FINISHED_AT_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ); FINISHED_AT_EPOCH=$(date +%s)
  RTO_SECONDS=$((FINISHED_AT_EPOCH - STARTED_AT_EPOCH))
  write_report
  exit 1
fi
echo " ready"

echo "-> 3/5 Restoring dump into target..."
if ! docker exec -i "$TARGET_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 < "$DUMP_FILE" > "$RESTORE_LOG" 2>&1; then
  FINDINGS="Restore failed — see $RESTORE_LOG: $(tail -5 "$RESTORE_LOG" | tr '\n' ' ')"
  echo "FAILED: $FINDINGS"
  FINISHED_AT_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ); FINISHED_AT_EPOCH=$(date +%s)
  RTO_SECONDS=$((FINISHED_AT_EPOCH - STARTED_AT_EPOCH))
  write_report
  exit 1
fi

echo "-> 4/5 Comparing row counts for every table, source vs. restored target..."
TABLES=$(docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name;")

TABLES_MATCH=true
MISMATCHES=""
while IFS= read -r TABLE; do
  [ -z "$TABLE" ] && continue
  SOURCE_COUNT=$(docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT count(*) FROM \"$TABLE\";" | tr -d ' \r')
  TARGET_COUNT=$(docker exec "$TARGET_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "SELECT count(*) FROM \"$TABLE\";" | tr -d ' \r')
  if [ "$SOURCE_COUNT" != "$TARGET_COUNT" ]; then
    TABLES_MATCH=false
    MISMATCHES="${MISMATCHES}${TABLE}: source=${SOURCE_COUNT} target=${TARGET_COUNT}; "
  fi
done <<< "$TABLES"

if [ "$TABLES_MATCH" = true ]; then
  RESULT="PASSED"
  echo "   all tables match."
else
  RESULT="FAILED"
  FINDINGS="Row count mismatch after restore: $MISMATCHES"
  echo "   MISMATCH: $FINDINGS"
fi

if [ "$VERIFY_AUDIT_CHAIN" = true ]; then
  echo
  echo "-> Verifying the audit hash chain against the restored copy (runbook §4)..."
  if [ -z "${DEFAULT_ADMIN_EMAIL:-}" ] || [ -z "${DEFAULT_ADMIN_PASSWORD:-}" ]; then
    AUDIT_CHAIN_RESULT="SKIPPED"
    AUDIT_CHAIN_DETAIL="DEFAULT_ADMIN_EMAIL/DEFAULT_ADMIN_PASSWORD not available in this shell or repo-root .env — cannot log in to call POST /api/v1/audit/chain/verify."
    echo "   SKIPPED: $AUDIT_CHAIN_DETAIL"
  else
    echo "   Booting a throwaway backend container against the restored target (HSM disabled for this container only — see script header)..."
    docker compose run --rm -d --no-deps --name "$AUDIT_BACKEND_CONTAINER" \
      -p "127.0.0.1:${AUDIT_BACKEND_PORT}:8080" \
      -e "DB_URL=jdbc:postgresql://${TARGET_CONTAINER}:5432/${DB_NAME}" \
      -e DB_PASSWORD=drill \
      -e REGISTERWERK_HSM_ENABLED=false \
      -e "REGISTERWERK_WALLET_MASTER_KEY=dr-drill-throwaway-wallet-master-key-not-for-real-use" \
      backend >/dev/null

    echo -n "   waiting for backend readiness"
    BACKEND_READY=false
    for _ in $(seq 1 150); do
      if curl -sf -o /dev/null "http://127.0.0.1:${AUDIT_BACKEND_PORT}/actuator/health"; then
        BACKEND_READY=true
        break
      fi
      echo -n "."
      sleep 1
    done

    if [ "$BACKEND_READY" != true ]; then
      AUDIT_CHAIN_RESULT="FAILED"
      AUDIT_CHAIN_DETAIL="Throwaway backend did not become ready within 150s — see: docker logs ${AUDIT_BACKEND_CONTAINER}"
      echo; echo "   FAILED: $AUDIT_CHAIN_DETAIL"
    else
      echo " ready"
      echo "   Logging in as ${DEFAULT_ADMIN_EMAIL}..."
      LOGIN_HTTP_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -c "$COOKIE_JAR" \
        -X POST "http://127.0.0.1:${AUDIT_BACKEND_PORT}/api/v1/public/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"${DEFAULT_ADMIN_EMAIL}\",\"password\":\"${DEFAULT_ADMIN_PASSWORD}\"}")

      if [ "$LOGIN_HTTP_STATUS" != "200" ]; then
        AUDIT_CHAIN_RESULT="FAILED"
        AUDIT_CHAIN_DETAIL="Login against the restored copy returned HTTP ${LOGIN_HTTP_STATUS} — DEFAULT_ADMIN_EMAIL/PASSWORD in this shell may not match the credentials the source database was seeded with."
        echo "   FAILED: $AUDIT_CHAIN_DETAIL"
      else
        # /api/v1/public/** (login included) is CSRF-ignored (SecurityConfig), so the login
        # response itself carries no XSRF-TOKEN cookie yet — only a request that actually goes
        # through CsrfFilter triggers SpaCsrfConfig.CsrfCookieFilter to write one. A throwaway
        # authenticated GET primes it before the state-changing POST below, exactly like a real
        # frontend's first mutating call after login would see it already set.
        curl -sf -o /dev/null -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
          "http://127.0.0.1:${AUDIT_BACKEND_PORT}/api/v1/audit/chain/status"

        XSRF_TOKEN=$(awk -F'\t' '$6 == "XSRF-TOKEN" { print $7 }' "$COOKIE_JAR" | tail -1)
        VERIFY_RESPONSE=$(curl -sf -b "$COOKIE_JAR" \
          -X POST "http://127.0.0.1:${AUDIT_BACKEND_PORT}/api/v1/audit/chain/verify" \
          -H "X-XSRF-TOKEN: ${XSRF_TOKEN}" -H "Content-Type: application/json") \
          || { AUDIT_CHAIN_RESULT="FAILED"; AUDIT_CHAIN_DETAIL="POST /api/v1/audit/chain/verify request failed."; }

        if [ "$AUDIT_CHAIN_RESULT" != "FAILED" ]; then
          CHAIN_VALID=$(printf '%s' "$VERIFY_RESPONSE" | node -e 'const r=JSON.parse(require("fs").readFileSync(0,"utf8")); process.stdout.write(String(r.valid))')
          if [ "$CHAIN_VALID" = "true" ]; then
            AUDIT_CHAIN_RESULT="PASSED"
            AUDIT_CHAIN_DETAIL="$VERIFY_RESPONSE"
            echo "   PASSED: audit hash chain intact on the restored copy. ${VERIFY_RESPONSE}"
          else
            AUDIT_CHAIN_RESULT="FAILED"
            AUDIT_CHAIN_DETAIL="$VERIFY_RESPONSE"
            RESULT="FAILED"
            echo "   FAILED: audit hash chain BROKEN on the restored copy. ${VERIFY_RESPONSE}"
          fi
        else
          echo "   FAILED: $AUDIT_CHAIN_DETAIL"
        fi
      fi
    fi

    docker rm -f "$AUDIT_BACKEND_CONTAINER" >/dev/null 2>&1 || true
  fi
fi

FINISHED_AT_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FINISHED_AT_EPOCH=$(date +%s)
RTO_SECONDS=$((FINISHED_AT_EPOCH - STARTED_AT_EPOCH))

echo "-> 5/5 Writing drill report..."
write_report

echo
echo "RTO for this drill: ${RTO_SECONDS}s. Result: $RESULT. Audit chain: $AUDIT_CHAIN_RESULT."
if [ "$VERIFY_AUDIT_CHAIN" != true ]; then
  echo "To verify the audit hash chain against the restored copy (runbook §4), either re-run with"
  echo "--verify-audit-chain, or point a backend instance's DB_URL at"
  echo "postgresql://localhost:${TARGET_PORT}/${DB_NAME} and call POST /api/v1/audit/chain/verify"
  echo "yourself before trusting this restore for anything beyond the row-count check above."
fi

if [ "$RECORD_DORA" = true ]; then
  echo
  echo "Recording DORA resilience-test result via ${DORA_BASE_URL}..."
  DORA_RESULT="$RESULT"
  if [ "$RESULT" = "FAILED" ]; then DORA_RESULT="FINDINGS_OPEN"; fi
  DORA_SCOPE="Postgres disaster-recovery restore drill (pg_dump/pg_restore path, runbook §2b)"
  if [ "$VERIFY_AUDIT_CHAIN" = true ]; then
    DORA_SCOPE="Postgres disaster-recovery restore drill (pg_dump/pg_restore path, runbook §2b + audit hash chain verification, runbook §4)"
  fi
  curl -sf -X POST "${DORA_BASE_URL}/api/v1/dora/resilience-tests" \
    -H "Authorization: Bearer ${DORA_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{
      \"testType\": \"SCENARIO_BASED\",
      \"scope\": \"${DORA_SCOPE}\",
      \"tlptRequired\": false,
      \"performedAt\": \"$(date -u +%Y-%m-%d)\",
      \"result\": \"${DORA_RESULT}\",
      \"findings\": $(printf '%s' "$FINDINGS" | node -e 'process.stdout.write(JSON.stringify(require("fs").readFileSync(0,"utf8")))'),
      \"testerName\": \"dr-restore-drill.sh (automated)\",
      \"reportRef\": \"${DRILL_ID}\"
    }" && echo "Recorded." || echo "Failed to record — the drill result above still stands, only the DORA record is missing."
fi

if [ "$RESULT" != "PASSED" ]; then
  exit 1
fi
