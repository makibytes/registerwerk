// k6 load/soak script against the operator API's real, representative read-heavy endpoints —
// the ones an operator dashboard actually hammers (asset list, transaction console, indexer
// admin, org registry), not a synthetic benchmark endpoint.
//
// Usage:
//   docker run --rm -i --network registerwerk_default \
//     -e BASE_URL=http://backend:8080 \
//     -e ADMIN_EMAIL=admin@local -e ADMIN_PASSWORD=changeme-please \
//     grafana/k6 run - < scripts/load/registry-read-load.js
//
// Or against the host-published port from outside the compose network:
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:48080 \
//     -e ADMIN_EMAIL=admin@local -e ADMIN_PASSWORD=changeme-please \
//     grafana/k6 run - < scripts/load/registry-read-load.js
//
// Override VUS/DURATION/RAMP env vars (see `options` below) for a real soak — the defaults here
// are a short smoke-scale run (see the "what this proves" note at the bottom of this file), not a
// soak test in themselves.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:48080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'changeme-please';

const loginFailureRate = new Rate('login_failures');
const readFailureRate = new Rate('read_failures');
const readDuration = new Trend('read_duration', true);

export const options = {
  scenarios: {
    default: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: __ENV.RAMP_UP || '10s', target: Number(__ENV.VUS || 10) },
        { duration: __ENV.HOLD || '30s', target: Number(__ENV.VUS || 10) },
        { duration: __ENV.RAMP_DOWN || '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    // Deliberately loose defaults for a demo-scale single-instance stack, not a production SLO —
    // see docs/operator/maintenance/slo.md for the real target this should eventually graduate
    // toward once run against production-shaped infrastructure.
    http_req_duration: ['p(95)<2000'],
    read_failures: ['rate<0.05'],
    login_failures: ['rate<0.01'],
  },
};

// k6 gives each VU its own module-scope state, but its IMPLICIT default cookie jar is reset at
// the start of every iteration (confirmed by hand against a running backend while writing this
// script: the request immediately after login carried the rw_session cookie, the next one in the
// same VU did not) — so persisting the session across iterations needs an explicitly-created jar
// reused across calls, not reliance on the default one. Logging in once per VU rather than once
// per request is still the point: a per-request login would dominate the load profile with auth
// cost instead of measuring the endpoints this script actually cares about.
let jar = null;
let loggedIn = false;

function login() {
  jar = http.cookieJar();
  const res = http.post(
    `${BASE_URL}/api/v1/public/auth/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    { headers: { 'Content-Type': 'application/json' }, jar }
  );
  const ok = check(res, { 'login succeeded': (r) => r.status === 200 });
  loginFailureRate.add(!ok);
  loggedIn = ok;
}

// Representative read-only GETs — no CSRF token needed (CSRF only guards state-changing
// methods), so these alone are enough to exercise realistic read-path load without also having
// to prime an XSRF-TOKEN cookie the way a mutating request would need.
const READ_ENDPOINTS = [
  '/api/v1/assets?page=0&size=20',
  '/api/v1/transactions?status=FAILED&page=0&size=20',
  '/api/v1/indexers',
  '/api/v1/org-identity/orgs?page=0&size=20',
  '/actuator/health',
];

export default function () {
  if (!loggedIn) {
    login();
    if (!loggedIn) {
      sleep(1);
      return;
    }
  }

  const path = READ_ENDPOINTS[Math.floor(Math.random() * READ_ENDPOINTS.length)];
  const res = http.get(`${BASE_URL}${path}`, { jar });
  readDuration.add(res.timings.duration);
  const ok = check(res, { 'read succeeded': (r) => r.status === 200 });
  readFailureRate.add(!ok);

  sleep(Math.random() * 0.5 + 0.2); // 200-700ms think time, not a tight request loop
}

// What a run of THIS script (as checked into the repo) actually proves: the harness itself
// works end to end against a real running backend, and gives real p95/error-rate numbers at
// whatever VUS/duration you pass it. What it does NOT claim: no multi-hour soak was run as part
// of building this script — VUS/HOLD default to a short smoke-scale configuration specifically so
// `k6 run` finishes in under a minute for a sanity check. A genuine soak (hours, realistic VU
// count derived from expected production traffic) is an operational activity for whoever runs
// this against a real deployment, not something to fabricate a "passing" report for here.
