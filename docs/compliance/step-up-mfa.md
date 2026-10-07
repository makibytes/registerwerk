---
title: Step-Up MFA & 4-Eyes
description: Step-up authentication and dual-control (4-eyes) for high-risk regulated operations.
---

# Step-Up MFA & 4-Eyes

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This page describes intended control mappings. It is not evidence that the configured MFA or
    dual-control flow satisfies a particular legal, regulatory, security, or segregation-of-duties
    requirement. Roles, protected actions, assurance level, recovery, and audit evidence require
    deployment-specific review.

Certain operations in Registerwerk are so consequential — or so clearly required to have dual oversight by regulation — that a normal login session is not sufficient. **Step-up authentication** requires the operator to re-prove their identity at the moment of executing the operation. The **4-eyes principle** (Vier-Augen-Prinzip) further requires a second, independent approver to confirm before the action executes.

---

## Why this exists

| Regulation | Obligation |
|---|---|
| GwG §6(2) | Internal control systems — high-risk decisions require documented dual oversight |
| eWpG §16 | Blocking operations (Sperrvermerk) must be traceable to a named, verified operator |
| BaFin KAIT | IT security requires MFA for privileged access to critical systems |
| DSGVO Art. 32 | Appropriate technical measures to protect personal data — MFA is baseline |

---

## Protected operations

The `@RequiresStepUp` annotation is placed on the following endpoints and service methods. Operations marked **4-eyes** additionally require a second approver.

| Operation | Step-up | 4-Eyes | Reason |
|---|---|---|---|
| `forceTransfer` | ✅ | ✅ | Irreversible on-chain operation |
| `forceBurn` | ✅ | ✅ | Permanent destruction of tokens |
| `forceApprove` | ✅ | ✅ | Compliance override |
| `setSupplyCap` | ✅ | ✅ | Economic parameter change |
| ERC-3525 slot creation, slot mint, forced value transfer | ✅ | ✅ | Creates slot supply cap / mints bond value into a slot |
| KYC override (approve despite flag) | ✅ | ✅ | AML gate bypass |
| Sperrvermerk create | ✅ | ✅ | Legal restriction on holder |
| Sperrvermerk lift | ✅ | ✅ | Legal restriction removal |
| Start impersonation | ❌ ¹ | ❌ | Privileged access to customer data |
| Screening hit accept | ✅ (high-score) | ✅ (score ≥ 80) | AML override for confirmed hit |
| Wallet private key export (break-glass) | ✅ | ✅ | Key material access |
| Entra: delete one authentication method | ✅ | ❌ | Removes one stale factor |
| Entra: reset all authentication methods | ✅ | ✅ | Forces MFA re-registration for another person |
| Entra: revoke sign-in sessions | ✅ | ❌ | Availability impact only, no privilege gain |
| Entra: issue Temporary Access Pass | ✅ | ✅ | A bearer credential that authenticates *as* the customer |

¹ `AdminImpersonationController` carries no `@RequiresStepUp`, and impersonation is refused
outright when `ENTRA_ENABLED=true`.

---

## Two tracks

How the second factor is proved depends on who issues session tokens. Both are enforced by the
same `@RequiresStepUp` annotation and the same aspect; only the check differs.

### Local TOTP — `ENTRA_ENABLED=false`, and the operator portal always

RFC 6238 TOTP (HMAC-SHA1, 30-second window, 6 digits), verified by
`StepUpTokenIssuer`. Enrol at `POST /api/v1/auth/step-up/enroll`, confirm at
`/enroll/confirm`, then exchange a code at `POST /api/v1/auth/step-up` for a short-lived token
carrying `acr=stepup`, valid 10 minutes. The caller sends that token in place of their session
token on the protected request. Rejection is **403**.

> **WebAuthn / FIDO2 is not implemented.** The `method` field on the step-up request is accepted
> and ignored. Earlier versions of this document described it as the primary factor; it never
> existed in the code. Under Entra sign-in, phishing-resistant MFA is available — but through
> Conditional Access, not through this module.

### Entra authentication context — `ENTRA_ENABLED=true`

The access token must carry the required Conditional Access authentication context in its `acrs`
claim. Registerwerk does not verify a factor itself; it states a requirement and lets Conditional
Access decide what satisfies it — which is what allows an operator to demand phishing-resistant
MFA for forced transfers without a code change.

Rejection is a **401 claims challenge**, so the SPA re-authenticates for that one action instead
of logging the user out:

```
WWW-Authenticate: Bearer realm="", authorization_uri="…",
                  error="insufficient_claims", claims="<base64>"
```

The context id is configuration, keyed on `@RequiresStepUp(reason = …)`:

