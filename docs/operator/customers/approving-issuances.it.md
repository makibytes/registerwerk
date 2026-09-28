---
title: Approvazione di un'emissione
description: La decisione che dà vita a uno strumento finanziario: cosa controllare, cosa significa e cosa non significa l'approvazione e cosa succede dopo.
---

# Approvazione di un'emissione { #approving-an-issuance }

Un emittente ha descritto uno strumento finanziario e lo ha presentato. Finché non approvi, è una descrizione. Dopo l'approvazione, può diventare un obbligo legale dell'emittente, detenuto dagli investitori.

Questa è la decisione di routine più importante che un operatore prende.

---

## Cosa stai effettivamente decidendo { #what-you-are-actually-deciding }

!!! warning "Sii preciso su cosa significa approvazione"
    Approvazione significa: **questa emissione soddisfa i criteri di ammissione del registro.**

    Ciò non significa che lo strumento sia lecito, che l'offerta sia conforme alle regole sul prospetto, che l'emittente possa emetterlo legalmente o che il token abbia effetto legale. Questi aspetti dipendono dall'autorizzazione dell'emittente, dalla sua consulenza e dalle sue circostanze.

    Se un emittente considera la tua approvazione come un parere di conformità, correggilo per iscritto. Questo malinteso costerà caro in seguito.

---

## Prima di guardare { #before-you-look }

Conferma prima le cose noiose: vengono squalificate più velocemente di qualsiasi altra cosa nei termini:

- [ ] L'entità emittente è **attiva** e il suo **KYC è approvato e non scaduto**.
- [ ] L'entità è registrata come emittente.
- [ ] Non esistono [sanzioni](../../compliance/sanctions-screening.md) aperte nei suoi confronti.

---

## Cosa controllare { #what-to-check }

### Identità { #identity }

| | |
|---|---|
| **Nome** | Sensato, e non ingannevolmente simile a uno strumento esistente. |
| **ISIN** | Unico: la piattaforma lo impone. Registerwerk non emette ISIN; l'emittente ne ottiene uno dalla propria agenzia di numerazione nazionale. Un'emissione senza uno è consentita ma limita l'interoperabilità. |
| **Giurisdizione** | Seleziona l'intero corpus di regole applicate per la vita dello strumento. Cambiarlo successivamente non è una modifica del campo. |

### Termini { #terms }

Per un'obbligazione: valore nominale, valuta, date di emissione e scadenza, tasso cedolare, conteggio dei giorni, frequenza di pagamento, callability, prezzo di emissione.

!!! tip "Tre cose che meritano una seconda occhiata"
    **Scadenza prima della data di emissione.** Raro e catastrofico se raggiunge la produzione: il piano cedole viene generato a partire da queste date.

    **Prezzo di emissione su un'obbligazione zero coupon.** Il valore predefinito è `1.0` — alla pari. Un'obbligazione zero coupon alla pari non paga interessi e rimborsa il valore nominale: uno strumento che non restituisce nulla. Se è davvero zero coupon, il prezzo di emissione dovrebbe essere scontato. Questo valore predefinito ha già causato confusione reale.

    **Convenzione di calcolo giorni.** Poco appariscente, ma cambia quanto denaro si muove. Verifica che corrisponda al term sheet invece di darlo per scontato.

### Convenzioni del piano cedole { #coupon-schedule-conventions }

Il salvataggio dei termini dell'obbligazione genera il piano cedole da cui lavorano i processi delle operazioni sul capitale. Le convenzioni seguono la prassi ICMA; ciascuna è impostata nei termini, con questi valori predefiniti:

| Impostazione | Predefinito | Effetto |
|---|---|---|
| Conteggio dei giorni | ACT/ACT (ICMA) | Un periodo regolare matura esattamente 1/frequenza; un primo periodo breve matura i suoi giorni effettivi rispetto al periodo regolare nozionale. Sono disponibili ACT/360, ACT/365 (fisso), 30/360 e 30E/360. |
| Piano | A ritroso dalla scadenza, primo periodo breve | Le date regolari si contano a ritroso dalla scadenza; un periodo irregolare va all'inizio. Se la scadenza è un fine mese, ogni data cedola è un fine mese. |
| Convenzione giorno lavorativo | Modified Following | Una data di pagamento in un giorno non lavorativo passa al giorno lavorativo successivo, salvo che ciò cambi mese: allora al precedente. Il rateo usa sempre le date non rettificate. |
| Calendario festività | TARGET2 | Fine settimana, 1° gennaio, Venerdì santo, Lunedì dell'Angelo, 1° maggio, 25 e 26 dicembre. |
| Data di registrazione | 1 giorno lavorativo prima del pagamento | Chi risulta nel registro alla fine di quel giorno riceve la cedola. |
| Annuncio | 5 giorni lavorativi prima della data di registrazione | Quando la cedola viene annunciata. |
| Periodi di grazia | 30 giorni interessi, 7 giorni capitale | Per quanto tempo dopo la data di pagamento un importo non pagato è solo scaduto. |

