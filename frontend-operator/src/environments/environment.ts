export const environment = {
  production: false,
  testEnvironment: true,
  // The operator portal bypasses Kong entirely (see CLAUDE.md / frontend-operator/nginx.conf) —
  // it must point straight at the backend, never at Kong's host port 48000. Kong IP-restricts
  // /api/v1/admin/** to operator-network CIDRs (see gateway/kong.yml), and a browser hitting
  // Kong directly in dev arrives from a Docker-bridge/host IP that isn't on that allowlist,
  // producing "Access denied by IP restriction policy." on every admin-console call.
  apiUrl: 'http://localhost:48080/api/v1',
  customerUrl: 'http://localhost:44201',
  // Base URL of a chaincheck instance (node-fleet monitor, a sibling product) to deep-link a
  // chaincache node row to, e.g. 'http://localhost:48090'. Empty means no deep-link is shown —
  // chaincheck is an independent product Registerwerk never requires.
  chaincheckUrl: '',
  // A CHAINCACHE-kind node's managementUrl is the origin the *backend* uses to reach Chaincache
  // (RestClient probes, docker-compose service DNS) — in a real deployment that's typically also
  // browser-reachable (real internal DNS), but in this repo's docker-compose demo stack it's the
  // Compose-internal service name (e.g. "chaincache-sepolia:8080"), which a host browser cannot
  // resolve at all. This maps such internal origins to their host-published equivalent purely for
  // the operator-portal deep link; it never affects any backend-to-Chaincache call. Empty/absent
  // origins fall through to managementUrl unchanged, which is the correct behavior for a real
  // deployment with a genuinely browser-reachable managementUrl.
  chaincacheConsoleOriginOverrides: {
    'chaincache-sepolia:8080': 'http://localhost:48090',
    'chaincache-base:8080': 'http://localhost:48091'
  } as Record<string, string>
};
