#!/usr/bin/env bash
set -euo pipefail

# PITR drill (T7-04): proves, for real and repeatably, that WAL-G base backup + continuous WAL
# archiving + point-in-time recovery work end to end on the exact image and archive settings the
# `docker-compose.wal.yml` overlay ships (postgres-wal/, PG 18 mechanics: recovery.signal +
# recovery_target_time in postgresql.auto.conf — there is no recovery.conf any more).
#
# It runs ONLY on throwaway docker objects it creates itself (containers + two named volumes
# prefixed `rw-pitr-`, removed on exit). It never reads, mounts or recreates the demo stack's
# `postgres` service or its pg18data volume, and publishes no host port.
#
# Sequence: init (archive_mode=on) -> create table, insert rows -> `wal-g backup-push` -> insert rows
# (before target) -> record target time -> insert rows (after target) -> insert a tail row and wait,
# with NO forced WAL switch, until its segment is archived (that wait is the measured RPO sample) ->
# simulate loss (remove container + wipe the data volume) -> restore base backup + WAL to the target
# time -> verify EXACTLY the rows committed up to the target exist.
#
# Measured and printed:
#   RPO sample  seconds between the commit of the last row and the archival of the WAL segment that
#               holds it (pg_stat_archiver.last_archived_time) — the data-loss window if the disk
#               had been lost at that instant. Its upper bound is archive_timeout + upload latency.
#   RTO         wall time from "data volume wiped" until the restored server is promoted and answers
#               queries (base-backup fetch + WAL replay + promote), on THIS drill's tiny data set.
#               It scales with base-backup size and WAL volume — re-measure against production data.
#
# Usage:
#   scripts/pitr-drill.sh [--archive-timeout SECONDS] [--rebuild] [--keep]
#   scripts/pitr-drill.sh --record <backend-base-url> <operator-bearer-token>
# --archive-timeout  archive_timeout of the drilled server (default 300 = the compose/Helm default;
#                    a lower value makes the drill faster but then the RPO it measures is for that value)
# --record           off by default: POST the result to /api/v1/dora/resilience-tests
#                    (SCENARIO_BASED test; token = an operator JWT with DORA write access. The
#                    `rw_session` cookie value returned by POST /api/v1/public/auth/login is one and
#                    is accepted as `Authorization: Bearer`.)
# --keep             leave the throwaway containers/volumes behind for inspection
# Requires: docker, perl. Report: ${PITR_REPORT_DIR:-$TMPDIR}/pitr-drill-report-<id>.json

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${PITR_IMAGE:-registerwerk/postgres-wal:18.6}"
ARCHIVE_TIMEOUT=300
REBUILD=false
KEEP=false
RECORD=false
DORA_BASE_URL=""
DORA_TOKEN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --archive-timeout) ARCHIVE_TIMEOUT="${2:?--archive-timeout needs seconds}"; shift 2 ;;
    --rebuild) REBUILD=true; shift ;;
    --keep) KEEP=true; shift ;;
    --record)
      RECORD=true
      DORA_BASE_URL="${2:?--record needs a backend base URL}"
      DORA_TOKEN="${3:?--record needs a bearer token}"
      shift 3 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

DRILL_ID="pitr-drill-$(date -u +%Y%m%dT%H%M%SZ)"
SRC="rw-${DRILL_ID}-src"
RESTORED="rw-${DRILL_ID}-restored"
VOL_DATA="rw-${DRILL_ID}-data"
VOL_DATA2="rw-${DRILL_ID}-data-restored"
VOL_ARCHIVE="rw-${DRILL_ID}-archive"
REPORT_DIR="${PITR_REPORT_DIR:-${TMPDIR:-/tmp}}"
REPORT_FILE="${REPORT_DIR%/}/pitr-drill-report-${DRILL_ID}.json"
DBU=drill
DBN=drill

RESULT="FAILED"
FINDINGS=""
now() { perl -MTime::HiRes=time -e 'printf "%.3f\n", time'; }
q() { docker exec "$1" psql -h 127.0.0.1 -U "$DBU" -d "$DBN" -tAqX -c "$2"; }

