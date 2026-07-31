---
title: Onboarding
---

# Onboarding

Questa guida ti accompagna nella registrazione della tua organizzazione presso il registro eWpG — dalla prima email di invito fino a un account completamente configurato.

## Come funziona l'onboarding

L'onboarding è avviato dall'operatore del registro, non tramite autoregistrazione. Il processo segue questi quattro passaggi:

```
L'operatore crea il soggetto
        |
        v
Ricevi un'email di invito con un token monouso
        |
        v
Utilizzi il token e configuri la tua organizzazione
        |
        v
Un amministratore attiva il tuo account — puoi iniziare a lavorare
```

## Passo 1 — Ricevere l'invito

L'operatore del registro crea per tuo conto un soggetto (società o persona fisica). Riceverai dal registro un'email con oggetto **«Your eWpG Registry Invitation»** contenente:

- Un **token di onboarding** monouso (valido 48 ore)
- Un collegamento al portale cliente

!!! warning "Scadenza del token"
    Il token di onboarding scade dopo 48 ore. Se è scaduto, contatta l'operatore del registro per richiederne uno nuovo. Non condividere il token — dà pieno accesso alla configurazione del tuo account.


## Passo 2 — Utilizzare il token

1. Fai clic sul collegamento nell'email di invito. Arriverai al portale cliente.
2. Ti verrà chiesto di accedere tramite il tuo provider di identità (vedi [Accedere](./authentication.md)). Per i nuovi utenti si tratta di solito di Microsoft Entra ID (già Azure AD) con il tuo indirizzo email aziendale.
3. Dopo l'accesso, il portale rileva il token di onboarding dall'URL e attiva automaticamente il tuo soggetto.
4. Vieni reindirizzato alla schermata **Welcome**, che mostra il ruolo assegnato (Issuer, Investor o Auditor).

## Passo 3 — Configurare la tua organizzazione

Dopo aver utilizzato il token puoi configurare il profilo della tua organizzazione:

### Dati dell'organizzazione

Vai su **Settings → Organization** e compila:

| Campo | Descrizione |
|-------|-------------|
| Legal name | La tua denominazione sociale registrata |
| LEI | Legal Entity Identifier (obbligatorio per gli emittenti) |
| Registration number | Numero di iscrizione della società |
| Jurisdiction | Paese di costituzione |
| Contact email | Contatto principale per le comunicazioni regolamentari |

### Gestione utenti

Se la tua organizzazione ha più utenti, vai su **Settings → Users** e invitali via email. Ogni utente invitato:
- riceve una propria email di invito
- accede con la propria identità aziendale
- riceve uno dei ruoli della tua organizzazione

### Configurare un proprio provider di identità (facoltativo)

Se la tua organizzazione usa un provider di identità proprio (per esempio un vostro Keycloak, Okta o un altro IdP compatibile OIDC), puoi configurarlo sotto **Settings → Identity Provider**.

Dovrai fornire:

```
OIDC Issuer URL:       https://your-idp.example.com/realms/your-realm
Client ID:             registerwerk-client
```

!!! info "Non c'è un campo per il client secret"
    La federazione si stabilisce da tenant a tenant nel vostro provider di identità. Registerwerk non esegue mai un flusso authorization-code verso il vostro tenant, quindi non ha alcun uso per un vostro client secret — e il campo è stato rimosso anziché lasciato a raccogliere una credenziale di cui nessuno ha bisogno. Vedi [Amministratore aziendale](workspaces/company-admin.md).


Una volta configurato e verificato, tutti gli utenti della tua organizzazione verranno reindirizzati al vostro IdP per l'autenticazione anziché all'accesso Entra ID predefinito.

## Passo 4 — Attivazione dell'account

Il tuo account ora è attivo. A seconda del ruolo:

- **Emittenti**: potrebbe esserti richiesto di completare una verifica KYC/antiriciclaggio prima di poter distribuire token sulla rete principale. Vedi [Creare un'emissione](lifecycle/primary-issuance.md).
- **Investitori**: il tuo account è pronto. Puoi collegare un wallet e consultare le tue posizioni.
- **Revisori**: il tuo account è pronto. Hai accesso in sola lettura a tutti i dati del registro.

## Serve aiuto?

Se incontri problemi durante l'onboarding, contatta l'operatore del registro tramite il collegamento di assistenza nell'email di invito o il pulsante **Help** nel piè di pagina del portale.
