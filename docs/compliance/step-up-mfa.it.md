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

L'annotazione `@RequiresStepUp` viene inserita sui seguenti endpoint e metodi di servizio. Le operazioni contrassegnate da **4 occhi** richiedono inoltre un secondo approvatore.

| Operazione | Step-up | 4 occhi | Motivo |
|---|---|---|---|
| `forceTransfer` | ✅ | ✅ | Operazione on-chain irreversibile |
| `forceBurn` | ✅ | ✅ | Distruzione permanente dei token |
| `forceApprove` | ✅ | ✅ | Deroga di conformità |
| `setSupplyCap` | ✅ | ✅ | Modifica di un parametro economico |
| Creazione slot ERC-3525, mint in uno slot, forced value transfer | ✅ | ✅ | Imposta il massimale dello slot / emette valore obbligazionario in uno slot |
| Deroga KYC (approva nonostante il contrassegno) | ✅ | ✅ | Aggiramento del gate AML |
| Creazione Sperrvermerk | ✅ | ✅ | Restrizione legale sul titolare |
| Revoca Sperrvermerk | ✅ | ✅ | Rimozione di una restrizione legale |
| Avvio della modalità supporto (impersonation) | ❌ ¹ | ❌ | Accesso privilegiato ai dati del cliente |
| Accettazione di un riscontro di screening | ✅ (punteggio alto) | ✅ (punteggio ≥ 80) | Deroga AML per un riscontro confermato |
| Esportazione della chiave privata del wallet (break-glass) | ✅ | ✅ | Accesso al materiale della chiave |
| Entra: eliminazione di un metodo di autenticazione | ✅ | ❌ | Rimuove un fattore obsoleto |
| Entra: reimpostazione di tutti i metodi di autenticazione | ✅ | ✅ | Forza la ri-registrazione MFA per un'altra persona |
| Entra: revoca delle sessioni di accesso | ✅ | ❌ | Solo impatto sulla disponibilità, nessun guadagno di privilegi |
| Entra: emissione di un Temporary Access Pass | ✅ | ✅ | Una credenziale al portatore che autentica *come* il cliente |

¹ `AdminImpersonationController` oggi non ha `@RequiresStepUp`, e la modalità supporto (impersonation)
viene rifiutata del tutto quando `ENTRA_ENABLED=true`. Questa riga in precedenza dichiarava una protezione
step-up che il codice non implementa.

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

L'attuale applicazione del doppio controllo richiede due utenti `REGISTRY_ADMIN` distinti. Non esiste un ruolo applicativo
`SECOND_APPROVER` e un `COMPLIANCE_OFFICER` non è accettato come sostituto
a meno che l'implementazione non venga modificata e rivista separatamente.

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

Invarianti chiave applicate da `StepUpEnforcementAspect` e `StepUpTokenValidator`:

- Iniziatore e approvatore **devono essere utenti diversi** (confronto `sub`)
- Il token dell'approvatore deve contenere `stepup_scope` **esattamente uguale** al token dell'annotazione `reason` —
altrimenti un'approvazione sarebbe una credenziale generica valida per qualsiasi azione 4-eyes nella sua finestra
- L'approvatore deve comunque essere un **`REGISTRY_ADMIN` abilitato nel database**, non semplicemente in base alle
  dichiarazioni del token, che riflettono lo stato solo al momento del conio
- L'approvazione è **vincolata alla richiesta per cui è stata data** (K3). L'approvatore la emette con `action` *e* `target` (`"METHOD /percorso?query"` della chiamata esatta; anche `targetBody`, il corpo JSON della richiesta, che ogni motivo vincola). Il token contiene `stepup_target`, lo SHA-256 in base64url della richiesta canonica (`v1`, metodo in maiuscolo, percorso senza barra finale, query ordinata e l'hash del corpo JSON canonico: chiavi ordinate, senza spazi, numeri decimali esatti in forma semplice). Il backend ricava lo stesso digest dalla richiesta effettiva; se differisce, la chiamata è rifiutata con **403**. I token senza destinazione non sono più accettati
- **I token di approvazione valgono solo nell'header.** Un'approvazione porta `use=dual_control` e l'audience `registerwerk-dual-control`. È accettata in `X-Dual-Control-Token` e altrove no: come `Authorization: Bearer` (o cookie di sessione) su qualsiasi endpoint, anche `@RequiresStepUp`, è rifiutata con **403**; un'approvazione in mano a qualcuno non può quindi mai essere riusata come sessione di un'altra persona. I normali token di step-up (senza scope né marcatura) restano la prova propria del chiamante.
- **Il corpo è sempre vincolato.** Ogni motivo vincola il corpo canonico della richiesta (`targetBody`, omesso se la richiesta non ne ha). Le eccezioni sono elencate in `registerwerk.auth.step-up.dual-control.body-opt-out-reasons`: contenuti che non sono JSON (upload del term sheet, import del keystore, import CSV CASP) e contenuti che sono materiale di chiave segreto o la password di un keystore (import di chiave grezza, export del keystore); metodo, percorso e query restano vincolati. I numeri sono decimali esatti, mai `double`; un corpo con una chiave JSON ripetuta o una richiesta con un parametro di query ripetuto non può essere vincolato ed è rifiutato. Il token di approvazione resta monouso anche se `bind-target-reasons` viene ristretto.
- **Il bootstrap è una porta a senso unico.** L'eccezione «basta uno step-up» vale solo finché non sono esistiti contemporaneamente due `REGISTRY_ADMIN` abilitati e con TOTP. Il database registra quel momento (`dual_control_bootstrap`, impostato da trigger, mai cancellato); da allora l'eccezione non torna più, anche se un amministratore viene poi disabilitato o perde l'authenticator. Disabilitare o eliminare un account operatore, `REGISTRY_ADMIN`, `COMPLIANCE_OFFICER` o `AUDIT` richiede il secondo approvatore (`OPERATOR_USER_DISABLE`, `OPERATOR_USER_DELETE`).
- L'approvazione è **monouso**: il suo `jti` viene scritto in `dual_control_token_use` insieme all'evento di audit in un'unica transazione (un secondo utilizzo, su qualsiasi replica, dà **403**). Un'azione che fallisce dopo il consumo dell'approvazione richiede una nuova approvazione
- L'approvazione è accettata solo in una **finestra breve** dopo l'emissione (`registerwerk.auth.step-up.dual-control.window-seconds`, predefinito 300 s); il token di autenticazione rafforzata dell'iniziatore mantiene i suoi 10 minuti

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

Ogni evento di autenticazione step-up e ogni operazione protetta genera un `AuditEvent`:

| Tipo evento | Contenuto |
|---|---|
| `STEP_UP_ISSUED` | ID utente, metodo, timestamp |
| `DUAL_CONTROL_INITIATED` | ID iniziatore, tipo di operazione, hash dei parametri di operazione |
| `DUAL_CONTROL_CONFIRMED` | ID approvatore, tipo di operazione, riferimento confirmed_token |
| `PROTECTED_OPERATION_EXECUTED` | Entrambi gli ID utente, il tipo di operazione, i parametri operativi completi |
| `STEP_UP_FAILED` | ID utente, motivo dell'errore, indirizzo IP |

Questi eventi fanno parte della [catena di controllo a prova di manomissione](../platform/audit-log.md) e non possono essere eliminati o modificati.
