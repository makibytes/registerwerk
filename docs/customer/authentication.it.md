---
title: Accedere
description: Come si accede, che cosa fa l'autenticazione a due fattori e che cosa fare quando non riesci a entrare.
---

# Accedere

Il modo in cui accedi dipende da come l'operatore del tuo registro ha configurato la piattaforma. Ci sono due modalità, e si comportano in modo abbastanza diverso da rendere utile sapere in quale ti trovi.

**Il modo più rapido per capirlo:** se la pagina di accesso mostra un campo email e un campo password, sei in modalità locale. Se mostra un pulsante **Sign in with Microsoft**, sei in modalità Entra.

---

## Le due modalità

=== "Modalità locale — la predefinita"

    Accedi con un indirizzo email e una password custoditi dal registro stesso.

    **Nessun secondo fattore all'accesso.** È la configurazione predefinita e ciò che ottieni da un normale `docker compose up`. È pensata per installazioni locali, dimostrative e di valutazione.

    La password si può reimpostare con il consueto percorso di reimpostazione.

=== "Modalità Entra — produzione"

    Accedi con **Microsoft Entra ID**, usando l'account Microsoft della tua organizzazione, e **l'autenticazione a due fattori è obbligatoria**.

    Il registro non vede mai la tua password. Microsoft ti autentica ed emette un token; il registro lo valida.

!!! info "Il personale dell'operatore usa sempre l'accesso integrato"
    Anche in modalità Entra, il personale dell'operatore del registro accede con nome utente e password e usa un'app di autenticazione locale per le azioni sensibili.

    Solo il portale **cliente** passa a Entra. Se hai letto che Entra è la modalità predefinita per tutti, personale dell'operatore compreso, era sbagliato — la piattaforma non si è mai comportata così.

---

## Autenticazione a due fattori

Si applica in modalità Entra.

L'autenticazione a due fattori è **obbligatoria** per il portale cliente in produzione. La applica l'accesso condizionale di Microsoft al momento dell'accesso, **non il portale** — se non hai registrato un secondo fattore, Microsoft te lo chiede prima di lasciarti proseguire. Non arrivi mai a Registerwerk senza esserti registrato.

La pagina **Security** (menu utente → Security) mostra il tuo stato e ti guida nella configurazione.

!!! note "Perché il registro non può darti un QR code di configurazione"
    La credenziale è di Microsoft. La sua API non offre alcun modo di creare un metodo authenticator o TOTP — il segreto non viene divulgato a nessuno, registro compreso.

    Perciò il codice che scansioni è mostrato sulla **pagina delle informazioni di sicurezza di Microsoft**. Il QR code sulla nostra pagina Security è semplicemente un **collegamento a quella pagina**, così puoi passare dal computer al telefono che ospiterà l'authenticator.

    È un vincolo di Entra, non una funzione mancante. Nessun software può fare diversamente.

**Per configurare Microsoft Authenticator:**

1. Installa **Microsoft Authenticator** sul telefono.
2. Apri **Security** nel portale e scansiona il QR code, oppure scegli **Set up now**.
3. Aggiungi un metodo di accesso sulla pagina di Microsoft e segui le sue istruzioni.
4. Torna al portale e scegli **I've finished** — la pagina ricontrolla e conferma.

### Telefono perso o sostituito

Contatta l'operatore del registro. Dopo aver verificato la tua identità per altra via, rimuoverà i tuoi vecchi metodi, **chiuderà le tue sessioni esistenti** ed emetterà un **Temporary Access Pass** — un codice di breve durata, di norma monouso, che ti consente un accesso per registrare un nuovo metodo.

Usalo prontamente; di solito scade entro un'ora.

!!! warning "Se la tua organizzazione ha un proprio tenant Entra, l'operatore non può aiutarti"
    I vostri utenti stanno nella *vostra* directory, non nella sua. Non può reimpostare i vostri metodi di autenticazione e la console di assistenza rifiuterà di provarci.

    Rivolgiti al tuo servizio informatico interno.

---

## Se la tua organizzazione usa un proprio provider di identità

Le organizzazioni che hanno configurato un provider di identità durante l'[onboarding](onboarding.md) accedono tramite il **proprio tenant Microsoft Entra**.

L'accesso si stabilisce **da tenant a tenant** in Entra, con la collaborazione B2B e le impostazioni di accesso tra tenant. Il registro non esegue mai un flusso authorization-code verso il vostro tenant e quindi **non chiede mai un client secret** — solo la vostra URL dell'emittente e il client id, a fini di identificazione.

