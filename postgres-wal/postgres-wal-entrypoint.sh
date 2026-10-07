#!/bin/sh
# Wraps the stock docker-entrypoint.sh: turns WAL archiving on for the server process.
#   PG_ARCHIVE_MODE     on (default) | off
#   PG_ARCHIVE_TIMEOUT  seconds; forces a WAL segment switch (and so an archive) at least this often
#                       while there is write activity. This is the RPO knob: documented RPO target
#                       is <= 15 min, the default of 300 s leaves ample headroom for upload latency.
set -e
. /usr/local/bin/walg-env.sh
if [ "$1" = "postgres" ]; then
  shift
  exec docker-entrypoint.sh postgres \
    -c archive_mode="${PG_ARCHIVE_MODE:-on}" \
    -c archive_command='wal-g wal-push %p' \
    -c archive_timeout="${PG_ARCHIVE_TIMEOUT:-300}" \
    "$@"
fi
exec docker-entrypoint.sh "$@"
