---
title: Signing in
description: How you sign in, what two-factor authentication does, and what to do when you cannot get in.
---

# Signing in

How you sign in depends on how your registry operator has configured the platform. There are two modes, and they behave differently enough that it is worth knowing which one you are in.

**The quickest way to tell:** if the sign-in page shows an email and password box, you are in local mode. If it shows a **Sign in with Microsoft** button, you are in Entra mode.

---

## The two modes

=== "Local mode — the default"

    You sign in with an email address and a password held by the registry itself.

    **No second factor at sign-in.** This is the default configuration and what you get from a standard `docker compose up`. It is intended for local, demo and evaluation deployments.

    Your password can be reset through the normal reset-password flow.

=== "Entra mode — production"

    You sign in with **Microsoft Entra ID**, using your organisation's Microsoft account, and **two-factor authentication is required**.

    The registry never sees your password. Microsoft authenticates you and issues a token; the registry validates it.

!!! info "Operator staff always use built-in login"
    Even in Entra mode, registry operator staff sign in with a username and password and use a local authenticator app for sensitive actions.

    Only the **customer** portal moves to Entra. If you have read that Entra is the default for everybody including operator staff, that was wrong — it has never been how the platform behaves.

---

## Two-factor authentication

Applies in Entra mode.

Two-factor authentication is **required** for the customer portal in production. It is enforced by Microsoft Conditional Access during sign-in, **not by the portal** — if you have not registered a second factor, Microsoft prompts you before you can continue. You never reach Registerwerk unenrolled.

The **Security** page (user menu → Security) shows your status and walks you through setup.

!!! note "Why the registry cannot give you a setup QR code"
    Microsoft owns the credential. Its API provides no way to create an authenticator or TOTP method — the secret is never disclosed to anybody, including the registry.

    So the code you scan is shown on **Microsoft's own security-info page**. The QR code on our Security page is simply a **link to that page**, so you can move from your desktop to the phone that will hold the authenticator.

    This is a constraint of Entra, not a missing feature. No software can do it differently.

**To set up Microsoft Authenticator:**

1. Install **Microsoft Authenticator** on your phone.
2. Open **Security** in the portal and scan the QR code, or select **Set up now**.
3. Add a sign-in method on Microsoft's page and follow its instructions.
4. Return to the portal and select **I've finished** — the page re-checks and confirms.

### Lost or replaced your phone

Contact the registry operator. After verifying your identity out of band they will remove your old methods, **sign out your existing sessions**, and issue a **Temporary Access Pass** — a short-lived, usually single-use code letting you sign in once to register a new method.

Use it promptly; it typically expires within the hour.

!!! warning "If your organisation runs its own Entra tenant, the operator cannot help"
    Your users are in *your* directory, not theirs. They cannot reset your authentication methods and the support console will refuse to try.

    Contact your own IT helpdesk.

---

## If your organisation uses its own identity provider

Organisations that configured an identity provider during [onboarding](onboarding.md) sign in through their **own Microsoft Entra tenant**.

Access is established **tenant-to-tenant** in Entra, using B2B collaboration and cross-tenant access settings. The registry never runs an authorisation-code flow against your tenant and therefore **never asks for a client secret** — only your issuer URL and client id, for identification.

With this model:

- Your administrators control which authentication methods are available and how strong they are.
- Multi-factor authentication performed in your tenant is accepted here **only if the registry operator has configured inbound MFA trust**. That is the operator's decision, not yours — a customer vouching for their own MFA would be a way to lower the bar applied to their own users.
- **The registry operator cannot reset your users' second factors.** Your helpdesk does.

---

## Where your permissions come from

!!! danger "Your identity provider does not decide what you can do"
    This surprises administrators, and getting it wrong has real consequences.

    Entra answers *who is this person*. **Registerwerk answers what they may do**, from its own user record. Entra app roles are consulted only once, when your account is first created, to pick a sensible default.

    So: **removing somebody's app role in Entra does not remove their Registerwerk permissions.** An administrator who does that and assumes access is revoked will be wrong.

    To change what somebody can do, change it in Registerwerk — your [company administrator](workspaces/company-admin.md) does this. To stop them signing in at all, disable the account in Entra.

Older documentation described roles being mapped from a `roles` or `groups` claim in your token. That is not how it works, and configuring such a claim will have no effect here.

---

## Sessions

Sessions last **8 hours** by default, after which you sign in again.

In Entra mode your organisation's Conditional Access policies may require you to re-authenticate sooner, and sensitive actions can demand fresh proof of identity regardless of how long your session has left. That is [step-up authentication](../compliance/step-up-mfa.md), and it is working as intended rather than a session problem.

---

## Calling the API directly

For integrations, obtain a token and send it as `Authorization: Bearer <token>`.

In **Entra mode**, get the token from Entra using your own app registration and the scope your operator gives you. In **local mode**, `POST /api/v1/public/auth/login` returns one.

!!! warning "Never put a token in frontend code or a repository"
    Use environment variables or a secrets manager. A leaked token is a session as you, for its remaining life.

[:octicons-arrow-right-24: API overview](../platform/api.md)

---

## When you cannot get in

| What you see | Usually means | Do |
|---|---|---|
| **Account not recognised** | Your Microsoft account is not in a tenant the operator has admitted | Contact the operator |
| **Access denied** after signing in | You signed in fine; you lack a role | Ask your company administrator |
| **A prompt to register security info** | Two-factor not yet set up | Follow it — it is required |
| **Token expired** | Session ended | Sign in again |
| **Redirect loop** | Misconfiguration on the operator's side | Contact the operator — this is not something you can fix |
| **Everything looks fine but nothing works** | Your organisation's [KYC](kyc.md) may have lapsed | Check the KYC page |

!!! tip "The difference between 401 and 403 is worth knowing"
    If you are reporting a problem, saying which you got will save everybody time.

    **401** — your token is not accepted. A sign-in problem.
    **403** — your token is fine, your permissions are not. A role problem, and your company administrator can probably fix it without involving the operator.

---

## Where next

- [Getting your account](onboarding.md)
- [Company administrator](workspaces/company-admin.md) — managing users and IdP settings
- [Step-up MFA](../compliance/step-up-mfa.md) — why some actions ask again
