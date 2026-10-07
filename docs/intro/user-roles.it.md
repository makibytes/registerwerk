---
title: Ruoli e permessi
description: Chi usa Registerwerk, che cosa può fare, e a quale obbligo regolamentare risponde ciascun ruolo.
---

# Ruoli e permessi

Registerwerk è multi-tenant: una singola installazione dell'operatore serve molti soggetti giuridici clienti. L'accesso è governato da un insieme di ruoli definito nell'enum `AppUserRole` e applicato da `@PreAuthorize` su ogni metodo dei controller.

---

## Panoramica dei ruoli

| Ruolo | Portale | Chi lo detiene | Obbligo regolamentare |
|---|---|---|---|
| `REGISTRY_ADMIN` | Operatore | Personale del registro | §15 eWpG responsabile del registro; §10 GwG responsabile antiriciclaggio |
| `COMPLIANCE_OFFICER` | Operatore | Team conformità / antiriciclaggio | §7 GwG responsabile conformità; art. 8 AMLD6 |
| `AUDIT` | Operatore | Revisori interni/esterni | §15(3) eWpG accesso alle registrazioni |
| `SUPPORT_AGENT` | Operatore | Personale di supporto | Solo sessioni cliente in sola lettura; nessuna funzione regolamentare |
| `ISSUER` | Cliente | Emittenti di strumenti finanziari | §4 eWpG obblighi dell'emittente |
| `INVESTOR` | Cliente | Titolari di token / investitori | |
| `COMPANY_ADMIN` | Cliente | Amministratori presso l'emittente | |
| `TRADER` | Cliente | Accesso di esecuzione per le integrazioni con sedi di negoziazione | Art. 26 MiFIR segnalazioni |

---

## Ruoli dell'operatore

### REGISTRY_ADMIN

Il ruolo con i privilegi più ampi. Un `REGISTRY_ADMIN` può:

- Creare, modificare e disattivare [soggetti giuridici](../intro/concepts.md#soggetti-clienti)
- Approvare e respingere [documenti KYC](../compliance/kyc-aml.md)
- Distribuire e amministrare [token rappresentativi di strumenti finanziari](../token-standards/index.md)
- Iscrivere un [Sperrvermerk](../compliance/sperrvermerk.md) (restrizione alla negoziazione) — richiede l'[autenticazione rafforzata](../compliance/step-up-mfa.md)
- Trasferire e distruggere token coattivamente — richiede autenticazione rafforzata + quattro occhi
- Avviare sessioni di [modalità supporto](#modalita-supporto) in sola lettura a fini di assistenza (autenticazione rafforzata e motivo registrato; le sessioni di scrittura esistono solo in modalità demo)
- Accedere a tutte le registrazioni della [pista di controllo](../platform/audit-log.md)
- Avviare le esportazioni regolamentari [MiFIR](../compliance/mifir.md) e [DAC8](../compliance/dac8.md)

!!! warning "Le operazioni coattive richiedono il doppio controllo"
    Il trasferimento coattivo, la distruzione coattiva e l'approvazione coattiva sono operazioni irreversibili on-chain. L'implementazione attuale richiede che un secondo operatore diverso (un `REGISTRY_ADMIN` o un `COMPLIANCE_OFFICER`) dia l'approvazione in doppio controllo; non esiste un ruolo applicativo `SECOND_APPROVER`. La sua adeguatezza giuridica e di policy richiede una revisione esterna.

### COMPLIANCE_OFFICER

Focalizzato sulle funzioni antiriciclaggio/KYC:

- Esaminare e gestire esecuzioni e corrispondenze dello [screening sanzioni](../compliance/sanctions-screening.md)
- Accettare o respingere le corrispondenze (sempre con autenticazione rafforzata e un secondo approvatore)
- Approvare i documenti KYC per le giurisdizioni assegnate
- Consultare i [Sperrvermerk](../compliance/sperrvermerk.md) (iscriverli e rimuoverli è riservato a `REGISTRY_ADMIN`, con autenticazione rafforzata e un secondo approvatore)
- Accedere alle registrazioni degli incidenti [DORA](../compliance/dora.md)
- Avviare a richiesta un nuovo screening sanzioni

### AUDIT

Accesso in sola lettura all'intera pista di controllo:

- Leggere tutte le voci della [pista di controllo](../platform/audit-log.md)
- Verificare l'integrità della catena di hash di revisione
- Esportare le registrazioni di revisione per un esame esterno
- Accedere allo storico delle esecuzioni di screening e alle versioni dei documenti KYC

### Approvatore in doppio controllo

L'approvazione in doppio controllo è oggi una capacità di un secondo, diverso utente che detiene `REGISTRY_ADMIN` o `COMPLIANCE_OFFICER`, non un ruolo applicativo separato. L'approvatore deve essere diverso da chi ha avviato l'operazione, essere ancora attivo nel database e superare i controlli di autenticazione rafforzata configurati. Le richieste possono essere inoltrate e approvate nella coda di approvazione dell'applicazione (vedi [Autenticazione rafforzata e quattro occhi](../compliance/step-up-mfa.md)).

### SUPPORT_AGENT

Personale dell'operatore per l'assistenza ai clienti. Un `SUPPORT_AGENT` può elencare i soggetti clienti e avviare sessioni di [modalità supporto](#modalita-supporto) in **sola lettura** (richiesti autenticazione rafforzata e motivo). Non può modificare nulla e non ha funzioni regolamentari. Assegnare o revocare il ruolo richiede autenticazione rafforzata e un secondo approvatore.

---

## Ruoli del cliente

Gli utenti clienti accedono alla piattaforma dal frontend cliente (`:44201`), le cui chiamate API passano per Kong. Il loro JWT porta un claim `entityId` (emesso anche come `entity_id`) che indica a quale `LegalEntity` appartengono, e il backend ne ricava l'isolamento dei dati a ogni richiesta.

`X-Entity-Id` è il nome di un *header*, non un claim — e un header che Kong **rimuove** deliberatamente dalle richieste in entrata perché non possa essere contraffatto. Nel backend nulla vi si affida.

### ISSUER

Un emittente può:

- Creare e gestire le proprie definizioni di [asset](../token-standards/index.md)
- Avviare la distribuzione dei token (se richiesto, previa approvazione dell'operatore)
- Gestire l'attivazione degli investitori per i propri token
- Proporre [operazioni societarie](../intro/concepts.md) — dividendi, frazionamenti, rimborsi anticipati — per la revisione dell'operatore, e ritirare una proposta prima che venga esaminata
- Attestare che il regolamento di un'operazione societaria è pronto — la prima delle due parti richieste, insieme alla conferma di un operatore
- Consultare lo storico delle operazioni societarie relative ai propri strumenti
- Scaricare estratti posizione e documenti regolamentari

### INVESTOR

Un investitore può:

- Consultare il proprio portafoglio (token detenuti, posizioni)
- Accettare richieste di trasferimento
- Consultare lo storico delle transazioni
- Consultare le operazioni societarie che riguardano le proprie posizioni e scaricare le conferme di regolamento
- Scaricare i propri estratti posizione

### COMPANY_ADMIN

Gestisce utenti e ruoli all'interno di un soggetto giuridico cliente:

- Invitare e rimuovere utenti aziendali
- Assegnare i ruoli `ISSUER` / `INVESTOR` / `TRADER` all'interno del proprio soggetto
- Consultare lo stato KYC del soggetto (senza poterlo approvare — possono farlo solo gli operatori)

### TRADER

Un utente, macchina o persona, autorizzato a interagire con le integrazioni delle sedi di negoziazione:

- Inviare e gestire proposte di vendita
- Consultare i report di esecuzione
- La piattaforma conserva un registro di ordini ed esecuzioni (esportazione operatore in `/api/v1/admin/trading/order-history`); **non** trasmette segnalazioni MiFIR RTS 22 — vedi [MiFIR](../compliance/mifir.md) (bozza, non validata)

---

## Modalità supporto

La modalità supporto («impersonation») consente al personale dell'operatore di aprire il portale cliente all'interno dell'organizzazione di un cliente per indagare sui problemi. È protetta e per impostazione predefinita in sola lettura:

- L'avvio richiede [autenticazione rafforzata](../compliance/step-up-mfa.md) e un motivo scritto obbligatorio (almeno 15 caratteri, più un riferimento di ticket facoltativo)
- La modalità predefinita è la **sola lettura**; la modalità di scrittura (`ACT_ON_BEHALF`) richiede un secondo approvatore ed esiste **solo in modalità demo**. In modalità produzione ogni sessione è in sola lettura
- `REGISTRY_ADMIN` e `SUPPORT_AGENT` possono avviare sessioni in sola lettura; solo `REGISTRY_ADMIN` può avviare una sessione di scrittura. `SUPPORT_AGENT` non può fare altro
- La chiamata di avvio non restituisce alcun token: un codice monouso (60 secondi) viene scambiato con un cookie di sessione httpOnly. La sessione dura al massimo 30 minuti
- Il `sub` del token resta l'identificativo utente dell'**operatore**, così ogni azione è attribuita all'operatore e mai al cliente; `imp` la contrassegna nella [pista di controllo](../platform/audit-log.md)
- Le sessioni sono registrate e visibili agli amministratori dell'azienda del cliente
- È visibile a tutti gli utenti `REGISTRY_ADMIN` tramite la barra nel frontend cliente

La modalità supporto è del tutto indisponibile quando `ENTRA_ENABLED=true` — il backend rifiuta di emettere una sessione per conto di un cliente. [Modalità supporto](../operator/customers/impersonation.md) ne descrive i dettagli e la governance.
