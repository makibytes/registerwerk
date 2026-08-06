---
title: Emittente
description: Per le organizzazioni che raccolgono capitali emettendo strumenti finanziari — crearli, distribuirli, amministrarli e rimborsarli.
---

# Emittente

**Stai prendendo denaro a prestito, o vendendo una quota, e lo fai emettendo uno strumento finanziario.** Descrivi lo strumento, lo fai approvare, lo porti su una blockchain, ammetti gli investitori, crei le unità — e poi amministri la cosa per anni.

Delle tre aree di lavoro è quella con più responsabilità attaccata. Ciò che crei qui è un'obbligazione giuridica della tua organizzazione.

---

## Che cosa c'è qui

| | |
|---|---|
| **Issuances** | Creare e amministrare i tuoi strumenti. L'evento principale. |
| **My dApps** | Pubblicare applicazioni sul marketplace — vedi [Editore di dApp](dapp-publisher.md). |
| **Company Admin** | Gestire i tuoi utenti e la tua organizzazione — vedi [Amministratore aziendale](company-admin.md). |
| **Marketplace** | Le applicazioni dell'ecosistema. |

---

## Prima della tua prima emissione

- **La tua organizzazione è attivata e il suo KYC è approvato.** Un emittente con il KYC scaduto non può emettere.
- **Conosci la tua giurisdizione.** Non è un'etichetta — seleziona l'intero corpo di regole applicate allo strumento per tutta la sua vita. [Quadri giuridici](../../legal/index.md).
- **Hai un ISIN, se ti serve.** Registerwerk ne impone l'unicità ma non li assegna; lo ottieni dalla tua agenzia nazionale di codifica. Puoi procedere senza, ma interoperare con il mondo esterno diventa più difficile.
- **Hai deciso chi può detenerlo.** Offerta al pubblico? Solo investitori professionali? Una sola giurisdizione? Questo determina il tuo standard di token, e cambiarlo dopo significa un nuovo strumento.

---

## Creare un'emissione

*Issuances → New Issuance.* Tre passaggi.

=== "1. Caratteristiche"

    Nome, ISIN, giurisdizione e l'economia dello strumento. Per un'obbligazione: valore nominale, valuta, date di emissione e scadenza, tasso cedolare, convenzione di calcolo giorni, frequenza di pagamento, rimborsabilità anticipata e prezzo di emissione come frazione del nominale.

    **Il prezzo di emissione** conta per le obbligazioni zero coupon: non pagano interessi e compensano l'investitore vendendo sotto la pari — compri a 800 €, ricevi 1.000 € a scadenza. Il valore predefinito è `1.0`.

    **La convenzione di calcolo giorni** (ACT/360, ACT/365, 30/360…) decide come un anno parziale diventa una frazione nel calcolo degli interessi. È poco appariscente e cambia il denaro.

=== "2. Chain e standard"

    Quale blockchain e quale standard di token.

    Per uno strumento regolamentato la risposta è di solito [ERC-3643](../../token-standards/erc3643.md), perché è quello che fa rispettare *chi può detenere questo* dentro il token stesso. [ERC-20](../../token-standards/erc20.md) è più semplice e compreso ovunque, ma non ha alcuna nozione di ammissibilità — chiunque riceva un'unità la possiede.

    Altre forme: ERC-1155 per molte serie in un solo contratto, ERC-3525 per strumenti semi-fungibili, ERC-4626/7540 per fondi e vault, DAML su Canton dove serve riservatezza verso le controparti, SPL-2022 su Solana.

    [:octicons-arrow-right-24: Scegliere uno standard di token](../issuers/token-standards.md)

=== "3. Verifica e invio"

    Controlla e invia. Lo stato passa da `DRAFT` a `PENDING_APPROVAL` e **la modifica si ferma**.

---

## Approvazione

L'operatore esamina. Poi:

| | |
|---|---|
| **Approvata** | `APPROVED`. Condizioni bloccate. Puoi distribuire. |
| **Respinta** | Torna a `DRAFT` con una motivazione registrata. Modifica e reinvia. |

Non esiste uno stato `REJECTED` — un'emissione respinta torna in bozza, dove è modificabile. La motivazione è registrata nella [pista di controllo](../../platform/audit-log.md).

---

## Distribuire

*Issuance → Deploy.* Registerwerk invia la transazione e registra l'indirizzo del contratto. Per ERC-3643 questo distribuisce l'intera suite — token, identity registry, trusted issuers registry, conformità — collegati tra loro.

Il contratto ora esiste e detiene **zero unità**.

[:octicons-arrow-right-24: Distribuire su una blockchain](../issuers/deploying-to-chain.md)

---

## Ammettere gli investitori

*Issuance → Investors.* Ogni investitore deve essere un soggetto con KYC approvato e un wallet registrato, iscritto nell'identity registry.

!!! warning "È un presupposto, non burocrazia"
    Sotto ERC-3643 un wallet non ammesso **non può ricevere token** — il trasferimento fallisce on-chain. Coniare prima di ammettere produce transazioni fallite e nient'altro.

