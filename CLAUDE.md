# Registerwerk — AI Context

Reference implementation for an electronic-securities registry that models issuance and administration of tokenized assets across blockchains. Two user groups: **Operators** (registry staff) and **Customers** (issuers/investors). Multi-tenant: one operator deployment serves many customer legal entities. Repository behavior is not evidence of eWpG compliance, regulatory authorisation, legal effect, or production readiness.

---

## Monorepo Structure

```
backend/              Spring Boot 4.1 / Java 25 — single API monolith (Spring Modulith 2.1)
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
- **Single PostgreSQL instance** — always hosts the `registerwerk` database, plus a `chaincache` database (created by `postgres-init/01-create-chaincache-db.sql`) when the optional `chaincache-true` profile is enabled — chaincache does not get its own dedicated Postgres container, mirroring how a managed instance (Cloud SQL on GKE) hosts multiple databases in production. Kong runs DB-less (`gateway/kong.yml` loaded via `KONG_DECLARATIVE_CONFIG`), so it has no database of its own — there is no `kong` or `konga` database/service in this stack.

---

## Backend

**Stack:** Java 25, Spring Boot 4.1, Spring Security 7, Spring Modulith 2.1, JPA/Hibernate, Flyway, Caffeine (30s TTL; only the `assets` cache is wired to a read path — see `CacheConfig`'s javadoc for why `deployments`/`entities` deliberately aren't yet), Jackson 3 (tools.jackson), Web3j (EVM), Solanaj (Solana), Daml Java bindings (Canton), plus native Starknet (Cairo/STARK ECDSA) and Stellar (Horizon/Ed25519) client code — see `blockchain/internal/deploy/`.
Build: `./mvnw verify` — runs unit + integration tests + JaCoCo. Coverage gate: bundle LINE ≥ 0.36 / BRANCH ≥ 0.23, plus stricter per-package floors (e.g. `customer/internal` 0.60/0.40, `registertransfer/internal` 0.85/0.70) — check `pom.xml` before adding code to those packages.
**Lazy datasource:** `spring.datasource.connection-fetch=lazy` — defers physical DB connection until first SQL statement.

**Package root:** `de.makibytes.registerwerk` — organized as **vertical Spring Modulith modules** (boundaries enforced by `ModulithArchitectureTest`). Each module follows the same convention:

| Subpackage | Contents |
|---|---|
| `api/` | Public entities, repositories, ports (`@NamedInterface`) — the only cross-module surface |
| `internal/` | Package-private services and jobs |
| `web/` + `web/dto/` | REST controllers + request/response records |
| `events/` | Domain events (`@NamedInterface`; implement `audit.api.AuditableEvent` for zero-wiring audit) |

Modules: `asset`, `audit`, `auth`, `blockchain`, `chain`, `customer`, `deployment`, `erc3643`, `kyc`, `marketplace`, `notification`, `onboarding`, `orgidentity`, `screening`, `stepup`, `trading`, `wallet`, … (see `platform/modules.md`). Cross-module checks use fail-closed ports (`screening.ScreeningGate`, `orgidentity.PermissionGate`).

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
- Flyway uses a single clean-install baseline, `V1__initial_schema.sql`. Add later changes as `V{n}__description.sql`; do not edit migrations after release. CI (`scripts/check-destructive-migrations.sh`, wired into `backend.yml`) rejects unguarded `DROP TABLE`, `DROP COLUMN`, and `TRUNCATE` statements; acknowledge an intentional destructive change with `-- migration-safety: ack (<why>)` directly above it.
- Emit audit events in every state-changing service method

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

**Operator structure:** sidebar + topbar layout; `ShellComponent` wraps all guarded routes.
**Customer structure:** sticky top nav; impersonation bar shown for all `REGISTRY_ADMIN` users (always, not only when actively impersonating).

---

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → built-in login everywhere; `true` → Entra sign-in for customers (operators keep built-in login) |
| `DEFAULT_ADMIN_EMAIL` / `_PASSWORD` | — | Seeds built-in admin `app_user` row |
| `JWT_DEV_SECRET` | `registerwerk-dev-jwt-secret-…` | HS256 signing key — must never be empty |
| `JWT_ISSUER_URI` | blank | OIDC issuer URI; blank → HS256 mode |
| `JWT_AUDIENCE` | blank | Expected `aud` of Entra tokens. Blank = any app in the tenant is accepted — required in production |
| `ENTRA_TENANT_ID` / `_CLIENT_ID` / `_CLIENT_SECRET` | blank | API app registration + app-only Graph credential |
| `ENTRA_SPA_CLIENT_ID` / `ENTRA_API_SCOPE` | blank | Served to the browser by `GET /api/v1/public/auth/config` |
| `ENTRA_SUPPORT_ENABLED` | `false` | Master switch for all Microsoft Graph calls (2FA status + operator support console) |
| `ENTRA_STEPUP_AUTH_CONTEXT_ID` | blank | Conditional Access auth context (c1–c99) for step-up; must be *published to apps* |
| `DB_URL` | `jdbc:postgresql://postgres:5432/registerwerk` | |
| `DB_USER` / `DB_PASSWORD` | `registerwerk` / — | |

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
```

**Documentation** is MkDocs Material (`mkdocs.yml` + `docs/`). The `docs` compose profile builds the site into a **static nginx image** (`docs/Dockerfile`) rather than running `mkdocs serve` — so doc changes need a rebuild to show up:

```bash
docker compose --profile docs up --build docs      # http://localhost:48003
```

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

**Kong 3.8** (`gateway/`): declarative `kong.yml`, DB-less. Plugins: `rate-limiting`, `proxy-cache` on public routes, `request-transformer` (strips client-supplied identity headers), `cors`, `bot-detection`, `ip-restriction` on `/api/v1/admin`, `response-transformer` (security headers). It does **not** validate JWTs — `openid-connect` is Enterprise/Konnect-only. Operator bypasses Kong entirely.