```yaml
registerwerk.auth.step-up.entra:
  auth-context-id: c1                 # ENTRA_STEPUP_AUTH_CONTEXT_ID
  reason-overrides:
    FORCE_BURN_EWG26: c2
    "Payment rail creation": c1       # quote reasons containing spaces
```

It is validated against the tenant at startup: a context that does not exist, or exists but is
**not published to apps**, fails startup in production mode. An unpublished context can never be
satisfied and produces a sign-in redirect loop with nothing in the logs to explain it.

#### Freshness works differently here

An Entra access token lives 60–90 minutes and `acrs` persists for its whole lifetime, so applying
`maxAgeMinutes` to `iat` would force a full browser redirect on nearly every protected call.
Instead:

- the **primary** freshness control is the Conditional Access policy on the authentication
  context (set *Sign-in frequency: Every time* for regulator-grade actions);
- `maxAgeMinutes` is checked against the `auth_time` claim as a backstop.

`auth_time` is an optional claim that must be requested on the API app registration. Without it
the check falls back to `iat`, which is weaker — the backend logs a warning the first time it
sees an Entra token lacking it.

---

## 4-Eyes implementation

The current dual-control enforcement requires two distinct `REGISTRY_ADMIN` users. There is no
`SECOND_APPROVER` application role, and a `COMPLIANCE_OFFICER` is not accepted as a substitute
unless the implementation is changed and separately reviewed.

**4-eyes is identical in both tracks**: a dual-control token is always minted locally after TOTP
verification and always validated against the local HS256 decoder, so it does not depend on how
the primary factor was proved.

```mermaid
sequenceDiagram
    participant Initiator
    participant Approver
    participant Backend

    Approver->>Backend: POST /api/v1/auth/step-up { code, action, target[, targetBody] }
    Backend-->>Approver: approver token (acr=stepup, stepup_scope, stepup_target, jti; 5 min, single use)
    Approver->>Initiator: Hand over the approver token
    Initiator->>Backend: POST /api/v1/auth/step-up { code, action }
    Backend-->>Initiator: initiator step-up token
    Initiator->>Backend: Protected call — Authorization: initiator token,<br/>X-Dual-Control-Token: approver token
    Backend->>Backend: Validate both, then execute + audit with both identities
```

Key invariants enforced by `StepUpEnforcementAspect` and `StepUpTokenValidator`:

- Initiator and approver **must be different users** (`sub` comparison)
- The approver's token must carry `stepup_scope` **exactly equal** to the annotation's `reason` —
  otherwise one approval would be a generic credential valid for any 4-eyes action in its window
- The approver must still be an **enabled `REGISTRY_ADMIN` in the database**, not merely per the
  token's claims, which reflect status only as of mint time
- The approval is **bound to the request it was given for** (K3). The approver mints it with `action` *and* `target` (`"METHOD /path?query"` of the exact call; also `targetBody`, the JSON body of the request, which every reason binds). The token carries `stepup_target`, the base64url SHA-256 of the canonical request (`v1`, upper-case method, path without trailing slash, sorted query, and the hash of the canonical JSON body: sorted keys, no whitespace, exact plain decimal numbers). The backend derives the same digest from the live request; if it differs, the call is refused with **403**. Tokens without a target are no longer accepted
- **Approver tokens are header-only.** An approval carries `use=dual_control` and the audience `registerwerk-dual-control`. It is accepted in `X-Dual-Control-Token` and nowhere else: as an `Authorization: Bearer` (or session cookie) on any endpoint, including `@RequiresStepUp` ones, it is refused with **403**, so an approval someone holds can never be replayed as another person's own session. Ordinary step-up tokens (no scope, no marker) remain the caller's own proof.
- **The body is always bound.** Every reason binds the canonical request body (`targetBody`, omitted when the request has none). The exceptions are listed in `registerwerk.auth.step-up.dual-control.body-opt-out-reasons`: payloads that are not JSON (term-sheet upload, keystore import, CASP CSV import) and payloads that are secret key material or a keystore password (raw key import, keystore export); method, path and query stay bound for these. Numbers are exact decimals, never doubles; a body with a repeated JSON key or a request with a repeated query parameter cannot be bound and is refused. The approval token stays single-use even if `bind-target-reasons` is narrowed.
- **Bootstrap is a one-way door.** The single-step-up exception applies only until two enabled, TOTP-enrolled `REGISTRY_ADMIN`s have existed at once. The database records that moment (`dual_control_bootstrap`, set by trigger, never cleared); from then on the exception never returns, even if an administrator is later disabled or loses their authenticator. Disabling or deleting an operator, `REGISTRY_ADMIN`, `COMPLIANCE_OFFICER` or `AUDIT` account needs the second approver (`OPERATOR_USER_DISABLE`, `OPERATOR_USER_DELETE`).
- The approval is **single use**: its `jti` is written to `dual_control_token_use` together with the audit event in one transaction (a second use, on any replica, is a **403**). An action that fails after the approval was consumed needs a fresh approval
- The approval is only accepted for a **short window** after it was minted (`registerwerk.auth.step-up.dual-control.window-seconds`, default 300 s); the initiator's own step-up token keeps its 10 minutes

