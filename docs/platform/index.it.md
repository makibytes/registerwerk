---
title: Architettura della piattaforma
description: Architettura interna del backend Registerwerk: moduli, sicurezza, audit e API.
---

# Architettura della piattaforma { #platform-architecture }

Questa sezione riguarda la progettazione interna della piattaforma Registerwerk per ingegneri e operatori.

- [Architettura del modulo](modules.md) — 22 contesti limitati Spring Modulith, grafico delle dipendenze
- [Sicurezza e autenticazione](security.md) — JWT, OIDC, applicazione dei ruoli, protezioni fail-fast
- [Pista di controllo](audit-log.md): catena hash antimanomissione, gestione delle partizioni
- [REST API Panoramica](api.md) — struttura URL, risposte agli errori, impaginazione
- [Sviluppo dApp](dapp-development.md): framework di autorizzazioni dell'ecosistema, flusso di lavoro di pubblicazione del marketplace
- [Interoperabilità DeFi](defi-interoperability.md): domande sulla giurisdizione, ponte intestatario/omnibus e una struttura di pronti contro termine/prestito di riferimento non approvata per l'uso in produzione
- [Astrazione account e transazioni sponsorizzate](account-abstraction.md) — roadmap ERC-4337/EIP-7702, sponsorizzazione del gas, passkey
