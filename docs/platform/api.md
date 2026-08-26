---
title: REST API Overview
description: URL structure, authentication, error responses, pagination, and API conventions.
---

# REST API Overview

All Registerwerk functionality is exposed through a REST API at `http://backend:8080`. The operator frontend connects directly; the customer frontend connects via Kong (`http://kong:8000`). The API is documented with OpenAPI 3 (Swagger UI available at `/swagger-ui.html`).

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

List endpoints support cursor-based pagination with `page` and `size` parameters:

```
GET /api/v1/assets?page=0&size=20&sort=createdAt,desc
```

Responses include a `X-Total-Count` header with the total record count (before pagination). The response body is always an array (never a wrapper object).

---

## Key API groups

### Assets (`/api/v1/assets`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/assets` | List all assets (paginated) |
| `POST` | `/api/v1/assets` | Create new asset |
| `GET` | `/api/v1/assets/{id}` | Get asset by ID |
| `POST` | `/api/v1/assets/{id}/deploy` | Deploy token to blockchain |
| `POST` | `/api/v1/assets/{id}/mint` | Mint tokens |
| `POST` | `/api/v1/assets/{id}/burn` | Burn tokens (step-up + 4-eyes) |
| `POST` | `/api/v1/assets/{id}/force-transfer` | Force-transfer (step-up + 4-eyes) |
| `POST` | `/api/v1/assets/{id}/freeze/{address}` | Freeze address (requires HolderBlock) |

### Customers (`/api/v1/customers`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/customers` | List legal entities |
| `POST` | `/api/v1/customers` | Create legal entity |
| `GET` | `/api/v1/customers/{id}` | Get entity |
| `POST` | `/api/v1/customers/{id}/kyc/documents` | Upload KYC document |
| `POST` | `/api/v1/customers/{id}/kyc/approve` | Approve KYC (COMPLIANCE_OFFICER + step-up) |
| `GET` | `/api/v1/customers/{id}/beneficial-owners` | List UBOs |
| `POST` | `/api/v1/customers/{id}/beneficial-owners` | Add UBO |

### Compliance (`/api/v1/compliance`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/compliance/screening/entities/{id}/screen` | Trigger manual screen |
| `GET` | `/api/v1/compliance/screening/entities/{id}/runs` | Get screening history |
| `POST` | `/api/v1/compliance/screening/hits/{hitId}/accept` | Accept/dismiss a hit |
| `GET` | `/api/v1/holder-blocks` | List all HolderBlocks |
| `POST` | `/api/v1/holder-blocks` | Create Sperrvermerk (step-up + 4-eyes) |
| `POST` | `/api/v1/holder-blocks/{id}/lift` | Lift Sperrvermerk (step-up + 4-eyes) |

### Regulatory Reporting (`/api/v1/regulatory-reporting`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/regulatory-reporting/mifir` | Trigger on-demand MiFIR export |
| `POST` | `/api/v1/regulatory-reporting/dac8` | Trigger on-demand DAC8 export |
| `GET` | `/api/v1/regulatory-reporting/submissions` | List submission history |

### DORA (`/api/v1/dora`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/dora/incidents` | List open ICT incidents |
| `POST` | `/api/v1/dora/incidents` | Report an ICT incident (Art. 17) |
| `PATCH` | `/api/v1/dora/incidents/{id}/status` | Update incident status / root cause |
| `POST` | `/api/v1/dora/incidents/{id}/report-to-authority` | Record initial/final authority report (Art. 19) |
| `GET` | `/api/v1/dora/providers` | List ICT third-party register (Art. 28) |
| `GET` | `/api/v1/dora/providers/expiring` | List providers with contracts expiring soon |
| `GET` | `/api/v1/dora/resilience-tests` | List resilience test results (Art. 24/25) |
| `GET` | `/api/v1/dora/resilience-tests/overdue` | List overdue resilience tests |
| `POST` | `/api/v1/dora/resilience-tests` | Record a resilience test result |

---

## OpenAPI / Swagger UI

The OpenAPI specification and interactive UI are served **by the backend** on port 8080, not by this documentation server.

| URL | Description |
|---|---|
| [`{{ backend_url }}/swagger-ui.html`]({{ backend_url }}/swagger-ui.html) | Interactive Swagger UI (browser) |
| [`{{ backend_url }}/api-docs`]({{ backend_url }}/api-docs) | OpenAPI 3 JSON (machine-readable) |
| [`{{ backend_url }}/actuator/health`]({{ backend_url }}/actuator/health) | Health check |
| [`{{ backend_url }}/actuator/info`]({{ backend_url }}/actuator/info) | Build info |

!!! info "This documentation site vs. the API"
    This site (port 48003) is a static MkDocs reference — it does not proxy the backend. Open the links above directly in a browser while the stack is running (`docker compose up -d`).

!!! warning "Swagger UI in production"
    The Swagger UI is disabled in the `prod` Spring profile. In development and staging environments it is accessible without authentication. In production it must be explicitly enabled and protected behind an IP allowlist or basic-auth.