---

## TOTP enrolment, storage and reset

- **No trust on first use.** Starting an enrolment (`POST /api/v1/auth/step-up/enroll`) requires the account's current password in the body (`{ "currentPassword": "…" }`), so a stolen or unattended session cannot bind an attacker's authenticator. Wrong passwords count towards the same lockout as wrong codes. Accounts whose second factor is managed by an external identity provider cannot enrol a local authenticator. Confirming (`/enroll/confirm`) consumes the code's time step, so it cannot be replayed as a step-up code.
- **Encrypted at rest.** The TOTP secret is envelope-encrypted (AES-256-GCM, a fresh data key per value wrapped by the platform KEK, the user id as additional authenticated data) in `app_user.totp_secret`; `totp_secret_kid` records the KEK provider. Secrets stored in plaintext by earlier versions are encrypted by a startup job; verification refuses a secret that is still plaintext (an administrator resets the enrolment).
- **State shared across replicas.** Replay protection (RFC 6238 §5.2: a code at or before the last accepted time step is refused) and the brute-force lockout (5 wrong or replayed codes lock step-up for 15 minutes) live in the table `totp_state` and are updated atomically, so a code accepted on one replica is refused on every other. Each attempt is reserved before the code is compared, so parallel guesses share one budget of five.
- **Self-service removal.** `POST /api/v1/auth/step-up/disenroll { "code": "…" }` requires a valid current code, clears the enrolment and ends the user's sessions. The user must enrol again before any step-up action.
- **Operator reset (lost device).** `POST /api/v1/admin/users/{id}/totp-reset` requires step-up **and** a second approver (reason `TOTP_RESET`). It clears the enrolment, ends the user's sessions and writes the audit event `TOTP_RESET` with both identities; you cannot reset your own enrolment this way. The user enrols again at next login.
- **Monitoring.** The gauge `registerwerk_stepup_unenrolled_operators` counts enabled local `REGISTRY_ADMIN` / `COMPLIANCE_OFFICER` accounts older than seven days without an authenticator. It is a warning metric, not a startup failure; alert on values above zero.

Lifecycle audit events: `TOTP_ENROLMENT_STARTED`, `TOTP_ENROLLED`, `TOTP_DISENROLLED`, `TOTP_RESET`; `DUAL_CONTROL_APPROVED` now also records the approval's token id and target digest, and `DUAL_CONTROL_BOOTSTRAP_USED` marks the single-actor exception while fewer than two TOTP-enrolled administrators exist.

---

## AOP enforcement

The `StepUpEnforcementAspect` intercepts any method annotated with `@RequiresStepUp` and:

1. Reads the authenticated JWT from the security context
2. Branches on the active track:
   - **local** — requires `acr=stepup` and `iat` within `maxAgeMinutes` (default 10); failure is **403**
   - **Entra** — requires `acrs` to contain the configured authentication context and `auth_time`
     within `maxAgeMinutes`; failure is a **401 claims challenge**
3. If `requireSecondApprover = true`, validates the `X-Dual-Control-Token` header and exposes the
   approver's id as request attribute `stepup.dualControlApproverId`, which controllers read with
   `@RequestAttribute` — they must not re-decode the token themselves
4. The claims challenge is emitted by `ClaimsChallengeAdvice`, not by Spring Security: the
   exception is thrown from an AOP `@Around` and so is resolved by `@RestControllerAdvice`, and
   Spring Security's `BearerTokenAuthenticationEntryPoint` has no code path that can serialise a
   `claims=` parameter anyway

---

## Audit events

Every step-up authentication event and every protected operation generates an `AuditEvent`:

| Event type | Contents |
|---|---|
| `STEP_UP_ISSUED` | User ID, method, timestamp |
| `DUAL_CONTROL_INITIATED` | Initiator ID, operation type, operation parameters hash |
| `DUAL_CONTROL_CONFIRMED` | Approver ID, operation type, confirmed_token reference |
| `PROTECTED_OPERATION_EXECUTED` | Both user IDs, operation type, full operation parameters |
| `STEP_UP_FAILED` | User ID, failure reason, IP address |

These events are part of the tamper-evident [audit chain](../platform/audit-log.md) and cannot be deleted or modified.
