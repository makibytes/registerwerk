#!/bin/sh
# Sourced by the entrypoint and by pg-backup.sh so both resolve the archive location identically.
# WAL-G must see exactly one storage prefix: an explicit S3 prefix wins over the local default.
if [ -n "${WALG_S3_PREFIX:-}" ]; then
  unset WALG_FILE_PREFIX
else
  unset WALG_S3_PREFIX
  export WALG_FILE_PREFIX="${WALG_FILE_PREFIX:-/wal-archive}"
fi
# Connection used by `wal-g backup-push` (local socket inside the postgres container).
export PGUSER="${PGUSER:-${POSTGRES_USER:-postgres}}"
export PGDATABASE="${PGDATABASE:-${POSTGRES_DB:-$PGUSER}}"
export PGDATA="${PGDATA:-/var/lib/postgresql/18/docker}"
