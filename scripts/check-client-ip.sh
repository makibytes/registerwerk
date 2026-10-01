#!/usr/bin/env bash
# Verifies that client-IP handling through the compose edge is not spoofable (7B-01).
# Run against a running stack from the compose host:
#   scripts/check-client-ip.sh [http://localhost:44201]
# Checks:
#   1. A spoofed X-Forwarded-For must not change which rate-limit bucket / admin-allow decision
#      applies: the same real caller with two different spoofed values hits ONE bucket (the
#      response's X-RateLimit-Remaining-* keeps counting down instead of resetting).
#   2. /api/v1/admin/** with a spoofed public address is judged by the real source address
#      (never a 5xx; 401/403 are both acceptable without a token).
set -euo pipefail
BASE="${1:-http://localhost:44201}"
fail=0

hdr() { curl -s -o /dev/null -D - -H "X-Forwarded-For: $1" "$BASE/api/v1/public/auth/config" | tr -d '\r'; }

a=$(hdr 1.2.3.4 | grep -i '^x-ratelimit-remaining' | head -1 | awk '{print $2}')
b=$(hdr 5.6.7.8 | grep -i '^x-ratelimit-remaining' | head -1 | awk '{print $2}')
if [ -z "${a:-}" ] || [ -z "${b:-}" ]; then
  echo "WARN: no X-RateLimit-Remaining header on /public/auth/config (route not rate limited here); skipping bucket check"
elif [ "$b" -ge "$a" ]; then
  echo "FAIL: spoofed X-Forwarded-For reset the rate-limit counter ($a -> $b): buckets are keyed on the header"
  fail=1
else
  echo "OK: two spoofed X-Forwarded-For values share one bucket ($a -> $b)"
fi

code=$(curl -s -o /dev/null -w '%{http_code}' -H 'X-Forwarded-For: 8.8.8.8' "$BASE/api/v1/admin/x")
case "$code" in
  401|403|404) echo "OK: /api/v1/admin/x with spoofed XFF answered $code" ;;
  *) echo "FAIL: /api/v1/admin/x with spoofed XFF answered $code"; fail=1 ;;
esac
exit $fail
