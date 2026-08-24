---
title: Ambito di conformità normativa
---

Questa pagina definisce cosa Registerwerk implementa per il supporto della conformità e cosa rimane all'operatore regolamentato.

## Disclaimer importante { #important-disclaimer }

Registerwerk è un software che consente la conformità, non un motore di determinazione legale. Gli obblighi normativi dipendono dalla giurisdizione, dall'ambito della licenza e dall'interpretazione della vigilanza.

## Profili giurisdizionali nell'ambito { #jurisdiction-profiles-in-scope }

Registerwerk include identificatori di giurisdizione e profili di requisiti KYC configurabili per:

- `DE_EWPG` (Germania, BaFin, contesto eWpG)
- `LU_CSSF` (Lussemburgo, CSSF)
- `FR_AMF` (Francia, AMF)
- `LI_TVTG` (Liechtenstein, FMA, contesto TVTG)

Questi profili sono controlli operativi per i flussi di lavoro di raccolta e approvazione dei documenti. Non costituiscono consulenza legale e devono essere esaminati dai team legali/di conformità prima dell'uso in produzione.

## Controlli implementati dalla piattaforma { #controls-implemented-by-platform }

- Valutazione della lista di controllo dei documenti KYC specifica per giurisdizione.
- Stato di approvazione per giurisdizione con scadenza e motivo di rifiuto.
- Giustificazione obbligatoria (`overrideNote`) per le approvazioni quando le prove richieste mancano, sono scadute o sono troppo vecchie.
- Flusso di eventi di controllo immutabile per invii, approvazioni, rifiuti e override KYC.
- API dedicata per il report degli override (`/api/v1/audit/reports/kyc-overrides`) destinata ai comitati di audit.
- Autorizzazione a livello di API per azioni sensibili KYC.
- Elementi costitutivi della conservazione dei dati in PostgreSQL/S3 con percorsi di recupero controllati.

## Controlli al di fuori dell'ambito della piattaforma { #controls-outside-platform-scope }

Gli operatori rimangono responsabili di:

- Stato di licenza e registrazione presso le autorità competenti.
- Metodologia di rischio AML/CFT e obblighi di segnalazione delle operazioni sospette.
- Qualità, ottimizzazione e policy di escalation del fornitore di screening sanzioni.
- Standard di verifica della titolarità effettiva e sufficienza probatoria.
- Qualifica giuridica e obblighi di informativa MiCA/MiFID/eWpG.
- Governance della normativa sulla privacy (base giuridica, decisioni DPIA, meccanismi di trasferimento, governance DSAR).

## Riferimenti normativi utilizzati per l'allineamento di base { #regulatory-references-used-for-baseline-alignment }

- Germania: struttura eWpG e obblighi di registro.
- UE: principi del quadro MiCA per i servizi su cripto-attività.
- UE: principi del GDPR per trattamento lecito, minimizzazione, sicurezza e responsabilità.
- Riferimento globale AML: approccio basato sul rischio delle Raccomandazioni FATF.

## Pacchetto di governance dell'operatore suggerito { #suggested-operator-governance-pack }

Prima del go-live, mantenere questi elementi al di fuori del codice sorgente e rivederli periodicamente:

- Nota legale giurisdizionale per l'ambito del prodotto e i limiti della licenza.
- Politica KYC/AML con matrice di escalation e livelli di autorità di approvazione.
- Procedure operative di monitoraggio delle sanzioni e delle transazioni.
- Registro dei controlli sulla protezione dei dati (conservazione, controllo degli accessi, risposta agli incidenti).
- Processo di gestione delle modifiche per gli aggiornamenti del profilo giurisdizionale e l'approvazione legale.
