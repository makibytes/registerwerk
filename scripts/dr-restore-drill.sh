#!/usr/bin/env bash
set -euo pipefail

# Automates docs/operator/dr/runbook.md §2b (pg_dump/pg_restore fallback) + the row-count/
# freshness half of §4/§7's post-recovery checklist, against a disposable target container.
#
# Deliberately scoped: this exercises the pg_dump/pg_restore fallback path (§2b), not the
# primary wal-g/S3 path (§2a) — that needs real backup-bucket credentials this environment
# doesn't have, and simulating it would produce a result nobody could trust. It also does NOT
# recompute the audit hash chain (that's the app's own logic, in AuditChainVerificationService;
# reimplementing its SHA-256 canonicalization here in bash would risk silently diverging from
# it and giving false confidence). Instead it prints the one command an operator needs to run
# that check for real, against the drill target, once this script has confirmed the restore
# itself succeeded.
#
# What it DOES prove, for real, every time it's run: the documented pg_dump/pg_restore path
# works end to end, RTO is measurable, and every table's row count survives the round trip —
# closing "no restore automation" and (with --record-dora) "ResilienceTest is never populated
# outside demo data."
#
# Usage:
#   scripts/dr-restore-drill.sh
#   scripts/dr-restore-drill.sh --record-dora <backend-base-url> <bearer-token>
#
# Requires: docker, docker compose, and a running `postgres` service (docker-compose.yml) —
# run from the repo root, or from anywhere with COMPOSE_FILE pointed at it. Only ever reads
# from the source database (pg_dump); every write goes to the disposable target container.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RECORD_DORA=false
DORA_BASE_URL=""
DORA_TOKEN=""
if [ "${1:-}" = "--record-dora" ]; then
  RECORD_DORA=true
  DORA_BASE_URL="${2:?--record-dora requires a backend base URL}"
  DORA_TOKEN="${3:?--record-dora requires a bearer token}"
fi

DB_USER="${DB_USER:-registerwerk}"
DB_NAME="registerwerk"
DRILL_ID="dr-drill-$(date -u +%Y%m%dT%H%M%SZ)"
DUMP_FILE="$(mktemp -t "${DRILL_ID}.sql.XXXXXX")"
RESTORE_LOG="$(mktemp -t "${DRILL_ID}.restore-log.XXXXXX")"
TARGET_CONTAINER="registerwerk-${DRILL_ID}"
TARGET_PORT="${TARGET_PORT:-55432}"
REPORT_FILE="${REPO_ROOT}/dr-drill-report-${DRILL_ID}.json"

RESULT="FAILED"
FINDINGS=""

cleanup() {
  docker rm -f "$TARGET_CONTAINER" >/dev/null 2>&1 || true
  rm -f "$DUMP_FILE" "$RESTORE_LOG"
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

echo "-> 2/5 Starting disposable Postgres 17 target on 127.0.0.1:${TARGET_PORT}..."
docker run -d --name "$TARGET_CONTAINER" \
  -e POSTGRES_DB="$DB_NAME" -e POSTGRES_USER="$DB_USER" -e POSTGRES_PASSWORD=drill \
  -p "127.0.0.1:${TARGET_PORT}:5432" \
  postgres:17.10-alpine >/dev/null

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

FINISHED_AT_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
FINISHED_AT_EPOCH=$(date +%s)
RTO_SECONDS=$((FINISHED_AT_EPOCH - STARTED_AT_EPOCH))

echo "-> 5/5 Writing drill report..."
write_report

echo
echo "RTO for this drill: ${RTO_SECONDS}s. Result: $RESULT."
echo "To verify the audit hash chain against the restored copy (runbook §4), point a backend"
echo "instance's DB_URL at postgresql://localhost:${TARGET_PORT}/${DB_NAME} and call"
echo "  POST /api/v1/audit/chain/verify"
echo "before trusting this restore for anything beyond the row-count check above."

if [ "$RECORD_DORA" = true ]; then
  echo
  echo "Recording DORA resilience-test result via ${DORA_BASE_URL}..."
  DORA_RESULT="$RESULT"
  if [ "$RESULT" = "FAILED" ]; then DORA_RESULT="FINDINGS_OPEN"; fi
  curl -sf -X POST "${DORA_BASE_URL}/api/v1/dora/resilience-tests" \
    -H "Authorization: Bearer ${DORA_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{
      \"testType\": \"SCENARIO_BASED\",
      \"scope\": \"Postgres disaster-recovery restore drill (pg_dump/pg_restore path, runbook §2b)\",
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
