---
title: Configurazione dell'ID Microsoft Entra
description: Registrazioni app, Accesso condizionale, autorizzazioni Graph e smoke test del tenant per la 2FA di produzione.
---

# Configurazione ID Microsoft Entra { #microsoft-entra-id-setup }

Questo è il runbook per mettere il portale clienti dietro l'ID Microsoft Entra con autenticazione a due fattori
imposta. Nulla di tutto questo si applica alle distribuzioni locali o demo: con
`ENTRA_ENABLED=false` (l'impostazione predefinita di `docker-compose.yml`) il portale utilizza l'accesso con nome utente/password
integrato e nessun secondo fattore.

**Richiede Microsoft Entra ID P1** per l'accesso condizionale e i contesti di autenticazione.

---

## Cosa può e non può fare Registerwerk { #what-registerwerk-can-and-cannot-do }

Due vincoli modellano l'intero progetto ed è bene comprenderli prima di iniziare:

**Non possiamo emettere un codice QR per Microsoft Authenticator.** Microsoft Graph non espone alcun modo per
creare un metodo authenticator o TOTP: `softwareOathMethods` e `microsoftAuthenticatorMethods`
supportano solo l'elenco, il recupero e l'eliminazione, e `secretKey` è documentato per restituire sempre `null`. Entra
possiede il segreto. La registrazione avviene quindi sulla
[pagina combinata di informazioni sulla sicurezza](https://learn.microsoft.com/en-us/entra/identity/authentication/concept-registration-mfa-sspr-combined)
di Microsoft, e la pagina `/security` di Registerwerk guida gli utenti lì. Il codice QR che visualizziamo codifica il *link*
a quella pagina, in modo che un utente al desktop possa continuare sul telefono che deterrà la credenziale.

**Entra External ID (CIAM) non può essere usato** se si desidera Microsoft Authenticator: i tenant esterni
supportano solo email OTP, SMS (un componente aggiuntivo a pagamento) e passkey. I clienti devono essere membri o guest B2B
in un tenant workforce, oppure federati dal proprio.

---

## 1. Registrazioni dell'app { #1-app-registrations }

Due registrazioni. Tienile separate: l'API contiene un segreto client e non deve mai essere un client pubblico.

### API — il backend { #api-the-backend }

| Impostazione | Valore |
|---|---|
| Nome | `Registerwerk API` |
| Application ID URI | `api://<api-client-id>` |
| Ambito esposto | `access_as_user` (amministratore + consenso utente) |
| Segreto cliente | Generane uno → `ENTRA_CLIENT_SECRET` |

**Attestazioni (claim) facoltative sul token di accesso** — aggiungile tutte e tre in *Configurazione token*:

| Attestazione (claim) | Perché è importante se manca |
|---|---|
| `acrs` | Entra non aggiunge mai il contesto di autenticazione in modo opportunistico, quindi ogni azione di step-up costa un reindirizzamento completo del browser. Sembra esattamente un bug dell'applicazione. |
| `xms_cc` | L'API non può sapere se il client supporta le sfide di attestazioni (claims challenge). |
| `auth_time` | La freschezza per lo step-up ricade silenziosamente su `iat`, una garanzia materialmente più debole. Il backend registra un avviso la prima volta che incontra un token privo di questa attestazione. |

### SPA — il frontend del cliente { #spa-the-customer-frontend }

| Impostazione | Valore |
|---|---|
| Nome | `Registerwerk Customer Portal` |
| Piattaforma | Applicazione a pagina singola |
| Redirect URI | `https://<customer-portal-host>/` |
| Autorizzazione API | `api://<api-client-id>/access_as_user` |

Nessun segreto client: è un client pubblico. La SPA pubblicizza `clientCapabilities: ['CP1']` nel codice;
niente da configurare qui.

---

## 2. Accesso condizionale { #2-conditional-access }

### Richiedere l'MFA per l'accesso { #require-mfa-to-sign-in }

Crea una policy mirata all'applicazione API, concedendo l'accesso solo con **Richiede un'autenticazione multifattoriale
** o, meglio, una **forza di autenticazione**. I punti di forza integrati sono
*Autenticazione multifattore*, *MFA senza password* e *MFA resistente al phishing*; i due controlli Grant
non possono essere combinati in un'unica policy.

> La forza di autenticazione si applica solo agli utenti esterni che si autenticano **con Entra ID**. Per
> email-one-time-passcode, SAML/WS-Fed o guest federati di Google, utilizza invece il semplice grant control MFA.

### Contesto di autenticazione per step-up { #authentication-context-for-step-up }

1. **Entra ID → Accesso condizionale → Contesto di autenticazione** → crea un contesto (c1–c99), ad es.
`Registerwerk regulator-grade action`.
2. **Seleziona "Pubblica nelle app".** Un contesto non pubblicato è invisibile alle risorse e non potrà mai essere soddisfatto: il sintomo è un ciclo di reindirizzamento di accesso senza nulla nei log. Registerwerk
lo verifica all'avvio e rifiuta di avviarsi in modalità di produzione se non è pubblicato.
3. Crea una policy con quel contesto come risorsa di destinazione, concedendo l'accesso solo con la forza di autenticazione
scelta, e imposta **Frequenza di accesso: Ogni volta**.
4. Imposta il suo ID come `ENTRA_STEPUP_AUTH_CONTEXT_ID`.

La frequenza di accesso è il vero controllo di freschezza per lo step-up: un token di accesso dura 60-90 minuti
e l'attestazione `acrs` persiste per tutta la sua durata, quindi senza di essa un token resta "rafforzato" (stepped
up) a lungo dopo che l'utente si è allontanato.

### Registra le informazioni di sicurezza { #register-security-information }

Forza la registrazione al primo accesso con l'**azione utente "Registra informazioni di sicurezza"** (è
un'azione utente, non un'app cloud), oppure con la policy di registrazione MFA di ID Protection.