La cedola per unità è valore nominale × tasso cedolare × frazione di giorni, senza arrotondamento; si arrotonda solo il diritto di ciascun detentore. Una cedola variabile non ha importo finché il tasso non è fissato e non viene annunciata prima. Si generano solo date di pagamento future, quindi termini inseriti in ritardo non creano cedole retrodatate. Il piano compare nella scheda **Corporate Actions** dell'asset.

### Come vengono generate le cedole e il rimborso

- **Annuncio.** La cedola (e il rimborso finale) viene generata automaticamente alla sua *data di annuncio*, non alla data di pagamento, così resta il tempo per attestare e confermare prima del pagamento. Il rimborso segue la stessa regola: data di pagamento = scadenza adeguata secondo la convenzione dei giorni lavorativi.
- **Record date.** I diritti sono fissati alla **fine della record date** (Europe/Berlin), in base al registro com'era allora. I trasferimenti successivi non li modificano. Per gli asset su chain, lo snapshot attende che il registro sia riconciliato oltre la record date e viene rifiutato («unmapped at record date») se un wallet con unità in quel momento non ha una voce nel registro. La record date di un dividendo, split o richiamo deve essere ancora futura alla proposta e all'approvazione (al più presto il giorno lavorativo successivo).
- **Arrotondamento.** Il diritto di ciascun detentore è arrotondato alla più piccola unità della valuta (half-even); la conferma mostra il totale pagato e la differenza di arrotondamento.
- **Scaduto, mancato, default.** Un importo non pagato oltre la data di pagamento appare prima come **OVERDUE** (solo operatori; i clienti vedono «pagamento in sospeso»). Solo dopo il periodo di tolleranza (30 giorni interessi, 7 giorni capitale) una cedola diventa **MISSED** e un'obbligazione **DEFAULTED**. Il regolamento cancella ognuno di questi stati: un rimborso regolato porta l'obbligazione a **REDEEMED**, un richiamo regolato a **CALLED**, una cedola regolata è **PAID**.
- **Doppio controllo.** L'emittente attesta; un operatore conferma. Un operatore non può mai attestare come emittente: la via per l'operatore è *Override attestation* (step-up e motivazione, verificata separatamente), anche durante l'impersonificazione. Una proposta deve essere approvata da una persona diversa dal proponente.
- **Diritti trattenuti.** I diritti dei pool di nominee (look-through) non vengono pagati e tengono aperta un'azione regolata, segnalata «held entitlements outstanding», finché non sono risolti.
- **Ordine dei job.** 05:30 cedole, 05:45 rimborsi, 06:00 transizioni giornaliere (Europe/Berlin): un'azione generata al mattino viene elaborata nella stessa esecuzione.

### Catena e standard { #chain-and-standard }

Lo standard del token si adatta a ciò che viene affermato?

!!! danger "Un ERC-20 per uno strumento riservato è la discrepanza da individuare"
    Se lo strumento può essere detenuto solo da investitori verificati o professionali, [ERC-20](../../token-standards/erc20.md) non può imporlo. Chiunque riceva un'unità ne diventa proprietario.

    Gli strumenti riservati dovrebbero usare [ERC-3643](../../token-standards/erc3643.md), dove l'idoneità viene verificata nel contratto del token e i trasferimenti non conformi falliscono con un revert on-chain.

    È il controllo tecnico più importante della revisione, perché in seguito è invisibile. Nulla si rompe all'approvazione. Si rompe la prima volta che un'unità raggiunge un wallet che non avrebbe mai dovuto detenerla — a quel punto sono già in circolazione 50.000 unità.

Conferma anche che mainnet rispetto a testnet era ciò che intendeva l'emittente. Approvare un'emissione sulla mainnet da parte di qualcuno inteso come una prova generale è una conversazione imbarazzante.

---

## Decidere { #deciding }

=== "Approva"

    Lo stato diventa `APPROVED`. **I termini si bloccano.** L'emittente può ora distribuire il contratto.

    I termini si possono impostare in blocco (con step-up) solo fino all'emissione, e ISIN, valuta, importo di emissione, taglio e date non sono più modificabili dal modulo di modifica dopo l'approvazione. Le modifiche successive sono **emendamenti**: *Modifica asset → Amend terms* richiede la base giuridica, lo step-up e un secondo operatore, registra ogni valore prima/dopo nella pista di controllo e rigenera le cedole future non ancora annunciate. Le cedole pagate o già annunciate non vengono mai riscritte. Valore nominale, cedola e scadenza di un'obbligazione Canton distribuita non si possono emendare qui: sono fissati nello strumento del ledger.

    Registra il motivo per cui hai approvato. La pista di controllo registra che l'hai fatto, non ciò che ti ha convinto.

