#!/bin/sh
# Base-backup job for the `backup` compose profile.
#   pg-backup.sh once        one `wal-g backup-push`, retention, exit
#   pg-backup.sh loop        repeat every BACKUP_INTERVAL_SECONDS (default 86400)
# Needs the postgres data volume mounted (WAL-G reads the data files itself) and a reachable server
# (PGHOST/PGPASSWORD) to run pg_backup_start/stop. Optional BACKUP_PUSHGATEWAY_URL receives
# backup_last_success_timestamp, which the BackupStale alert watches.
set -eu
. /usr/local/bin/walg-env.sh
BACKUP_RETAIN_FULL="${BACKUP_RETAIN_FULL:-7}"

run_once() {
  wal-g backup-push "$PGDATA"
  wal-g delete retain FULL "$BACKUP_RETAIN_FULL" --confirm
  if [ -n "${BACKUP_PUSHGATEWAY_URL:-}" ]; then
    printf 'backup_last_success_timestamp %s\n' "$(date +%s)" |
      wget -q -O- --post-data="$(cat)" "${BACKUP_PUSHGATEWAY_URL%/}/metrics/job/pg-backup" >/dev/null ||
      echo "warning: could not push backup_last_success_timestamp" >&2
  fi
  echo "backup complete: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

case "${1:-once}" in
  once) run_once ;;
  loop)
    while true; do
      run_once || echo "backup failed; retrying next interval" >&2
      sleep "${BACKUP_INTERVAL_SECONDS:-86400}"
    done ;;
  *) echo "usage: pg-backup.sh once|loop" >&2; exit 2 ;;
esac
