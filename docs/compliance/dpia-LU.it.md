---
title: DPIA — Lussemburgo
description: Bozza di Valutazione d'impatto sulla protezione dei dati per la giurisdizione LU_CSSF — richiede l'approvazione del DPO e della consulenza legale prima dell'avvio in produzione.
---

# Valutazione d'impatto sulla protezione dei dati — Giurisdizione LU_CSSF

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Si tratta di una bozza del repository, non di una DPIA approvata. Il responsabile del trattamento e il DPO devono
    definire ambito, necessità, proporzionalità, rischi, misure di mitigazione, requisiti di consultazione,
    titolarità, approvazione e prove di revisione prima di farvi affidamento.

# Évaluation d'Impact sur la Protection des Données (RGPD Art. 35)

**Sistema:** Registerwerk  
**Giurisdizione:** LU — CSSF / Loi du 5 août 2005 / AML Law 2004  
**DPO:** [Da compilare]  
**Data:** 2026-05-21  
**Stato:** DRAFT — richiede l'approvazione del DPO e della consulenza legale prima dell'avvio in produzione

---

## 1. Base giuridica per il trattamento ad alto rischio

| Trattamento | Base giuridica | Articolo GDPR |
|---|---|---|
| KYC / adeguata verifica della clientela | Obbligo legale — AML Law 2004 Art. 3, CSSF Circular 19/732 | Art. 6(1)(c) |
| Tenuta del registro titoli | Obbligo legale — CSSF Circular 22/811 (strumenti basati su DLT) | Art. 6(1)(c) |
| Screening sanzioni / PEP | Obbligo legale — AML Law 2004 Art. 3(4), Regolamento UE 2580/2001 | Art. 6(1)(c) |
| Segnalazione delle transazioni MiFIR | Obbligo legale — Regolamento UE 600/2014 (MiFIR) Art. 26 | Art. 6(1)(c) |
| Segnalazione MiCAR CASP | Obbligo legale — Regolamento UE 2023/1114 Art. 60 | Art. 6(1)(c) |

---

## 2. Valutazione del rischio

| Rischio | Probabilità | Gravità | Rischio residuo | Controllo |
|---|---|---|---|---|
| Divulgazione di KYC PII a soggetti non autorizzati | Basso | Alto | LOW | RBAC; AES-256; TLS 1.3; pista di controllo |
| Violazione dei dati RBE (Register des Bénéficiaires Effectifs) | Basso | Alto | LOW | Ruolo `COMPLIANCE_OFFICER` limitato; principio dei quattro occhi |
| Trasferimento dati fuori dallo SEE | Basso | Medio | LOW | AWS eu-central-1; SCC |
| Screening sanzioni mancato | Basso | Alto | LOW | Aggiornamento quotidiano di OpenSanctions; accettazione a quattro occhi |
| Manomissione del registro | Molto basso | Critico | LOW | Catena di hash SHA-256; trigger WORM; ancoraggio giornaliero |
| Ritardi nella richiesta di accesso dell'interessato (SLA 30 giorni) | Basso | Basso | LOW | Endpoint DSAR implementati |

**Rischio complessivo:** MEDIUM — mitigato da misure tecniche e organizzative.

---

## 3. Requisiti specifici per il Lussemburgo

- **Registre des Bénéficiaires Effectifs (RBE):** l'estratto UBO è archiviato e aggiornato ai sensi dell'AML Law 2004 Art. 3.
- **CSSF Circular 19/732:** il questionario AML è raccolto per ciascun emittente; archiviato come tipo di documento KYC `AML_QUESTIONNAIRE`.
- **CSSF Circular 22/811:** il repository contiene componenti di registro orientati alla DLT, ma nessuna determinazione del responsabile del registro specifica per lo strumento né prova di notifica alla CSSF. Entrambe sono condizioni bloccanti per l'avvio in produzione.
- **Conservazione:** 5 anni dopo la fine del rapporto ai sensi dell'AML Law 2004 Art. 4 (KYC); 10 anni per il registro (politica di equivalenza eWpG/CSSF).
- **Diritti dell'interessato:** il GDPR si applica direttamente in Lussemburgo. Endpoint DSAR: `GET /api/v1/me/dsar/export`, cancellazione: `POST /api/v1/me/dsar/erasure`.

---

## 4. Considerazioni transfrontaliere

Le entità lussemburghesi possono detenere titoli emessi nelle giurisdizioni DE_EWPG o FR_AMF. I flussi di dati transfrontalieri tra le giurisdizioni dell'operatore utilizzano:
- TLS 1.3 in transito
- AWS eu-central-1 (SEE) per l'archiviazione
- Clausole contrattuali standard (SCC) per eventuali subresponsabili del trattamento extra-SEE

---

## 5. Approvazione

| Ruolo | Nome | Data |
|---|---|---|
| DPO | | |
| Responsabile conformità CSSF | | |
| Amministratore delegato | | |
