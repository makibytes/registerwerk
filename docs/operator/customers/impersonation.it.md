---
title: Modalità supporto — vedere ciò che vedono loro
description: Agire dentro il portale di un cliente per assisterlo: come funziona, a chi viene attribuito, quali sono i limiti e come governarla.
---

# Modalità supporto (impersonation) — vedere ciò che vedono loro

Un cliente dice che il Trading Desk non gli lascia mettere in vendita una posizione. Guardi il suo account nel portale operatore e sembra tutto a posto. Chiedi uno screenshot e ricevi la fotografia di un monitor.

**La modalità supporto chiude quel circolo.** Apre il portale cliente con l'organizzazione del cliente selezionata, così vedi esattamente ciò che vede lui.

Dà accesso alla vista che un cliente ha dei propri dati ed è quindi protetta: l'avvio richiede una prova di autenticazione rafforzata recente e un motivo scritto, la sessione predefinita è in **sola lettura**, e una sessione con scrittura richiede un secondo approvatore ed esiste solo in modalità demo.

---

## Che cos'è davvero

Non è un reset della password. Non è accedere come lui. Non ottieni mai le sue credenziali e lui non viene mai disconnesso.

La chiamata di avvio (`POST /api/v1/impersonation`, motivo di autenticazione rafforzata `ADMIN_IMPERSONATION`) porta il cliente, un **motivo obbligatorio** (almeno 15 caratteri) e, facoltativamente, un riferimento di ticket. **Non restituisce alcun token**, ma un URL di passaggio con un **codice monouso**, valido 60 secondi. Il portale cliente scambia il codice con un cookie di sessione httpOnly; riusare il codice termina la sessione. Il token non passa quindi mai dalle mani dell'operatore né dalla cronologia del suo browser.

Il token di sessione dietro il cookie porta:

| Claim | Valore |
|---|---|
| `sub` | **Il tuo** id utente — non il suo |
| `entityId` | L'organizzazione cliente all'interno della quale agisci |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `imp_mode` | `READ_ONLY` (predefinito) oppure `ACT_ON_BEHALF` |
| `jti` | L'id del record `impersonation_session` |
| `exp` | 30 minuti (`registerwerk.auth.impersonation-ttl-seconds`, 1800 per impostazione predefinita) |

!!! success "Il soggetto resti tu, ed è tutto il disegno"
    Poiché `sub` resta il tuo id utente, **ogni azione che compi è attribuita a te** nel [registro di audit](../../platform/audit-log.md) — non al cliente e non a un attore «sistema» condiviso.

    Un cliente non può mai essere incolpato di qualcosa che un operatore ha fatto durante l'impersonazione, e un operatore non può mai nascondersi dietro l'identità di un cliente. Senza questa proprietà, la modalità supporto sarebbe inutilizzabile in un contesto regolamentato.

    Il flag `imp: true` contrassegna la sessione come impersonata, così le azioni impersonate si distinguono da quelle ordinarie nel registro.

### Modalità

| | `READ_ONLY` (predefinita) | `ACT_ON_BEHALF` |
|---|---|---|
| Endpoint di avvio | `POST /api/v1/impersonation` | `POST /api/v1/impersonation/act-on-behalf` |
| Chi può avviarla | `REGISTRY_ADMIN` o `SUPPORT_AGENT` | solo `REGISTRY_ADMIN` |
| Autenticazione rafforzata | Sì (`ADMIN_IMPERSONATION`) | Sì, più un **secondo approvatore** (`ADMIN_IMPERSONATION_ACT_ON_BEHALF`) |
| Cosa può fare la sessione | Solo lettura: `POST`, `PUT`, `PATCH` e `DELETE` sono rifiutati con `403 IMPERSONATION_READ_ONLY` | Scrittura, salvo la lista di divieto sotto |
| Modalità produzione | Disponibile | **Rifiutata.** In modalità produzione ogni sessione attiva è applicata come sola lettura, anche una sessione di scrittura residua |

La lista di divieto di `ACT_ON_BEHALF` (`registerwerk.auth.impersonation-deny-patterns`) restituisce `403 IMPERSONATION_ACTION_DENIED` per le attestazioni del cliente e l'amministrazione degli account: conferma del pagamento, contestazione e regolamento dei trade, dichiarazioni di default del repo desk, gestione degli utenti dell'azienda, impostazioni dell'identity provider dell'azienda, webhook, identità dell'organizzazione ed eliminazione di documenti KYC.

