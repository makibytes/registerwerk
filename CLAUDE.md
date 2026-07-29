# Registerwerk — AI Context

Reference implementation for an electronic-securities registry that models issuance and administration of tokenized assets across blockchains. Two user groups: **Operators** (registry staff) and **Customers** (issuers/investors). Multi-tenant: one operator deployment serves many customer legal entities. Repository behavior is not evidence of eWpG compliance, regulatory authorisation, legal effect, or production readiness.

---

## Monorepo Structure

```
backend/              Spring Boot 4.1 / Java 25 — single API monolith (Spring Modulith 2.1)
contracts/            Foundry smart contracts (EVM + confidential)
frontend-operator/    Angular 21 — operator admin portal (:4200)
frontend-customer/    Angular 21 — customer portal (:4201)
gateway/              Kong declarative config
indexer/              Off-chain event indexers (EVM + Solana)
docker-compose.yml    / .env.example
```

---

## Key Architecture Decisions

- **Operator frontend bypasses Kong** — nginx proxies `/api/` directly to `backend:8080`. Uses built-in HS256 JWT login (`POST /api/v1/public/auth/login`).
- **Customer frontend's API calls go through Kong** (the frontend itself is always opened directly at `:4201`) — Kong adds rate limiting/caching/security headers only; JWT validation and entity/role extraction happen in the backend itself (`JwtEntityClaimsConverter`), not via a Kong-injected header. Kong's `openid-connect` plugin is Enterprise/Konnect-only and not active in this repo's OSS setup (`gateway/plugins/oidc-entra.yml` is a ready-to-merge snippet for anyone who is running Enterprise/Konnect).
- **Backend is a pure resource server** — stateless JWT validation; does not issue OIDC tokens.
- **Auth toggle:** `ENTRA_ENABLED=false` → HS256 dev mode with `JWT_DEV_SECRET`; `=true` → OIDC, validated by the backend (optionally fronted by Kong for the customer app).
- **Single PostgreSQL instance** — hosts only the `registerwerk` database. Kong runs DB-less (`gateway/kong.yml` loaded via `KONG_DECLARATIVE_CONFIG`), so it has no database of its own — there is no `kong` or `konga` database/service in this stack.

---

## Backend

**Stack:** Java 25, Spring Boot 4.1, Spring Security 7, Spring Modulith 2.1, JPA/Hibernate, Flyway, Caffeine (30s TTL), Jackson 3 (tools.jackson), Web3j (EVM), Solanaj (Solana), Daml Java bindings (Canton), plus native Starknet (Cairo/STARK ECDSA) and Stellar (Horizon/Ed25519) client code — see `blockchain/internal/deploy/`.
Build: `./mvnw verify` — runs unit + integration tests + JaCoCo (30% line coverage gate).
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
- New Flyway migrations: `V{n}__description.sql` — never edit existing
- Emit audit events in every state-changing service method

---

## Frontend (both apps)

**Angular 21, standalone components, `provideZonelessChangeDetection()`.**

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
| `ENTRA_ENABLED` | `false` | `false` → built-in login; `true` → OIDC via Kong |
| `DEFAULT_ADMIN_EMAIL` / `_PASSWORD` | — | Seeds built-in admin `app_user` row |
| `JWT_DEV_SECRET` | `registerwerk-dev-jwt-secret-…` | HS256 signing key — must never be empty |
| `JWT_ISSUER_URI` | blank | OIDC issuer URI; blank → HS256 mode |
| `DB_URL` | `jdbc:postgresql://postgres:5432/registerwerk` | |
| `DB_USER` / `DB_PASSWORD` | `registerwerk` / — | |

---

## Development

```bash
docker compose up --build                    # full stack
docker compose up --build frontend-operator  # rebuild one service
cd backend && ./mvnw verify                  # tests + coverage
cd frontend-operator && npm start            # :4200
cd frontend-customer && npm start            # :4201
cd contracts && forge test -vvv
cd contracts/cairo && scarb build && snforge test   # Cairo (Starknet) contracts
cd daml && dpm build                                # Daml (Canton) bond templates — SDK via dpm
```

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

**Kong 3.8** (`gateway/`): declarative `kong.yml`. Plugins: `openid-connect` (customer JWT), `request-transformer` (injects entity headers), caching on public routes. Operator bypasses Kong entirely.
