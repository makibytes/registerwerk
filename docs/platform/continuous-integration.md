# Continuous integration

Registerwerk's checks run as GitHub Actions workflows in `.github/workflows/`. Most of them are
path-filtered (a backend-only change does not build the frontends) and run for pushes to `main`, `develop`,
`feat/**`, `fix/**` and `review/**` and for pull requests to `main` and `develop`.

| Workflow | What it guards |
|---|---|
| Backend CI | Unit and integration tests with the JaCoCo coverage gate, Canton-profile tests, a Trivy scan of the production image, OWASP Dependency-Check in its own job, destructive-migration check |
| Frontend CI | Operator and customer portal lint, tests, production build and image scan; lint of the shared UI library |
| Contracts CI | `forge test`, formatting, contract-size gate, coverage report, Slither |
| Indexers CI | EVM subgraph: ABI parity with the Forge artifacts, manifest rendering, codegen, build, npm audit |
| Cairo Contracts CI / Daml (Canton) CI | Starknet and Canton contract builds and tests |
| Zama Relayer CI | Relayer lint, tests, image build and scan |
| Documentation | Strict MkDocs build of the real docs image and the headless-browser checks against it |
| Docs generated pages and translation parity | Generated step-up matrix is current; translations do not fall behind English |
| Infra Config CI | Compose files, Kong config (and its Helm copy), Helm charts, Prometheus rules, `actionlint` for every workflow, the Docker build scripts |
| Secret Scanning | gitleaks over the full history (reviewed false positives are fingerprinted in `.gitleaksignore`) |
| Claims registry pins | The claims verifier, its tests and the re-pin tool on every push and pull request (see below) |
| Claims governance | The same verifier plus the heavy evidence commands; `main`, `feat/**`, `fix/**`, `release/**` and pull requests to `main` |
| Container Build & Sign | Builds, scans, pushes and signs the images; `main` only, once Backend CI, Frontend CI, Contracts CI and Secret Scanning have succeeded for the same commit |

## Hash checks: what is compared, and why a pin goes stale

CI compares SHA-256 values in exactly three places, and none of them is an image digest. Container
images are referenced by tag (`node:24.19.0-alpine`), so CI does not compare image hashes at all.
The `sha256:` lines in a failed Docker log are BuildKit printing layer ids while it pulls.

| Check | Pinned value | Why |
|---|---|---|
| gitleaks download | the release tarball's SHA-256 in `secret-scanning.yml` | A tampered download must not run |
| `docs/claims/registry.schema.json` | `EXPECTED_SCHEMA_SHA256` in `scripts/verify-claims.mjs` | The verifier implements one closed schema version |
| Claims evidence files | `evidence[].sha256` in `docs/claims/registry.json` | A claim states which exact file reproduced it ([claims control](../claims/README.md)) |

These values are reproducible: the same bytes always give the same hash, and `.gitattributes` fixes line
endings so a Windows checkout cannot change them. The evidence pins are the ones that fail in practice,
and only because the pinned file was edited: for example `contracts/src/standards/erc3525/ERC3525.sol`,
`contracts/test/EwpgERC3525Test.t.sol` or `indexer/evm/subgraph/scripts/validate-abi-parity.mjs`. That is
the intended behaviour (the evidence no longer shows what was reviewed), so the check is kept, but it is
made cheap:

* **Early.** "Claims registry pins" runs on every push and pull request, so the commit that edits a pinned
  file is the commit that turns red, instead of `main` a few days later.
* **Actionable.** The failure names the file and both hashes.
* **One command to renew.** After the file is final, run the evidence command and re-pin:

```bash
node scripts/repin-claims.mjs                 # dry run: lists stale pins, exit 1 if there are any
node scripts/repin-claims.mjs --write --run   # runs the affected evidence commands, then re-pins
git diff docs/claims/registry.json
```

The tool changes only the pins, the record hash of the affected claims and `registryRevision`. It never
touches a statement, status, reviewer or date, and it is not a review: the repository owner still approves
the registry diff. Do not re-pin a file you did not intend to change.

## Docker Hub rate limits

GitHub-hosted runners share egress addresses, and anonymous Docker Hub pulls are limited per address
(`429 Too Many Requests` / `toomanyrequests`, before the first Dockerfile instruction runs). Three measures:

* `scripts/docker-hub-mirror.sh` points the runner's Docker daemon at Google's pull-through cache,
  `mirror.gcr.io`, with a daemon *reload*. A restart would recreate the `docker0` bridge, which Chrome
  reports as `net::ERR_NETWORK_CHANGED` in the docs browser checks.
* `scripts/docker-build-retry.sh` wraps `docker build` and retries (up to four attempts, growing pause)
  only when the output shows a registry or network error. A Dockerfile or compile error fails on the first
  attempt. `scripts/test-docker-build-retry.sh` tests those rules against a stub `docker`.
* Images that are not built from a Dockerfile are addressed explicitly: the Postgres service container in
  `backend.yml` is `mirror.gcr.io/library/postgres:…`, and the backend test step sets
  `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=mirror.gcr.io/` so Testcontainers (Postgres, MinIO, Ryuk) pulls
  through the cache too.

!!! note "Authenticated pulls"
    A Docker Hub read-only access token stored as a repository secret and used with `docker login`
    raises the limit far above the anonymous one and would remove this class of failure entirely. The
    workflows do not use one today.

## Where toolchain versions are pinned

A version that several files must agree on is kept in one place where the platform allows it, and
listed here where it cannot be:

| Tool | Single source | Other places to bump with it |
|---|---|---|
| Node 24.19.0 | `.nvmrc` (every `actions/setup-node` step reads it) | `node:24.19.0-alpine` in `docs/Dockerfile`, both frontend Dockerfiles and `zama-relayer/Dockerfile` (the `engines` fields say `24.x` and need no change) |
| Foundry v1.8.5 | `.github/actions/setup-foundry` | `contracts/Dockerfile.demo`, the anvil image in `docker-compose.yml`, `GraphNodeReorgIT` |
| PostgreSQL 18.6 | `TestPostgres.IMAGE` | `docker-compose.yml`, `postgres-wal/Dockerfile`, the service image in `backend.yml` |
| Trivy v0.73.0 | repeated in each scanning step | all steps that scan an image |

The contracts' Solidity libraries are git submodules under `contracts/lib/`. CI initialises only those
(the `.github/actions/contract-libs` action) because `docs/_chaincache` is a private repository the
workflow token cannot read. `contracts/Dockerfile.demo` fetches the same commits by hash, so its refs
must move with the gitlinks.

## Running the checks locally

```bash
node scripts/verify-claims.test.mjs && node scripts/repin-claims.test.mjs && node scripts/verify-claims.mjs
bash scripts/test-docker-build-retry.sh
node scripts/verify-docs-parity.mjs && node scripts/gen-stepup-matrix.mjs --check
docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7      # every workflow
```

The backend, frontend, contract and docs checks have their own commands in `CLAUDE.md`.
