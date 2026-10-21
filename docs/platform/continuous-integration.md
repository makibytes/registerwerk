# Continuous integration

Registerwerk's checks run as GitHub Actions workflows in `.github/workflows/`. Most of them are
path-filtered (a backend-only change does not build the frontends) and run for pushes to `main`, `develop`,
`feat/**`, `fix/**` and `review/**` and for pull requests to `main` and `develop`.

| Workflow | What it guards |
|---|---|
| Backend CI | Unit and integration tests with the JaCoCo coverage gate, Canton-profile tests, a Trivy scan of the production image, OWASP Dependency-Check in its own job (NVD data from the project's mirror, see below), destructive-migration check |
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

## Where the OWASP Dependency-Check job gets its vulnerability data

The job reads the NVD from the Dependency-Check project's own mirror, a data feed that
[github.com/dependency-check/DependencyCheck_Builder](https://github.com/dependency-check/DependencyCheck_Builder)
rebuilds from the NVD every few hours and serves from GitHub Pages (`nvdDatafeedUrl` in `backend.yml`). It does not
call the NVD REST API: a full download through the API needs about 410,000 records, delivered at roughly 2,000 a
minute in October 2026, so it overran a two-hour limit with half of them, and the plugin's maintainers describe the
slowness as upstream and not fixable from the client. The mirror is about 200 MB and a cold run from an empty
database takes minutes. No NVD API key is used.

- **Freshness.** A step reads the mirror's `cache.properties` first and fails the job if the data is more than seven
  days old (it warns after three), so a mirror that stopped updating cannot make the scan pass against stale data.
- **Caching.** The database is cached per ISO week and plugin version, so a run only fetches what changed since. GitHub
  scopes caches to a branch and to the default branch, so the first run on a new branch starts cold; that now costs
  minutes, not hours.
- **Retries.** A run that ends before it wrote its report (the mirror was unreachable) is retried up to three times; a
  run that wrote a report and still failed is the CVSS 7 gate and is not retried.
- **Trust.** The mirror is run by the project that also publishes the plugin this job executes, and each file carries a
  SHA-256 the plugin verifies. If you would rather not depend on it, mirror the NVD yourself with the project's `vulnz`
  tool and point `NVD_FEED_URL` at your copy; the `{0}` placeholder is replaced by a year or `modified`.

## Token permissions

Every workflow starts with a read-only `GITHUB_TOKEN` (`contents: read`). A job asks for more only where it
needs it: `security-events: write` for the jobs that upload SARIF to code scanning (the image scans, Slither,
CodeQL), `actions: read` for the gate in Container Build & Sign, `packages: write` and `id-token: write` for the
jobs that push and sign images, and `contents: write` for the job that attaches the SBOM to a release. A new
job inherits nothing beyond reading the repository.

## Hash checks: what is compared, and why a pin goes stale

CI compares SHA-256 values in exactly three places, and none of them is an image digest. Container
images are referenced by tag (`node:26.12.0-alpine`), so CI does not compare image hashes at all.
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

## The docs browser check and network changes

The Documentation workflow starts the docs image as a container and drives it with headless Chromium
(`docs/scripts/verify-browser.mjs`). Chromium watches the host's network events and aborts every request that
is in flight when they change, reporting `net::ERR_NETWORK_CHANGED` on the console. Docker causes such events when it
restarts (see the mirror script above) and also when it merely starts a container: a new veth interface, `docker0`
coming up and address assignment over the following seconds. Started two seconds before the browser, as it was
at first, the container made about one run in ten fail with nothing wrong in the docs (five of the last eight
failures of the workflow). Two rules now apply:

* **Container first, browser last.** The workflow starts the container, then installs the browser (~20 s of
  otherwise idle network), and only then runs the check. A new step that starts a container belongs *before* the
  browser install, not after.
* **A network change is retried, nothing else is.** `docs/scripts/lib/network-retry.mjs` runs the whole check again,
  on a fresh browser, up to three times, when the only errors an attempt saw are `ERR_NETWORK_CHANGED` (or an
  attempt failed after the browser reported one). Any other console error or failed assertion fails at once. Each
  retry prints a `::warning::` naming the failed request, so a flap is visible without failing the run. The policy
  has unit tests (`npm --prefix docs run test:unit`) that run in the workflow before the image is built.

## Where toolchain versions are pinned

A version that several files must agree on is kept in one place where the platform allows it, and
listed here where it cannot be:

| Tool | Single source | Other places to bump with it |
|---|---|---|
| Node 26.12.0 | `.nvmrc` (every `actions/setup-node` step reads it) | `node:26.12.0-alpine` in `docs/Dockerfile`, both frontend Dockerfiles and `zama-relayer/Dockerfile` (the `engines` fields say `26.x` and need no change for a patch release) |
| Foundry v1.8.5 | `.github/actions/setup-foundry` | `contracts/Dockerfile.demo`, the anvil image in `docker-compose.yml`, `GraphNodeReorgIT` |
| PostgreSQL 18.6 | `TestPostgres.IMAGE` | `docker-compose.yml`, `postgres-wal/Dockerfile`, the service image in `backend.yml` |
| Trivy v0.76.0 | `.github/actions/trivy-image-scan` (every image scan and its SARIF upload) | none |
| Kong 3.9.4 | `docker-compose.yml` | `gateway/docker-compose.kong.yml`, `kong.image.tag` in the Helm `values.yaml` (the Kong subchart otherwise defaults to an old image), the two `kong config parse` jobs in `infra.yml` |
| Valkey 9.1.2 | `docker-compose.yml` | `gateway/docker-compose.kong.yml`, `valkey.image.tag` in the Helm `values.yaml` |
| nginx 1.30.6 (stable line) | `frontend-operator/Dockerfile` | `frontend-customer/Dockerfile`, `docs/Dockerfile`, the comments in the Helm `values.yaml` that name it |
| Go 1.27.2 and WAL-G v3.0.9 | `postgres-wal/Dockerfile` | none (WAL-G publishes no images, it is built from the tag) |
| Mermaid 12.2.0 | `docs/package.json` | the vendored file name in `docs/Dockerfile` and `docs/overrides/main.html` |

The contracts' Solidity libraries are git submodules under `contracts/lib/`. CI initialises only those
(the `.github/actions/contract-libs` action) because `docs/_chaincache` is a private repository the
workflow token cannot read. `contracts/Dockerfile.demo` fetches the same commits by hash, so its refs
must move with the gitlinks.

## Running the checks locally

```bash
node scripts/verify-claims.test.mjs && node scripts/repin-claims.test.mjs && node scripts/verify-claims.mjs
bash scripts/test-docker-build-retry.sh
node scripts/verify-docs-parity.mjs && node scripts/gen-stepup-matrix.mjs --check
docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12      # every workflow
npm --prefix docs run test:unit                                       # retry policy of the docs browser check
```

The backend, frontend, contract and docs checks have their own commands in `CLAUDE.md`.
