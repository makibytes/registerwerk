---
title: Step-Up MFA e 4 occhi
description: Autenticazione step-up e doppio controllo (4 occhi) per operazioni regolamentate ad alto rischio.
---

# Step-Up MFA e 4 occhi { #step-up-mfa-4-eyes }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina descrive le mappature dei controlli previste. Non è una prova che il flusso a doppio controllo MFA o
    configurato soddisfi un particolare requisito legale, normativo, di sicurezza o di separazione dei compiti
    . Ruoli, azioni protette, livello di garanzia, ripristino e prove di audit richiedono una revisione specifica per l'implementazione.

Certe operazioni in Registerwerk sono così consequenziali (o così chiaramente obbligate ad avere una doppia supervisione per regolamento) che una normale sessione di accesso non è sufficiente. L'**autenticazione step-up** richiede che l'operatore provi nuovamente la propria identità al momento dell'esecuzione dell'operazione. Il **principio dei 4 occhi** (Vier-Augen-Prinzip) richiede inoltre la conferma di un secondo approvatore indipendente prima che l'azione venga eseguita.

---

## Perché esiste { #why-this-exists }

| Regolamento | Obbligo |
|---|---|
| GwG §6(2) | Sistemi di controllo interno: le decisioni ad alto rischio richiedono una doppia supervisione documentata |
| eWpG §16 | Le operazioni di blocco (Sperrvermerk) devono essere riconducibili a un operatore identificato e verificato |
| BaFin KAIT | La sicurezza IT richiede MFA per l'accesso privilegiato ai sistemi critici |
| DSGVO Art. 32 | Misure tecniche adeguate per proteggere i dati personali — MFA è la linea di base |

---

## Operazioni protette { #protected-operations }

Ogni annotazione `@RequiresStepUp` del backend è elencata, con endpoint, motivo, età massima, requisito del secondo approvatore e vincolo del corpo, nella [matrice step-up](step-up-matrix.md) generata (solo in inglese). Quella pagina è l'elenco completo e viene rigenerata dal codice (un controllo di CI fallisce se è obsoleta); la tabella seguente è un estratto breve e selezionato, e **non è esaustiva**.

| Operazione | Step-up | 4 occhi | Motivo (`@RequiresStepUp`) |
|---|---|---|---|
| Trasferimento coattivo, distruzione coattiva, approvazione coattiva (endpoint operatore ed emittente) | sì | sì | `FORCED_TRANSFER_EWG24`, `FORCE_BURN_EWG26`, `FORCED_APPROVE_OVERRIDE`, varianti `ISSUER_*` |
| Impostare il tetto di emissione (supply cap) | sì | sì | `SUPPLY_CAP_CHANGE_MICAR46` |
| ERC-3525: creazione slot, mint di slot, trasferimento di valore coattivo | sì | sì | `ERC3525_SLOT_CREATE`, `ERC3525_SLOT_MINT`, `ERC3525_FORCED_VALUE_TRANSFER_EWG24` |
| Approvare / respingere il KYC (anche approvazione con override) | sì | sì | `KYC_APPROVE`, `KYC_REJECT` |
| Iscrivere / rimuovere uno Sperrvermerk | sì | sì | `SPERRVERMERK_CREATE`, `SPERRVERMERK_LIFT` |
| Avviare la modalità supporto (sola lettura) | sì | no | `ADMIN_IMPERSONATION` |
| Avviare la modalità supporto, agire per conto del cliente (solo modalità demo) | sì | sì | `ADMIN_IMPERSONATION_ACT_ON_BEHALF` |
| Accettare una corrispondenza di screening, confermare un PEP (sempre, qualunque sia il punteggio) | sì, 15 min | sì | `SCREENING_HIT_ACCEPT`, `SCREENING_PEP_CONFIRM` |
| Esportazione chiave del wallet, importazione chiave grezza, importazione keystore | sì | sì | `WALLET_KEYSTORE_EXPORT`, `WALLET_IMPORT_RAW`, `WALLET_IMPORT_KEYSTORE` |
| Rotazione della KEK, un wallet | sì | no | `WALLET_KEK_ROTATION` |
| Rotazione della KEK, tutti i wallet | sì | sì | `WALLET_KEK_ROTATION_ALL` |
| Reintegro di un soggetto dopo la chiusura | sì | sì | `ENTITY_REINSTATE` |
| Riconoscimento di una verifica della catena di audit | sì | sì | `AUDIT_CHAIN_VERIFICATION_ACK` |
| Reset TOTP di un altro operatore | sì | sì | `TOTP_RESET` |
| Entra: eliminare un metodo di autenticazione | sì | no | `ENTRA_AUTH_METHOD_DELETE` |
| Entra: reimpostare tutti i metodi di autenticazione | sì | sì | `ENTRA_MFA_RESET` |
| Entra: revocare le sessioni di accesso | sì | no | `ENTRA_REVOKE_SIGNIN_SESSIONS` |
| Entra: emettere un Temporary Access Pass | sì | sì | `ENTRA_TEMPORARY_ACCESS_PASS` |