Scegli il tipo di iscrizione per ciascun titolare:

- **Collettiva** (*Sammeleintragung*, iscrizione collettiva) — un depositario detiene per molti investitori sottostanti.
- **Individuale** (*Einzeleintragung*, iscrizione individuale) — l'investitore è nominato direttamente, tramite un riferimento pseudonimo. Il §17(2) eWpG richiede contenuti aggiuntivi: diritti di terzi, restrizioni alla disposizione, annotazioni sulla capacità giuridica. Il §19(2) ti obbliga a inviare estratti di registro ai titolari consumatori.

Un asset può portare entrambe le forme contemporaneamente.

[:octicons-arrow-right-24: Gestire i tuoi investitori](../issuers/managing-investors.md)

---

## Coniare ed emettere

*Issuance → Mint.* Le unità vengono all'esistenza e sono assegnate ai titolari. Poi `APPROVED` → `ISSUED` e lo strumento è vivo.

!!! danger "Coniare crea valore dal nulla"
    Un errore qui non è un numero sbagliato in un report — sono strumenti finanziari veri nelle mani sbagliate.

    Regole di controllo del conio possono limitare quanto un indirizzo potrà mai ricevere, l'azione richiede l'[autenticazione rafforzata](../../compliance/step-up-mfa.md) e ogni conio è registrato con un attore nominato.

---

## Conviverci: cinque anni di amministrazione

È la parte che si sottovaluta. L'emissione dura una settimana. L'amministrazione, il resto del decennio.

### Operazioni societarie

Cedole due volte l'anno e, alla fine, il rimborso. Registerwerk crea automaticamente le operazioni cedolari dal piano dei pagamenti e le fa avanzare lungo le loro date.

Il tuo compito è approvare il regolamento — e questo richiede il [principio dei quattro occhi](../../compliance/step-up-mfa.md), perché pagare la lista di titolari sbagliata è l'errore catastrofico classico dell'amministrazione titoli ed è molto difficile da rimediare.

Le tre date che decidono chi viene pagato: **data di registrazione** (chi detiene in quell'istante ne ha diritto), **data di stacco** (da qui lo strumento tratta senza il pagamento), **data di pagamento** (il denaro si muove).

[:octicons-arrow-right-24: Le operazioni societarie in dettaglio](../lifecycle/redemption.md)

### Tenere d'occhio la lista dei titolari

I tuoi investitori negoziano tra loro e non puoi impedirlo. Ciò che ottieni è visibilità: il registro si aggiorna e la tua lista di titolari cambia.

Fai attenzione ai **limiti di detenzione**, se il tuo strumento ne ha — una regola di conformità che fa fallire i trasferimenti una volta raggiunto un limite. Gli investitori lo vivono come un fallimento inspiegato, quindi conoscere i propri limiti fa risparmiare richieste di assistenza.

### Estratti di registro

Per i titolari consumatori con iscrizione individuale, gli estratti del §19(2) sono generati e conservati come documenti di registro. Riproducibili anni dopo, perché un estratto che non sai riprodurre non è una prova.

### Sospensione

`ISSUED` → `SUSPENDED` congela la negoziazione senza porre fine allo strumento — per un'operazione societaria, una controversia o un errore sospettato. Reversibile.

### Rimborso

A scadenza: fotografia delle posizioni, spettanze, approvazione a quattro occhi, pagamento, token distrutti, `REDEEMED`. Terminale — da lì non si esce.

Le righe dei titolari sono **cancellate logicamente, mai rimosse**: un'iscrizione ai sensi del §16 che sparisce non può soddisfare obblighi di conservazione né di prova di manomissione.

---

## Cose che ti sorprenderanno

!!! info "Non puoi bloccare un'operazione lecita tra titolari ammessi"
    Una volta emesso, lo strumento tratta secondo le proprie regole di conformità. Quelle regole le fissi tu all'emissione; non decidi sulle singole operazioni.

!!! info "Non puoi modificare un'emissione approvata"
    Le condizioni si bloccano con l'approvazione. Una modifica significa una nuova emissione, o una correzione dell'operatore con pista di controllo.

!!! info "Il KYC dei tuoi investitori non è un tuo giudizio"
    L'operatore verifica i soggetti. Non puoi ammettere un investitore che l'operatore non ha approvato, per quanto bene tu lo conosca.

!!! info "Un trasferimento coattivo richiede l'operatore"
    Le correzioni ai sensi del §24 eWpG — una chiave persa, un provvedimento giudiziario, un'iscrizione errata — sono azioni dell'operatore a quattro occhi, non qualcosa che esegui tu.

---

## Dove andare adesso

- [La vita di uno strumento finanziario](../lifecycle/index.md) — l'arco intero, da capo a fondo
- [Scegliere uno standard di token](../issuers/token-standards.md)
- [Amministratore aziendale](company-admin.md) — gestire gli utenti della tua organizzazione
