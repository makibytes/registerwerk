---
title: DPIA — Liechtenstein
description: Bozza di Valutazione d'impatto sulla protezione dei dati per la giurisdizione LI_TVTG — richiede l'approvazione del DPO e della consulenza legale prima dell'avvio in produzione.
---

# Datenschutz-Folgenabschätzung (DSGVO Art. 35) — Liechtenstein / TVTG

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Si tratta di una bozza del repository, non di una DPIA approvata. Il responsabile del trattamento e il DPO devono
    definire ambito, necessità, proporzionalità, rischi, misure di mitigazione, requisiti di consultazione,
    titolarità, approvazione e prove di revisione prima di farvi affidamento.

# Valutazione d'impatto sulla protezione dei dati — Giurisdizione LI_TVTG

**Sistema:** Registerwerk  
**Giurisdizione:** LI — FMA / TVTG (Token- und VT-Dienstleister-Gesetz) / SPG (Sorgfaltspflichtgesetz)  
**DPO:** [Da compilare]  
**Data:** 2026-05-21  
**Stato:** DRAFT — richiede l'approvazione del DPO e della consulenza legale prima dell'avvio in produzione

---

## 1. Base giuridica

| Trattamento | Base giuridica | Articolo DSGVO |
|---|---|---|
| KYC / obblighi di diligenza (Sorgfaltspflichten) | Obbligo legale — SPG art. 3-5; TVTG §29-31 | Art. 6(1)(c) |
| Registro dei titoli VT (VT-Wertpapierregister) | Obbligo legale — TVTG §3 (modello del container di token) | Art. 6(1)(c) |
| Screening sanzioni / PEP | Obbligo legale — SPG art. 6; UE 2023/1113 (adattamento TVTG) | Art. 6(1)(c) |
| Documento informativo sul token (Token-Informationsdokument) | Obbligo legale — TVTG §9 | Art. 6(1)(b) |

---

## 2. Valutazione del rischio

| Rischio | Probabilità | Gravità | Rischio residuo | Misura |
|---|---|---|---|---|
| Accesso non autorizzato ai dati KYC | Basso | Alto | BASSO | RBAC; AES-256; TLS 1.3; log di audit |
| Vulnerabilità di sicurezza dello smart contract | Basso | Alto | BASSO | Audit Trail of Bits / OpenZeppelin (obbligo TVTG) |
| Falsificazione del registro | Molto basso | Critico | BASSO | Catena di hash SHA-256; trigger WORM; ancoraggio giornaliero |
| Trasferimento transfrontaliero di dati | Basso | Medio | BASSO | AWS eu-central-1 (SEE); SCK |

---

## 3. Requisiti specifici del Liechtenstein

- **TVTG §9 documento informativo sul token:** campo obbligatorio nel tipo di documento KYC `TOKEN_WHITEPAPER`; firmato digitalmente tramite PAdES.
- **Audit dello smart contract:** il TVTG richiede un audit di sicurezza indipendente. Il tipo di documento `SMART_CONTRACT_AUDIT` è configurato come campo obbligatorio in `JurisdictionRequirementConfig.buildLiTvtg()`.
- **Obbligo di segnalazione alla FMA:** i prestatori di servizi TT (TT-Dienstleister) ai sensi del TVTG §12 devono essere segnalati alla FMA. Registrazione nel registro `third_party_provider` (V18).
- **Obblighi di diligenza SPG:** dichiarazione dei titolari effettivi (wirtschaftlich Berechtigte ≥ 25%) tramite l'entità `BeneficialOwner` (V12); conforme alla SPG.
- **Conservazione:** 10 anni (TVTG §33); 5 anni per i documenti antiriciclaggio (SPG art. 7).
- **Diritti degli interessati:** la DSGVO si applica direttamente in Liechtenstein (SEE). Accesso: `GET /api/v1/me/dsar/export`; cancellazione: `POST /api/v1/me/dsar/erasure`.

---

## 4. Protezione dei dati e Travel Rule (adattamento TVTG del TFR)

Il Liechtenstein ha recepito il regolamento UE sul trasferimento di fondi (TFR, Reg. UE 2023/1113) in qualità di membro del SEE. Soglia: EUR 1.000 (adattamento TVTG). Il `TravelRuleService` è configurato per LI_TVTG.

---

## 5. Approvazione

| Ruolo | Nome | Data |
|---|---|---|
| Responsabile della protezione dei dati | | |
| Responsabile conformità FMA | | |
| Amministratore delegato | | |
