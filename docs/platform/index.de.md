---
title: Plattformarchitektur
description: Interne Architektur des Registerwerk-Backends – Module, Sicherheit, Audit und API.
---

# Plattformarchitektur

Dieser Abschnitt behandelt das interne Design der Registerwerk-Plattform für Ingenieure und Betreiber.

- [Modularchitektur](modules.md) – 22 Spring-Modulith-Bounded-Contexts, Abhängigkeitsgraph
- [Sicherheit & Authentifizierung](security.md) – JWT, OIDC, Rollendurchsetzung, Fail-Fast-Guards
- [Audit-Log](audit-log.md) – manipulationssicher nachweisbare Hash-Kette, Partitionsverwaltung
- [REST-API-Übersicht](api.md) – URL-Struktur, Fehlerantworten, Paginierung
- [dApp-Entwicklung](dapp-development.md) – Ökosystem-Berechtigungs-Framework, Marketplace-Veröffentlichungsworkflow
- [DeFi-Interoperabilität](defi-interoperability.md) – Jurisdiktionsfragen, Nominee-/Omnibus-Brücke und eine Referenz-Repo-/Kreditfazilität, die nicht für den Produktionseinsatz zugelassen ist
- [Kontoabstraktion und gesponserte Transaktionen](account-abstraction.md) – ERC-4337/EIP-7702-Unterstützung, Gas-Sponsoring, Passkeys
