---
title: Protezione dei dati (DSGVO / GDPR)
description: Inventario dei dati personali e flussi di lavoro DSAR parziali, con la crittografia attuale e le lacune di copertura.
---

# Protezione dei dati (DSGVO / GDPR) { #data-protection-dsgvo-gdpr }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina registra le mappature di controllo della privacy previste e il comportamento attuale del repository. Non è
    una valutazione di conformità GDPR/DSGVO, un ROPA approvato, una DPIA, una decisione di conservazione o una determinazione della base giuridica. I ruoli del titolare/responsabile del trattamento, le finalità, le basi giuridiche, l'inventario dei dati, la conservazione, la gestione dei diritti e le misure di sicurezza richiedono una revisione specifica per l'implementazione da parte del titolare del trattamento, del DPO, dei responsabili della sicurezza e di consulenti qualificati.

**Regolamento (UE) 2016/679** (GDPR, o DSGVO in tedesco) si applica a tutti i dati personali trattati dagli operatori di Registerwerk. In quanto registro titoli che tratta nomi, date di nascita, codici fiscali, numeri di passaporto e dati finanziari di persone fisiche, Registerwerk è un titolare del trattamento (e talvolta un responsabile del trattamento) soggetto all'insieme completo degli obblighi del GDPR.

---

## Dati personali in Registerwerk { #personal-data-in-registerwerk }

La posizione principale dei dati personali è l'entità `NaturalPerson`. Ciò include:

| Campo | Categoria GDPR | Scopo |
|---|---|---|
| `givenName`, `familyName` | Dati personali | Verifica dell'identità KYC |
| `dateOfBirth` | Dati personali | Verifica dell'identità KYC |
| `nationality`, `countryOfResidence` | Dati personali | Screening sanzioni, reporting |
| `taxId`, `taxIdCountry` | Dati personali sensibili | Segnalazione DAC8/CARF |
| Campi `address` | Dati personali | Verifica KYC, corrispondenza documenti |
| `pepStatus` | Categoria particolare (politica) | Due diligence rafforzata |
| File di documenti (passaporti, carte d'identità) | Dati personali sensibili | Verifica KYC — archiviati in S3 |

---

## Crittografia a riposo — non implementata per i campi `NaturalPerson` { #encryption-at-rest-not-implemented-for-naturalperson-fields }

I dati personali (PII) di `NaturalPerson` sono attualmente mappati su colonne ordinarie del database. Il repository non implementa la crittografia delle colonne a livello di applicazione, DEK per record, il wrapping delle chiavi KEK o la cancellazione crittografica per questi campi. La crittografia del volume del database e dell'archivio oggetti può essere configurata esternamente, ma deve essere verificata in ogni implementazione e non sostituisce i controlli a livello di applicazione laddove richiesti.

---

## Art. 30 — Registri delle attività di trattamento (ROPA) { #art-30-records-of-processing-activities-ropa }

Il repository contiene una bozza di documento ROPA e un inventario iniziale delle attività di trattamento. Completezza, basi giuridiche, periodi di conservazione, proprietà e approvazione non sono stabiliti dal repository:

| Attività | Base giuridica | Conservazione |
|---|---|---|
| Verifica dell'identità KYC/KYB | Obbligo legale (GwG, TVTG, AMF) | Per giurisdizione (5–10 anni) |
| Screening delle sanzioni | Obbligo legale | Per giurisdizione |
| Iscrizioni registro titoli | Obbligo legale (eWpG, TVTG) | Per giurisdizione (5–10 anni) |
| Segnalazione delle transazioni (MiFIR) | Obbligo legale | Secondo le regole di conservazione MiFIR |
| DAC8 dichiarazione fiscale | Obbligo legale | Regole per stato membro |
| Comunicazione dell'assistenza clienti | Interesse legittimo | 3 anni dopo l'ultimo contatto |
| Registro di controllo | Obbligo legale | Per giurisdizione |

La bozza è archiviata in `docs/compliance/ropa.md`. Una distribuzione deve assegnare un proprietario, completarla e approvarla, registrare le prove della revisione e impostare una cadenza di revisione.

---

## Art. 35 — Valutazione dell'impatto sulla protezione dei dati (DPIA) { #art-35-data-protection-impact-assessment-dpia }

Il repository contiene bozze di DPIA per giurisdizione. Se sia richiesta una DPIA, e se una bozza sia completa e approvata, sono aspetti da determinare per l'implementazione:

- `docs/compliance/dpia-DE.md` — Implementazione eWpG tedesca
- `docs/compliance/dpia-LU.md` — Implementazione CSSF lussemburghese
- `docs/compliance/dpia-FR.md` — Implementazione AMF francese
- `docs/compliance/dpia-LI.md` — Implementazione TVTG del Liechtenstein

Questi file sono input di revisione, non prova di un DPIA approvato.

---

## Art. 17 — Diritto alla cancellazione ("diritto all'oblio") { #art-17-right-to-erasure-right-to-be-forgotten }

L'art. 17 del GDPR attribuisce agli interessati il diritto di richiedere la cancellazione dei propri dati personali. Tuttavia, l'art. 17, paragrafo 3, lettera b), prevede un'esenzione per i dati conservati per adempiere a un obbligo legale. Per Registerwerk:

- Le iscrizioni del registro titoli **non possono essere cancellate** durante il periodo di conservazione (eWpG §15, TVTG art. 10) — si applica l'esenzione per obbligo di legge
- I documenti KYC devono essere conservati per tutta la durata del rapporto commerciale più il periodo di conservazione
- L'attuale servizio di cancellazione contrassegna come tombstone i campi di contatto/autenticazione `AppUser` selezionati dopo la revisione dell'operatore; non cancella tutti i dati personali associati a un'entità

Comportamento attuale:
1. Una richiesta di cancellazione crea un elemento di lavoro dell'operatore.
2. Il completamento sostituisce i valori nome/e-mail `AppUser` selezionati, cancella l'hash della password e disabilita l'utente.
3. La copertura relativa a `NaturalPerson`, documenti KYC, partecipazioni, transazioni e altri dati collegati è incompleta; nessun DEK viene distrutto perché la crittografia DEK per record non è implementata.
4. Vengono emessi eventi di richiesta/risoluzione, ma ciò da solo non dimostra la cancellazione completa né la gestione legale della richiesta.

---

## Endpoint dei diritti dell'interessato { #data-subject-rights-endpoints }

| Diritto | Endpoint |
|---|---|
| Art. 15/20 — Accesso/portabilità | `GET /api/v1/me/dsar/export` — esportazione parziale di soggetto giuridico/stato KYC; non un'esportazione completa dei dati personali |
| Art. 16 — Rettifica | Nessun flusso di lavoro di rettifica DSAR completo è documentato qui |
| Art. 17 — Cancellazione | `POST /api/v1/me/dsar/erasure` — registra una richiesta per la revisione dell'operatore; le richieste completate attualmente contrassegnano come tombstone solo i campi selezionati di `AppUser` |

I flussi di richiesta e risoluzione emettono eventi di controllo. Resta da verificare la copertura end-to-end DSAR e la completezza dell'audit.

---

## Art. 32 — Sicurezza del trattamento { #art-32-security-of-processing }

Misure tecniche implementate:

| Misura | Implementazione |
|---|---|
| Crittografia in transito | TLS 1.3 su tutti gli endpoint (Kong + backend) |
| Crittografia a riposo | La crittografia del campo `NaturalPerson` non è implementata; la crittografia del database/archivio oggetti a livello di distribuzione deve essere configurata e verificata separatamente |
| Controllo accessi | Basato su ruoli (`@PreAuthorize`) + step-up per letture sensibili |
| Registrazione di controllo | Catena hash a prova di manomissione per tutte le operazioni |
| MFA | WebAuthn / TOTP per tutti gli account operatore |
| Pseudonimizzazione | `NaturalPerson.id` (UUID) utilizzato nei riferimenti tra moduli al posto del nome |
| Risposta all'incidente | Esistono registrazioni manuali degli incidenti e monitoraggio delle scadenze; non è implementata l'automazione della notifica alle autorità/interessati |

---

## Art. 33/34 — Notifica di violazione { #art-3334-breach-notification }

Se si verifica una violazione dei dati personali:

- Art. 33: notificare l'**autorità di controllo** entro 72 ore dalla conoscenza dell'evento
- Art. 34: informare gli **interessati** senza ingiustificato ritardo se la violazione presenta un rischio elevato

Non è implementato alcun flusso di lavoro automatico di notifica di violazione dell'autorità GDPR o dell'interessato. Gli operatori devono stabilire, testare e provare un processo specifico per la distribuzione.