!!! note "SUPPORT_AGENT"
    `SUPPORT_AGENT` è un ruolo del personale dell'operatore dedicato al supporto: può avviare sessioni in sola lettura ed elencare i soggetti clienti per sceglierne uno, nient'altro. Assegnarlo o revocarlo richiede autenticazione rafforzata e un secondo approvatore, ed è incluso nelle revisioni degli accessi.

Si possono impersonare solo soggetti giuridici **attivi**. La sessione è registrata in `impersonation_session` (attore, soggetto, modalità, motivo, ticket, approvatore, scadenza), e **gli amministratori dell'azienda del cliente vedono ogni sessione sul loro soggetto** in `GET /api/v1/company/impersonation-sessions`.

---

## Usarla

1. Nel portale operatore, apri la scheda del cliente e scegli **Impersonate**. Inserisci il motivo (e un riferimento di ticket se ne hai uno). La finestra propone la sola lettura; la modalità di scrittura compare solo in modalità demo.
2. Vieni trasferito al portale cliente su `/admin/handoff`. Il frammento dell'URL porta il `code` monouso, `entityId` e `entityName`; il portale scambia il codice con il proprio cookie di sessione e ti lascia sulla dashboard.
3. Una **barra permanente** sta in cima a ogni pagina: *Acting as **Nordwind Energie GmbH*** (in una sessione in sola lettura: *Viewing … (read-only support session - changes are blocked)*), con **Switch company** ed **Exit impersonation**.
4. Guarda e diagnostica. Tutto ciò che fai è registrato a tuo nome.
5. Scegli **Exit impersonation** al termine. La sessione termina ed è registrata; se non lo fai, scade dopo 30 minuti.

Puoi anche entrare senza aver scelto prima un cliente — la barra indica allora *Admin mode — no company selected* e offre **Select company**, con un elenco ricercabile. Un `SUPPORT_AGENT` arriva a questo selettore di azienda dopo l'accesso.

!!! tip "La barra è sempre visibile per una ragione"
    Ogni `REGISTRY_ADMIN` vede la barra di impersonazione nel portale cliente in ogni momento, che sia selezionata un'azienda o meno. È un promemoria costante del fatto che non sei un utente ordinario di questa interfaccia, e rende molto più difficile lavorare per errore nel contesto sbagliato.

---

## Quando usarla

**Buoni motivi**

- Riprodurre un problema segnalato dal cliente che non vedi nel portale operatore.
- Controllare come appare la vista di un cliente dopo una modifica di configurazione.
- Accompagnare un cliente lungo un flusso mentre è al telefono.
- Confermare che un problema di permessi o di ammissibilità sia quello che pensi.

**Cattivi motivi**

!!! danger "Non usare la modalità supporto per fare il lavoro al posto del cliente"
    Inserire un ordine, creare una proposta di vendita o inviare un'emissione per conto di un cliente produce una registrazione che mostra che *un operatore* ha preso una decisione commerciale dentro l'account di un cliente.

    Anche con un'attribuzione perfetta — forse *soprattutto* con un'attribuzione perfetta — è una registrazione difficile da spiegare a un'autorità di vigilanza o in una controversia. La volontà del cliente non vi compare da nessuna parte.

    Guarda, diagnostica, spiega. Lascia agire il cliente.

!!! danger "Non usarla per leggere dati a cui altrimenti non avresti diritto"
    La modalità supporto ti dà la vista del cliente sulle sue informazioni. Se *tu* sia legittimato a consultarle in assenza di un motivo di assistenza è una questione di [protezione dei dati](../../compliance/data-protection.md), non tecnica. La pista di controllo mostrerà che hai guardato.

---

## I suoi limiti

### In modalità Entra non funziona

Quando `ENTRA_ENABLED=true`, i clienti accedono tramite Microsoft Entra ID, che emette le sessioni direttamente a ciascun utente. Registerwerk non può emettere una sessione per conto di un cliente, e il backend **rifiuta** di provarci.

Il portale cliente mostra un messaggio esplicito anziché un reindirizzamento inspiegato:

> **Impersonation is unavailable.** This portal signs in through Microsoft Entra ID, which issues the session directly to each user. Registerwerk cannot act on a customer's behalf in this mode. Ask the customer to sign in themselves, or use the operator portal's read-only views.

È un vincolo reale, non una lacuna da aggirare. Nelle installazioni Entra la tua cassetta degli attrezzi per l'assistenza sono le viste del portale operatore più una condivisione dello schermo.

