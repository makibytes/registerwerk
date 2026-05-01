# Registerwerk — AI Context

eWpG-compliant electronic securities registry. Issues and manages tokenized securities across blockchains. Two user groups: **Operators** (registry staff) and **Customers** (issuers/investors). Multi-tenant: one operator deployment serves many customer legal entities.

---

## Monorepo Structure

```
backend/              Spring Boot 4 / Java 25 — single API monolith
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
- **Customer frontend goes through Kong** — Kong validates JWTs, injects `X-Entity-Id` / `X-Entity-Roles` headers.
- **Backend is a pure resource server** — stateless JWT validation; does not issue OIDC tokens.
- **Auth toggle:** `ENTRA_ENABLED=false` → HS256 dev mode with `JWT_DEV_SECRET`; `=true` → OIDC via Kong.
- **Single PostgreSQL instance** — hosts `registerwerk`, `kong`, and `konga` databases.

---

## Backend

**Stack:** Java 25, Spring Boot 4, Spring Security 7, JPA/Hibernate, Flyway, Caffeine (30s TTL), Web3j (EVM), Solanaj.
Build: `./mvnw verify` — runs unit + integration tests + JaCoCo (70% line coverage gate).

**Package root:** `de.makibytes.registerwerk`

| Package | Contents |
|---|---|
| `domain/` | JPA entities + enums — zero Spring dependencies |
| `application/` | Use-case services (auth, asset, customer, erc3643, indexer, notification, chain) |
| `infrastructure/persistence/jpa/` | JpaRepository interfaces |
| `web/controller/` | REST controllers |
| `web/dto/` | Request/Response records |
| `web/mapper/` | MapStruct mappers |
| `config/` | Spring @Configuration beans |

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
```

---

## Smart Contracts (`contracts/`)

Foundry. `AssetTokenFactory` uses `CREATE2` for deterministic pre-computed addresses. Standards: `EwpgERC20/721/1155/3643`, confidential variants (Zama fhEVM). Contract addresses stored in `registerwerk.contracts.*` config post-deployment.

## Indexers / Gateway

**Indexers:** EVM (Graph Node / RPC) and Solana write to `token_transfer` / `indexer_state` tables. `IndexerMonitorService` checks liveness.

**Kong 3.8** (`gateway/`): declarative `kong.yml`. Plugins: `openid-connect` (customer JWT), `request-transformer` (injects entity headers), caching on public routes. Operator bypasses Kong entirely.
