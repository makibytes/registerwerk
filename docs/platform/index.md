---
title: Platform Architecture
description: Internal architecture of the Registerwerk backend — modules, security, audit, and API.
---

# Platform Architecture

This section covers the internal design of the Registerwerk platform for engineers and operators.

- [Module Architecture](modules.md) — 22 Spring Modulith bounded contexts, dependency graph
- [Security & Authentication](security.md) — JWT, OIDC, role enforcement, fail-fast guards
- [Audit Log](audit-log.md) — tamper-evident hash chain, partition management
- [REST API Overview](api.md) — URL structure, error responses, pagination
- [dApp Development](dapp-development.md) — ecosystem permission framework, marketplace publishing workflow
- [DeFi Interoperability](defi-interoperability.md) — jurisdiction questions, nominee/omnibus bridge, and a reference repo/lending facility that is not approved for production use
- [Account Abstraction & Sponsored Transactions](account-abstraction.md) — ERC-4337/EIP-7702 support, gas sponsorship, passkeys
