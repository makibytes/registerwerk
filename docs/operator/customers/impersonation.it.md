---
title: Modalità supporto — vedere ciò che vedono loro
description: Agire dentro il portale di un cliente per assisterlo: come funziona, a chi viene attribuito, quali sono i limiti e come governarla.
---

# Modalità supporto (impersonation) — vedere ciò che vedono loro

Un cliente dice che il Trading Desk non gli lascia mettere in vendita una posizione. Guardi il suo account nel portale operatore e sembra tutto a posto. Chiedi uno screenshot e ricevi la fotografia di un monitor.

**La modalità supporto chiude quel circolo.** Apre il portale cliente con l'organizzazione del cliente selezionata, così vedi esattamente ciò che vede lui.

È anche la cosa più potente che puoi fare senza l'approvazione di una seconda persona, e merita di essere usata con intenzione.

---

## Che cos'è davvero

Non è un reset della password. Non è accedere come lui. Non ottieni mai le sue credenziali e lui non viene mai disconnesso.

Il backend emette un **token di breve durata** che porta:

| Claim | Valore |
|---|---|
| `sub` | Il **tuo** identificativo utente — non il suo |
| `entityId` | L'organizzazione cliente dentro cui stai agendo |
| `roles` | `COMPANY_ADMIN`, `ISSUER`, `INVESTOR`, `TRADER` |
| `imp` | `true` |
| `exp` | Breve — la durata standard di un token |

!!! success "Il soggetto resti tu, ed è tutto il disegno"
    Poiché `sub` resta il tuo identificativo utente, **ogni azione che compi è attribuita a te** nella [pista di controllo](../../platform/audit-log.md) — non al cliente, né a un generico attore «di sistema».

    Un cliente non può mai essere incolpato di qualcosa che un operatore ha fatto mentre lo assisteva, e un operatore non può mai nascondersi dietro l'identità di un cliente. Senza questa proprietà, la modalità supporto sarebbe inutilizzabile in un contesto regolamentato.

    Il flag `imp: true` marca la sessione come modalità supporto, così quelle azioni sono distinguibili da quelle ordinarie nel log.

---

## Usarla

1. Nel portale operatore, apri la scheda del cliente e scegli **Impersonate**.
2. Vieni trasferito al portale cliente su `/admin/handoff`, che consuma il token dal frammento dell'URL e ti deposita nella dashboard.
3. In cima a ogni pagina compare una **barra persistente**: *Acting as **Nordwind Energie GmbH***, con **Switch company** ed **Exit impersonation**.
4. Lavora. Tutto ciò che fai è registrato a tuo nome.
5. Scegli **Exit impersonation** quando hai finito.

Puoi anche entrare senza scegliere prima un cliente — la barra riporta *Admin mode — no company selected* e offre **Select company**, con un elenco ricercabile.

!!! tip "La barra è sempre visibile per una ragione"
    Qualsiasi `REGISTRY_ADMIN` vede la barra della modalità supporto nel portale cliente in ogni momento, che sia selezionata una società o no. È un promemoria costante che non sei un utente ordinario di questa interfaccia, e rende molto più difficile lavorare per sbaglio nel contesto sbagliato.

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

- **Il token è di breve durata.** Le sessioni lunghe scadono; rientra anziché cercare di prolungarla.
- **Ottieni un insieme fisso di ruoli**, non i ruoli specifici di un singolo utente. Non puoi riprodurre un problema che dipende dai permessi più ristretti di un utente.
- **Autenticazione rafforzata e quattro occhi restano applicabili.** La modalità supporto non li aggira.
- **Non puoi impersonare un altro operatore.** Riguarda solo i soggetti giuridici clienti.

---

## Governarla

La modalità supporto è una capacità permanente di ogni `REGISTRY_ADMIN`. Questo la rende una questione di controllo più che tecnica, e i revisori lo chiederanno.

!!! tip "Pratiche che vale la pena adottare"

    **Richiedi un motivo, registrato fuori dalla piattaforma.** Un riferimento a un ticket, prima della sessione. La pista di controllo registra che hai usato la modalità supporto; non può registrare *perché*.

    **Rivedi periodicamente gli eventi di modalità supporto.** Sono interrogabili. Un'occhiata mensile a chi ha assistito chi, confrontata con i ticket, trasforma un potere illimitato in un potere vigilato.

    **Tieni ristretto `REGISTRY_ADMIN`.** Ogni titolare può entrare presso ogni cliente. È l'argomento più forte a favore di un elenco di amministratori snello.

    **Di' ai clienti che esiste.** Scoprire a posteriori che il personale dell'operatore può entrare nel loro portale danneggia la fiducia molto più della capacità in sé. Presentata bene — *possiamo vedere ciò che vedete voi, ogni azione è registrata a nostro nome* — rassicura.

    **Non lasciare mai una sessione aperta.** Esci quando hai finito. Un browser incustodito in una sessione di modalità supporto è un browser incustodito dentro l'account di un cliente.

---

## Che cosa chiederà un revisore

Tieni pronte le risposte:

- Chi detiene `REGISTRY_ADMIN`, e quante persone sono?
- Come colleghi un evento di modalità supporto a un motivo di assistenza?
- Come rileveresti un uso della modalità supporto *senza* un ticket corrispondente?
- Sai dimostrare che quelle azioni sono attribuite all'operatore e non al cliente?

L'ultima è una dimostrazione dal vivo e conviene provarla: entra presso un soggetto di prova, compi un'azione innocua, mostra la voce di audit che nomina il tuo utente con `imp` impostato.

---

## Dove andare adesso

- [Assistenza due fattori](two-factor-support.md) — l'altro grande flusso di assistenza
- [Pista di controllo](../../platform/audit-log.md)
- [Ruoli e permessi](roles.md)
