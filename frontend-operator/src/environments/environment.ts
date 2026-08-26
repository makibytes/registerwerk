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
  chaincheckUrl: ''
};
