#!/bin/sh
# Starts the Canton sandbox with the ports the Registerwerk backend expects, and uploads the bond DAR when one is
# mounted at /dars (the backend addresses its templates by package name, #registerwerk-canton).
set -eu

dar=/dars/registerwerk-canton-0.1.0.dar
set -- sandbox --no-tty \
  -c /opt/canton/config/registerwerk-sandbox.conf \
  --ledger-api-port 5001 --admin-api-port 5002 --json-api-port 5013
if [ -f "$dar" ]; then
  set -- "$@" --dar "$dar"
else
  echo "No DAR at $dar: build it with 'dpm build' in daml/ and mount it, or upload it later." >&2
fi
exec /opt/canton/bin/canton "$@"