=== "Rifiuta"

    Lo stato torna a **`DRAFT`** — di nuovo modificabile — con il motivo registrato.

    Non esiste uno stato `REJECTED`. Un'emissione rifiutata è una bozza. Questo sorprende gli operatori che si aspettano uno stato senza via d'uscita.

    **Scrivi un motivo su cui l'emittente possa agire.** "Non conforme" produce un nuovo invio della stessa cosa. "Lo strumento è riservato agli investitori professionali ma usa ERC-20, che non può imporlo: reinvialo come ERC-3643" ne produce uno corretto.

---

## Dopo l'approvazione { #after-approval }

Non hai finito con essa. L'emittente:

1. **Distribuisce** il contratto.
2. **Ammette investitori** — ciascuno con un'entità KYC approvata e un wallet registrato.
3. **Conia** le unità.
4. **Emette**, rendendola attiva.

Sarai coinvolto di nuovo quando gli investitori avranno bisogno di onboarding, e da allora in poi in modo permanente per le operazioni societarie.

!!! info "Il regolamento di un'operazione societaria richiede un secondo operatore"
    Approvare un'operazione societaria per il regolamento richiede il [principio dei quattro occhi](../../compliance/step-up-mfa.md).

    Pagare l'elenco titolari sbagliato è l'errore catastrofico classico nell'amministrazione titoli, ed è molto difficile da invertire. Assicurati che la tua rotazione abbia davvero due persone disponibili quando cadono le date cedola: un controllo a quattro occhi che nessuno può soddisfare un venerdì pomeriggio è un controllo che finisce per essere aggirato.


### Ordini di sottoscrizione e iscrizioni a registro

Gli investitori sottoscrivono tramite il portale. Lei (o l'emittente) gestisce la coda nella scheda **Subscription orders** dell'attività:

1. **Assegnare**, per intero o in misura ridotta. La dimensione dell'emissione e il massimo detenibile dall'investitore (comprese le sue altre assegnazioni aperte) sono verificati sotto blocco, quindi assegnazioni parallele non possono eccedere.
2. Attendere che l'investitore **accetti**. L'assegnazione ha allora un termine di pagamento (per impostazione predefinita 10 giorni lavorativi TARGET). Se non viene pagata in tempo, un processo pianificato la segna come **decaduta** e libera la capacità.
3. **Confermare il pagamento** quando il denaro è sul conto ([step-up](../../compliance/step-up-mfa.md)). Per un'obbligazione l'importo dovuto è unità assegnate × valore nominale × prezzo di emissione: un pagamento insufficiente viene rifiutato, uno eccedente registrato come *rimborso dovuto* — il rimborso stesso è un pagamento manuale. Per le attività senza condizioni obbligazionarie inserisce l'importo ricevuto.
4. **Regolare.** KYC, screening sanzioni, Sperrvermerk, congelamento del registro, finalità, mercato di riferimento e limite di detenzione vengono ricontrollati. Su un'attività ERC-20 o ERC-3643 distribuita le unità vengono coniate e la sincronizzazione dei titolari le accredita nel registro quando il trasferimento è indicizzato; per gli altri standard distribuiti l'ordine resta *pagato* finché non emette le unità a mano. Senza distribuzione il registro viene accreditato direttamente.
5. **Rilasciare** restituisce un'assegnazione con una motivazione; su un ordine pagato il pagamento è segnato come rimborso dovuto.

Le iscrizioni a registro spettano a lei, non all'emittente. Nella scheda **Holders** dell'attività, *Add register entry* e *Change §17(2) attributes* richiedono ciascuna un'istruzione (chi, e un riferimento); un campo vuoto significa nessuna modifica, rimuovere un diritto richiede la propria casella e un secondo approvatore. Gli emittenti richiedono tramite *richieste di modifica*, che lei esegue (quattro occhi) o rifiuta. Su un'attività distribuita un'iscrizione manuale è solo un collegamento di wallet con nominale 0.

---

## Sospensione e rimborso { #suspension-and-redemption }

**Suspend** (`ISSUED` → `SUSPENDED`) blocca la negoziazione senza terminare lo strumento, per un'operazione societaria, una controversia o un sospetto errore. Reversibile.

**Redeem** è definitivo. Non c'è via d'uscita da `REDEEMED`.

Entrambi sono registrati con un attore nominato.

---

## Dove andare adesso { #where-next }

- [Esaminare il KYC](kyc-process.md) — la verifica obbligata prima di questa
- [Progettazione e approvazione](../../customer/lifecycle/design.md) — il punto di vista dell'emittente sullo stesso passaggio
- [Scelta di uno standard di token](../../customer/issuers/token-standards.md)
