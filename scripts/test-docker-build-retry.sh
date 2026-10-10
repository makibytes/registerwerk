#!/usr/bin/env bash
# Tests scripts/docker-build-retry.sh against a stub `docker` that plays back a scripted sequence of
# outcomes (ok / transient / real), so no Docker daemon or network is needed.
#   bash scripts/test-docker-build-retry.sh
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir "$work/bin"
cat > "$work/bin/docker" <<'STUB'
#!/usr/bin/env bash
count=$(( $(cat "$STUB_DIR/count" 2>/dev/null || echo 0) + 1 ))
echo "$count" > "$STUB_DIR/count"
echo "$*" >> "$STUB_DIR/args"
case "$(sed -n "${count}p" "$STUB_DIR/plan")" in
  ok)        echo "stub: built"; exit 0 ;;
  transient) echo "ERROR: failed to solve: node:24: unexpected status from HEAD request: 429 Too Many Requests" >&2; exit 1 ;;
  tls)       echo "Get \"https://registry-1.docker.io/v2/\": net/http: TLS handshake timeout" >&2; exit 1 ;;
  denied)    echo "Error response from daemon: toomanyrequests: You have reached your unauthenticated pull rate limit." >&2; exit 1 ;;
  *)         echo "ERROR: process \"/bin/sh -c npm ci\" did not complete successfully: exit code: 1" >&2; exit 1 ;;
esac
STUB
chmod +x "$work/bin/docker"

failures=0
# run_case <name> <expected exit: 0|nonzero> <expected docker calls> <expected output regex or ""> <plan...>
run_case() {
  local name=$1 want_exit=$2 want_calls=$3 want_out=$4
  shift 4
  export STUB_DIR="$work/$name"
  mkdir -p "$STUB_DIR"
  printf '%s\n' "$@" > "$STUB_DIR/plan"
  local out status calls
  out=$(env -u GITHUB_ACTIONS PATH="$work/bin:$PATH" DOCKER_BUILD_PAUSE=0 "$here/docker-build-retry.sh" -t demo . 2>&1)
  status=$?
  calls=$(cat "$STUB_DIR/count" 2>/dev/null || echo 0)
  local ok=1
  if [ "$want_exit" = 0 ] && [ "$status" -ne 0 ]; then ok=0; fi
  if [ "$want_exit" != 0 ] && [ "$status" -eq 0 ]; then ok=0; fi
  [ "$calls" = "$want_calls" ] || ok=0
  if [ -n "$want_out" ] && ! grep -qE "$want_out" <<<"$out"; then ok=0; fi
  grep -qx -- 'build -t demo \.' <(sort -u "$STUB_DIR/args") || ok=0   # the arguments reach `docker build` unchanged
  if [ "$ok" = 1 ]; then
    echo "ok   - $name"
  else
    echo "FAIL - $name (exit $status, docker calls $calls; wanted exit=$want_exit calls=$want_calls out=/$want_out/)"
    echo "$out" | sed 's/^/       | /'
    failures=$((failures + 1))
  fi
}

run_case first_try_succeeds       0       1 ""                                 ok
run_case rate_limit_then_ok       0       2 "retrying in 0s"                   transient ok
run_case tls_timeout_then_ok      0       2 "retrying in 0s"                   tls ok
run_case daemon_pull_limit_then_ok 0      3 "attempt 2/4"                      denied transient ok
run_case real_error_is_final      nonzero 1 "no registry or network error"     real ok
run_case error_after_a_hiccup     nonzero 2 "no registry or network error"     transient real ok
run_case gives_up_after_four      nonzero 4 "after 4 attempts"                 transient transient transient transient ok

[ "$failures" = 0 ] && echo "docker-build-retry tests passed" || { echo "$failures case(s) failed"; exit 1; }
