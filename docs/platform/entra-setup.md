---
title: Microsoft Entra ID Setup
description: App registrations, Conditional Access, Graph permissions and the tenant smoke test for production 2FA.
---

# Microsoft Entra ID Setup

This is the runbook for putting the customer portal behind Microsoft Entra ID with enforced
two-factor authentication. Nothing here applies to local or demo deployments — with
`ENTRA_ENABLED=false` (the `docker-compose.yml` default) the portal uses built-in
username/password login and no second factor.

**Requires Microsoft Entra ID P1** for Conditional Access and authentication contexts.

---

## What Registerwerk can and cannot do

Two constraints shape the whole design, and are worth understanding before you start:

**We cannot issue you a QR code for Microsoft Authenticator.** Microsoft Graph exposes no way to
create an authenticator or TOTP method — `softwareOathMethods` and `microsoftAuthenticatorMethods`
support only list, get and delete, and `secretKey` is documented as always returning `null`. Entra
owns the secret. Registration therefore happens on Microsoft's
[combined security-info page](https://learn.microsoft.com/en-us/entra/identity/authentication/concept-registration-mfa-sspr-combined),
and Registerwerk's `/security` page guides users there. The QR code we render encodes the *link*
to that page, so a user at a desktop can continue on the phone that will hold the credential.

**Entra External ID (CIAM) cannot be used** if you want Microsoft Authenticator: external tenants
support only email OTP, SMS (a paid add-on) and passkeys. Customers must be members or B2B guests
in a workforce tenant, or federated from their own.

---

## 1. App registrations

Two registrations. Keep them separate: the API holds a client secret and must never be a public
client.

### API — the backend

| Setting | Value |
|---|---|
| Name | `Registerwerk API` |
| Application ID URI | `api://<api-client-id>` |
| Exposed scope | `access_as_user` (admin + user consent) |
| Client secret | Generate one → `ENTRA_CLIENT_SECRET` |

**Optional claims on the access token** — add all three under *Token configuration*:

| Claim | Why it matters if missing |
|---|---|
| `acrs` | Entra never adds the authentication context opportunistically, so every step-up action costs a full browser redirect. This looks exactly like an application bug. |
| `xms_cc` | The API cannot tell that the client understands claims challenges. |
| `auth_time` | Step-up freshness silently falls back to `iat`, a materially weaker guarantee. The backend logs a warning the first time it sees a token without it. |

### SPA — the customer frontend

| Setting | Value |
|---|---|
| Name | `Registerwerk Customer Portal` |
| Platform | Single-page application |
| Redirect URI | `https://<customer-portal-host>/` |
| API permission | `api://<api-client-id>/access_as_user` |

No client secret — it is a public client. The SPA advertises `clientCapabilities: ['CP1']` in
code; nothing to configure here.

---

## 2. Conditional Access

### Require MFA to sign in

Create a policy targeting the API application, granting access only with **Require multifactor
authentication** — or, better, an **authentication strength**. The built-in strengths are
*Multifactor authentication*, *Passwordless MFA* and *Phishing-resistant MFA*; the two grant
controls cannot be combined in one policy.

> Authentication strength applies only to external users who authenticate **with Entra ID**. For
> email-one-time-passcode, SAML/WS-Fed or Google-federated guests, use the plain MFA grant control
> instead.

### Authentication context for step-up

1. **Entra ID → Conditional Access → Authentication context** → create a context (c1–c99), e.g.
   `Registerwerk regulator-grade action`.
2. **Tick "Publish to apps".** An unpublished context is invisible to resources and can never be
   satisfied — the symptom is a sign-in redirect loop with nothing in the logs. Registerwerk
   verifies this at startup and refuses to boot in production mode if it is unpublished.
3. Create a policy with that context as its target resource, granting access only with your chosen
   authentication strength, and set **Sign-in frequency: Every time**.
4. Set its id as `ENTRA_STEPUP_AUTH_CONTEXT_ID`.

Sign-in frequency is the real freshness control for step-up: an access token lives 60–90 minutes
and the `acrs` claim persists for its lifetime, so without it a token stays "stepped up" long after
the user walked away.

### Register security information

Force enrolment at first sign-in with the **user action "Register security information"** (it is a
user action, not a cloud app), or with the ID Protection MFA registration policy.

---

## 3. Microsoft Graph — the operator support console

Only needed for the customer 2FA status page and the operator lost-phone console. Set
`ENTRA_SUPPORT_ENABLED=true` and grant the API registration:

| Permission | Type |
|---|---|
| `UserAuthenticationMethod.ReadWrite.All` | Application |
| `User.RevokeSessions.All` | Application |

Grant admin consent, then assign the service principal the **Authentication Administrator**
directory role. Deliberately *not* Privileged Authentication Administrator: Authentication
Administrator can act on members but not on admins, which is the containment you want for a
credential that lives in an application's configuration.

Also enable **Temporary Access Pass** under *Authentication methods → Policies* and scope it to the
customer group — a TAP can be created for any user, but only users in policy scope can sign in with
one.

---

## 4. Federated customers

For a customer who keeps their own Entra tenant:

1. Set their legal entity's `identity_model` to `FEDERATED` and record their issuer URL (the tenant
   id is derived from it).
