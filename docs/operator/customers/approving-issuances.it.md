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
