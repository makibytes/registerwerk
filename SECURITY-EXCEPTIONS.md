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
  allowlist in `indexer/evm/subgraph/scripts/verify-audit-exceptions.mjs` (run by `npm run audit:build-tools` in `.github/workflows/indexers.yml`) that fails when the advisory graph changes.
- **Exit condition:** remove this exception as soon as Graph CLI releases without the affected
  `decompress` dependency.

## Backend OWASP Dependency-Check suppressions

- **Scope:** the `dependency-check` job in `.github/workflows/backend.yml` (`failBuildOnCVSS=7`; it needs
  the `NVD_API_KEY` repository secret, and the NVD database is cached between runs).
- **Where:** `backend/dependency-check-suppressions.xml`, three entries. Each is a CPE false positive,
  scoped to one package URL pattern and its CVE ids (no blanket suppressions):
  - the Azure SDK umbrella CPE matching `azure-core`, `azure-core-http-netty`, `azure-json` and
    `azure-identity` against CVE-2026-33117, which concerns only the Key Vault Keys library
    (the project uses its fixed release);
  - `azure-identity` against CVE-2023-36415 (fixed in 1.10.2; the project uses 1.18.3);
  - `dev.cel:protobuf` (cel-java, not protobuf-java) against CVE-2022-3171 and CVE-2024-7254.
- **Evidence:** each entry carries its reasoning in a `<notes>` block and was checked against the CVE
  record (MITRE) and OSV for the real Maven coordinates.
- **Expiry:** every entry has `until="2027-04-09Z"`, after which the build fails again. Renew only after
  re-checking the CVE record and OSV for the then-current versions; delete the entry once the match is gone.
- **Not suppressed:** real findings are fixed in `backend/pom.xml` instead (`vertx-core`, `kotlin`,
  Tomcat and Jackson overrides); remove an override once the Spring Boot BOM ships the fixed version.