2. Configure **cross-tenant access settings** in Entra for inbound B2B collaboration.
3. Decide whether to trust MFA from their tenant, and record it in `idp_mfa_trusted`. This is
   operator-controlled: a customer vouching for their own MFA could otherwise lower the bar applied
   to their own users.

Registerwerk cannot manage a federated user's authentication methods — the support console shows
their tenant id and refuses every mutating action with a 409 rather than making a Graph call that
would fail confusingly.

Note that a **Temporary Access Pass cannot be issued to an external guest** at all. The console
detects this (guest `userType` plus `#EXT#` in the UPN) and disables the button with an
explanation.

---

## 5. Environment

```bash
ENTRA_ENABLED=true
JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
JWT_AUDIENCE=api://<api-client-id>          # or the bare client id — must match the token's aud

ENTRA_TENANT_ID=<tenant-id>
ENTRA_CLIENT_ID=<api-client-id>
ENTRA_CLIENT_SECRET=<api-client-secret>
ENTRA_SPA_CLIENT_ID=<spa-client-id>
ENTRA_API_SCOPE=api://<api-client-id>/access_as_user

ENTRA_SUPPORT_ENABLED=true
ENTRA_STEPUP_AUTH_CONTEXT_ID=c1
```

`JWT_AUDIENCE` is not optional in production. Entra signs every token for a tenant with the same
keys, so without an audience check a token issued to *any other application in your tenant* is
accepted here as a Registerwerk session. `ProductionReadinessCheck` refuses to start without it.

The operator portal is unaffected by all of this: it keeps built-in HS256 login and local TOTP
step-up, which is why `JWT_DEV_SECRET` still matters even in a fully Entra-enabled deployment.

---

## 6. Tenant smoke test

Several behaviours cannot be verified without a real tenant. Work through this list before
declaring the deployment good.

- [ ] **`/actuator/health/entra` reports UP**, with a non-zero published authentication-context
      count. This covers Graph reachability, token acquisition and context availability in one call.
- [ ] **Sign in as a test customer.** Conditional Access should force MFA registration if none
      exists.
- [ ] **Decode the access token.** Confirm `aud` matches `JWT_AUDIENCE`, and that `acrs`,
      `xms_cc` and `auth_time` are present. If `acrs` is missing, re-check the optional claims —
      this is the single most common misconfiguration.
- [ ] **Call a step-up endpoint.** Expect a 401 with `error="insufficient_claims"`, then a
      redirect, then success. If instead every call redirects, `acrs` is not being issued
      opportunistically.
- [ ] **Open `/security`.** It should show registered methods and a "last checked" time.
- [ ] **Run the lost-phone flow end to end** against a test account: reset methods → revoke
      sessions → issue a TAP → sign in with the TAP → register a new method. Confirm the TAP
      appears exactly once in the UI and appears nowhere in `audit_event`.
- [ ] **Try the TAP flow against an external guest.** The button should be disabled with an
      explanation, not fail at Graph.
- [ ] **Confirm `audit_event` rows exist** for each operator action, with the correct `actor_id` —
      this is what the principal-normalisation filter exists to get right.

### Known uncertainties

These depend on tenant configuration and on Microsoft behaviour that is not fully documented:

- Whether Entra refuses to delete a user's **default** authentication method while others remain.
  The adapter deletes the default last and reports per-method failures rather than assuming.
- Exact TAP behaviour for an internal-but-guest account; the `#EXT#` heuristic distinguishes
  external guests and should be confirmed empirically.
- Whether cross-tenant MFA trust satisfies an authentication-context requirement for federated
  users. Microsoft documents that FIDO2, Windows Hello and certificate-based auth satisfy strength
  only in the user's *home* tenant.
- Graph throttling under sustained `/two-factor/refresh` polling. The backend throttles per user,
  but tenant-wide limits still apply.
