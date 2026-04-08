---
id: api-reference
title: API Reference
sidebar_label: API Reference
sidebar_position: 9
---

# API Reference

The eWpG Registry provides a REST API for all registry operations. This page provides an overview of the API structure, authentication, and links to the live interactive documentation.

## Interactive documentation

The Swagger UI is available at:

```
http://localhost:8080/swagger-ui.html
```

For production:

```
https://api.registerwerk.example.com/swagger-ui.html
```

The full OpenAPI 3 specification (JSON) is available at:

```
http://localhost:8080/v3/api-docs
```

## Authentication

All API endpoints (except `/api/v1/public/**`) require a Bearer JWT token:

```bash
curl https://api.registerwerk.example.com/api/v1/issuances \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIs..."
```

See [Authentication](../customer/authentication) for how to obtain a token.

## API groups

### Public endpoints (`/api/v1/public/`)

No authentication required.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/public/chains` | List all enabled chains |
| `GET` | `/api/v1/public/health` | Basic health check |

### Customer endpoints (`/api/v1/`)

Require authentication. Responses are scoped to the authenticated entity.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/issuances` | List issuances for your entity |
| `POST` | `/api/v1/issuances` | Create a new issuance |
| `GET` | `/api/v1/issuances/{id}` | Get issuance details |
| `PUT` | `/api/v1/issuances/{id}` | Update issuance (DRAFT only) |
| `POST` | `/api/v1/issuances/{id}/submit` | Submit for approval |
| `POST` | `/api/v1/issuances/{id}/deploy` | Deploy to blockchain |
| `POST` | `/api/v1/issuances/{id}/suspend` | Suspend token |
| `POST` | `/api/v1/issuances/{id}/redeem` | Mark as redeemed |
| `GET` | `/api/v1/issuances/{id}/investors` | List investors |
| `POST` | `/api/v1/issuances/{id}/investors` | Add investor |
| `DELETE` | `/api/v1/issuances/{id}/investors/{investorId}` | Remove investor |
| `POST` | `/api/v1/issuances/{id}/investors/{investorId}/whitelist` | Whitelist wallet on-chain |
| `GET` | `/api/v1/investments` | List token holdings (investor) |
| `GET` | `/api/v1/transfers` | List transfers for your entity |
| `GET` | `/api/v1/audit-log` | Audit log (scoped to your entity) |
| `GET` | `/api/v1/profile` | Your entity profile |
| `POST` | `/api/v1/wallets` | Register a wallet |
| `DELETE` | `/api/v1/wallets/{address}` | Remove a wallet |

### Admin endpoints (`/api/v1/admin/`)

Require `REGISTRY_ADMIN` role.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/admin/entities` | List all entities |
| `POST` | `/api/v1/admin/entities` | Create entity + send invitation |
| `PATCH` | `/api/v1/admin/entities/{id}/status` | Update entity status |
| `GET` | `/api/v1/admin/kyc` | List pending KYC reviews |
| `POST` | `/api/v1/admin/kyc/{id}/approve` | Approve KYC |
| `POST` | `/api/v1/admin/kyc/{id}/reject` | Reject KYC |
| `POST` | `/api/v1/admin/issuances/{id}/approve` | Approve issuance |
| `POST` | `/api/v1/admin/issuances/{id}/reject` | Reject issuance |
| `GET` | `/api/v1/admin/chains` | List all chains |
| `POST` | `/api/v1/admin/chains` | Add a chain |
| `PATCH` | `/api/v1/admin/chains/{chainId}` | Update chain config |
| `POST` | `/api/v1/admin/chains/refresh` | Reload chain clients |
| `GET` | `/api/v1/admin/audit-log` | Full audit log (all entities) |

## Error responses

All errors follow a standard format:

```json
{
  "timestamp": "2025-04-06T12:00:00Z",
  "status": 422,
  "error": "Unprocessable Entity",
  "code": "ISSUANCE_INVALID_STATE",
  "message": "Cannot submit issuance in state ISSUED",
  "path": "/api/v1/issuances/abc123/submit"
}
```

Common error codes:

| Code | HTTP | Description |
|------|------|-------------|
| `UNAUTHORIZED` | 401 | Missing or invalid JWT |
| `FORBIDDEN` | 403 | Insufficient role for this operation |
| `NOT_FOUND` | 404 | Resource does not exist |
| `ISSUANCE_INVALID_STATE` | 422 | State transition not allowed |
| `BLOCKCHAIN_ERROR` | 502 | RPC call to chain failed |
| `INDEXER_UNAVAILABLE` | 503 | Graph node not reachable |

## Rate limiting

API calls are rate-limited at the Kong gateway:

- 300 requests/minute per authenticated consumer
- 10 requests/minute for authentication-related endpoints

Rate limit headers are included in responses:

```
X-RateLimit-Limit-Minute: 300
X-RateLimit-Remaining-Minute: 287
```

# API Reference

The full OpenAPI specification is available at:

```
http://localhost:8080/v3/api-docs
http://localhost:8080/swagger-ui.html
```

## Key endpoints

### Entities
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/entities` | List all entities |
| `POST` | `/api/v1/entities` | Create entity |
| `GET` | `/api/v1/entities/{id}` | Get entity |
| `PUT` | `/api/v1/entities/{id}` | Update entity |
| `GET` | `/api/v1/entities/{id}/kyc/documents` | List KYC documents |
| `POST` | `/api/v1/entities/{id}/kyc/documents` | Upload KYC document |

### Assets
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/assets` | List all assets |
| `POST` | `/api/v1/assets` | Create asset |
| `GET` | `/api/v1/assets/{id}` | Get asset |
| `POST` | `/api/v1/assets/{id}/deployments` | Deploy to chain |
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/history` | Transfer history |
| `GET` | `/api/v1/assets/{id}/holders` | List holders |

### ERC-3643
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/assets/{id}/deployments/{depId}/erc3643` | Get T-REX suite |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/compliance-modules` | Add module |
| `POST` | `/api/v1/assets/{id}/deployments/{depId}/erc3643/trusted-issuers` | Add issuer |

### ONCHAINID
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/identities` | List identities |
| `POST` | `/api/v1/identities` | Create ONCHAINID |
| `POST` | `/api/v1/identities/{id}/claims` | Issue KYC claim |
| `DELETE` | `/api/v1/identities/{id}/claims/{claimId}` | Revoke claim |

### Admin
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/admin/chains` | List chain configs |
| `POST` | `/api/v1/admin/chains` | Add chain |
| `PUT` | `/api/v1/admin/chains/{id}` | Update chain |
| `POST` | `/api/v1/admin/chains/refresh` | Reload Web3j clients |
| `GET` | `/api/v1/audit` | Query audit log |

### Public (no auth)
| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/public/assets/by-address/{address}` | Lookup token |
| `GET` | `/api/v1/public/chains` | List active chains |
| `GET` | `/api/v1/onboarding/token-info/{token}` | Validate onboarding token |
