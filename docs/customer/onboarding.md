---
id: onboarding
title: Onboarding
sidebar_label: Onboarding
---

# Onboarding

This guide walks you through registering your organization with the eWpG Registry — from the first invitation email to having a fully configured account.

## How onboarding works

Onboarding is initiated by the registry operator, not by self-registration. The process follows these four steps:

```
Operator creates entity
        |
        v
You receive an invitation email with a one-time token
        |
        v
You redeem the token and configure your organization
        |
        v
Admin activates your account — you can start working
```

## Step 1 — Receive your invitation

The registry operator creates an entity (company or individual) on your behalf. You will receive an email from the registry with the subject **"Your eWpG Registry Invitation"** containing:

- A one-time **onboarding token** (valid for 72 hours)
- A link to the customer portal

:::warning Token expiry
The onboarding token expires after 72 hours. If it has expired, contact the registry operator to request a new one. Do not share the token — it grants full setup access to your account.
:::

## Step 2 — Redeem the token

1. Click the link in the invitation email. You will be taken to the customer portal.
2. You will be asked to log in via your identity provider (see [Authentication](./authentication)). For new users, this is typically Microsoft Entra ID (formerly Azure AD) with your corporate email address.
3. After logging in, the portal detects your onboarding token from the URL and activates your entity automatically.
4. You are redirected to the **Welcome** screen, which shows your assigned role (Issuer, Investor, or Auditor).

## Step 3 — Configure your organization

After redeeming the token you can configure your organization profile:

### Organization details

Navigate to **Settings → Organization** and fill in:

| Field | Description |
|-------|-------------|
| Legal name | Your registered company name |
| LEI | Legal Entity Identifier (required for issuers) |
| Registration number | Company registration number |
| Jurisdiction | Country of incorporation |
| Contact email | Primary contact for regulatory notifications |

### User management

If your organization has multiple users, navigate to **Settings → Users** and invite them by email. Each invited user:
- Receives their own invitation email
- Logs in with their own corporate identity
- Is assigned one of your organization's roles

### Configuring your own identity provider (optional)

If your organization uses a custom identity provider (e.g., your own Keycloak, Okta, or another OIDC-compatible IdP), you can configure it under **Settings → Identity Provider**.

You will need to provide:

```
OIDC Issuer URL:       https://your-idp.example.com/realms/your-realm
Client ID:             registerwerk-client
Client Secret:         (provided by your IdP admin)
Redirect URI:          https://portal.registerwerk.example.com/auth/callback
```

:::tip
The redirect URI is shown on the settings page. Copy it exactly into your IdP configuration. The registry only supports the `authorization_code` flow with PKCE.
:::

Once configured and verified, all users in your organization will be redirected to your IdP for authentication instead of the default Entra ID login.

## Step 4 — Account activation

Your account is now active. Depending on your role:

- **Issuers**: You may be asked to complete a KYC/AML review before you can deploy tokens to mainnet. See [Creating an issuance](./issuers/creating-issuance).
- **Investors**: Your account is ready. You can connect a wallet and view your holdings.
- **Auditors**: Your account is ready. You have read-only access to all registry data.

## Need help?

If you encounter problems during onboarding, contact the registry operator using the support link in the invitation email or the **Help** button in the portal footer.
