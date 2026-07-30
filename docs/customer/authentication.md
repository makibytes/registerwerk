---
id: authentication
title: Authentication
sidebar_label: Authentication
---

# Authentication

The eWpG Registry uses OpenID Connect (OIDC) for authentication. All access is federated — there are no registry-specific passwords to manage.

## Login options

### Microsoft Entra ID (default)

The registry operator pre-configures Microsoft Entra ID (formerly Azure Active Directory) as the default identity provider. This is used for:

- Registry operator staff
- Customer organizations that have not configured their own IdP

To log in:

1. Navigate to the customer portal at `https://portal.registerwerk.example.com`
2. Click **Sign in with Microsoft**
3. Enter your corporate Microsoft account email address
4. Complete two-factor authentication (required — see below)
5. You are redirected back to the portal

:::note
Your Microsoft account must belong to the same tenant that the registry operator has whitelisted. If you see an "Account not recognized" error, contact your registry operator.
:::

### Two-factor authentication

Two-factor authentication is **required** for the customer portal in production. It is enforced by
Microsoft Conditional Access during sign-in, not by the portal itself: if you have not yet
registered a second factor, Microsoft prompts you to do so before you can continue.

The **Security** page (user menu → Security) shows your current status and walks you through
setup. Because Microsoft owns the credential, the setup code you scan is shown on Microsoft's own
security-info page — the registry never sees or stores it. The QR code on our Security page is
simply a link to that Microsoft page, so you can move from your desktop to the phone that will
hold the authenticator.

To set up Microsoft Authenticator:

1. Install **Microsoft Authenticator** on your phone.
2. Open **Security** in the portal and scan the QR code, or select **Set up now**.
3. Add a sign-in method on Microsoft's page and follow its instructions.
4. Return to the portal and select **I've finished** — the page re-checks and confirms.

#### Lost or replaced your phone?

Contact the registry operator. After verifying your identity out-of-band, they will remove your
old authentication methods, sign out your existing sessions, and issue a **Temporary Access
Pass** — a short-lived, usually single-use code that lets you sign in once and register a new
method. Deliver-and-use it promptly: it typically expires within the hour.

If your organisation runs its own Microsoft Entra tenant (see below), the registry operator
cannot do this — contact your own IT administrator instead.

### Self-managed identity provider

Organizations that completed IdP configuration during [onboarding](./onboarding) sign in through
their own Microsoft Entra tenant. Registry access is then established **tenant-to-tenant** in
Microsoft Entra (B2B collaboration and cross-tenant access settings) — the registry never runs an
authorization-code flow against your tenant and therefore never asks for a client secret. Only
your issuer URL and client id are recorded, for identification.

With this model your own tenant issues the credentials, so:

- your administrators control which authentication methods are available and how strong they are;
- multi-factor authentication performed in your tenant is accepted here only if the registry
  operator has configured inbound MFA trust;
- **the registry operator cannot reset your users' 2FA** — your own helpdesk does.

The registry supports any OIDC-compliant identity provider that can issue JWTs with the following standard claims:

| Claim | Description |
|-------|-------------|
| `sub` | Unique user identifier (stable across sessions) |
| `email` | User email address |
| `name` | Display name |
| `groups` or `roles` | Used to map registry roles (optional) |

### Role mapping

If your IdP includes a `roles` or `groups` claim, the registry can map IdP groups to registry roles automatically. Contact the registry operator to configure the mapping. For example:

```json
{
  "sub": "abc123",
  "email": "alice@example.com",
  "roles": ["issuer-admin", "company-admin"]
}
```

## API access with JWT tokens

If you need to call the REST API directly (e.g., from an integration script), you must obtain a JWT access token from your identity provider and include it in the `Authorization` header.

### Obtaining a token (client credentials flow)

For machine-to-machine integrations, use the client credentials grant:

```bash
curl -X POST https://login.microsoftonline.com/{tenant-id}/oauth2/v2.0/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id={your-client-id}" \
  -d "client_secret={your-client-secret}" \
  -d "scope=api://registerwerk/.default"
```

### Using the token

Include the token in every API request:

```bash
curl https://api.registerwerk.example.com/api/v1/issuances \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIs..."
```

Tokens are valid for 1 hour by default. Your integration should handle token refresh automatically.

:::warning
Never embed access tokens in frontend code or commit them to version control. Use environment variables or a secrets manager.
:::

## Session management

- Portal sessions last 8 hours by default
- Idle sessions time out after 2 hours of inactivity
- All sessions are terminated immediately on password change or IdP-side logout

## Troubleshooting login issues

| Error | Likely cause | Solution |
|-------|-------------|----------|
| "Account not recognized" | User not in whitelisted tenant | Contact registry operator |
| "Access denied" | Missing role assignment | Ask your company admin to assign a role |
| "Token expired" | Session timed out | Log in again |
| Redirect loop | Misconfigured redirect URI | Contact registry operator |
