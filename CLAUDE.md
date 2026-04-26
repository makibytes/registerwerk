# Registerwerk — AI Context Document

This file is the authoritative reference for AI assistants working on this codebase.
Read this before exploring files or making changes.

---

## 1. What This Is

An **eWpG-compliant electronic securities registry** (German *Gesetz über elektronische Wertpapiere*). It issues, manages, and audits tokenized securities across multiple blockchains. Two user groups:

- **Operators** — registry staff who manage everything (customers, assets, audit)
- **Customers** — issuers and investors (banks, corporations) who access their own portfolio

The product is multi-tenant: one operator deployment serves many customer legal entities.

---

## 2. Monorepo Structure

```
registerwerk/
├── backend/              Spring Boot 4 / Java 25 — the single API monolith
├── contracts/            Foundry smart contracts (EVM + confidential)
├── frontend-operator/    Angular 21 — registry operator admin portal (:4200)
├── frontend-customer/    Angular 21 — issuer / investor customer portal (:4201)
├── gateway/              Kong declarative config + plugin scripts
├── indexer/              Off-chain event indexers (EVM + Solana)
├── docker-compose.yml    Full-stack local dev environment
├── .env.example          Copy to .env; all required vars are documented here
├── SPEC.md               Original product specification
└── CLAUDE.md             ← this file
```

---

## 3. System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  frontend-operator (:4200)       frontend-customer (:4201)          │
│  Angular 21, Manrope font        Angular 21, Manrope font           │
│  Nginx → proxies /api/ directly  Nginx → proxies /api/ via Kong     │
│  to backend:8080 (bypass Kong)   to :8000                           │
└──────────────┬────────────────────────────┬────────────────────────┘
               │ (operator FE bypasses Kong)│ (customer FE through Kong)
               │                     ┌──────▼──────┐
               │                     │    Kong      │  :8000 (proxy), :8001 (admin)
               │                     │  API Gateway │  :1337 (Konga UI)
               │                     │  + OIDC auth │
               │                     └──────┬───────┘
               │                            │ X-Entity-Id / X-Entity-Roles headers
               └──────────────┬─────────────┘
                        ┌──────▼──────┐
                        │   Backend   │  :8080 — Spring Boot OAuth2 Resource Server
                        │  Spring 4   │  Stateless JWT validation
                        └──────┬──────┘
                  ┌────────────┼────────────┐
           ┌──────▼──────┐  ┌──▼───┐  ┌────▼────────┐
           │  PostgreSQL  │  │  S3  │  │  Blockchains │
           │   :5432      │  │(docs)│  │  (Web3j/Sol) │
           └─────────────┘  └──────┘  └─────────────┘
```

### Key architectural decisions

- **Operator frontend bypasses Kong** — its nginx.conf proxies `/api/` directly to `backend:8080`. Operator auth uses the backend's own `/api/v1/public/auth/login` endpoint (HS256 JWT). No Kong OIDC plugin involved.
- **Customer frontend goes through Kong** — Kong validates JWTs and injects entity claims before forwarding to the backend.
- **Backend is a pure resource server** — it does not issue tokens for OIDC flows; Kong handles that. For the built-in admin mode it issues HS256 tokens itself.
- **Single PostgreSQL instance** — one `postgres` container hosts: the app DB (`registerwerk`), Kong's DB (`kong`), and Konga's DB (`konga`). The `docker/postgres/init/` script creates all three.

---

## 4. Backend Architecture

### Tech stack
| Layer | Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.0.x |
| Security | Spring Security 7 / OAuth2 Resource Server |
| Persistence | Spring Data JPA / Hibernate / Flyway |
| Email | Spring Mail / Thymeleaf templates |
| Cache | Caffeine (30 s TTL, max 1000 entries) |
| Blockchain | Web3j (EVM) + Solanaj (Solana) |
| Build | Maven (`./mvnw verify` runs unit + IT + JaCoCo) |
| Tests | JUnit 5, Mockito, Testcontainers (Postgres), Spring Boot Test |
| Coverage gate | 70% line coverage enforced by JaCoCo |

### Package layout (`de.makibytes.registerwerk.*`)

```
config/          Spring @Configuration beans (Security, Blockchain, Cache, S3, Web, ContractAddresses, AuthProperties)
domain/          JPA entities + enums — zero Spring dependencies
  entity/        LegalEntity, AppUser, EntityNameHistory, EntityMergeRecord
  audit/         AuditEvent
  enums/         EntityStatus, EntityType, …
