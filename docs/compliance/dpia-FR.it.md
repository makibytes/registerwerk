---
title: DPIA — Francia
description: Bozza di Valutazione d'impatto sulla protezione dei dati per la giurisdizione FR_AMF — richiede l'approvazione del DPO e della consulenza legale prima dell'avvio in produzione.
---

# Analyse d'Impact relative à la Protection des Données (RGPD Art. 35)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Si tratta di una bozza del repository, non di una DPIA approvata. Il responsabile del trattamento e il DPO devono
    definire ambito, necessità, proporzionalità, rischi, misure di mitigazione, requisiti di consultazione,
    titolarità, approvazione e prove di revisione prima di farvi affidamento.

# Valutazione d'impatto sulla protezione dei dati — Giurisdizione FR_AMF

**Sistema:** Registerwerk  
**Giurisdizione:** FR — AMF / ACPR / Code monétaire et financier / Loi PACTE  
**DPO:** [Da compilare]  
**Data:** 2026-05-21  
**Stato:** DRAFT — richiede l'approvazione del DPO e della consulenza legale prima dell'avvio in produzione

---

## 1. Quadro giuridico

| Trattamento | Base giuridica | Articolo RGPD |
|---|---|---|
| KYC / LCB-FT | Obbligo legale — CMF Art. L561-5, Loi PACTE | Art. 6(1)(c) |
| Registro dei titoli finanziari tokenizzati | Obbligo legale — AMF DOC-2022-15 | Art. 6(1)(c) |
| Screening sanzioni / PEP | Obbligo legale — R. 2016/847, EU 2023/1113 | Art. 6(1)(c) |
| Reporting MiFIR | Obbligo legale — UE 600/2014 Art. 26 | Art. 6(1)(c) |
| Dichiarazione dei titolari effettivi | Obbligo legale — Loi PACTE Art. 52 | Art. 6(1)(c) |

---

## 2. Valutazione dei rischi

| Rischio | Probabilità | Gravità | Rischio residuo | Misura |
|---|---|---|---|---|
| Accesso non autorizzato ai dati KYC | Basso | Alto | BASSO | RBAC; AES-256; TLS 1.3; pista di controllo |
| Mancata conformità TRACFIN (déclaration de soupçon) | Basso | Alto | BASSO | Flusso TRACFIN tramite AMF/ACPR; ruolo `COMPLIANCE_OFFICER` |
| Violazione del registro (falsificazione) | Molto basso | Critico | BASSO | Catena di hash SHA-256; trigger WORM |
| Trasferimento fuori SEE | Basso | Medio | BASSO | AWS eu-central-1; CCT (Clauses Contractuelles Types) |

---

## 3. Requisiti specifici per la Francia

- **Extrait Kbis ≤ 3 mesi:** raccolto tramite il tipo di documento `COMMERCIAL_REGISTER_EXTRACT`; l'età viene verificata in `DocumentRequirement.maxAge`.
- **Dichiarazione dei titolari effettivi:** modello `BeneficialOwner` (V12) ai sensi della Loi PACTE, soglia 25%.
- **TRACFIN:** la déclaration de soupçon (SAR) viene registrata tramite `POST /api/v1/admin/ict-incidents` (DORA) con category=AML_SAR. Il documento viene inviato manualmente al portale TRACFIN (ACPR).
- **Conservazione:** 5 anni (LCB-FT); 10 anni per il registro (equivalenza con l'eWpG).
- **Diritti degli interessati:** CNIL — accesso tramite `GET /api/v1/me/dsar/export`; cancellazione tramite `POST /api/v1/me/dsar/erasure`.

---

## 4. Consultazione della CNIL

La CNIL raccomanda la consultazione dell'autorità competente per i trattamenti di dati su larga scala relativi a titoli finanziari tokenizzati. Questa AIPD dovrà essere sottoposta alla CNIL prima della messa in produzione.

---

## 5. Validazione

| Ruolo | Nome | Data |
|---|---|---|
| DPO | | |
| Responsabile conformità AMF | | |
| Direttore Generale | | |
