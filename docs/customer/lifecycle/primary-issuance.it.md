---
title: 2. Emissione primaria
description: Distribuire il contratto, ammettere gli investitori e creare i titoli — il momento in cui uno strumento finanziario viene all'esistenza.
---

# Fase 2 — Emissione primaria

*L'obbligazione è approvata. Ora deve diventare reale.*

L'**emissione primaria** è l'operazione tra emittente e primi investitori: l'unico momento in cui Nordwind riceve denaro. Tutto ciò che segue — ogni negoziazione, ogni prestito — avviene tra investitori. Il bilancio di Nordwind non ne risente.

Vale la pena tenere a mente questa distinzione: spiega perché questa fase è così controllata e le successive comparativamente libere.

---

## L'ordine delle operazioni

```mermaid
graph TB
    A["1 Distribuire il contratto<br/><small>un contenitore vuoto on-chain</small>"] --> B["2 Ammettere gli investitori<br/><small>chi può detenerlo</small>"]
    B --> C["3 Coniare<br/><small>i titoli nascono</small>"]
    C --> D["4 Emettere<br/><small>il registro entra in funzione</small>"]
```

L'ordine non è arbitrario. Con ERC-3643 un investitore non ammesso **non può ricevere token** — il trasferimento viene annullato. Coniare prima di ammettere produce solo transazioni fallite.

---

## 1. Distribuire il contratto

*Issuances → la tua emissione → Deploy.*

Registerwerk invia la transazione che iscrive il contratto sulla blockchain scelta e registra l'indirizzo risultante. Per ERC-3643 non si tratta di un contratto ma dell'intera suite — token, identity registry, trusted issuers registry, compliance — collegati tra loro.

