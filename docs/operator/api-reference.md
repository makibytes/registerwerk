---
title: API Reference
---

# API Reference

Registerwerk exposes a REST API for all registry operations. This page is the operator's entry point; the conventions (errors, pagination, idempotency, amounts) are on the [REST API overview](../platform/api.md), and every route is listed in the generated [API route index](../platform/api-routes.md).

## Interactive documentation

The OpenAPI document and Swagger UI are **off by default**. Set `SWAGGER_ENABLED=true` to serve them from the backend:

```
http://localhost:48080/swagger-ui.html
http://localhost:48080/api-docs
```

!!! warning "The spec is unauthenticated when enabled"
    With `SWAGGER_ENABLED=true` these paths are `permitAll`. Nothing refuses the setting in production mode. Keep it off on internet-facing deployments.

## Authentication

All API endpoints except `/api/v1/public/**` (and the onboarding token endpoints) require a Bearer JWT:

```bash
curl http://localhost:48080/api/v1/entities \
  -H "Authorization: Bearer <jwt>"
```

Operator tokens come from `POST /api/v1/public/auth/login` (the operator portal uses it directly). With `ENTRA_ENABLED=true`, customer tokens come from Entra; otherwise from the same local endpoint. See [Security & authentication](../platform/security.md) and, for the customer side, [Signing in](../customer/authentication.md). The backend validates every token itself; Kong does not.

## Where to find an endpoint

| Need | Where |
|---|---|
| Every route, its role expression and its step-up requirement | [API route index](../platform/api-routes.md) |
| Which routes need step-up or a second approver, and which operations bind the request body | [Step-up matrix](../compliance/step-up-matrix.md) |
| Request and response schemas | OpenAPI document (`SWAGGER_ENABLED=true`) |
| Error format, pagination, `Idempotency-Key`, decimal amounts | [REST API overview](../platform/api.md) |

## Error responses

Errors use the `ErrorResponse` record (`status`, `message`, `timestamp`, `path`); the status mapping (400, 401, 403, 404, 409, 500) is documented on the [REST API overview](../platform/api.md#error-response-format). Some endpoints add a machine-readable `code`, for example `IDEMPOTENCY_KEY_REQUIRED`, `IMPERSONATION_READ_ONLY` or `IMPERSONATION_ACTION_DENIED`.

## Rate limiting

Customer API calls pass through Kong, which limits each client IP to 300 requests per minute and 10,000 per hour, counted in Redis so the limit is shared across Kong replicas (see [API gateway](installation/api-gateway.md)). Calls from the operator portal do not pass through Kong and are not subject to this limit. Rate-limit headers (`X-RateLimit-Limit-Minute`, `X-RateLimit-Remaining-Minute`) are included in responses; an exceeded limit is answered `429`.
