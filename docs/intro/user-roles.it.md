---
title: Ruoli e permessi
description: Chi usa Registerwerk, che cosa può fare, e a quale obbligo regolamentare risponde ciascun ruolo.
---

# Ruoli e permessi

Registerwerk è multi-tenant: una singola installazione dell'operatore serve molti soggetti giuridici clienti. L'accesso è governato da un insieme di ruoli definito nell'enum `AppRole` e applicato da `@PreAuthorize` su ogni metodo dei controller.

---

## Panoramica dei ruoli

| Ruolo | Portale | Chi lo detiene | Obbligo regolamentare |
|---|---|---|---|
| `REGISTRY_ADMIN` | Operatore | Personale del registro | §15 eWpG responsabile del registro; §10 GwG responsabile antiriciclaggio |
| `COMPLIANCE_OFFICER` | Operatore | Team conformità / antiriciclaggio | §7 GwG responsabile conformità; art. 8 AMLD6 |
| `AUDITOR` | Operatore | Revisori interni/esterni | §15(3) eWpG accesso alle registrazioni |
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
- Attivare la modalità supporto per un utente cliente, a fini di assistenza — capacità permanente, vedi l'avvertenza sotto
- Accedere a tutte le registrazioni della [pista di controllo](../platform/audit-log.md)
- Avviare le esportazioni regolamentari [MiFIR](../compliance/mifir.md) e [DAC8](../compliance/dac8.md)

!!! warning "Le operazioni coattive richiedono il doppio controllo"
    Trasferimento coattivo, distruzione coattiva e approvazione coattiva sono operazioni on-chain irreversibili. L'implementazione attuale richiede che un secondo, diverso `REGISTRY_ADMIN` fornisca il token di doppio controllo; non esiste un ruolo applicativo `SECOND_APPROVER`. La sua adeguatezza giuridica e regolamentare richiede una revisione esterna.

### COMPLIANCE_OFFICER

Focalizzato sulle funzioni antiriciclaggio/KYC:

- Esaminare e gestire esecuzioni e corrispondenze dello [screening sanzioni](../compliance/sanctions-screening.md)
- Accettare o respingere le corrispondenze (con doppio controllo per i soggetti ad alto rischio)
- Approvare i documenti KYC per le giurisdizioni assegnate
- Iscrivere e revocare un [Sperrvermerk](../compliance/sperrvermerk.md) — richiede autenticazione rafforzata
- Accedere alle registrazioni degli incidenti [DORA](../compliance/dora.md)
- Avviare a richiesta un nuovo screening sanzioni

### AUDITOR

Accesso in sola lettura all'intera pista di controllo:

- Leggere tutte le voci della [pista di controllo](../platform/audit-log.md)
- Verificare l'integrità della catena di hash di revisione
- Esportare le registrazioni di revisione per un esame esterno
- Accedere allo storico delle esecuzioni di screening e alle versioni dei documenti KYC

### Approvatore in doppio controllo

L'approvazione in doppio controllo è oggi una capacità di un secondo, diverso `REGISTRY_ADMIN`, non un ruolo applicativo separato. L'approvatore deve essere diverso da chi ha avviato l'operazione e deve superare i controlli di autenticazione rafforzata configurati.

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
- Queste azioni sono segnalate alle autorità tramite [MiFIR RTS 22](../compliance/mifir.md)

---

## Modalità supporto

Gli utenti `REGISTRY_ADMIN` possono entrare in modalità supporto nel portale di un utente cliente per indagare su un problema o assistere nell'onboarding. La modalità supporto:

- Emette un token di breve durata il cui `sub` resta l'identificativo utente dell'**operatore**, così ogni azione è attribuita all'operatore e mai al cliente
- È registrata nella [pista di controllo](../platform/audit-log.md), contrassegnata con `imp` perché quelle azioni restino distinguibili
- È visibile a tutti gli utenti `REGISTRY_ADMIN` tramite la barra nel frontend cliente
- Scade con il token; rientra anziché cercare di prolungarla

!!! warning "La modalità supporto non è protetta da autenticazione rafforzata"
    L'`AdminImpersonationController` non porta alcun `@RequiresStepUp`. Qualsiasi `REGISTRY_ADMIN` può entrare nel portale di qualsiasi cliente senza una seconda sfida di autenticazione e senza una seconda persona.

    Trattala come una questione di controllo più che tecnica: tieni ristretto l'elenco degli amministratori, richiedi un motivo registrato fuori dalla piattaforma e rivedi periodicamente gli eventi. [Modalità supporto](../operator/customers/impersonation.md) ne tratta il governo.

La modalità supporto è inoltre del tutto indisponibile quando `ENTRA_ENABLED=true` — il backend rifiuta di emettere una sessione per conto di un cliente.