---

## 3. Microsoft Graph: la console di supporto dell'operatore { #3-microsoft-graph-the-operator-support-console }

Necessario solo per la pagina di stato 2FA del cliente e la console per lo smarrimento del telefono dell'operatore. Imposta
`ENTRA_SUPPORT_ENABLED=true` e concedi la registrazione API:

| Autorizzazione | Tipo |
|---|---|
| `UserAuthenticationMethod.ReadWrite.All` | Applicazione |
| `User.RevokeSessions.All` | Applicazione |

Concedere il consenso dell'amministratore, quindi assegnare all'entità servizio il ruolo di directory **Amministratore
dell'autenticazione**. Deliberatamente *non* Amministratore dell'autenticazione con privilegi elevati: l'Amministratore
dell'autenticazione può agire sui membri ma non sugli amministratori, il che rappresenta il contenimento desiderato
per una credenziale che risiede nella configurazione di un'applicazione.

Abilitare anche **Pass di accesso temporaneo** in *Metodi di autenticazione → Criteri* e applicarlo al gruppo di
clienti — è possibile creare un TAP per qualsiasi utente, ma solo gli utenti nell'ambito del criterio possono
utilizzarlo per accedere.

---

## 4. Clienti federati { #4-federated-customers }

Per un cliente che mantiene il proprio tenant Entra:

1. Imposta `identity_model` della persona giuridica su `FEDERATED` e registra l'URL dell'emittente (l'ID tenant
ne deriva).
2. Configura le **impostazioni di accesso tra tenant** in Entra per la collaborazione B2B in entrata.
3. Decidi se fidarti dell'MFA del loro tenant e registralo in `idp_mfa_trusted`. Questo è
controllato dall'operatore: un cliente che garantisce per il proprio MFA potrebbe altrimenti abbassare il livello applicato
ai propri utenti.

Registerwerk non può gestire i metodi di autenticazione di un utente federato: la console di supporto mostra
il loro ID tenant e rifiuta ogni azione di mutazione con un 409 invece di effettuare una chiamata Graph che
fallirebbe in modo confuso.

Nota che un **pass di accesso temporaneo non può essere rilasciato a un ospite esterno**. La console
rileva questo (ospite `userType` più `#EXT#` nello UPN) e disabilita il pulsante con una
spiegazione.

---

## 5. Ambiente { #5-environment }

