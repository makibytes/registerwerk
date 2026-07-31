---
title: DPIA — Germania
description: Bozza di Valutazione d'impatto sulla protezione dei dati per la giurisdizione DE_EWPG — richiede l'approvazione del DPO e della consulenza legale prima dell'avvio in produzione.
---

# Datenschutz-Folgenabschätzung (DSGVO Art. 35)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa è una bozza del repository, non una DPIA approvata. Il titolare del trattamento
    dell'implementazione e il DPO devono definire ambito, necessità, proporzionalità, rischi, misure di
    mitigazione, obblighi di consultazione, titolarità, approvazione e prove della revisione prima di farvi affidamento.

# Valutazione d'impatto sulla protezione dei dati — Giurisdizione DE_EWPG

**Sistema:** Registerwerk  
**Giurisdizione:** DE — eWpG / BaFin / GwG  
**DPO:** [Da compilare]  
**Data:** 2026-05-21  
**Stato:** DRAFT — richiede l'approvazione del DPO e della consulenza legale prima dell'avvio in produzione

---

## 1. Necessità e proporzionalità

**Trattamento:** Registerführung (tenuta del registro) per strumenti finanziari elettronici tokenizzati ai sensi dell'eWpG.

**Necessità:** Richiesto dalla legge. L'eWpG §7 impone il registro centrale. Il GwG §10 impone la KYC. L'eWpG §15 impone una conservazione di 10 anni. Il trattamento non può essere ridotto al di sotto di questi minimi di legge.

**Proporzionalità:** I dati raccolti sono il minimo richiesto da eWpG e GwG. I dati personali delle persone fisiche sono limitati agli amministratori e ai titolari effettivi (soglia del GwG §3 ≥25%). I dati personali degli investitori sono raccolti solo quando l'investitore è una persona fisica.

---

## 2. Valutazione del rischio

| Rischio | Probabilità | Gravità | Rischio residuo | Controllo |
|---|---|---|---|---|
| Divulgazione non autorizzata di KYC PII | Medio | Alto | LOW | Accesso basato sui ruoli; AES-256 a riposo; TLS 1.3; registro di controllo |
| Manomissione del registro di controllo | Basso | Critico | LOW | Catena di hash SHA-256 + trigger WORM + ancoraggio pubblico giornaliero |
| Compromesso chiave portafoglio | Basso | Critico | LOW | Crittografia della busta KMS; nessun endpoint exportRaw; accesso registrato |
| Screening delle sanzioni mancato | Basso | Alto | LOW | Ri-screening quotidiano; doppia lista (OpenSanctions + Refinitiv); Accettazione a 4 occhi |
| Violazione dei dati (hacker) | Basso | Alto | MEDIUM | Isolamento della rete; WAF (rilevamento bot Kong + limitazione IP); test di penetrazione annuale |
| Cancellazione illegale di voci di registro | Molto basso | Critico | LOW | Trigger WORM; registro di controllo immutabile; separazione dei ruoli DB |
| Trasferimento transfrontaliero senza garanzie | Basso | Medio | LOW | AWS eu-central-1; Clausole Contrattuali Tipo |
| Ritardi nella richiesta di accesso del soggetto | Basso | Basso | LOW | Endpoint DSAR su /api/v1/me/dsar/ |

**Livello di rischio complessivo:** MEDIUM — mitigato dai controlli descritti in ROPA.

---

## 3. Attività di trattamento ad alto rischio

| Attività | Fattore scatenante ex art. 35 | Esito DPIA |
|---|---|---|
| Dati UBO (PEP, stato sanzioni) | Potenziale categoria particolare di dati (proxy dell'opinione politica) | Giustificato dall'art. 6(1)(c) obbligo legale; art. 9(2)(g) rilevante interesse pubblico |
| Registro di controllo: non può essere eliminato | Si applica l'eccezione dell'art. 17(3)(b) | Giustificato: eWpG §15(3) la conservazione di 10 anni è obbligatoria; documentato nell'informativa sul consenso |
| Identità dell'investitore (persone fisiche) | Trattamento su larga scala | Ridotto al minimo: solo indirizzo del portafoglio + importo nominale, salvo che l'investitore sia una persona fisica |

---

## 4. Misure per affrontare i rischi

1. **Minimizzazione dei dati:** raccolti solo i dati richiesti da eWpG/GwG.
2. **Crittografia:** AES-256-GCM per documenti + KMS busta per chiavi portafoglio.
3. **Controllo accessi:** Ruolo `COMPLIANCE_OFFICER` per KYC; `REGISTRY_ADMIN` con MFA per operazioni sensibili.
4. **Applicazione della conservazione:** `KycMonitoringJob` applica la scadenza; cancellazione automatizzata dei dati dell'interessato su `POST /api/v1/me/dsar/erasure` (tombstone dei PII; hash di controllo conservato).
5. **Risposta all'incidente:** Classificazione dell'incidente DORA in `ict_incident`; notifica di violazione entro 72h per DSGVO Art. 33.
6. **Diritti dell'interessato:** Endpoint DSAR implementati; risposta SLA 30 giorni.
7. **Consultazione DPO:** Questo DPIA richiede la revisione di DPO prima dell'inizio dell'elaborazione.

---

## 5. Consultazione con DPO

**Nome del DPO:** [Da compilare]  
**Data di approvazione del DPO:** [Da compilare]  
**Parere del DPO:** [Da compilare]

---

## 6. Firma

| Ruolo | Nome | Data |
|---|---|---|
| DPO | | |
| Consulente legale | | |
| CTO | | |
| Amministratore Delegato | | |

*Il presente DPIA deve essere rivisto annualmente e in caso di qualsiasi modifica significativa alle attività di elaborazione.*
