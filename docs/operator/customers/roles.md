---
id: roles
title: Roles and Permissions
sidebar_position: 3
---

## Role definitions

| Role | Description |
| --- | --- |
| `ROLE_REGISTRY_ADMIN` | Full access — manages all entities, assets, chains, and audit log |
| `ROLE_COMPLIANCE_OFFICER` | KYC/KYB workflow role for approvals without configured checklist gaps and for rejections; not a legal compliance determination |
| `ROLE_ISSUER` | Can create and manage own assets; view own entity |
| `ROLE_INVESTOR` | Can view own holdings; read-only on public asset data |
| `ROLE_COMPANY_ADMIN` | Can manage users and IdP settings for own entity |
| `ROLE_AUDIT` | Read-only access to audit log and all asset data |

## Role assignment

Roles are defined in the OIDC provider (Entra ID App Roles or Keycloak Realm Roles) and included in the JWT `roles` claim. The backend reads them via `JwtEntityClaimsConverter`.

## Entity-scoped access

Most operations are entity-scoped. Even a `ROLE_ISSUER` can only:

- Read/update **their own** legal entity
- Create/manage **their own** assets
- View holders of **their own** tokens

Cross-entity access requires `ROLE_REGISTRY_ADMIN`.

## Compliance override policy

- `ROLE_COMPLIANCE_OFFICER` can approve jurisdiction KYC only when the configured checklist reports no gaps.
- Approvals with configured checklist gaps require an explicit `overrideNote` and are restricted to `ROLE_REGISTRY_ADMIN`.
- Every override is written to the audit trail with compliance gap counters (`missingCount`, `expiredCount`, `tooOldCount`).

## Fine-grained authorization

The backend uses `@PreAuthorize` annotations with SpEL components:

```java
@PreAuthorize("hasRole('REGISTRY_ADMIN') or @entityOwnershipChecker.isOwner(#entityId, authentication)")
public LegalEntity getEntity(UUID entityId) { ... }
```

## Typical setup per customer type

| Customer Type | Roles |
| --- | --- |
| Issuer company admin | `ROLE_ISSUER`, `ROLE_COMPANY_ADMIN` |
| Issuer user | `ROLE_ISSUER` |
| Investor | `ROLE_INVESTOR` |
| External auditor | `ROLE_AUDIT` |
| Registry operator | `ROLE_REGISTRY_ADMIN` |
