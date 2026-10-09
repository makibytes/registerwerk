# Registerwerk — AI Context

Reference implementation for an electronic-securities registry that models issuance and administration of tokenized assets across blockchains. Two user groups: **Operators** (registry staff) and **Customers** (issuers/investors). Multi-tenant: one operator deployment serves many customer legal entities. Repository behavior is not evidence of eWpG compliance, regulatory authorisation, legal effect, or production readiness.

---

## Monorepo Structure

```
backend/              Spring Boot 4.1 / JDK 25 — single API monolith (Spring Modulith 2.1)
contracts/            Foundry smart contracts (EVM + confidential)
frontend-operator/    Angular 22 — operator admin portal (:44200)
frontend-customer/    Angular 22 — customer portal (:44201)
gateway/              Kong declarative config
indexer/              Off-chain event indexers (EVM + Solana)
docker-compose.yml    / .env.example
```

---

## Key Architecture Decisions

- **Operator frontend bypasses Kong** — nginx proxies `/api/` directly to `backend:8080`. Uses built-in HS256 JWT login (`POST /api/v1/public/auth/login`).
- **Customer frontend's API calls go through Kong** (the frontend itself is always opened directly at `:44201`) — Kong adds rate limiting/caching/security headers only; JWT validation and entity/role extraction happen in the backend itself (`SecurityConfig` + `EntraPrincipalNormalizationFilter`), not via a Kong-injected header. Kong's `openid-connect` plugin is Enterprise/Konnect-only and not active in this repo's OSS setup (`gateway/plugins/oidc-entra.yml` is a ready-to-merge snippet for anyone who is running Enterprise/Konnect).
- **Backend is a pure resource server** — stateless JWT validation; does not issue OIDC tokens.
- **Auth toggle:** `ENTRA_ENABLED=false` → HS256 dev mode with `JWT_DEV_SECRET`; `=true` → Entra sign-in for customers, validated by the backend. `DelegatingJwtDecoder` routes on the JWS `alg` header (HS256 → local, else JWKS), so the operator portal keeps built-in login and local TOTP step-up in both modes. Both branches are issuer-pinned; the OIDC branch is also audience-pinned via `JWT_AUDIENCE`.
- **Two-factor auth** is Entra's, not ours: Graph cannot create an Authenticator/TOTP method, so enrolment happens on Microsoft's security-info page and `/security` guides users there. Conditional Access enforces it at sign-in — no app-side gate. Step-up is dual-track: local TOTP (403) in HS256 mode, `acrs` + a 401 claims challenge in Entra mode. Runbook: `docs/platform/entra-setup.md`.
- **Dual control (four eyes)** — a second, different, enabled `REGISTRY_ADMIN` or `COMPLIANCE_OFFICER` approves sharp operations (`@RequiresStepUp(requireSecondApprover = true)` plus the `DualControlGate` reasons; generated list: `docs/compliance/step-up-matrix.md`). The approver's token is `use=dual_control` (audience `registerwerk-dual-control`), valid only in the `X-Dual-Control-Token` header — it is refused as a Bearer on every endpoint — and is bound to the action (reason) and a digest of the exact target (method, path, query and canonical JSON body), single use, with a 300 s window (`registerwerk.auth.step-up.dual-control.window-seconds`). The body is bound for every reason except the five in `body-opt-out-reasons` (secret-bearing or non-JSON payloads; their method/path/query stay bound). The initiator sends the real request with their *own* step-up token as Bearer. Until two enabled, TOTP-enrolled admins exist a write-once bootstrap latch lets one step-up suffice. Duplicate JSON keys, repeated query parameters and numbers that are not exact decimals are refused when the digest is built.
- **In-app approval queue** (`/api/v1/approvals`, `stepup` module, both portals): the initiator files `{action, method, path, query, body}`, an eligible approver sees it in the inbox and approves with their own fresh TOTP, the initiator *claims* the single-use token (the claimed token also carries the initiator id, so it is useless to anyone else) and retries the original request. Self-approval is impossible (DB constraint); requests expire after 15 min (`registerwerk.auth.step-up.approval-queue.*`). The copy-the-block flow (`POST /api/v1/auth/step-up` with `action`/`target`/`targetBody`) still works.
- **Production mode has one switch**: `shared.ProductionMode` (`REGISTERWERK_PRODUCTION_MODE` / `registerwerk.production-mode`, resolved from the Spring `Environment`). Never read the variable with `System.getenv` or `@Value` — inject `ProductionMode`. It turns the readiness checks into start-up refusals (`docs/operator/security/production-mode.md`) and enables the production-only behaviour: impersonation is read-only only, lenders need an eligible entity, peer listings are refused while the venue is `DEMO_ONLY`, forward-priced vaults need a dealing cut-off.
- **Impersonation**: operator starts it with step-up + a mandatory reason; `READ_ONLY` by default, `ACT_ON_BEHALF` exists in demo mode only (second approver + deny-list). The `SUPPORT_AGENT` role may start read-only sessions and nothing else.
- **Shared money/time/unit helpers** (use them, do not add local copies): `shared.Money` (ISO 4217 minor units, `HALF_UP`, unknown currency refused), `shared.RegisterClock` (the register's calendar day in `registerwerk.register.time-zone`, default `Europe/Berlin`), `deployment.api.RegisterUnits` (the register counts **whole units**: ERC-20/3643/721/1155/3525, SPL and Daml bond tokens deploy with `decimals = 0`, and coupon, redemption, subscription and trading flows refuse — 409 — an asset whose live deployment does not report 0 decimals, e.g. Starknet ERC-20 (fixed 18) and Stellar assets (7)).
- **Single PostgreSQL instance** — always hosts the `registerwerk` database, plus a `chaincache` database (created by `postgres-init/01-create-chaincache-db.sql`) when the optional `chaincache-true` profile is enabled — chaincache does not get its own dedicated Postgres container, mirroring how a managed instance (Cloud SQL on GKE) hosts multiple databases in production. Kong runs DB-less (`gateway/kong.yml` loaded via `KONG_DECLARATIVE_CONFIG`), so it has no database of its own — there is no `kong` or `konga` database/service in this stack.

---

## Backend

**Stack:** JDK 25 (required; `java.version` is 25 — set `JAVA_HOME` to a 25 JDK before `./mvnw`), Spring Boot 4.1, Spring Security 7, Spring Modulith 2.1, JPA/Hibernate, Flyway, Caffeine (30s TTL; only the `assets` cache is wired to a read path — see `CacheConfig`'s javadoc for why `deployments`/`entities` deliberately aren't yet), Jackson 3 (tools.jackson), Web3j (EVM), Solanaj (Solana), Daml Java bindings (Canton), plus native Starknet (Cairo/STARK ECDSA) and Stellar (Horizon/Ed25519) client code — see `blockchain/internal/deploy/`.
Build: `./mvnw verify` — runs unit + integration tests + JaCoCo. Coverage gate: bundle LINE ≥ 0.36 / BRANCH ≥ 0.23, plus stricter per-package floors (e.g. `customer/internal` 0.60/0.40, `registertransfer/internal` 0.85/0.70) — check `pom.xml` before adding code to those packages.
**Lazy datasource:** `spring.datasource.connection-fetch=lazy` — defers physical DB connection until first SQL statement.

**Package root:** `de.makibytes.registerwerk` — organized as **vertical Spring Modulith modules** (boundaries enforced by `ModulithArchitectureTest`). Each module follows the same convention:

| Subpackage | Contents |
|---|---|
| `api/` | Public entities, repositories, ports (`@NamedInterface`) — the only cross-module surface |
| `internal/` | Package-private services and jobs |
| `web/` + `web/dto/` | REST controllers + request/response records |
| `events/` | Domain events (`@NamedInterface`; implement `audit.api.AuditableEvent` for zero-wiring audit) |

Modules (39, every top-level package carries `@ApplicationModule`): `accessreview`, `admin`, `asset`, `audit`, `auth`, `blockchain`, `bootstrap`, `chain`, `corporateactions`, `customer`, `deployment`, `dora`, `endpoint`, `entra`, `erc3643`, `externalref`, `finality`, `idempotency`, `indexer`, `infrastructure`, `kyc`, `lending`, `marketplace`, `notification`, `onboarding`, `orgidentity`, `payment`, `registerstatement`, `registertransfer`, `regreporting`, `repo`, `screening`, `shared`, `stepup`, `support`, `trading`, `travelrule`, `wallet`, `webhook` (one line each in `docs/platform/modules.md`). Cross-module checks use fail-closed ports (`screening.ScreeningGate`, `orgidentity.PermissionGate`).

**URL auth:**

| Pattern | Auth |
|---|---|
| `/api/v1/public/**` | No |
| `/api/v1/onboarding/token-info/**`, `/onboarding/complete` | No |
| `/api/v1/**` | JWT required |

**Error handling:** `GlobalExceptionHandler` maps to `ErrorResponse(status, message, timestamp, path)`.
`EntityNotFoundException`→404, `InvalidCredentialsException`→401, `AccessDeniedException`→403, `InvalidStateTransitionException`→409, `IllegalArgumentException`→400.

**Audit:** every state-mutating operation emits via `AuditEventPublisher` → `audit_event` table (partitioned).

**Coding conventions:**
- Domain entities in `domain/` — no Spring deps
- Services in `application/` — business logic; never depend on controllers or DTOs
- Controllers in `web/` — call services, no business logic
- DTOs are Java `record` types with Bean Validation annotations
- `@Transactional` at service method level, not on repositories
- `@PreAuthorize("hasRole('REGISTRY_ADMIN')")` on controllers/methods
- Flyway: baseline `V1__initial_schema.sql` plus later `V{n}__description.sql` files (the directory is the list; the baseline is re-squashed from time to time, so do not hard-code migration numbers in docs or code). Do not edit migrations after release. CI (`scripts/check-destructive-migrations.sh`, wired into `backend.yml`) rejects unguarded `DROP TABLE`, `DROP COLUMN`, and `TRUNCATE` statements; acknowledge an intentional destructive change with `-- migration-safety: ack (<why>)` directly above it.
- Emit audit events in every state-changing service method
- Amounts and dates: `Money` for rounding/minor units, `RegisterClock` for "today", `RegisterUnits` before any flow that turns register units into money or a mint
- Roles (`auth.api.AppUserRole`): `REGISTRY_ADMIN`, `AUDIT`, `COMPLIANCE_OFFICER`, `RELATIONSHIP_MANAGER` (read-only, assigned entities), `SUPPORT_AGENT` (read-only impersonation), `ISSUER`, `INVESTOR`, `COMPANY_ADMIN`, `TRADER`, `DAPP_PUBLISHER`. There is no second-approver role: it is a capability of `REGISTRY_ADMIN`/`COMPLIANCE_OFFICER`. Roles live in the `app_user` row, never in the IdP
- DB logins: Flyway runs as the schema owner, the application runs as the DML-only `registerwerk_app` role (see the env table)
- Signing custody: software keystores (KEK-wrapped DEKs; `rotate-kek`/`rotate-kek-all`), a PKCS#11 HSM, or a cloud-KMS signer (`registerwerk.wallet.signer=kms`, GCP only; attach with `POST /api/v1/admin/wallets/attach-kms`)

---

## Frontend (both apps)

**Angular 22, standalone components, native zoneless change detection.** Angular 22 is zoneless
by default, so neither Zone.js nor an explicit `provideZonelessChangeDetection()` provider is used.

**Critical patterns:**
- `standalone: true` everywhere — no NgModules
- `@if / @else / @for` control flow — never `*ngIf` / `*ngFor`
- `inject()` preferred over constructor injection
- **Zoneless CDR:** after any `subscribe()` callback that mutates component state, call `cdr.markForCheck()` — Angular won't auto-detect without Zone.js
- API services in `core/api/`, return `Observable<T>` via `HttpClient`
- Auth interceptor attaches `Authorization: Bearer <jwt>`
- Error handling: `MatSnackBar` (operator) or inline error state (customer)

**Design tokens:** `--rw-*` namespace in `styles.scss`. Never hardcode colors in component styles.

| Identity | Key tokens |
|---|---|
| Operator | `--rw-sidebar-bg: #07091A`, `--rw-accent: #F59E0B` (amber), IBM Plex Mono for addresses |
| Customer | `--rw-nav-bg: #111827`, `--rw-accent: #0D9488` (teal) |
| Both | Font: **Manrope**; Angular Material M3 (operator: indigo palette, customer: teal palette) |

**Shared UI library:** `frontend-shared/` (`@registerwerk/ui`) is compiled by whichever app imports it, so its `node_modules` must point at *that app's* single copy of Angular. It is an untracked symlink, re-pointed by `frontend-shared/link-node-modules.mjs` from the `pre*` npm hooks (`prestart`/`prebuild`/`pretest`/`prelint`) and by the Dockerfiles. Never commit it, and never build with a link to the other app: two Angular copies crash at runtime inside shared components, a dangling link fails the build with NG2012.

**Support session (customer portal `/select-company`):** step-up -> `POST /api/v1/impersonation` -> `POST /api/v1/public/auth/impersonate` lives in `AdminService.startReadOnlySession`; the picker renders failures inline (`SUPPRESS_ERROR_TOAST` keeps the global toast away). Step-up refusals carry a `code` (`STEP_UP_ENROLMENT_REQUIRED`, `STEP_UP_CODE_INVALID`, `STEP_UP_CODE_REPLAYED`, `STEP_UP_LOCKED`; `CodedAccessDeniedException`). Regression nets: `impersonation-flow.spec.ts` (real services, mocked network), `StepUpEnrollmentIT`, and the `impersonation` journey in `docs/scripts/verify-frontends.mjs` (`VERIFY_ONLY=impersonation`). The built-in bootstrap admin has no authenticator, so it cannot start a session until it enrols one in the operator portal; the demo's `dual-control.admin@registerwerk-demo.internal` is pre-enrolled.

**Operator structure:** sidebar + topbar layout; `ShellComponent` wraps all guarded routes.
**Customer structure:** sticky top nav; impersonation bar shown for `REGISTRY_ADMIN` and `SUPPORT_AGENT` users (always, not only when actively impersonating; read-only sessions show a banner).

---

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `REGISTERWERK_PRODUCTION_MODE` | `false` | The single production switch (`shared.ProductionMode`): readiness checks refuse start-up instead of warning; read-only impersonation only; see `docs/operator/security/production-mode.md` |
| `ENTRA_ENABLED` | `false` | `false` → built-in login everywhere; `true` → Entra sign-in for customers (operators keep built-in login) |
| `DEFAULT_ADMIN_EMAIL` / `_PASSWORD` | — | Seeds built-in admin `app_user` row |
| `JWT_DEV_SECRET` | `registerwerk-dev-jwt-secret-…` | HS256 signing key — must never be empty |
| `JWT_ISSUER_URI` | blank | OIDC issuer URI; blank → HS256 mode |
| `JWT_AUDIENCE` | blank | Expected `aud` of Entra tokens. Blank = any app in the tenant is accepted — required in production |
| `ENTRA_TENANT_ID` / `_CLIENT_ID` / `_CLIENT_SECRET` | blank | API app registration + app-only Graph credential |
| `ENTRA_SPA_CLIENT_ID` / `ENTRA_API_SCOPE` | blank | Served to the browser by `GET /api/v1/public/auth/config` |
| `ENTRA_SUPPORT_ENABLED` | `false` | Master switch for all Microsoft Graph calls (2FA status + operator support console) |
| `ENTRA_STEPUP_AUTH_CONTEXT_ID` | blank | Conditional Access auth context (c1–c99) for step-up; must be *published to apps* |
| `REGISTERWERK_WALLET_SIGNER` | blank | `kms` → EVM signing keys live in a cloud KMS (GCP Cloud KMS `EC_SIGN_SECP256K1_SHA256` via ADC/workload identity; `REGISTERWERK_WALLET_KMS_PROVIDER`/`_KEY_VERSION`/`_TIMEOUT`/`_MAX_ATTEMPTS`); counts as the production HSM/KMS target; wallets enrolled via `POST /api/v1/admin/wallets/attach-kms` |
| `DB_URL` | `jdbc:postgresql://postgres:5432/registerwerk` | |
| `DB_USER` / `DB_PASSWORD` | `registerwerk` / — | Schema **owner** login; used only by Flyway (`SPRING_FLYWAY_USER/PASSWORD`) |
| `DB_APP_USER` / `DB_APP_PASSWORD` | `registerwerk_app` / — | Runtime login, DML only (no ownership/DDL, no UPDATE/DELETE/TRUNCATE on `audit_event`); falls back to `DB_USER` when unset (un-split dev only — production mode refuses it) |
| `REGISTERWERK_AUDIT_ANCHOR_SINK` | `none` | `s3` writes the daily signed audit anchor to an S3 Object Lock bucket (`REGISTERWERK_AUDIT_ANCHOR_S3_*`) |

---

## Development

### Mandatory final browser verification

After any frontend, documentation UI, authentication, routing, CSS, icon/font, or user-facing workflow change, always run final smoke checks in **headless Google Chrome/Chromium** against the built, running containers. Exercise every affected role and view (including impersonation when relevant), verify navigation and primary actions, and fail the check on uncaught page exceptions, browser console errors, failed same-origin API requests, or missing/broken visual assets. Capture screenshots for visual review; Angular compilation and API-only tests are not substitutes for this browser check. Record the checked routes and result in the final response. If Chrome cannot run in the environment, state that explicitly instead of claiming the UI was verified.

With the local demo stack running, execute `cd docs && npm run test:frontends && npm run test:browser`. The frontend suite uses the seeded trader and local operator accounts, writes desktop/mobile screenshots to `/tmp/registerwerk-headless`, and accepts `CUSTOMER_BASE_URL`, `OPERATOR_BASE_URL`, credential, and screenshot-directory environment overrides.

```bash
docker compose up --build                    # full stack
docker compose up --build frontend-operator  # rebuild one service
cd backend && ./mvnw verify                  # tests + coverage
cd frontend-operator && npm start            # :44200
cd frontend-customer && npm start            # :44201
cd contracts && forge test -vvv
cd contracts/cairo && scarb build && snforge test   # Cairo (Starknet) contracts
cd daml && dpm build                                # Daml (Canton) bond templates — SDK via dpm
docker compose --profile docs up                    # docs server :48003
docker compose -f docker-compose.yml -f docker-compose.wal.yml up -d postgres   # opt-in WAL-G archiving (PITR); `--profile backup` adds the base-backup job
scripts/pitr-drill.sh                               # real PITR drill on throwaway containers (never the demo DB)
```

**Documentation** is MkDocs Material (`mkdocs.yml` + `docs/`). The `docs` compose profile builds the site into a **static nginx image** (`docs/Dockerfile`) rather than running `mkdocs serve` — so doc changes need a rebuild to show up:

```bash
git submodule update --init docs/_chaincache             # canonical Chaincache docs
docker compose --profile docs up --build docs      # http://localhost:48003
```

Normal builds deliberately fail when `docs/_chaincache` is missing. CI has no cross-repository
credential and explicitly sets `ALLOW_MISSING_CHAINCACHE_DOCS=1`, which builds marked placeholder
pages while retaining the same fifth-path routes and browser checks.

Build it strictly before committing doc changes (CI enforces this via `.github/workflows/docs.yml`). Use the image built from `docs/Dockerfile`, not the bare `squidfunk/mkdocs-material` image — this site needs `mkdocs-static-i18n` (see `mkdocs.yml`'s `plugins:` list), which the bare image doesn't have; running `--strict` against it fails immediately with `Config value 'plugins': The "i18n" plugin is not installed`, not a real link-check failure:

```bash
docker build -f docs/Dockerfile -t registerwerk-docs:local .
```

`docs/Dockerfile` runs `mkdocs build --strict` during `docker build`; its final image is nginx, so
do not append `build --strict` to `docker run` (nginx has no such command). For live authoring
with reload, build the MkDocs stage explicitly and run its `serve` command:

```bash
docker build --target build -f docs/Dockerfile -t registerwerk-docs:authoring .
docker run --rm -p 48003:8000 -v $PWD/mkdocs.yml:/docs/mkdocs.yml:ro -v $PWD/docs:/docs/docs:ro \
  registerwerk-docs:authoring serve -a 0.0.0.0:8000
```

MkDocs builds a page for every Markdown file under `docs_dir`; the explicit `nav:` only controls the sidebar. Its live-reload watcher also scans the complete tree regardless of `exclude_docs`, so keep generated and dependency directories out of `docs/`. Use MkDocs admonition syntax (`!!! note`).

---

## Smart Contracts (`contracts/`)

Foundry; libs are pinned git submodules under `contracts/lib/` (`git submodule update --init` after clone). `AssetTokenFactory` uses `CREATE2` for deterministic pre-computed addresses. Standards: `EwpgERC20/721/1155/3643`, confidential variants (Zama fhEVM). Contract addresses stored in `registerwerk.contracts.*` config post-deployment.

## Ecosystem (onchain identity, permissions, dApp marketplace)

SWIAT-style institutional ecosystem in `contracts/src/ecosystem/` + backend modules `orgidentity` / `marketplace`:

- **OrgRegistry** — wallet→org bindings (org = its ONCHAINID address; one org per wallet per chain; org-scoped role hashes). Dual auth: operator `OPERATOR_ROLE` or an ERC-734 MANAGEMENT key on the org's ONCHAINID.
- **PermissionRegistry** — operator grants permissions (`keccak256("<slug>.<action>")`) to orgs; org admins delegate to member roles / set role restriction.
- **EcosystemTrustedIssuersRegistry** — issuers trusted per claim topic (1=KYC, 2=AML, 3=Accreditation).
- **DappRegistry** — anchors approved marketplace manifests (keccak256 of raw bytes) + opt-in instance attestation.
- **PermissionOracle** — the single stable facade dApps store; customer dApps inherit `RegisterwerkGated` (`requiresPermission` / `requiresClaim` / `requiresActiveMember`). Minimal example: `test/ecosystem/SampleGatedDapp.t.sol`.

Marketplace = metadata-only listings: signed manifests (EIP-191 `personal_sign` over the 0x-hex *string* of `keccak256(manifest_raw_bytes)` — not the raw hash bytes; schema `backend/src/main/resources/schemas/dapp-manifest.schema.json`), container images pinned by OCI digest, operator review with step-up + 4-eyes, onchain anchoring on approval. Wallet binding uses nonce challenges + `personal_sign`. New role: `DAPP_PUBLISHER`. Config keys: `registerwerk.contracts.{org-registry,permission-registry,ecosystem-tir,permission-oracle,dapp-registry}.*` (deploy via `script/DeployEcosystem.s.sol`). Developer guide: `docs/platform/dapp-development.md`.

**Payment rails** (backend module `payment`, `/api/v1/payment-rails`): the operator curates payment methods for the cash leg — stablecoins, the Pontes instant-payment API, ERC-7573-style DvP settlement (`contracts/src/settlement/DvpSettlement.sol`), and off-chain SEPA. Stablecoin records expose MiCAR-related disclosure fields and an auditable operator attestation; Registerwerk does not independently verify issuer authorisation, token classification, redemption rights, or legal compliance. dApp manifests may declare `paymentMethods`: a `{"rail": "<code>"}` reference (validated against the enabled-rail catalog at submission *and* approval) or a `{"custom": {...}}` descriptor the dApp implements itself — advisory, not a whitelist. `EwpgBondDesk` demonstrates a same-transaction token/payment pattern; that technical behavior is not legal settlement evidence.

**Reference example dApps** (`contracts/src/examples/`, `examples/dapps/`): `BoardroomGovernance` (`boardroom` slug) demonstrates org-admin role restriction/delegation; `EwpgBondDesk` (`bond-desk` slug) demonstrates an ERC-3643 (T-REX) suite with ecosystem permission gating (bootstrap helper: `contracts/test/helpers/TrexSuiteDeployer.sol`) and a configured stablecoin payment leg. These are technical examples, not legally classified instruments or verified MiCAR payment arrangements. Both ship as manifests under `backend/src/main/resources/demo/dapps/` and are seeded as `PUBLISHED` demo marketplace listings by `EcosystemDemoDataSeeder` when `registerwerk.seed-demo-data=true`.

## Indexers / Gateway

**Indexers:** EVM (Graph Node / RPC) and Solana write to `token_transfer` / `indexer_state` tables. `IndexerMonitorService` checks liveness.

**Kong 3.9** (`gateway/`, image `kong:3.9.3` in `docker-compose.yml`): declarative `kong.yml`, DB-less. Plugins: `rate-limiting`, `proxy-cache` on public routes, `request-transformer` (strips client-supplied identity headers), `cors`, `bot-detection`, `ip-restriction` on `/api/v1/admin`, `response-transformer` (security headers). It does **not** validate JWTs — `openid-connect` is Enterprise/Konnect-only. Operator bypasses Kong entirely.
