# Dependency advisory exceptions

Production dependency audits must report no high or critical vulnerabilities. Development and
build tooling follows the same rule unless an upstream package has no patched release and the
exception is both isolated and enforced by CI.

## EVM subgraph Graph CLI

- **Scope:** `indexer/evm/subgraph`, build and deployment tooling only; neither package is shipped
  in a Registerwerk runtime image.
- **Pinned dependency:** `@graphprotocol/graph-cli` 0.98.1.
- **Remaining path:** `@graphprotocol/graph-cli` -> `decompress` 4.2.1.
- **Advisories:** `GHSA-mp2f-45pm-3cg9` and `GHSA-h39j-r5qq-r9mm` concern unsafe extraction of
  attacker-controlled archives. Registerwerk's CI only runs the pinned CLI against repository
  manifests and ABIs; it does not pass untrusted archives to the CLI.
- **Why not `npm audit fix --force`:** npm proposes Graph CLI 0.97.1, a downgrade that still uses
  the affected `decompress` release and therefore does not remove the root cause.
- **Compensating controls:** exact package pins, patched overrides for every other reported Graph
  CLI transitive dependency, production-only audit enforcement, and an executable full-audit
  allowlist in `scripts/verify-audit-exceptions.mjs` that fails when the advisory graph changes.
- **Exit condition:** remove this exception as soon as Graph CLI releases without the affected
  `decompress` dependency.