Che sia richiesto il secondo approvatore può dipendere anche dalla richiesta: le modifiche agli utenti operatore, i declassamenti e le chiusure di incidenti DORA e i declassamenti della classificazione del cliente lo impongono nel servizio (`DualControlGate`); questi motivi sono nella seconda tabella della matrice.

L'avvio di una sessione in modalità supporto è descritto in [Modalità supporto](../operator/customers/impersonation.md); la sessione è rifiutata del tutto quando `ENTRA_ENABLED=true`.

---

## Due tracce { #two-tracks }

Il modo in cui viene dimostrato il secondo fattore dipende da chi emette i token di sessione. Entrambi sono applicati dalla
stessa annotazione `@RequiresStepUp` e dallo stesso aspetto; differisce solo il controllo.

### Locale TOTP — `ENTRA_ENABLED=false` e il portale dell'operatore sempre { #local-totp-entraenabledfalse-and-the-operator-portal-always }

RFC 6238 TOTP (HMAC-SHA1, finestra di 30 secondi, 6 cifre), verificato da
`StepUpTokenIssuer`. Iscriviti a `POST /api/v1/auth/step-up/enroll`, conferma a
`/enroll/confirm`, quindi scambia un codice a `POST /api/v1/auth/step-up` con un token di breve durata
che trasporta `acr=stepup`, valido 10 minuti. Il chiamante invia quel token al posto della propria sessione
token sulla richiesta protetta. Il rifiuto è **403**.

> **WebAuthn / FIDO2 non è implementato.** Il campo `method` nella richiesta di incremento viene accettato
> e ignorato. Le versioni precedenti di questo documento lo descrivevano come il fattore principale; non è mai esistito
> nel codice. Sotto l'accesso a Entra, MFA resistente al phishing è disponibile, ma tramite
> Accesso condizionale, non tramite questo modulo.

### Contesto di autenticazione Entra: `ENTRA_ENABLED=true` { #entra-authentication-context-entraenabledtrue }

Il token di accesso deve contenere il contesto di autenticazione di accesso condizionale richiesto nel suo `acrs`
reclamo. Registerwerk non verifica un fattore in sé; stabilisce un requisito e consente a Conditional
Access di decidere cosa lo soddisfa, ovvero ciò che consente a un operatore di richiedere
MFA resistente al phishing per trasferimenti forzati senza una modifica del codice.

Il rifiuto è una **sfida delle attestazioni 401**, quindi la SPA si autentica nuovamente per quell'unica azione invece
di disconnettere l'utente:

```
WWW-Authenticate: Bearer realm="", authorization_uri="…",
                  error="insufficient_claims", claims="<base64>"
```

L'ID del contesto è configurazione, indicizzata da `@RequiresStepUp(reason = …)`:

```yaml
registerwerk.auth.step-up.entra:
  auth-context-id: c1                 # ENTRA_STEPUP_AUTH_CONTEXT_ID
  reason-overrides:
    FORCE_BURN_EWG26: c2
    "Payment rail creation": c1       # quote reasons containing spaces
```

Viene convalidato rispetto al tenant all'avvio: un contesto che non esiste, o esiste ma è
**non pubblicato nelle app**, non riesce ad avviarsi in modalità di produzione. Un contesto non pubblicato non può mai essere soddisfatto e produce un ciclo di reindirizzamento di accesso senza nulla nei log che lo spieghi.

#### Freshness funziona diversamente qui { #freshness-works-differently-here }

Un token di accesso Entra dura 60-90 minuti e `acrs` persiste per tutta la sua vita, quindi l'applicazione di
`maxAgeMinutes` a `iat` forzerebbe un reindirizzamento completo del browser su quasi tutte le chiamate protette.
Invece:

- il controllo di aggiornamento **primario** è il criterio di accesso condizionale sul contesto di autenticazione
  (impostare *Frequenza di accesso: Ogni volta* per le azioni di livello regolatorio);
- `maxAgeMinutes` viene confrontato con l'attestazione `auth_time` come backstop.