```bash
ENTRA_ENABLED=true
JWT_ISSUER_URI=https://login.microsoftonline.com/<tenant-id>/v2.0
JWT_AUDIENCE=api://<api-client-id>          # or the bare client id — must match the token's aud

ENTRA_TENANT_ID=<tenant-id>
ENTRA_CLIENT_ID=<api-client-id>
ENTRA_CLIENT_SECRET=<api-client-secret>
ENTRA_SPA_CLIENT_ID=<spa-client-id>
ENTRA_API_SCOPE=api://<api-client-id>/access_as_user

ENTRA_SUPPORT_ENABLED=true
ENTRA_STEPUP_AUTH_CONTEXT_ID=c1
```

`JWT_AUDIENCE` non è opzionale in produzione. Entra firma ogni token per un tenant con le stesse chiavi, quindi
senza una verifica dell'audience un token rilasciato a *qualsiasi altra applicazione nel tuo tenant* viene
accettato qui come sessione Registerwerk. `ProductionReadinessCheck` si rifiuta di avviarsi senza di essa.

Il portale dell'operatore non è influenzato da tutto ciò: mantiene il login HS256 integrato e lo step-up TOTP
locale, motivo per cui `JWT_DEV_SECRET` è ancora importante anche in una distribuzione completamente abilitata per
Entra.

---

## 6. Smoke test del tenant { #6-tenant-smoke-test }

Molti comportamenti non possono essere verificati senza un vero tenant. Elaborare questo elenco prima di
dichiarare valida la distribuzione.

- [ ] **`/actuator/health/entra` riporta UP**, con un conteggio del contesto di autenticazione pubblicato diverso da zero.
Copre la raggiungibilità di Graph, l'acquisizione del token e la disponibilità del contesto in un'unica chiamata.
- [ ] **Accedi come cliente di prova.** L'accesso condizionale dovrebbe forzare la registrazione MFA se non ne
esiste già una.
- [ ] **Decodifica il token di accesso.** Conferma che `aud` corrisponde a `JWT_AUDIENCE` e che sono presenti `acrs`,
`xms_cc` e `auth_time`. Se manca `acrs`, ricontrolla le attestazioni facoltative —
questo è l'errore di configurazione più comune.
- [ ] **Chiama un endpoint step-up.** Previsto un 401 con `error="insufficient_claims"`, quindi un reindirizzamento
, quindi successo. Se invece ogni chiamata viene reindirizzata, `acrs` non viene emesso
opportunisticamente.
- [ ] **Apri `/security`.** Dovrebbe mostrare i metodi registrati e l'ora dell'"ultimo controllo".
- [ ] **Esegui il flusso di telefono smarrito dall'inizio alla fine** su un account di prova: ripristinare i metodi
→ revocare le sessioni → emettere un TAP → accedere con il TAP → registrare un nuovo metodo. Conferma che il TAP
appaia esattamente una volta nell'interfaccia utente e non appaia da nessuna parte in `audit_event`.
- [ ] **Prova il flusso TAP con un guest esterno.** Il pulsante deve essere disabilitato con una spiegazione,
non fallire su Graph.
- [ ] **Conferma che esistano righe in `audit_event`** per ogni azione dell'operatore, con l'`actor_id` corretto —
è esattamente ciò che il filtro di normalizzazione del principal deve garantire.

### Incertezze note { #known-uncertainties }

Dipendono dalla configurazione del tenant e dal comportamento di Microsoft che non è completamente documentato:

- Se Entra rifiuta di eliminare il metodo di autenticazione **predefinito** di un utente mentre gli altri rimangono.
L'adattatore elimina il metodo predefinito per ultimo e segnala gli errori metodo per metodo anziché presumere il risultato.
- Comportamento TAP esatto per un account interno ma guest; l'euristica `#EXT#` distingue i guest esterni
e deve essere confermata empiricamente.
- Se il trust MFA cross-tenant soddisfi un requisito di contesto di autenticazione per gli utenti federati.
Microsoft documenta che FIDO2, Windows Hello e l'autenticazione basata su certificato soddisfano il requisito di
forza solo nel tenant *home* dell'utente.
- Throttling di Graph in caso di polling sostenuto su `/two-factor/refresh`. Il backend applica un throttling per
utente, ma restano comunque in vigore i limiti a livello di tenant.
