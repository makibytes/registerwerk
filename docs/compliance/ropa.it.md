---
title: Registro delle attività di trattamento
description: Bozza di registro delle attività di trattamento ai sensi dell'Art. 30 GDPR.
---

# Verzeichnis von Verarbeitungstätigkeiten (DSGVO Art. 30) { #verzeichnis-von-verarbeitungstatigkeiten-dsgvo-art-30 }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questo documento di archivio è una bozza di inventario, non un record approvato o completo ai sensi dell'Articolo 30.
    Il titolare del trattamento/responsabile del trattamento deve stabilire ambito, finalità, basi giuridiche, destinatari,
    trasferimenti, conservazione, misure di sicurezza, proprietà, approvazione e prove di revisione.
# Registri delle attività di trattamento (GDPR Art. 30) { #records-of-processing-activities-gdpr-art-30 }

**Controller:** [Nome dell'operatore da compilare]  
**DPO:** [Contatto da compilare]  
**Ultimo aggiornamento:** 2026-05-21  
**Versione:** 1.0

---

## 1. Onboarding del cliente e KYC { #1-customer-onboarding-kyc }

| Campo | Valore |
|---|---|
| **Scopo** | Verifica dell'identità del cliente e onboarding per l'emissione elettronica di titoli (GwG §10, eWpG §3) |
| **Base giuridica** | Obbligo legale (DSGVO art. 6(1)(c)) — GwG §10, eWpG |
| **Categorie di dati** | Nome della persona giuridica, LEI, numero di registrazione, data di costituzione, documenti KYC (estratto di registrazione, dichiarazione UBO, documenti di identità, delibere del consiglio), stato KYC |
| **Persone fisiche** | Amministratori, UBO: nome, data di nascita, nazionalità, indirizzo, tipo/numero documento di identità, PEP/stato sanzioni |
| **Destinatari** | BaFin (DE), CSSF (LU), AMF (FR), FMA (LI) — solo su richiesta normativa |
| **Trasferimenti verso paesi terzi** | Nessuno pianificato; AWS S3 (eu-central-1) per archiviazione documenti — Clausole Contrattuali Tipo |
| **Conservazione** | 10 anni dopo la fine del rapporto (eWpG §15(3)); 5 anni per i record KYC (GwG §8) |
| **Misure di sicurezza** | AES-256-GCM a riposo; TLS 1.3 in transito; accesso basato sui ruoli (COMPLIANCE_OFFICER, REGISTRY_ADMIN); registro di controllo |

## 2. Registro dei titoli elettronici { #2-electronic-securities-registry }

| Campo | Valore |
|---|---|
| **Scopo** | Tenuta del registro elettronico dei titoli per eWpG (Registerführung) |
| **Base giuridica** | Obbligo legale (Art. 6(1)(c)) — eWpG §7, §15, §16, §17 |
| **Categorie di dati** | Titolare del bene: indirizzo del wallet, importo nominale, data di acquisizione, stato della whitelist; cronologia delle transazioni |
| **Persone fisiche** | Identità del titolare per le persone fisiche: nome, data di nascita, nazionalità, codice fiscale (tramite HolderIdentity) |
| **Destinatari** | BaFin (divulgazioni ordinate dal tribunale); emittente (ai sensi dell'eWpG §15) |
| **Conservazione** | 10 anni dopo il rimborso/annullamento (eWpG §15(3)) |
| **Misure di sicurezza** | Registro di controllo immutabile con catena hash; trigger WORM; ancoraggio quotidiano; rilevamento della deriva della catena |

## 3. Screening sanzioni e PEP { #3-sanctions-pep-screening }

| Campo | Valore |
|---|---|
| **Scopo** | Screening AML/CTF continuativo ai sensi del GwG §10 Abs. 1 Nr. 5 |
| **Base giuridica** | Obbligo legale (Art. 6(1)(c)) — GwG §10, MiCAR Art. 60 |
| **Categorie di dati** | Nome dell'entità, LEI, numero di registrazione — confrontato con OFAC SDN, EU CFSP, UN 1267, UK HMT, CH-SECO |
| **Responsabili del trattamento (processori)** | OpenSanctions (dati aperti, GDPR-neutro); Refinitiv World-Check (richiesto DPA) |
| **Conservazione** | 5 anni (GwG §8) |
| **Misure di sicurezza** | Risultati dello screening archiviati in DB crittografato; principio dei quattro occhi per accettare un riscontro |

## 4. Trading ed elaborazione delle transazioni { #4-trading-transaction-processing }

| Campo | Valore |
|---|---|
| **Scopo** | Esecuzione di operazioni su titoli presso sedi di negoziazione (Assetera, Archax, Talos, simulate) |
| **Base giuridica** | Necessità contrattuale (Art. 6(1)(b)); obbligo legale per la segnalazione MiFIR (Art. 6(1)(c)) |
| **Categorie di dati** | ID trader, ID entità, proposte di vendita, record di esecuzione, indirizzi wallet |
| **Destinatari** | BaFin/AMF — segnalazioni di transazione MiFIR RTS 22 |
| **Conservazione** | 7 anni (MiFIR Art. 25(1)); 5 anni (GwG) |
| **Misure di sicurezza** | Accesso basato sui ruoli (`TRADER`); registro di controllo per operazione |

## 5. Registrazione di controllo { #5-audit-logging }

| Campo | Valore |
|---|---|
| **Scopo** | Traccia di controllo della sicurezza e della conformità; requisito di integrità eWpRV §6 |
| **Base giuridica** | Obbligo legale (Art. 6(1)(c)) — eWpG §15, eWpRV §6, DORA Art. 9 |
| **Categorie di dati** | ID attore, ruolo attore, tipo evento, ID/tipo soggetto, payload (può includere nomi di entità) |
| **Conservazione** | 10 anni (eWpG §15(3)); solo in aggiunta (append-only), non può essere eliminato |
| **Misure di sicurezza** | Catena hash SHA-256; trigger WORM sul DB; ancoraggio quotidiano alla blockchain pubblica; ruolo DB con restrizioni |

## 6. Gestione degli utenti dell'operatore { #6-operator-user-management }

| Campo | Valore |
|---|---|
| **Scopo** | Autenticazione e autorizzazione del personale del registro |
| **Base giuridica** | Legittimo interesse (Art. 6(1)(f)) — sicurezza informatica, controllo accessi |
| **Categorie di dati** | E-mail, password con hash, ruoli, ultimo accesso, token azione |
| **Conservazione** | Durata del rapporto di lavoro + 2 anni |
| **Misure di sicurezza** | Hashing della password BCrypt; JWT (di breve durata, 8 ore); MFA per operazioni sensibili |

## 7. Rapporti normativi (MiFIR, DAC8, Steuerbescheinigung) { #7-regulatory-reporting-mifir-dac8-steuerbescheinigung }

| Campo | Valore |
|---|---|
| **Scopo** | Obbligo di segnalazione delle transazioni alle autorità competenti |
| **Base giuridica** | Obbligo legale (Art. 6(1)(c)) — MiFIR Art. 26, DAC8, EStG §43 |
| **Categorie di dati** | Nome dell'investitore, codice fiscale, partecipazioni, transazioni, IBAN (per Steuerbescheinigung) |
| **Destinatari** | BaFin (DE), AMF (FR), CSSF (LU), FMA (LI), BZSt (DAC8/CARF), DGFiP (FR), ACD (LU) |
| **Conservazione** | 7 anni (MiFIR); 10 anni (eWpG) |
| **Misure di sicurezza** | PDF firmati PAdES-B-LT; SFTP ai portali delle autorità; ricevute di invio |

---

## Diritti dell'interessato { #data-subject-rights }

| Diritto | Attuazione |
|---|---|
| Art. 15 Accesso | `GET /api/v1/me/dsar/export` |
| Art. 17 Cancellazione | `POST /api/v1/me/dsar/erasure` — i dati personali (PII) vengono contrassegnati come tombstone; la catena di hash di controllo è preservata (Art. 17(3)(b), obbligo di legge) |
| Art. 20 Portabilità | `GET /api/v1/me/dsar/export` restituisce JSON |
| Art. 21 Opposizione | Non applicabile (base giuridica: obbligo di legge) |
| Art. 22 Decisione automatizzata | Nessuna decisione automatizzata; tutte le approvazioni KYC sono sottoposte a revisione umana |