cleanup() {
  if [ "$KEEP" = true ]; then
    echo "Kept: containers $SRC $RESTORED; volumes $VOL_DATA $VOL_DATA2 $VOL_ARCHIVE"
    return
  fi
  docker rm -f "$SRC" "$RESTORED" >/dev/null 2>&1 || true
  docker volume rm -f "$VOL_DATA" "$VOL_DATA2" "$VOL_ARCHIVE" >/dev/null 2>&1 || true
}
trap cleanup EXIT
fail() { FINDINGS="$1"; echo "FAILED: $1" >&2; exit 1; }

wait_ready() { # container, max seconds — TCP only, so the entrypoint's socket-only init server never counts
  for _ in $(seq 1 "$2"); do
    q "$1" "SELECT 1" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

echo "== PITR drill: $DRILL_ID (archive_timeout=${ARCHIVE_TIMEOUT}s) =="
if [ "$REBUILD" = true ] || ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "-> building $IMAGE (WAL-G from source, pinned tag)"
  docker build -q -t "$IMAGE" "$REPO_ROOT/postgres-wal" >/dev/null
fi

echo "-> 1/7 initialising throwaway server with archive_mode=on (local file archive volume)"
docker volume create "$VOL_DATA" >/dev/null
docker volume create "$VOL_ARCHIVE" >/dev/null
docker run -d --name "$SRC" \
  -e POSTGRES_USER="$DBU" -e POSTGRES_DB="$DBN" -e POSTGRES_PASSWORD=drill \
  -e PG_ARCHIVE_TIMEOUT="$ARCHIVE_TIMEOUT" \
  -v "$VOL_DATA":/var/lib/postgresql -v "$VOL_ARCHIVE":/wal-archive \
  "$IMAGE" >/dev/null
wait_ready "$SRC" 90 || fail "source server not ready within 90s"
[ "$(q "$SRC" "SHOW archive_mode")" = "on" ] || fail "archive_mode is not on"
echo "   archive_command = $(q "$SRC" "SHOW archive_command"); archive_timeout = $(q "$SRC" "SHOW archive_timeout")"

q "$SRC" "CREATE TABLE drill_events (id bigserial PRIMARY KEY, label text NOT NULL, committed_at timestamptz NOT NULL DEFAULT clock_timestamp())" >/dev/null
for _ in 1 2 3 4 5; do q "$SRC" "INSERT INTO drill_events(label) VALUES ('pre-backup')" >/dev/null; done

echo "-> 2/7 wal-g backup-push"
docker exec -u postgres -e PGUSER="$DBU" -e PGDATABASE="$DBN" -e POSTGRES_USER="$DBU" "$SRC" \
  sh -c '. /usr/local/bin/walg-env.sh && wal-g backup-push "$PGDATA"' 2>&1 | tail -3
BACKUP_LIST=$(docker exec -u postgres "$SRC" wal-g backup-list 2>&1 | tail -2 | tr '\n' ' ')
echo "   backup-list: $BACKUP_LIST"

echo "-> 3/7 inserting rows around the recovery target"
for _ in $(seq 1 10); do q "$SRC" "INSERT INTO drill_events(label) VALUES ('before-target')" >/dev/null; sleep 0.3; done
sleep 2
TARGET=$(q "$SRC" "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS.US') || '+00'")
sleep 2
for _ in $(seq 1 10); do q "$SRC" "INSERT INTO drill_events(label) VALUES ('after-target')" >/dev/null; sleep 0.3; done
echo "   recovery target time: $TARGET"

echo "-> 4/7 tail row + wait for its WAL segment to be archived WITHOUT forcing a switch (RPO sample)"
q "$SRC" "INSERT INTO drill_events(label) VALUES ('tail')" >/dev/null
T_TAIL=$(q "$SRC" "SELECT extract(epoch FROM clock_timestamp())")
TAIL_WAL=$(q "$SRC" "SELECT pg_walfile_name(pg_current_wal_insert_lsn())")
DEADLINE=$(( $(date +%s) + ARCHIVE_TIMEOUT + 180 ))
ARCHIVED_AT=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  ROW=$(q "$SRC" "SELECT coalesce(last_archived_wal,''), coalesce(extract(epoch FROM last_archived_time)::text,'') FROM pg_stat_archiver")
  LAST_WAL="${ROW%%|*}"; LAST_AT="${ROW##*|}"
  if [ "${#LAST_WAL}" -eq 24 ] && { [ "$LAST_WAL" = "$TAIL_WAL" ] || [[ "$LAST_WAL" > "$TAIL_WAL" ]]; }; then ARCHIVED_AT="$LAST_AT"; break; fi
  sleep 3
done
[ -n "$ARCHIVED_AT" ] || fail "segment $TAIL_WAL was not archived within archive_timeout+180s"
RPO_SECONDS=$(perl -e "printf '%.1f', $ARCHIVED_AT - $T_TAIL")
FAILED_COUNT=$(q "$SRC" "SELECT failed_count FROM pg_stat_archiver")
echo "   segment $TAIL_WAL archived ${RPO_SECONDS}s after the last commit (archiver failed_count=$FAILED_COUNT)"

echo "-> 5/7 simulating total loss: removing the server container and wiping its data volume"
T_LOSS=$(now)
docker rm -f "$SRC" >/dev/null
docker volume rm -f "$VOL_DATA" >/dev/null
docker volume create "$VOL_DATA2" >/dev/null

echo "-> 6/7 restoring base backup + WAL to $TARGET (recovery.signal + recovery_target_time, PG18)"
docker run --rm -i --user postgres -e RECOVERY_TARGET="$TARGET" \
  -v "$VOL_DATA2":/var/lib/postgresql -v "$VOL_ARCHIVE":/wal-archive \
  --entrypoint sh "$IMAGE" -s <<'EOS'
set -eu
. /usr/local/bin/walg-env.sh
install -d -m 0700 "$PGDATA"
wal-g backup-fetch "$PGDATA" LATEST </dev/null
touch "$PGDATA/recovery.signal"
cat >> "$PGDATA/postgresql.auto.conf" <<CONF
restore_command = 'wal-g wal-fetch %f %p'
recovery_target_time = '${RECOVERY_TARGET}'
recovery_target_action = 'promote'
CONF
EOS
T_FETCHED=$(now)
docker run -d --name "$RESTORED" \
  -e POSTGRES_USER="$DBU" -e POSTGRES_DB="$DBN" -e POSTGRES_PASSWORD=drill \
  -e PG_ARCHIVE_TIMEOUT="$ARCHIVE_TIMEOUT" \
  -v "$VOL_DATA2":/var/lib/postgresql -v "$VOL_ARCHIVE":/wal-archive \
  "$IMAGE" >/dev/null
PROMOTED=false
for _ in $(seq 1 300); do
  if [ "$(q "$RESTORED" "SELECT NOT pg_is_in_recovery()" 2>/dev/null || true)" = "t" ]; then PROMOTED=true; break; fi
  [ "$(docker inspect -f '{{.State.Running}}' "$RESTORED")" = "true" ] || break
  sleep 0.5
done
T_UP=$(now)
if [ "$PROMOTED" != true ]; then docker logs --tail 30 "$RESTORED" >&2 || true; fail "restored server did not finish recovery"; fi
RTO_SECONDS=$(perl -e "printf '%.1f', $T_UP - $T_LOSS")
FETCH_SECONDS=$(perl -e "printf '%.1f', $T_FETCHED - $T_LOSS")
echo "   RTO ${RTO_SECONDS}s (backup-fetch ${FETCH_SECONDS}s, WAL replay + promote $(perl -e "printf '%.1f', $T_UP - $T_FETCHED")s)"
docker logs "$RESTORED" 2>&1 | grep -E "recovery stopping|recovery target|archive recovery complete|selected new timeline" | sed 's/^/   log: /' | head -6

echo "-> 7/7 verifying exactly the rows committed up to the target exist"
N_PRE=$(q "$RESTORED" "SELECT count(*) FROM drill_events WHERE label='pre-backup'")
N_BEFORE=$(q "$RESTORED" "SELECT count(*) FROM drill_events WHERE label='before-target'")
N_AFTER=$(q "$RESTORED" "SELECT count(*) FROM drill_events WHERE label IN ('after-target','tail')")
N_LATE=$(q "$RESTORED" "SELECT count(*) FROM drill_events WHERE committed_at > '$TARGET'::timestamptz")
echo "   pre-backup=$N_PRE (want 5) before-target=$N_BEFORE (want 10) after-target+tail=$N_AFTER (want 0) committed-after-target=$N_LATE (want 0)"
if [ "$N_PRE" = 5 ] && [ "$N_BEFORE" = 10 ] && [ "$N_AFTER" = 0 ] && [ "$N_LATE" = 0 ]; then
  RESULT="PASSED"
else
  FINDINGS="Row set after PITR does not match the target: pre=$N_PRE before=$N_BEFORE after+tail=$N_AFTER late=$N_LATE"
fi

mkdir -p "$REPORT_DIR"
cat > "$REPORT_FILE" <<JSON
{
  "drillId": "$DRILL_ID",
  "result": "$RESULT",
  "archiveTimeoutSeconds": $ARCHIVE_TIMEOUT,
  "recoveryTarget": "$TARGET",
  "rpoSampleSeconds": $RPO_SECONDS,
  "rtoSeconds": $RTO_SECONDS,
  "rowsPre": $N_PRE, "rowsBeforeTarget": $N_BEFORE, "rowsAfterTargetOrTail": $N_AFTER, "rowsCommittedAfterTarget": $N_LATE,
  "wal-g": "$(docker run --rm --entrypoint wal-g "$IMAGE" --version 2>&1 | head -1 | tr '\t' ' ')",
  "postgres": "$(docker exec "$RESTORED" postgres --version)"
}
JSON
echo
echo "PITR DRILL ${RESULT}: measured RPO sample ${RPO_SECONDS}s (bound: archive_timeout ${ARCHIVE_TIMEOUT}s + upload latency; target <= 900s), RTO ${RTO_SECONDS}s on a near-empty database."
echo "Report: $REPORT_FILE"

if [ "$RECORD" = true ]; then
  DORA_RESULT="$RESULT"; [ "$RESULT" = "PASSED" ] || DORA_RESULT="FINDINGS_OPEN"
  SUMMARY="WAL-G PITR drill ${DRILL_ID}: restored to ${TARGET}; archive_timeout ${ARCHIVE_TIMEOUT}s; measured RPO sample ${RPO_SECONDS}s; measured RTO ${RTO_SECONDS}s (near-empty drill database, re-measure on production-sized data). ${FINDINGS}"
  BODY=$(printf '%s' "$SUMMARY" | perl -MJSON::PP -e 'local $/; print JSON::PP->new->allow_nonref->encode(<STDIN>)')
  echo "Recording DORA resilience test via ${DORA_BASE_URL}..."
  curl -fsS -X POST "${DORA_BASE_URL%/}/api/v1/dora/resilience-tests" \
    -H "Authorization: Bearer ${DORA_TOKEN}" -H "Content-Type: application/json" \
    -d "{\"testType\":\"SCENARIO_BASED\",\"scope\":\"PostgreSQL point-in-time recovery drill (WAL-G base backup + WAL archive, runbook section 2a)\",\"tlptRequired\":false,\"performedAt\":\"$(date -u +%Y-%m-%d)\",\"result\":\"${DORA_RESULT}\",\"findings\":${BODY},\"testerName\":\"pitr-drill.sh (automated)\",\"reportRef\":\"${DRILL_ID}\"}" \
    && echo && echo "Recorded." || echo "Failed to record — the drill result above still stands; only the DORA record is missing."
fi

[ "$RESULT" = "PASSED" ] || exit 1