!!! warning "Pianifica i processi di assistenza tenendone conto prima di passare"
    Gli operatori che hanno costruito il flusso di assistenza sulla modalità supporto e poi abilitano Entra scoprono la perdita nel momento peggiore. Decidi come assisterai i clienti senza di essa *prima* del passaggio, non dopo.

### Altri limiti

- **La sessione è di breve durata.** Scade dopo 30 minuti; rientra (con un nuovo motivo) anziché cercare di prolungarla.
- **Il codice di passaggio è monouso e vale 60 secondi.** Se il portale non lo ritira in tempo, o se viene riusato, la sessione termina; ricomincia.
- **Ottieni un insieme fisso di ruoli**, non i ruoli specifici di un singolo utente. Non puoi riprodurre un problema che dipende dai permessi più ristretti di un utente.
- **Autenticazione rafforzata e quattro occhi non vengono aggirati.** L'avvio richiede la tua prova di autenticazione rafforzata; una sessione di scrittura richiede in più un secondo approvatore. All'interno di una sessione le operazioni protette del cliente restano protette, e la coda di approvazione rifiuta le sessioni di impersonazione.
- **Non puoi impersonare un altro operatore.** Riguarda solo i soggetti giuridici clienti.

---

## Governarla

La modalità supporto è disponibile a ogni `REGISTRY_ADMIN` e a ogni `SUPPORT_AGENT`. È quindi una questione di controllo oltre che tecnica, e i revisori la solleveranno.

!!! tip "Pratiche che vale la pena adottare"

    **Rendi utile il motivo.** La piattaforma rifiuta un avvio senza un motivo di almeno 15 caratteri e lo registra, insieme al riferimento di ticket facoltativo, in `impersonation_session` e nell'evento di audit. Metti il numero di ticket nel campo ticket e scrivi nel motivo ciò che ti serve vedere.

    **Rivedi periodicamente gli eventi di impersonazione.** Sono interrogabili (nomi degli eventi sotto). Uno sguardo mensile a chi ha impersonato chi, confrontato con i ticket, trasforma un potere ampio in un potere supervisionato. Gli amministratori dell'azienda del cliente possono fare lo stesso controllo dal proprio lato.

    **Preferisci `SUPPORT_AGENT` per il personale di supporto.** Può avviare sessioni in sola lettura e nient'altro, quindi il supporto non ha bisogno di un account `REGISTRY_ADMIN`.

    **Mantieni ristretto `REGISTRY_ADMIN`.** Ogni titolare può avviare sessioni per ogni cliente attivo.

    **Di' ai clienti che esiste.** Scoprire a posteriori che il personale dell'operatore può entrare nel loro portale danneggia la fiducia molto più della capacità in sé. Presentata bene — *possiamo vedere ciò che vedete voi, ogni azione è registrata a nostro nome e i vostri amministratori possono consultare ogni sessione* — rassicura.

    **Non lasciare mai una sessione aperta.** Esci al termine. Un browser incustodito in una sessione impersonata è un browser incustodito dentro l'account di un cliente (scade comunque dopo 30 minuti).

---

## Che cosa chiederà un revisore

Tieni pronte le risposte:

- Chi detiene `REGISTRY_ADMIN` o `SUPPORT_AGENT`, e quante persone sono?
- Come colleghi un evento di impersonazione a un motivo di supporto? (Motivo e ticket sono in `impersonation_session` e nell'evento `ADMIN_IMPERSONATION_STARTED`.)
- Come individueresti un'impersonazione *senza* ticket corrispondente?
- Puoi dimostrare che le azioni impersonate sono attribuite all'operatore e non al cliente?
- L'impersonazione con scrittura è disattivata in produzione? (Sì: la modalità produzione rifiuta `ACT_ON_BEHALF` e declassa a sola lettura ogni sessione attiva.)

La pista di audit contiene gli eventi `ADMIN_IMPERSONATION_STARTED`, `ADMIN_IMPERSONATION_HANDOFF_EXCHANGED` e `ADMIN_IMPERSONATION_ENDED`; le richieste all'interno di una sessione portano il contrassegno `imp`. La domanda sull'attribuzione è una dimostrazione dal vivo e vale la pena provarla: impersona un soggetto di prova, guarda una pagina, mostra le voci di audit che nominano il tuo utente con `imp` impostato e mostra la sessione nella vista degli amministratori dell'azienda del cliente.

---

## Dove andare adesso

- [Assistenza due fattori](two-factor-support.md) — l'altro grande flusso di assistenza
- [Pista di controllo](../../platform/audit-log.md)
- [Ruoli e permessi](roles.md)