Ottieni un **hash di transazione** (la ricevuta) e un **indirizzo di contratto** (dove risiede ora l'obbligazione). Entrambi sono pubblici; chiunque può consultarli in un block explorer.

A questo punto il contratto esiste e detiene **zero titoli**. Nessuno possiede nulla.

??? note "Per gli specialisti: indirizzi deterministici"

    La factory distribuisce con `CREATE2`, quindi l'indirizzo del contratto è una funzione pura di deployer, salt e bytecode. Può essere calcolato *prima* della distribuzione.

    Non è un gioco di prestigio. Significa che l'indirizzo può essere annotato nel registro, comunicato alle controparti e citato negli accordi prima ancora che la transazione sia inclusa in un blocco — e che una distribuzione fallita e ripetuta atterra allo stesso indirizzo. I sistemi a valle non devono attendere una ricevuta per sapere dove guardare.

    [:octicons-arrow-right-24: Distribuire su una blockchain](../issuers/deploying-to-chain.md)

---

## 2. Ammettere gli investitori

*Issuance → Investors → Add investor.*

Il collocatore di Nordwind ha trovato acquirenti. Prima che uno di loro possa ricevere anche un solo titolo, deve essere ammesso:

1. **Il suo soggetto giuridico deve essere attivato e con KYC approvato.** Non a giudizio dell'emittente, ma dell'operatore. Vedi [Esaminare il KYC](../../operator/customers/kyc-process.md).
2. **Deve registrare un indirizzo wallet** (un *endpoint*) su cui ricevere. Vedi [Collegare un wallet](../investors/wallet-setup.md).
3. **Viene iscritto nell'identity registry**, ed è questo che lo ammette on-chain.

Solo allora può detenere l'obbligazione.

!!! warning "È il passaggio che si sottovaluta"
    Ammettere gli investitori non è un adempimento amministrativo da sbrigare dopo. È un presupposto imposto dal contratto del token stesso. Un emittente che ha coniato prima di ammettere si ritrova con un contratto pieno di titoli e nessun modo lecito di muoverli.

### Che cosa contiene un'iscrizione a registro

Ogni investitore ammesso diventa un **titolare** — una riga del registro. Nel modello dell'eWpG questa è la registrazione che conta, purché l'operatore sia un responsabile del registro autorizzato (cosa che questo repository non stabilisce), e il diritto tedesco ne conosce due forme:

=== "Iscrizione collettiva (Sammeleintragung)"

    Il registro indica un **depositario** che detiene per conto di molti investitori sottostanti. Il registro vede il depositario; il depositario tiene i propri libri per i suoi clienti.

    Il modello familiare, e il modo in cui oggi è detenuta la maggior parte degli strumenti istituzionali.

=== "Iscrizione individuale (Einzeleintragung)"

    Il registro indica **direttamente l'investitore**, identificato da un riferimento pseudonimo anziché da un nome in chiaro on-chain.

    Il §17(2) eWpG richiede per queste iscrizioni più contenuto: diritti di terzi sulla posizione, restrizioni alla disposizione e ogni annotazione sulla capacità giuridica del titolare. E il §19(2) obbliga l'emittente a inviare un **estratto di registro** (*Registerauszug*) ai titolari consumatori — dopo l'iscrizione iniziale, dopo ogni variazione che li riguarda e almeno una volta l'anno.

    Registerwerk produce e conserva questi estratti come documenti di registro a pieno titolo, perché un estratto che non si può riprodurre in seguito non prova nulla.

Uno stesso asset può portare entrambe le forme insieme — il registro la chiama posizione `MIXED`.


!!! info "Le iscrizioni le effettua l'operatore"
    Un'iscrizione, e qualsiasi modifica di un attributo del §17(2), è effettuata dall'operatore del registro sulla base di un'istruzione registrata: **chi l'ha impartita** (titolare, beneficiario, tribunale, curatore, modifica delle condizioni da parte dell'emittente) e un **riferimento**. L'emittente non modifica il registro; presenta una *richiesta* che l'operatore esegue o rifiuta. Rimuovere un diritto o una restrizione richiede un'azione esplicita e un secondo approvatore, e i valori prima e dopo vengono conservati.

---

## Sottoscrizioni: dall'ordine al registro

Gli investitori sottoscrivono tramite il portale; il registro non viene più riempito digitando posizioni in una finestra di dialogo. Un ordine attraversa questi stati:

```mermaid
stateDiagram-v2
    direction LR
    SUBMITTED --> ALLOCATED: allocate
    ALLOCATED --> PAYMENT_CONFIRMED: accepted and paid
    PAYMENT_CONFIRMED --> SETTLED: settle
    ALLOCATED --> LAPSED: not paid in time
    ALLOCATED --> RELEASED: released
    SUBMITTED --> REJECTED: reject
```

1. **Inviare.** Qualsiasi investitore già registrato può inviare un ordine finché l'attività è aperta alla sottoscrizione (`APPROVED` o `ISSUED`) e l'investitore rientra nel mercato di riferimento MiFID.
2. **Assegnare.** L'emittente o l'operatore assegna, per intero o in misura ridotta. Le assegnazioni contano contro la dimensione dell'emissione e contro il massimo detenibile dall'investitore **insieme alle sue altre assegnazioni aperte**, così due assegnazioni parallele non possono restare ciascuna sotto il limite.
3. **Accettare.** L'investitore accetta l'assegnazione. Nel registro non viene ancora iscritto nulla. Per un'obbligazione vengono mostrati l'importo dovuto (unità assegnate × valore nominale × prezzo di emissione), un riferimento di pagamento e un termine di pagamento — per impostazione predefinita 10 giorni lavorativi TARGET. Un'assegnazione non pagata in tempo **decade** e libera la sua capacità; l'emittente o l'operatore possono anche **rilasciarla**.
4. **Confermare il pagamento.** L'emittente o l'operatore conferma l'arrivo del denaro (richiede lo [step-up](../../compliance/step-up-mfa.md)). Un pagamento insufficiente viene rifiutato; un pagamento eccedente viene accettato e mostrato come *rimborso dovuto*.
5. **Regolare.** Prima di scrivere qualsiasi cosa vengono rieseguiti gli stessi controlli del regolamento di uno scambio: KYC approvato, nessuna corrispondenza sanzioni irrisolta, nessun [Sperrvermerk](holding.md), registro non congelato per un trasferimento, finalità della chain, mercato di riferimento e limite di detenzione. Poi le unità vengono emesse. Se l'attività è **distribuita**, le unità vengono coniate sul wallet dell'investitore e la sincronizzazione dei titolari le accredita nel registro quando il trasferimento è indicizzato. Altrimenti il registro viene accreditato direttamente; una seconda sottoscrizione sullo stesso wallet incrementa l'iscrizione esistente.

!!! note "Punti aperti"
    Uno standard di token senza conio automatico lascia l'ordine su *pagamento confermato*: un operatore emette le unità. Per le attività senza condizioni obbligazionarie l'operatore inserisce l'importo ricevuto; non c'è un prezzo calcolato. Nelle iscrizioni collettive viene iscritto come titolare l'investitore, non un depositario — la questione non è ancora decisa.

---

## 3. Coniare

*Issuance → Mint.*

**Coniare** significa creare unità che prima non esistevano e assegnarle a un titolare. È il momento in cui lo strumento viene all'esistenza.

Nordwind conia 50.000 titoli distribuiti tra i suoi investitori nelle proporzioni sottoscritte. L'offerta totale del contratto passa da zero a 50.000. Ogni iscrizione a registro riporta il valore nominale detenuto dall'investitore.

!!! danger "Il conio è il punto più tagliente del sistema"
    Coniare crea valore dal nulla. Un errore qui non è un numero sbagliato in un report — sono strumenti veri nelle mani sbagliate.

    Registerwerk lo tratta perciò come un'operazione controllata: le **regole di controllo del conio** possono limitare quanto un dato indirizzo potrà mai ricevere, l'operazione richiede [autenticazione rafforzata](../../compliance/step-up-mfa.md), e ogni conio è registrato nel registro di controllo con la persona che lo ha eseguito.

### Dove va il denaro

Nota che cosa la piattaforma **non** ha fatto: non ha spostato 50 milioni di euro.

La gamba contante di un'emissione primaria — gli investitori che pagano Nordwind — è una questione di pagamenti, e Registerwerk supporta diverse risposte, dette **binari di pagamento**:

| Binario | Che cos'è |
|---|---|
| **Stablecoin** | Un token che rappresenta una valuta, in circolazione sulla stessa chain dello strumento. |
| **Pontes** | Un'API di pagamento bancario istantaneo. |
| **DvP ERC-7573** | Un contratto di regolamento che rende ciascuna gamba condizionata all'altra. |
| **SEPA off-chain** | Un normale bonifico bancario, riconciliato per riferimento. |

Il terzo merita attenzione. La **consegna contro pagamento** elimina il rischio più antico del regolamento titoli: che una parte adempia e l'altra no. Con la consegna contro pagamento lo strumento si muove *se e solo se* si muove il pagamento — non per promessa, ma come proprietà della transazione.

??? note "Per gli specialisti: la consegna contro pagamento, e ciò che non prova"

    `DvpSettlement.sol` implementa uno schema in stile ERC-7573. Una parte blocca la propria gamba in deposito a garanzia; la controparte regola poi entrambe le gambe in un'unica transazione, oppure lo scambio scade e il deposito viene restituito. Al momento del regolamento il tuo client trasmette un'impronta delle condizioni concordate (parti, importi, token, scadenza): se quanto bloccato differisce in un qualsiasi dettaglio, non si muove nulla. Il regolamento si ferma anche finché una delle parti è congelata dai controlli di conformità del token. `EwpgBondDesk` mostra la stessa forma «token e pagamento nella stessa transazione».

    Due precisazioni oneste:

    **L'atomicità è per singolo registro.** Se lo strumento è su Ethereum e il denaro arriva via SEPA, nessun contratto può renderli atomici. Ciò che la consegna contro pagamento offre lì è un rilascio condizionato, non un'unica transazione. L'atomicità vera richiede entrambe le gambe sullo stesso registro.

    **Il regolamento tecnico non è il regolamento giuridico.** Che un contratto esegua entrambi i trasferimenti in una transazione prova che cosa ha fatto un computer. Se ciò costituisca estinzione dell'obbligazione, opponibilità in caso di insolvenza o buona consegna secondo la legge applicabile è una questione giuridica che il codice non risolve.

    I binari stablecoin portano campi informativi legati a MiCAR — emittente, autorizzazione, qualifica di token di moneta elettronica, rimborso alla pari, white paper — più un'attestazione verificabile dell'operatore che qualcuno li abbia effettivamente controllati. Registerwerk non verifica nulla di tutto ciò in modo indipendente. [:octicons-arrow-right-24: Binari di pagamento](../../platform/defi-interoperability.md)

---

## 4. Emettere

Il passaggio finale: `APPROVED` → `ISSUED`.

L'obbligazione è attiva. Il registro è la fonte di verità di Registerwerk. Gli investitori vedono le proprie posizioni, ricevono gli estratti e possono — da qui in poi — negoziare.

```mermaid
stateDiagram-v2
    direction LR
    APPROVED --> ISSUED: emettere
    ISSUED --> SUSPENDED: sospendere
    SUSPENDED --> ISSUED: riattivare
    ISSUED --> REDEEMED: rimborsare
    SUSPENDED --> REDEEMED: rimborsare
    note right of ISSUED
        Sei qui.
        Attiva e negoziabile.
    end note
```

`SUSPENDED` congela la negoziazione senza chiudere lo strumento — per un'operazione societaria, una controversia o un errore sospetto. Reversibile. `REDEEMED` non lo è.

---

## Che cosa è appena successo, in un paragrafo

Nordwind ha descritto un'obbligazione, un operatore l'ha approvata, un contratto è stato distribuito, gli investitori sono stati verificati e ammessi a quel contratto, 50.000 titoli sono stati creati a loro nome e il registro ha annotato tutto. Nordwind ha 50 milioni di euro. Cinquanta investitori hanno un credito verso Nordwind. E ogni passaggio è attribuibile a una persona con nome e cognome, in un registro che nessuno può modificare di nascosto.

[Fase 3: Detenzione e custodia :octicons-arrow-right-24:](holding.md){ .md-button .md-button--primary }