application/     Use-case services — orchestrate domain + infrastructure
  auth/          AuthService, JwtMintingService, DefaultAdminSeeder
  asset/         AssetService, AssetLifecycleService, AssetDeploymentService, HolderService
  blockchain/    Per-standard deployment services (ERC-20/721/1155/3643, Confidential, Solana)
  customer/      LegalEntityService, KycService, OnboardingService, DocumentService, EntityHistoryService
  erc3643/       ClaimIssuanceService, IdentityRegistryService, OnChainIdService, Erc3643LifecycleService
  indexer/       GraphNodeSyncService, SolanaTransferSyncService, IndexerMonitorService, TokenHistoryService
  notification/  EmailService, OnboardingEmailService, WelcomeEmailService
  chain/         ChainConfigService
  exception/     EntityNotFoundException, InvalidCredentialsException, LoginDisabledException, InvalidStateTransitionException
infrastructure/
  persistence/jpa/   All JpaRepository interfaces
web/
  controller/    REST controllers (one per domain area) + GlobalExceptionHandler
  dto/           Request/Response records
  mapper/        MapStruct mappers (EntityMapper, ChainConfigMapper)
  security/      JwtEntityClaimsConverter, EntityOwnershipChecker
```

### Config properties

Central config beans use `@ConfigurationProperties`:
- `RegisterwerkAuthProperties` (`registerwerk.auth.*`) — entraEnabled, devSecret, tokenTtlSeconds, defaultAdmin
- `ContractAddressConfig` (`registerwerk.contracts.*`) — per-chain factory addresses
- `BlockchainConfig` / `CacheConfig` / `S3Config` / `WebConfig` — standard Spring beans

### Security model

Two auth modes controlled by `ENTRA_ENABLED`:

| `ENTRA_ENABLED` | Auth flow |
|---|---|
| `false` (default) | Operator FE sends email+password → `POST /api/v1/public/auth/login` → backend mints HS256 JWT from `JWT_DEV_SECRET` |
| `true` | OIDC via Kong (Entra ID / other IdP) — login endpoint returns 404 |

JWT claims mapped to Spring authorities via `JwtEntityClaimsConverter` reading the `roles` claim → `ROLE_*` granted authorities.

`SecurityConfig` permits `/api/v1/public/**` without auth; all other `/api/v1/**` requires authentication.

### URL conventions

| Pattern | Who reaches it | Auth required |
|---|---|---|
| `/api/v1/public/**` | Anyone | No |
| `/api/v1/onboarding/token-info/**` | Anyone | No |
| `/api/v1/onboarding/complete` | Anyone | No |
| `/api/v1/**` | Authenticated users | Yes (JWT) |
| `/swagger-ui/**`, `/api-docs/**` | Dev only | No |

### Database

Flyway migrations in `backend/src/main/resources/db/migration/`:
- `V1__initial_schema.sql` — full schema (~464 lines): legal entities, KYC, onboarding tokens, assets, deployments, holders, mint control, partitioned audit log, entity merges, chain registry, token history, indexer state, ONCHAINID, ERC-3643 suites
- `V2__app_user.sql` — `app_user` table for built-in admin accounts (BCrypt hashes, roles)

`ddl-auto: validate` in production — schema changes always go through Flyway.

### Error handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps all exceptions to `ErrorResponse(status, message, timestamp, path)`:

| Exception | HTTP |
|---|---|
| `EntityNotFoundException` | 404 |
| `InvalidCredentialsException` | 401 |
| `LoginDisabledException` | 404 |
| `InvalidStateTransitionException` | 409 |
| `IllegalArgumentException` | 400 |
| `UnsupportedOperationException` | 501 |
| `AccessDeniedException` | 403 |
| `MethodArgumentNotValidException` | 400 |
| `NoResourceFoundException` | 404 |
| `Exception` (catch-all) | 500 + ERROR log |

### Audit trail

`AuditEventPublisher` publishes Spring events; `AuditEventPersistenceListener` persists them to the partitioned `audit_event` table. All state-mutating operations emit audit events. Query via `GET /api/v1/audit/events`.

---

## 5. Frontend Architecture

Both apps are **Angular 21 standalone-component apps** with a shared design system.

### Common patterns

- `standalone: true` everywhere — no NgModules
- `@if / @else / @for` control flow (Angular 17+ syntax) — no `*ngIf` / `*ngFor`
- `inject()` function preferred over constructor injection in services/components
- `provideZonelessChangeDetection()` — signal-ready, no NgZone dependency
- API services live in `src/app/core/api/` — one service per backend domain
- Auth interceptor (`core/interceptors/auth.interceptor.ts`) attaches `Authorization: Bearer <jwt>` to every outgoing request when a token is present
- Auth guard (`core/auth/auth.guard.ts`) protects all routes except `/login`

### Operator frontend (`frontend-operator/`)

```
src/app/
├── app.config.ts          providers: router, httpClient+interceptors, animations
├── app.routes.ts          / → ShellComponent (guarded); /login → LoginComponent
├── core/
│   ├── auth/              LoginComponent, AuthService, auth.guard.ts
│   ├── api/               asset.service, audit.service, entity.service, erc3643.service, kyc.service, mint-control.service, onboarding.service
│   ├── interceptors/      auth.interceptor, error.interceptor
│   └── models/            TypeScript interfaces for all API responses
├── layout/
│   ├── shell/             ShellComponent — sidebar + topbar + router-outlet
│   └── sidebar/           SidebarComponent — nav items with amber active state
├── features/
│   ├── dashboard/
│   ├── customers/         customer-list, customer-detail
│   ├── onboarding/        token-generator, create-entity
│   ├── assets/            asset-list, asset-detail, asset-edit, dialogs
│   └── audit/             audit-log
└── shared/components/     status-badge
```

`AuthService` has two branches:
- `loginWithCredentials(email, password)` — real JWT flow (ENTRA_ENABLED=false)
- `login()` — stub Microsoft flow, TODO stub (ENTRA_ENABLED=true)

### Customer frontend (`frontend-customer/`)

```
src/app/
├── app.config.ts
├── app.routes.ts          /onboarding (public); all others guarded
├── core/
│   ├── auth/              LoginComponent (native inputs, glassmorphism card), AuthService
│   ├── api/               company.service, erc3643.service, investment.service, issuance.service, kyc.service
│   └── models/
├── layout/
│   ├── shell/             ShellComponent — nav + router-outlet
│   └── nav/               NavComponent — dark horizontal nav, avatar user menu
├── features/
│   ├── dashboard/
│   ├── investments/       investment-list, investment-detail
│   ├── issuances/         issuance-list, issuance-detail, issuance-wizard, add-holder-dialog
│   ├── kyc/               kyc-status
│   ├── company-admin/     user-management, idp-settings
│   └── onboarding-setup/  onboarding-entry, redeem-token, setup-idp, setup-users
└── shared/components/     status-badge, chain-icon, bar-chart, donut-chart
```

Customer `AuthService` uses `setToken()`, `getUserName()`, `getUserEmail()`, `hasRole()` — the nav component uses these to show/hide links based on role.

---

## 6. Design System

Both frontends share the same design language with distinct identities.

### Typography

**Font family: Manrope** (Google Fonts) — geometric humanist sans-serif. Loaded via `<link>` in `index.html`. The operator app also loads **IBM Plex Mono** for code/token displays.

```
font-family: 'Manrope', 'Helvetica Neue', sans-serif;
-webkit-font-smoothing: antialiased;
```

Page headings: `font-size: 21px; font-weight: 700; letter-spacing: -0.4px`
Body: `font-size: 14px; font-weight: 400`
Labels/caps: `font-size: 11–12px; font-weight: 600; letter-spacing: 0.5px; text-transform: uppercase`

### Angular Material

Both apps use **Angular Material M3** via `mat.define-theme()`:
- Operator: `mat.$indigo-palette` (primary), density scale 0
- Customer: `mat.$teal-palette` (primary), density scale 0

Dark mode re-applies only color tokens (efficient):
```scss
html { @include mat.all-component-themes($light); }
@media (prefers-color-scheme: dark) {
  html { @include mat.all-component-colors($dark); }
}
```

### CSS Design Tokens

Both `styles.scss` files define a shared `--rw-*` token namespace. All component styles use these variables — never hardcoded colors.

#### Core tokens (light → dark)

| Token | Light | Dark |
|---|---|---|
| `--rw-bg` | `#EEF1F8` / `#F2F5FA` | `#0D1117` |
| `--rw-surface` | `#FFFFFF` | `#161B22` |
| `--rw-surface-raised` | `#FFFFFF` | `#21262D` |
| `--rw-border` | `#DDE1EB` / `#E0E6EF` | `#30363D` |
| `--rw-text-primary` | `#0D1526` / `#0F1A2E` | `#E6EDF3` |
| `--rw-text-secondary` | `#4A5568` / `#475569` | `#8B949E` |
| `--rw-text-muted` | `#8C98AE` / `#94A3B8` | `#484F58` |
| `--rw-shadow-sm` | `rgba(13,21,38,0.08)` | `rgba(0,0,0,0.35)` |

#### Operator-specific tokens

| Token | Value |
|---|---|
| `--rw-sidebar-bg` | `#07091A` (always dark, ignores color-scheme) |
| `--rw-sidebar-fg-active` | `#FCD34D` (amber) |
| `--rw-sidebar-active-bg` | `rgba(252,211,77,0.1)` |
| `--rw-sidebar-icon-active` | `#FCD34D` |
| `--rw-accent` | `#F59E0B` |
| `--rw-toolbar-bg` | `#FFFFFF` / dark: `#161B22` |

#### Customer-specific tokens

| Token | Value |
|---|---|
| `--rw-nav-bg` | `#111827` (always dark) |
| `--rw-nav-accent` | `#2DD4BF` (teal-400) |
| `--rw-accent` | `#0D9488` (teal-600) |

#### Status badge colors

Tokens: `--rw-{status}-bg` / `--rw-{status}-fg` for: `draft`, `pending`, `approved`, `issued`, `rejected`, `revoked`. All adapt to dark mode. Applied via `.status-badge.status-{value}` class.

#### Chain chip colors

Tokens: `--rw-chain-{chain}-bg` / `--rw-chain-{chain}-fg` for: `ethereum`, `polygon`, `base`, `solana`. Applied via `.chain-chip.chain-{chain}` class.

### Shell layouts

**Operator** — sidebar + main:
```
[sidebar 232px dark] | [topbar 54px] 
                     | [content scrollable, var(--rw-bg)]
```
Sidebar: amber gradient brand icon, nav items with `routerLinkActive="active"`, version footer.

**Customer** — top nav + main:
```
[nav bar 56px dark, sticky]
[content scrollable, var(--rw-bg)]
```
Nav: teal gradient brand icon, horizontal nav links, avatar user-menu (MatMenu).

### Login pages

**Operator login** — full dark page, split layout on ≥900px:
- Left panel: decorative dot-grid + radial glow, brand, headline, tagline
- Right panel: form on `#0D1020` background; amber gradient CTA button
- Toggles between Microsoft button and email/password form via `environment.entraEnabled`

**Customer login** — centered glassmorphism card:
- Background: `#0F1A2E` with mesh gradients and dot-grid overlay
- Card: `backdrop-filter: blur(20px)`, subtle glass border
- Native `<input>` elements (no Material overhead), teal gradient CTA button
- Password visibility toggle, inline error banner

### Global utility classes

| Class | Purpose |
|---|---|
| `.page-header` | Flex row, h1 title + action button |
| `.content-card` | White surface with border + shadow |
| `.filter-row` | Flex wrap row for filter inputs |
| `.full-width-table` | `width: 100%` on mat-table |
| `.text-muted` | `var(--rw-text-muted)` color |
| `.token-display` | IBM Plex Mono, for JWT/token strings |
| `.warning-banner` | Amber left-border info block |
| `.page-container` | Max-width 1280px centered (customer only) |
| `.stat-card` | Dashboard metric card styling |
| `.loading-overlay` | Centered spinner padding |

### Material table overrides (both apps)

```scss
.mat-mdc-table { background: transparent !important; }
.mat-mdc-row:hover .mat-mdc-cell { background: var(--rw-border-subtle); }
.mat-mdc-header-cell {
  color: var(--rw-text-muted) !important;
  font-size: 11px !important;
  font-weight: 600 !important;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}
```

---

## 7. Smart Contracts (`contracts/`)

Foundry project. Solidity contracts organized by standard:

```
contracts/src/
├── tokens/         EwpgERC20, EwpgERC721, EwpgERC1155, EwpgERC3643 (T-REX wrapper)
├── compliance/     EwpgCompliance, WhitelistRegistry, MintController
├── confidential/   ConfidentialERC20, ConfidentialERC3643 (Zama fhEVM)
├── factory/        AssetTokenFactory (CREATE2 deterministic addresses)
└── interfaces/     ISolvers, shared interfaces
```

`AssetTokenFactory` uses `CREATE2` so the backend can pre-compute deployment addresses before sending the transaction. Contract addresses are stored in `registerwerk.contracts.*` config properties post-deployment.

---

## 8. Off-Chain Indexers (`indexer/`)

Two indexers running alongside the backend:
- `evm/` — listens to EVM chain events (transfers, compliance changes) via Graph Node or direct RPC polling
- `solana/` — polls Solana RPC for SPL token transfers

Both write to the `token_transfer` and `indexer_state` tables. `IndexerMonitorService` in the backend checks indexer liveness.

---

## 9. API Gateway (`gateway/`)

Kong 3.8 with declarative config (`kong.yml`) and plugin scripts. Key plugins:
- `openid-connect` — validates JWTs from Entra ID for customer routes
- `request-transformer` — injects `X-Entity-Id` / `X-Entity-Roles` headers from JWT claims
- Caching layer on public/read routes

The operator frontend bypasses Kong entirely (nginx proxies directly to `backend:8080`). Only customer traffic goes through Kong.

---

## 10. Environment Variables Quick Reference

| Variable | Default | Purpose |
|---|---|---|
| `ENTRA_ENABLED` | `false` | `false` → built-in email/password login; `true` → Microsoft OIDC |
| `DEFAULT_ADMIN_EMAIL` | — | Seeds `app_user` row for built-in admin |
| `DEFAULT_ADMIN_PASSWORD` | — | BCrypt-hashed on startup; rotate by changing + restarting |
| `JWT_DEV_SECRET` | `registerwerk-dev-jwt-secret-change-in-production!!` | HS256 signing key (must not be empty) |
| `JWT_ISSUER_URI` | `` (blank) | OIDC issuer URL; blank → HS256 dev mode |
| `DB_URL` | `jdbc:postgresql://postgres:5432/registerwerk` | |
| `DB_USER` / `DB_PASSWORD` | `registerwerk` / — | |

**Important**: `JWT_DEV_SECRET` must never be passed as an empty string — `docker-compose.yml` uses `${JWT_DEV_SECRET:-registerwerk-dev-jwt-secret-change-in-production!!}` to guarantee a non-empty default.

---

## 11. Development Workflow

```bash
# Start full stack (builds all Docker images)
docker compose up --build

# Rebuild only one service after code change
docker compose up --build frontend-operator

# Backend tests + coverage (must stay ≥70%)
cd backend && ./mvnw verify

# Frontend dev server (uses environment.ts, not prod build)
cd frontend-operator && npm start   # :4200
cd frontend-customer && npm start   # :4201

# Smart contract tests
cd contracts && forge test -vvv
```

---

## 12. Coding Conventions

### Backend (Java)

- Domain entities live in `domain/`; they have no Spring dependencies
- Services in `application/` own business logic; they depend on repositories and other services, never on controllers or DTOs
- Controllers in `web/` call services; they do no business logic
- DTOs are Java `record` types; request records have Bean Validation annotations
- `@Transactional` at the service method level, not on repositories
- `@PreAuthorize("hasRole('REGISTRY_ADMIN')")` on controllers or methods, not security config matchers
- Exceptions are domain-specific (`EntityNotFoundException`, etc.); `GlobalExceptionHandler` maps them to HTTP
- New migrations get the next `V{n}__description.sql` filename; never edit existing migrations
- Audit events emitted via `AuditEventPublisher.publish(...)` in every state-changing service method

### Frontend (Angular)

- All components are `standalone: true`; never add NgModules
- Use `@if / @else / @for` control flow, never `*ngIf` / `*ngFor`
- Use `inject()` in component body for DI; use constructor injection only in classes without decorators or when required
- Template-only styling goes in the component's `styles: []` array
- Global-scope styles go in `styles.scss` using `--rw-*` tokens
- Never hardcode colors in component styles; always reference a `--rw-*` token
- API services return `Observable<T>` using `HttpClient`; components subscribe with `.subscribe()` or `async` pipe
- Error handling in components: show user-facing message via `MatSnackBar` (operator) or inline error state (customer)