Con questo modello:

- I vostri amministratori decidono quali metodi di autenticazione sono disponibili e quanto sono robusti.
- L'autenticazione a più fattori eseguita nel vostro tenant è accettata qui **solo se l'operatore del registro ha configurato la fiducia MFA in entrata**. È una decisione dell'operatore, non vostra — un cliente che garantisce per la propria MFA sarebbe un modo per abbassare l'asticella applicata ai propri utenti.
- **L'operatore del registro non può reimpostare i secondi fattori dei vostri utenti.** Lo fa il vostro servizio di assistenza.

---

## Da dove vengono i tuoi permessi

!!! danger "Il tuo provider di identità non decide che cosa puoi fare"
    Questo sorprende gli amministratori, e sbagliarlo ha conseguenze concrete.

    Entra risponde a *chi è questa persona*. **Registerwerk risponde a che cosa può fare**, partendo dal proprio record utente. I ruoli applicativi di Entra sono consultati una sola volta, alla creazione iniziale dell'account, per scegliere un valore predefinito sensato.

    Quindi: **togliere a qualcuno il ruolo applicativo in Entra non gli toglie i permessi di Registerwerk.** L'amministratore che lo fa credendo l'accesso revocato si sbaglia.

    Per cambiare ciò che qualcuno può fare, cambialo in Registerwerk — lo fa il tuo [amministratore aziendale](workspaces/company-admin.md). Per impedirgli del tutto l'accesso, disattiva l'account in Entra.

Una documentazione più vecchia descriveva ruoli mappati da un claim `roles` o `groups` nel token. Non funziona così, e configurare un claim del genere qui non avrà alcun effetto.

---

## Sessioni

Le sessioni durano **8 ore** per impostazione predefinita, dopodiché accedi di nuovo.

In modalità Entra le politiche di accesso condizionale della tua organizzazione possono richiedere una riautenticazione anticipata, e le azioni sensibili possono pretendere una prova d'identità fresca a prescindere da quanto manca alla fine della sessione. È l'[autenticazione rafforzata](../compliance/step-up-mfa.md), e funziona come previsto anziché essere un problema di sessione.

---

## Chiamare l'API direttamente

Per le integrazioni, ottieni un token e invialo come `Authorization: Bearer <token>`.

In **modalità Entra** ottieni il token da Entra usando la tua registrazione applicativa e lo scope che ti indica l'operatore. In **modalità locale**, `POST /api/v1/public/auth/login` ne restituisce uno.

!!! warning "Non mettere mai un token nel codice frontend o in un repository"
    Usa variabili d'ambiente o un gestore di segreti. Un token trapelato è una sessione a tuo nome, per tutta la sua vita residua.

[:octicons-arrow-right-24: Panoramica dell'API](../platform/api.md)

---

## Quando non riesci a entrare

| Che cosa vedi | Di solito significa | Fai |
|---|---|---|
| **Account not recognised** | Il tuo account Microsoft non è in un tenant ammesso dall'operatore | Contatta l'operatore |
| **Access denied** dopo l'accesso | L'accesso è riuscito; ti manca un ruolo | Chiedi al tuo amministratore aziendale |
| **Una richiesta di registrare le informazioni di sicurezza** | Due fattori non ancora configurati | Seguila — è obbligatoria |
| **Token expired** | Sessione terminata | Accedi di nuovo |
| **Ciclo di reindirizzamenti** | Configurazione errata lato operatore | Contatta l'operatore — non è cosa che puoi risolvere |
| **Sembra tutto a posto ma nulla funziona** | Il [KYC](kyc.md) della tua organizzazione potrebbe essere scaduto | Controlla la pagina KYC |

!!! tip "La differenza tra 401 e 403 vale la pena di conoscerla"
    Se stai segnalando un problema, dire quale dei due hai ottenuto farà risparmiare tempo a tutti.

    **401** — il tuo token non è accettato. Un problema di accesso.
    **403** — il token va bene, i permessi no. Un problema di ruoli, che il tuo amministratore aziendale può probabilmente risolvere senza coinvolgere l'operatore.

---

## Dove andare adesso

- [Ottenere l'account](onboarding.md)
- [Amministratore aziendale](workspaces/company-admin.md) — gestire utenti e impostazioni IdP
- [MFA rafforzata](../compliance/step-up-mfa.md) — perché alcune azioni chiedono di nuovo