`auth_time` è un'attestazione facoltativa che deve essere richiesta durante la registrazione dell'app API. Senza di esso
il controllo ricade su `iat`, che è più debole: il backend registra un avviso la prima volta che
vede un token Entra privo di esso.

---

## Implementazione 4-Eyes { #4-eyes-implementation }

Il secondo approvatore deve essere un altro utente attualmente abilitato come `REGISTRY_ADMIN` **oppure** `COMPLIANCE_OFFICER` (`StepUpTokenValidator.ELIGIBLE_APPROVER_ROLES`; i ruoli dell'approvatore vengono riletti dal database). Non esiste un ruolo `SECOND_APPROVER` separato. Chi avvia le decisioni KYC e di screening può essere a sua volta `REGISTRY_ADMIN` o `COMPLIANCE_OFFICER`, quindi per queste azioni è possibile una coppia di `COMPLIANCE_OFFICER`; nessuno può approvare la propria richiesta.

**4-eyes è identico in entrambe le tracce**: un token a doppio controllo viene sempre coniato localmente dopo la verifica TOTP
e sempre convalidato rispetto al decoder HS256 locale, quindi non dipende da come
è stato dimostrato il fattore principale.

```mermaid
sequenceDiagram
    participant Initiator
    participant Approver
    participant Backend

    Approver->>Backend: POST /api/v1/auth/step-up { code, action, target[, targetBody] }
    Backend-->>Approver: approver token (acr=stepup, stepup_scope, stepup_target, jti; 5 min, single use)
    Approver->>Initiator: Hand over the approver token
    Initiator->>Backend: POST /api/v1/auth/step-up { code, action }
    Backend-->>Initiator: initiator step-up token
    Initiator->>Backend: Protected call — Authorization: initiator token,<br/>X-Dual-Control-Token: approver token
    Backend->>Backend: Validate both, then execute + audit with both identities
```

Invece di passare un token a mano, chi avvia l'operazione può usare la coda di approvazione dell'applicazione (sezione successiva). Entrambe le vie terminano nello stesso controllo sull'endpoint protetto.

Invarianti chiave applicate da `StepUpEnforcementAspect` e `StepUpTokenValidator`:

- Iniziatore e approvatore **devono essere utenti diversi** (confronto `sub`)
- Il token dell'approvatore deve contenere `stepup_scope` **esattamente uguale** al token dell'annotazione `reason` —
altrimenti un'approvazione sarebbe una credenziale generica valida per qualsiasi azione 4-eyes nella sua finestra
- L'approvatore deve essere ancora un `REGISTRY_ADMIN` **oppure** un `COMPLIANCE_OFFICER` **abilitato nel database**, e non soltanto secondo i claim del token, che riflettono lo stato solo al momento dell'emissione
- L'approvazione è **vincolata alla richiesta per cui è stata data** (K3). L'approvatore la emette con `action` *e* `target` (`"METHOD /percorso?query"` della chiamata esatta; anche `targetBody`, il corpo JSON della richiesta, che ogni motivo vincola). Il token contiene `stepup_target`, lo SHA-256 in base64url della richiesta canonica (`v1`, metodo in maiuscolo, percorso senza barra finale, query ordinata e l'hash del corpo JSON canonico: chiavi ordinate, senza spazi, numeri decimali esatti in forma semplice). Il backend ricava lo stesso digest dalla richiesta effettiva; se differisce, la chiamata è rifiutata con **403**. I token senza destinazione non sono più accettati
- **I token di approvazione valgono solo nell'header.** Un'approvazione porta `use=dual_control` e l'audience `registerwerk-dual-control`. È accettata in `X-Dual-Control-Token` e altrove no: come `Authorization: Bearer` (o cookie di sessione) su qualsiasi endpoint, anche `@RequiresStepUp`, è rifiutata con **403**; un'approvazione in mano a qualcuno non può quindi mai essere riusata come sessione di un'altra persona. I normali token di step-up (senza scope né marcatura) restano la prova propria del chiamante.
- **Il corpo è sempre vincolato.** Ogni motivo vincola il corpo canonico della richiesta (`targetBody`, omesso se la richiesta non ne ha). Le eccezioni sono elencate in `registerwerk.auth.step-up.dual-control.body-opt-out-reasons`: contenuti che non sono JSON (upload del term sheet, import del keystore, import CSV CASP) e contenuti che sono materiale di chiave segreto o la password di un keystore (import di chiave grezza, export del keystore); metodo, percorso e query restano vincolati. I numeri sono decimali esatti, mai `double`; un corpo con una chiave JSON ripetuta o una richiesta con un parametro di query ripetuto non può essere vincolato ed è rifiutato. Il token di approvazione resta monouso anche se `bind-target-reasons` viene ristretto.
- **Il bootstrap è una porta a senso unico.** L'eccezione «basta uno step-up» vale solo finché non sono esistiti contemporaneamente due `REGISTRY_ADMIN` abilitati e con TOTP. Il database registra quel momento (`dual_control_bootstrap`, impostato da trigger, mai cancellato); da allora l'eccezione non torna più, anche se un amministratore viene poi disabilitato o perde l'authenticator. Disabilitare o eliminare un account del personale dell'operatore (senza legame con un'azienda) o un account con un ruolo protetto (`REGISTRY_ADMIN`, `COMPLIANCE_OFFICER`, `SUPPORT_AGENT`, `AUDIT`) richiede il secondo approvatore (`OPERATOR_USER_DISABLE`, `OPERATOR_USER_DELETE`).
- L'approvazione è **monouso**: il suo `jti` viene scritto in `dual_control_token_use` insieme all'evento di audit in un'unica transazione (un secondo utilizzo, su qualsiasi replica, dà **403**). Un'azione che fallisce dopo il consumo dell'approvazione richiede una nuova approvazione
- L'approvazione è accettata solo in una **finestra breve** dopo l'emissione (`registerwerk.auth.step-up.dual-control.window-seconds`, predefinito 300 s); il token di autenticazione rafforzata dell'iniziatore mantiene i suoi 10 minuti

---

## Coda di approvazione dell'applicazione { #in-app-approval-queue }

Entrambi i portali inoltrano e decidono le approvazioni tramite `/api/v1/approvals` invece di scambiarsi token:

1. Chi avvia l'operazione inoltra la richiesta esatta (azione = il motivo `@RequiresStepUp` dell'endpoint, metodo, percorso, query e corpo JSON). La richiesta è accettata solo se quell'azione è il motivo di quella rotta; il corpo è salvato in forma canonica, così l'approvatore vede ciò che verrà eseguito.
2. Un approvatore idoneo diverso da chi ha avviato l'operazione (`REGISTRY_ADMIN` o `COMPLIANCE_OFFICER`) la vede nella casella **Approvals** e approva con un codice TOTP recente, oppure respinge. L'autoapprovazione è impossibile, anche a livello di database.
3. Chi ha avviato l'operazione ritira un token di approvazione monouso, vincolato a quel digest e a chi ha avviato (un token ritirato da un utente è inutile in altre mani), e poi invia la richiesta vera con il proprio token step-up e `X-Dual-Control-Token`.

Le richieste che nessuno decide, o che non vengono ritirate, scadono dopo 15 minuti (`registerwerk.auth.step-up.approval-queue.ttl`). La coda rifiuta le sessioni di modalità supporto. Eventi di audit: `APPROVAL_REQUEST_CREATED`, `_APPROVED`, `_REJECTED`, `_CANCELLED`, `_CLAIMED`, `_EXPIRED` (il corpo non compare mai nell'evento; il suo digest sì).

---

## Registrazione TOTP, archiviazione e reimpostazione { #totp-enrolment-storage-reset }

- **Nessuna fiducia al primo utilizzo.** L'avvio di una registrazione (`POST /api/v1/auth/step-up/enroll`) richiede la password attuale dell'account nel corpo (`{ "currentPassword": "…" }`): una sessione rubata o lasciata incustodita non può quindi vincolare l'autenticatore di un attaccante. Le password errate contano nello stesso blocco dei codici errati. Gli account il cui secondo fattore è gestito da un identity provider esterno non possono registrare un autenticatore locale. La conferma (`/enroll/confirm`) consuma l'intervallo temporale del codice, che quindi non può essere riutilizzato come codice di autenticazione rafforzata.
- **Cifrato a riposo.** Il segreto TOTP è cifrato con envelope encryption (AES-256-GCM, una nuova chiave dati per valore avvolta dalla KEK della piattaforma, l'ID utente come dati autenticati aggiuntivi) in `app_user.totp_secret`; `totp_secret_kid` registra il provider KEK. I segreti salvati in chiaro dalle versioni precedenti vengono cifrati da un job di avvio; la verifica rifiuta un segreto ancora in chiaro (un amministratore reimposta la registrazione).
- **Stato condiviso tra repliche.** La protezione dal replay (RFC 6238 §5.2: un codice all'ultimo intervallo accettato o prima è rifiutato) e il blocco anti brute force (5 codici errati o riutilizzati bloccano l'autenticazione rafforzata per 15 minuti) risiedono nella tabella `totp_state` e sono aggiornati in modo atomico: un codice accettato su una replica viene rifiutato su tutte le altre. Ogni tentativo viene riservato prima del confronto del codice, così i tentativi paralleli condividono un budget di cinque.
- **Rimozione in autonomia.** `POST /api/v1/auth/step-up/disenroll { "code": "…" }` richiede un codice attuale valido, elimina la registrazione e termina le sessioni dell'utente. L'utente deve registrarsi di nuovo prima di qualsiasi azione di autenticazione rafforzata.
- **Reimpostazione da parte dell'operatore (dispositivo perso).** `POST /api/v1/admin/users/{id}/totp-reset` richiede l'autenticazione rafforzata **e** un secondo approvatore (motivo `TOTP_RESET`). Elimina la registrazione, termina le sessioni dell'utente e scrive l'evento di audit `TOTP_RESET` con entrambe le identità; non è possibile reimpostare così la propria registrazione. L'utente si registra di nuovo al prossimo accesso.
- **Monitoraggio.** Il gauge `registerwerk_stepup_unenrolled_operators` conta gli account locali abilitati `REGISTRY_ADMIN` / `COMPLIANCE_OFFICER` con più di sette giorni e privi di autenticatore. È una metrica di avviso, non un errore di avvio; generare un allarme per valori superiori a zero.

Eventi di audit del ciclo di vita: `TOTP_ENROLMENT_STARTED`, `TOTP_ENROLLED`, `TOTP_DISENROLLED`, `TOTP_RESET`; `DUAL_CONTROL_APPROVED` registra ora anche l'ID del token di approvazione e il digest della destinazione, e `DUAL_CONTROL_BOOTSTRAP_USED` segnala l'eccezione a singolo attore finché esistono meno di due amministratori registrati con TOTP.

---

## Applicazione AOP { #aop-enforcement }

`StepUpEnforcementAspect` intercetta qualsiasi metodo annotato con `@RequiresStepUp` e:

1. Legge lo JWT autenticato dal contesto di sicurezza
2. Si dirama in base alla traccia attiva:
   - **locale** — richiede `acr=stepup` e `iat` entro `maxAgeMinutes` (default 10); il fallimento è **403**
   - **Entra** — richiede che `acrs` contenga il contesto di autenticazione configurato e `auth_time`
     entro `maxAgeMinutes`; il fallimento è una **sfida delle attestazioni 401**
3. Se `requireSecondApprover = true`, convalida l'intestazione `X-Dual-Control-Token` ed espone l'ID dell'approvatore
   come attributo di richiesta `stepup.dualControlApproverId`, che i controller leggono con
   `@RequestAttribute` — non devono ridecodificare il token autonomamente
4. La sfida delle attestazioni viene emessa da `ClaimsChallengeAdvice`, non da Spring Security: l'eccezione
   viene generata da un AOP `@Around` e quindi viene risolta da `@RestControllerAdvice`, e il
   `BearerTokenAuthenticationEntryPoint` di Spring Security non ha comunque alcun percorso di codice in grado
   di serializzare un parametro `claims=`

---

## Eventi di controllo { #audit-events }

L'attività di step-up e quattro occhi è registrata con questi tipi di evento (l'elenco è quello che esiste nel codice; non esiste un evento separato «step-up emesso»):

| Tipo di evento | Contenuto |
|---|---|
| `TOTP_ENROLMENT_STARTED`, `TOTP_ENROLLED`, `TOTP_DISENROLLED`, `TOTP_RESET` | Utente interessato; per un reset anche attore e approvatore |
| `DUAL_CONTROL_APPROVED` | Chi ha avviato, approvatore, motivo, id del token di approvazione e digest del bersaglio; scritto prima che l'operazione protetta prosegua |
| `DUAL_CONTROL_BOOTSTRAP_USED` | È stata usata l'eccezione a singolo attore mentre esistevano meno di due amministratori con TOTP |
| `APPROVAL_REQUEST_CREATED / _APPROVED / _REJECTED / _CANCELLED / _CLAIMED / _EXPIRED` | Transizioni della coda di approvazione: attore e ruolo, approvatore a approvazione e ritiro, digest e id del token (mai il corpo) |

L'azione controllata stessa (per esempio `FORCED_TRANSFER` o `SPERRVERMERK_CREATE`) porta il proprio evento. Questi eventi fanno parte della [catena di audit](../platform/audit-log.md) a prova di manomissione.
