---
title: REST API Overview
description: URL structure, authentication, error responses, pagination, and API conventions.
---

# REST API Overview

All Registerwerk functionality is exposed through a REST API at `http://backend:8080`. The operator frontend connects directly; the customer frontend connects via Kong (`http://kong:8000`). Every mapped route is listed in the generated [API route index](api-routes.md). An OpenAPI 3 document and Swagger UI exist but are **off by default** (see [OpenAPI / Swagger UI](#openapi-swagger-ui)).

---

## URL structure

| Pattern | Auth required | Available to |
|---|---|---|
| `/api/v1/public/**` | No | Everyone |
| `/api/v1/onboarding/token-info/**` | No | Customer onboarding flow |
| `/api/v1/onboarding/complete` | No | Customer onboarding flow |
| `/api/v1/**` | JWT required | Authenticated users (role-dependent) |

---

## Authentication

All protected endpoints require:

```
Authorization: Bearer <jwt>
```

**The backend validates every token itself, on every request.** Kong does not validate JWTs and does not tell the backend who the caller is — its `openid-connect` plugin is an Enterprise feature and is not active in this OSS setup. Kong additionally *strips* client-supplied identity headers, so nothing can be smuggled in ahead of the backend.

Operator tokens are issued by `POST /api/v1/public/auth/login` (HS256, `iss: registerwerk-local`). Customer tokens are issued by the OIDC provider when `ENTRA_ENABLED=true`, and by the same local endpoint otherwise. A delegating decoder routes on the JWS `alg` header; both branches are issuer-pinned and the OIDC branch is audience-pinned. See [Security & authentication](security.md).

---

## Error response format

All errors follow the `ErrorResponse` record:

```json
{
  "status": 404,
  "message": "Asset with id 'abc...' not found",
  "timestamp": "2026-05-22T10:15:30Z",
  "path": "/api/v1/assets/abc..."
}
```

| HTTP status | Thrown by | Cause |
|---|---|---|
| 400 | `IllegalArgumentException` | Invalid input (validation failure, bad enum value) |
| 401 | `InvalidCredentialsException` | Wrong password, expired JWT |
| 403 | `AccessDeniedException` | Insufficient role, step-up required |
| 404 | `EntityNotFoundException` | Resource does not exist |
| 409 | `InvalidStateTransitionException` | Operation not allowed in current state (e.g., deploy already-deployed asset) |
| 500 | Unexpected exception | Internal server error (details not exposed in prod) |

!!! info "Error messages in production"
    `error.include-message` is set to `never` in the `prod` profile. In development and test, it is `always`. This prevents stack traces from leaking in production responses.

---

## Pagination

List endpoints that page accept `page` (zero-based) and `size`, for example:

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

The response shape is **per endpoint**: some return a plain JSON array (the total is then in the `X-Total-Count` response header, which the CORS configuration exposes to browsers), others return the `PageResponse` wrapper `{ content, totalElements, totalPages, page, size }`. Check the endpoint's schema in the OpenAPI document before relying on either shape.

---

## Idempotency-Key and amounts { #idempotency-key-and-amounts }

Money- and state-moving admin/issuer endpoints require an `Idempotency-Key` header: mint, burn, forced transfers/approvals, force-burn, freeze/whitelist changes, Solana token administration, slot and vault operations, ERC-3643 agent actions, lending market operations and reconciliation, payment-rail changes, wallet import, register-transfer handover/completion and asset redemption. A `POST`, `PUT`, `PATCH` or `DELETE` without a valid key is rejected with `400` and the code `IDEMPOTENCY_KEY_REQUIRED` (or `IDEMPOTENCY_KEY_INVALID`) before anything runs. Other endpoints stay opt-in.

- Send a unique value per user action (a UUID; 8-255 characters of `A-Za-z0-9._:-`) and **reuse the same value when you retry the same request** after a timeout or a `5xx`. A retry then returns the original result (`X-Idempotent-Replay: true`) or the same transaction instead of executing twice.
- The key is scoped to the caller: the legal entity for customer tokens, the acting user for operator tokens. The same key with a different method, path or body is answered `422`; a request that is still running is answered `409`.
- The key is also stored on the outbox row of the resulting chain transaction, so a replay maps to the same signed transaction even after the cached response has expired. `401`/`403` (including step-up challenges) and `5xx` responses are not cached, so repeating the request after a step-up with the same key is safe.

**Amounts are decimal strings.** Send token amounts (`amount`, `value`, `newCap`, `navPerShare`, ...) as JSON strings such as `"1000000000000000000000"`. A JavaScript number loses precision above 2^53. For one release a JSON number is still accepted if it is exactly representable (an integer below 2^53, or a decimal of at most 15 significant digits) and a deprecation warning is logged; anything else is answered `400` with `Invalid amount: ...`.

## Route groups

The generated [API route index](api-routes.md) is the complete, code-derived list (method, path, role expression, step-up). The main base paths:

| Area | Base path |
|---|---|
| Assets and deployments (issuer mint/burn under `.../deployments/{depId}/issuer/`, operator forced operations under `.../deployments/{depId}/admin/`) | `/api/v1/assets`, `/api/v1/deployments` |
| Legal entities and KYC (`/api/v1/entities/{entityId}/kyc/...`), KYC review queue | `/api/v1/entities`, `/api/v1/kyc` |
| Sanctions screening (`/api/v1/compliance/screening/...`, hits under `/hits/{hitId}/accept`) and other compliance functions | `/api/v1/compliance` |
| Sperrvermerk (holder blocks) | `/api/v1/holder-blocks` |
| Regulatory reporting (MiFIR, DAC8) | `/api/v1/regulatory-reporting` |
| DORA incidents, providers, resilience tests | `/api/v1/dora` |
| Trading, repo desk, lending, corporate actions | `/api/v1/trading`, `/api/v1/repo-desk`, `/api/v1/lending`, `/api/v1/corporate-actions` |
| Four-eyes approval queue | `/api/v1/approvals` |
| Audit log, chain verification | `/api/v1/audit` |
| Operator administration (users, wallets, ...) | `/api/v1/admin` |
| Customer-company self-service | `/api/v1/company`, `/api/v1/me` |
| Public, unauthenticated (chains, platform capabilities, Travel Rule) | `/api/v1/public` |

Approving KYC, for example, is `POST /api/v1/entities/{entityId}/kyc/approve`: the initiator is a `REGISTRY_ADMIN` or `COMPLIANCE_OFFICER` and the call needs step-up and a second approver (see the [step-up matrix](../compliance/step-up-matrix.md)).

---

## OpenAPI / Swagger UI { #openapi-swagger-ui }

The OpenAPI document and Swagger UI are served **by the backend**, not by this documentation server, and are **disabled unless `SWAGGER_ENABLED=true`** (default `false`, in every profile).

| URL (when enabled) | Description |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Interactive Swagger UI (browser) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | OpenAPI 3 JSON (machine-readable) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Health check |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Build info |

!!! info "This documentation site vs. the API"
    This site (port 48003) is a static MkDocs reference — it does not proxy the backend. Open the links above directly in a browser while the stack is running (`docker compose up -d`).

!!! warning "Enabling SWAGGER_ENABLED exposes the spec without authentication"
    When enabled, `/swagger-ui.html`, `/swagger-ui/**` and `/api-docs/**` are public (`permitAll` in the security configuration): anyone who can reach the backend can read the full route and schema catalogue. Nothing refuses `SWAGGER_ENABLED=true` in production mode. Leave it off in production, or put the backend behind an allow-list that excludes these paths.
