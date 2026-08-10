---
title: What an operator does
description: The operator's role in full — the decisions that are yours, the portal, and a fifteen-minute local start.
---

# What an operator does

You run the registry. Customers depend on it being correct, available, and staffed by someone who understands what they are approving.

This page is the job. [How Registerwerk is built](architecture.md) is the system; [Serving customers](customers/index.md) is the detail of each process.

---

## The role, honestly

Most of the work is **judgement about people and instruments**, not infrastructure. You will spend far more time deciding whether an entity is who it claims to be, and whether an issuance should be admitted, than restarting containers.

The powers that are yours alone all share a property: **each can cause harm that is hard or impossible to reverse.**

| | Why it is yours |
|---|---|
| **Admitting an organisation** | Everything downstream assumes this check happened. |
| **Approving an issuance** | Creates something that becomes a legal obligation held by investors. |
| **Correcting the register** | Forced transfers and burns under §§24/26 eWpG move other people's property. |
| **Acting as a customer** | [Impersonation](customers/impersonation.md) puts you inside their portal. |

---

## Your day

### Routine

- **The approval queue.** Entities awaiting KYC review, issuances awaiting approval.
- **The audit log.** Read it when nothing is wrong, so you know what normal looks like.
- **Health.** Indexer lag, chain RPC health, screening availability, [audit partition headroom](maintenance/monitoring.md).
- **Support.** Usually one of three things — see below.

### On a schedule

- **Review `REGISTRY_ADMIN` membership.** Every holder can approve issuances, correct the register, and impersonate any customer.
- **Check KYC expiries coming up.** Warning a customer a month out prevents an outage they will experience as your fault.
- **Verify the audit chain**, and keep the evidence. An integrity control nobody exercises is indistinguishable from one that does not work.
- **Test restores.** A backup nobody has restored is a hypothesis.

### The three-question triage

Before investigating anything exotic, a customer problem is usually:

1. **KYC lapsed** — transfers stop, everything else looks normal.
2. **Wallet not registered or not admitted** — transfers fail on-chain rather than pending.
3. **Role missing** — they get a `403` and call it "the page is broken".

A `401` means the token is bad. A `403` means the token is fine and the role is not. That distinction alone resolves a large share of tickets.

---

## The operator portal

At `:4200`. It bypasses the gateway entirely and uses built-in username/password login with local TOTP for step-up — in every configuration, including deployments where customers use Microsoft Entra ID.

| Area | |
|---|---|
| **Customers** | Legal entities, their status, their KYC. |
| **Onboarding** | Create entities, generate invitation tokens. |
| **Assets** | Every issuance across every customer. |
| **Users** | Accounts and roles, including [2FA support](customers/two-factor-support.md). |
| **Compliance** | Sanctions screening cases, KYC review. |
| **Audit** | The tamper-evident log. |
| **Organizations / Permissions** | On-chain identity and ecosystem permissions. |
| **dApp review** | Marketplace submissions. |
| **Payment rails** | Curating the cash-leg catalogue. |
| **Wallets / Network nodes** | Custodied wallets, chain and RPC health. |

!!! warning "The portal's navigation is not a security boundary"
    Operator-portal routes are not role-filtered in the browser. Access is enforced by the **backend**, per request, from your token.

    So a user with only `AUDIT` sees menu entries for things they cannot do, and gets a refusal on opening them. Nothing is exposed — but do not infer from a visible menu item that somebody may use it.

---

## Fifteen minutes to a local registry

```bash
git clone <your-registerwerk-remote> && cd registerwerk
git submodule update --init --recursive
cp .env.example .env
docker compose up --build
```

!!! danger "Leave `JWT_ISSUER_URI` blank for a local start"
    Setting it switches the customer portal into OIDC mode, which needs a real Entra tenant, app registrations and Conditional Access. A half-configured issuer URI produces sign-in failures that look like bugs.

    Local mode is the default and the right starting point. Turn Entra on deliberately, following [Entra ID setup](../platform/entra-setup.md).

Then:

| | |
|---|---|
| Operator portal | `http://localhost:4200` |
| Customer portal | `http://localhost:4201` |
| Backend health | `curl http://localhost:8080/actuator/health` |
| Through the gateway | `curl http://localhost:8000/api/v1/public/chains` |
| Documentation | `docker compose --profile docs up` → `http://localhost:8003` |

Sign in with `DEFAULT_ADMIN_EMAIL` / `DEFAULT_ADMIN_PASSWORD` from your `.env`.

### Exercise lending and repo locally

Set `SEED_DEMO_DATA=true` before starting Compose. The stack deploys two real lending markets to its disposable Anvil chain, registers their verified immutable parameters, and seeds a separate bilateral Repo Desk book.

When the browser is on another host (for example `nibbler.local`), also set the address the **browser** can reach:

```dotenv
SEED_DEMO_DATA=true
ANVIL_PUBLIC_RPC_URL=http://nibbler.local:8545
```

Add that RPC to a disposable browser wallet as chain ID `11155111`. The Anvil mnemonic is the standard public development mnemonic:

```text
test test test test test test test test test test test junk
```

!!! danger "Demo keys only"
    This mnemonic and every account derived from it are public. Use a separate browser profile and never send real assets to these addresses. Importing the mnemonic into an existing wallet can replace or mingle with real accounts.

The demo maps company users to these derived accounts:

| Customer login (password `demo1234!`) | Company | Anvil account |
|---|---|---|
| `maria.braun@nordbank-invest.de` | Nordbank Invest | account 1 |
| `sabine.mueller@rheinische-kapital.de` | Rheinische Kapital | account 2 |
| `lisa.hoffmann@aurora-finance.de` | Aurora Finance | account 3 |
| `sandra.richter@fd-fonds.de` | Frankfurt Digital Fonds | account 4 |
| `ute.koenig@wi-invest.de` | Württemberg Invest | account 5 |

All five can use the Repo Desk. The first three hold Green Bond collateral; Rheinische, Frankfurt and Württemberg hold Infrastructure Note collateral. The on-chain lending demo is reset/redeployed by the one-shot `demo-onchain-deploy` service, while the Repo Desk starts with three RFQs and two private quotes.

Kong runs DB-less from `gateway/kong.yml`, so there are no gateway database credentials, and there is no `kong` or `konga` database. Its admin API is bound to loopback — reach it with `docker compose exec kong kong health`, never expose it.

For anything beyond a local trial, go to [Prerequisites](installation/prerequisites.md) and read [Environment](configuration/environment.md) properly.

---

## Before you serve real customers

- [ ] `DEFAULT_ADMIN_PASSWORD` and `JWT_DEV_SECRET` changed from their defaults.
- [ ] `JWT_AUDIENCE` set, if Entra is enabled. **Not optional** — without it, a token issued to any other application in your tenant is accepted here as a valid session.
- [ ] Backups configured **and restored at least once** — including the object store, which is not in the database.
- [ ] [Monitoring](maintenance/monitoring.md) in place, with audit partition headroom alerting.
- [ ] More than one `REGISTRY_ADMIN`, held by **different people**, so [four-eyes](../compliance/step-up-mfa.md) controls are real.
- [ ] A tested [disaster recovery](dr/runbook.md) procedure.
- [ ] Your KYC and issuance-approval criteria written down, so decisions are consistent and explicable.

---

## Where next

- [How Registerwerk is built](architecture.md)
- [Serving customers](customers/index.md)
- [Troubleshooting](troubleshooting.md)
