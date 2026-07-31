---
title: Revisione della conformità delle operazioni di pronti contro termine/prestiti
description: Risultati di conformità prioritari per EwpgRepoFacility e lo stack EwpgRepoMarket/Vault/Oracle, con mappatura per giurisdizione e stato di rafforzamento.
---

# Revisione della conformità delle strutture di prestito/repo { #repolending-facility-compliance-review }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina registra i risultati tecnici e le mappature dei controlli previsti. Non costituisce prova di
    conformità legale, autorizzazione normativa, approvazione del prodotto o disponibilità alla produzione.
    Repo, prestito titoli, garanzie collaterali, custodia, escussione della garanzia, insolvenza e questioni di riutilizzo
    richiedono una revisione specifica dell'operatore, del prodotto, dello strumento, della transazione e della giurisdizione
    da parte di un consulente qualificato e dei responsabili del rischio.

Data della revisione: 21-07-2026. Ambito: `contracts/src/examples/EwpgRepoFacility.sol`, l'evoluzione del mercato isolato
sotto `contracts/src/lending/` (`EwpgRepoMarket`, `EwpgRepoMarketFactory`,
`EwpgRepoVault`, `oracle/RegisterwerkNavOracle`), e il modulo backend read-model `lending`.
Complementare a [Interoperabilità DeFi](../platform/defi-interoperability.md), che copre la logica di prodotto/normativa
per la progettazione della struttura; questo documento è l'analisi del gap di sicurezza/conformità.

I risultati sono classificati **P0** (è necessario correggere o ottenere l'approvazione legale prima dell'uso in produzione contro titoli reali), **P1** (dovrebbe essere corretto; riduzione significativa del rischio, non bloccante per il rilascio di un'implementazione di riferimento) e **P2** (documentato per consapevolezza; limite MVP accettabile o richiede una modifica di progettazione più ampia rispetto all'ambito di questo passaggio).

---

## Riepilogo: rilasciato vs. ancora aperto { #summary-shipped-vs-still-open }

| # | Risultato | Gravità | Stato |
|---|---|---|---|
| 1 | Manca l'interruttore automatico per la deviazione del prezzo dell'oracolo | P0 | **Risolto** — vedi sotto |
| 2 | Obsolescenza (staleness) dell'oracolo opt-in, non imposta per i mercati distribuiti | P0 | **Risolto** — vedi sotto |
| 3 | Revisione legale sui prestiti con margine specifica per giurisdizione non eseguita | P0 | **Aperto — lavoro di consulenza** |
| 4 | A `EwpgRepoVault` manca `ReentrancyGuard` | P1 | **Risolto** — vedi sotto |
| 5 | Il registro delle garanzie può desincronizzarsi dal saldo del token dopo un trasferimento forzato | P1 | **Risolto** — vedi sotto |
| 6 | Il backend `LendingPositionController` non aveva `@PreAuthorize` | P1 | **Risolto** — vedi sotto |
| 7 | `borrowPaused` on-chain non ha mai raggiunto il backend/frontend | P1 | **Risolto** — vedi sotto |
| 8 | L'escussione non è realmente permissionless quando il gruppo di liquidatori verificati è ridotto | P1 | **Aperto** |
| 9 | Il flag del pool di intestatari (nominee) è asserito solo off-chain, non verificato on-chain | P2 | **Aperto** |
| 10 | Il congelamento del wallet del mutuatario non raggiunge le garanzie già impegnate | P2 | **Aperto** |
| 11 | Mancate corrispondenze stringa di autorizzazione/NatSpec (`repo-oracle.*`, `repo-vault.*` rispetto alle costanti `repo-markets.*` effettive) | P2 | **Risolto** — solo commenti |
| 12 | `EwpgRepoVault.totalAssets()` itera l'intero elenco dei mercati senza limiti | P2 | **Aperto** |

---

## P0 — è necessario correggere o ottenere l'approvazione prima della produzione { #p0-must-fix-or-get-sign-off-before-production }

### 1. Interruttore automatico per la deviazione del prezzo dell'oracolo (risolto) { #1-oracle-price-deviation-circuit-breaker-fixed }

**Prima:** `RegisterwerkNavOracle.pushPrice` (e `EwpgRepoFacility.updatePrice`) accettava qualsiasi
prezzo diverso da zero senza alcun limite rispetto al prezzo di riferimento precedente. Una singola chiave
`PUSH_PRICE` compromessa o inserita per errore poteva marcare una garanzia a un valore arbitrariamente alto,
consentendo un indebitamento eccessivo che prosciuga il pool, oppure arbitrariamente basso, innescando
escussioni di massa non necessarie.

**Correzione:** `RegisterwerkNavOracle.pushPrice` ora va in revert con `ExcessiveDeviation` se il nuovo prezzo
devia più di `maxDeviationBps` (default 2000 = 20%, regolabile dall'operatore tramite
`setMaxDeviationBps`) rispetto al prezzo di riferimento precedente. Il primissimo invio per un asset non ha limiti
(non esiste un prezzo di riferimento precedente con cui confrontarsi). Esiste una `pushPriceWithOverride` con
autorizzazione separata (protetta da `OVERRIDE_PRICE`, distinta dalla normale `PUSH_PRICE`) per un legittimo
riprezzamento di grande entità, quindi una normale chiave di automazione del feed NAV non può bypassare da sola
l'interruttore.

**Non risolto in questo passaggio:** `EwpgRepoFacility.updatePrice` (la struttura in pool più vecchia) non ha
ancora alcun limite di deviazione: la struttura è trattata come un'implementazione di riferimento congelata e la
correzione è confluita nel nuovo stack `EwpgRepoMarket`/oracolo che la sostituisce. Se la struttura resta in
uso in produzione, occorre riportare lì lo stesso interruttore.

Test: `contracts/test/lending/RegisterwerkNavOracle.t.sol` (7 test).

### 2. Obsolescenza (staleness) dell'oracolo opt-in, non imposta per i mercati distribuiti (risolto) { #2-oracle-staleness-opt-in-not-enforced-for-deployed-markets-fixed }

**Prima:** `EwpgRepoMarket._currentPrice()` già rifiutava un prezzo di riferimento obsoleto quando
`maxPriceAgeSeconds != 0`, ma `0` (controllo di obsolescenza disattivato) era un argomento valido del costruttore,
senza alcuna protezione che impedisse a un operatore di distribuire un mercato reale in quel modo — per errore,
oppure tramite una chiave operatore compromessa usata deliberatamente per disattivare l'unica salvaguardia
contro un prezzo di riferimento congelato o un feed di prezzo bloccato.

**Correzione:** `EwpgRepoMarket` di per sé è invariato (la costruzione diretta con `maxPriceAgeSeconds == 0`
funziona ancora, intenzionalmente, per i test unitari). `EwpgRepoMarketFactory.createMarket` — l'unico percorso
che distribuisce un mercato reale approvato dall'operatore — ora va in revert con `InvalidMaxPriceAge` se
`maxPriceAgeSeconds == 0`.

Test: `contracts/test/lending/EwpgRepoMarketFactory.t.sol::test_createMarket_revertsWithZeroMaxPriceAge`.

### 3. Revisione legale sui prestiti con margine specifica per giurisdizione (aperto — lavoro di consulenza) { #3-jurisdiction-specific-margin-lending-legal-review-open-counsel-work }

**Risultato, invariato rispetto all'avviso preesistente in `defi-interoperability.md`:** costituire in pegno un
titolo come garanzia di un prestito attiva un livello normativo indipendente dalla correttezza dello smart contract
— regole di segregazione della custodia, licenze per il prestito con margine e (a seconda della giurisdizione)
restrizioni sulla reipotecazione che un semplice prestito garantito in contanti non attiva mai.

| Giurisdizione | Regolatore | Regime rilevante | Stato |
|---|---|---|---|
| `DE_EWPG` | BaFin | Regole KWG sul prestito con margine / Wertpapierleihe, custodia eWpG | Non esaminato |
| `LU_CSSF` | CSSF | Regole CSSF su custode/depositario in materia di reipotecazione | Non esaminato |
| `FR_AMF` | AMF | Restrizioni CMF sul teneur de compte-conservation | Non esaminato |
| `LI_TVTG` | FMA | Segregazione della custodia del token-container ai sensi del TVTG | Non esaminato |

**Nessun ulteriore irrobustimento del contratto può sostituire questo punto.** Questo risultato viene riportato
invariato — è esplicitamente fuori ambito per un passaggio di conformità limitato al codice e richiede un
consulente esterno specifico per giurisdizione prima di utilizzare `EwpgRepoFacility` o `EwpgRepoMarket`
contro titoli reali in produzione.

---

## P1 — dovrebbe essere corretto { #p1-should-fix }

### 4. A `EwpgRepoVault` manca `ReentrancyGuard` (risolto) { #4-ewpgrepovault-missing-reentrancyguard-fixed }

**Prima:** `EwpgRepoVault` era l'unico contratto nello stack di prestito (`EwpgRepoFacility` ed
`EwpgRepoMarket` proteggono entrambi ogni funzione che muta lo stato) privo di `ReentrancyGuard` — le sue
funzioni ereditate ERC-4626 `deposit`/`mint`/`withdraw`/`redeem` e le proprie `allocate`/`deallocate`
effettuano tutte chiamate esterne al token senza protezione dalla reentrancy a livello di vault.

**Correzione:** `EwpgRepoVault` ora eredita `ReentrancyGuard`. `deposit`/`mint`/`withdraw`/`redeem` vengono
sovrascritte esclusivamente per aggiungere `nonReentrant` attorno all'implementazione OZ (nessun cambiamento
di logica); `allocate`/`deallocate` hanno ricevuto il modificatore direttamente.

Test: la suite esistente `contracts/test/lending/EwpgRepoVault.t.sol` continua a passare invariata
(la protezione è additiva; nessun cambiamento di comportamento per chi la chiama legittimamente).

### 5. Il registro delle garanzie può desincronizzarsi dal saldo del token dopo un trasferimento forzato (risolto) { #5-collateral-ledger-can-desync-from-token-balance-after-a-forced-transfer-fixed }

**Prima:** un `forcedTransfer` o `forceBurn` di un emittente/agente sul token dato in garanzia (una Berichtigung
ai sensi del §24 eWpG, oppure un'azione di congelamento disposta dal tribunale o ai sensi di AWG/GwG a livello
di token) può spostare token fuori dal saldo di `EwpgRepoMarket` senza passare da `repay`/`liquidate` — la
contabilità interna del mercato in `positions[borrower].collateralAmount` non ha modo di osservarlo. Se non
riconciliata, la garanzia registrata supera quanto il mercato può effettivamente restituire, per cui un
successivo `repay`/`liquidate` va in revert oppure, peggio, paga più del dovuto attingendo ai fondi di altri
partecipanti.

**Correzione:** una nuova `EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)`, protetta da
CONFIGURE, consente all'operatore di correggere la garanzia registrata di una posizione **riducendola** a
quanto una specifica transazione di trasferimento forzato ha effettivamente rimosso. La funzione riceve
l'importo corretto come parametro esplicito — anziché tentare di dedurlo dal `balanceOf(this)` aggregato del
token — perché quel saldo somma tutti i mutuatari del mercato, quindi solo una riconciliazione off-chain della
specifica transazione di trasferimento forzato (lo stesso atto dell'operatore che l'ha disposta) può attribuire
correttamente la riduzione a un singolo mutuatario. L'invariante applicata on-chain è unidirezionale: la
chiamata va in revert (`ReconciliationWouldIncreaseCollateral`) se il nuovo importo non è strettamente
inferiore a quello attualmente registrato, quindi non può mai fabbricare garanzie mai realmente impegnate.

Test: `contracts/test/lending/EwpgRepoMarket.t.sol` (`test_reconcileCollateral_*`, 4 test).

### 6. Il backend `LendingPositionController` non aveva `@PreAuthorize` (risolto) { #6-backend-lendingpositioncontroller-had-no-preauthorize-fixed }

**Prima:** `GET /api/v1/lending/my-positions` e `/supply-positions` non avevano alcun `@PreAuthorize` a
livello di metodo o di classe, a differenza di ogni altro controller rivolto al cliente in questo modulo.
La delimitazione dell'accesso era puramente implicita — una chiamata non autenticata risolveva un `appUserId`
nullo e restituiva silenziosamente un elenco vuoto anziché essere respinta esplicitamente.

**Correzione:** `@PreAuthorize("isAuthenticated()")` aggiunto a livello di classe, seguendo lo stesso schema
usato da `PositionStatementController`/`SteuerbescheinigungController` per altri endpoint di lettura rivolti
al cliente.

### 7. `borrowPaused` on-chain non ha mai raggiunto il backend/frontend (risolto) { #7-on-chain-borrowpaused-never-reached-the-backendfrontend-fixed }

**Prima:** `EwpgRepoMarket.borrowPaused` (e `LendingMarketStatus.PAUSED` nel read-model del backend)
esistevano, ma nulla impostava mai lo stato persistito su DB a `PAUSED` — il flag on-chain e il read-model
erano disconnessi, per cui un mercato in pausa risultava comunque `ACTIVE` ovunque, e il tentativo di un
trader di prendere in prestito su di esso andava semplicemente in revert on-chain senza alcun preavviso.

**Correzione:** `LendingMarketService.resolveEffectiveStatus` legge dal vivo il flag on-chain per qualsiasi
mercato il cui stato persistito è `ACTIVE`, riflettendo `PAUSED` in ogni risposta di elenco/dettaglio senza
modificare la riga DB (una lettura dal vivo, non un cambio di stato) — un errore nella lettura on-chain
ripiega sullo stato persistito anziché far fallire l'intero elenco, seguendo lo stesso schema best-effort già
usato per le letture del fattore di salute delle posizioni. Lo stepper di prestito rivolto al cliente ora
mostra uno stato esplicito "mercato temporaneamente in pausa" invece di lasciare che la transazione vada in
revert.

Test: `LendingMarketServiceTest` (`activeMarketReflectsOnchainBorrowPaused`,
`activeMarketStaysActiveWhenNotPaused`, `retiredMarketSkipsOnchainCheck`,
`onchainReadFailureFallsBackToPersistedStatus`).

### 8. Escussione non realmente permissionless quando il gruppo di liquidatori verificati è ridotto (aperto) { #8-liquidation-not-truly-permissionless-when-the-verified-liquidator-set-is-thin-open }

**Risultato:** `liquidate` non è nominalmente soggetta a gate al livello `RegisterwerkGated` (documentata come
"permissionless, come in Aave, perché il wall T-REX del token svolge gratuitamente il lavoro di conformità").
Questo è vero solo a metà: il wall blocca anche il **destinatario** della garanzia sequestrata (chi esegue
l'escussione), non solo il mutuatario. Se il pool di indirizzi verificati T-REX, non congelati e non bloccati
per paese disposti a eseguire l'escussione è ridotto, una posizione in sofferenza potrebbe non avere alcun
soggetto idoneo a farlo — la posizione resta scoperta, penalizzando i depositanti, senza alcun percorso di
riserva per chiuderla. Un soggetto verificato vicino al `maxBalancePerInvestor` sul token dato in garanzia è
inoltre impedito dal ricevere la garanzia sequestrata a meno che non sia esso stesso contrassegnato come pool
di intestatari (nominee).

**Raccomandazione:** progettare un percorso di escussione di ultima istanza (ad es. un indirizzo controllato
dall'operatore, pre-contrassegnato come pool di intestatari, autorizzato a eseguire l'escussione e a
ridistribuire o mettere in deposito immediatamente la garanzia sequestrata) per i mercati in cui non si può
presumere che il gruppo di soggetti verificati sia ampio. Non implementato in questo passaggio — è una nuova
progettazione del controllo accessi, non una correzione delimitata.

---

## P2 — documentato, accettabile per ora o richiede un lavoro di progettazione più ampio { #p2-documented-acceptable-for-now-or-requires-larger-design-work }

### 9. Lo stato del pool di intestatari (nominee) è asserito solo off-chain (aperto) { #9-nominee-pool-status-is-asserted-off-chain-only-open }

L'intero modello di pooling dipende da un'azione off-chain dell'operatore
(`EwpgModularCompliance.setNomineePool`) che contrassegna il mercato come pool di intestatari sul token dato
in garanzia, più un KYC/AML look-through off-chain dei depositanti del pool stesso
(vedi [Interoperabilità DeFi § ponte nominee/omnibus](../platform/defi-interoperability.md#the-nomineeomnibus-bridge-nominee_pool)).
Nulla nei contratti di prestito verifica on-chain che questo flag sia stato effettivamente impostato prima di
accettare pegni — il primo pegno che supererebbe il limite per investitore va semplicemente in revert a
livello di token se il flag manca, il che è una rete di sicurezza funzionale ma non offre alcun segnale
proattivo. **Raccomandazione:** un evento on-chain che correli la distribuzione di un mercato al suo flag di
pool di intestatari (ad es. la factory che legge e registra il flag al momento di `createMarket`)
migliorerebbe la verificabilità senza modificare il modello di sicurezza. Rinviato come miglioramento di
osservabilità gradito ma non prioritario, non come lacuna nel modello di conformità in sé.

### 10. Il congelamento del wallet del mutuatario non raggiunge la garanzia già impegnata (aperto) { #10-borrower-wallet-freeze-doesnt-reach-already-pledged-collateral-open }

Una volta che la garanzia è impegnata in un mercato, è il contratto del pool — non il mutuatario — il titolare
registrato del token. Un successivo Sperrvermerk ai sensi del §16 eWpG o un congelamento AWG/GwG sul wallet
del mutuatario non copre più quella garanzia già impegnata, poiché il controllo del congelamento viene
eseguito sull'indirizzo `from` di un trasferimento, e per qualsiasi movimento successivo il `from` è il pool.
Se questo soddisfi l'intento normativo di un congelamento del wallet è di per sé una questione legale legata
al punto #3 sopra, non una lacuna dello smart contract con un'ovvia correzione nel codice (congelare la
*posizione* anziché il wallet richiederebbe un nuovo stato e un nuovo punto di applicazione in ogni contratto
di prestito). Documentato affinché la revisione legale del punto #3 lo consideri esplicitamente.

### 11. Mancate corrispondenze stringa di autorizzazione/NatSpec (risolto — solo commenti) { #11-permission-string-natspec-mismatches-fixed-comment-only }

Due mancate corrispondenze tra la costante effettivamente applicata e la stringa documentata nei commenti
NatSpec (un problema di governance/igiene dell'audit — la stringa errata potrebbe fuorviare chiunque conceda
le autorizzazioni leggendo la documentazione anziché il codice):
- il commento del documento di `RegisterwerkNavOracle.pushPrice` riportava `repo-oracle.push-price`; la
  costante effettiva è `PUSH_PRICE = keccak256("repo-markets.push-price")`. Risolto.
- il commento a livello di contratto di `EwpgRepoVault` riportava `repo-vault.curate`; la costante effettiva è
  `CURATE = keccak256("repo-markets.curate-vault")`. Risolto.

Nessun cambiamento di comportamento: entrambe le costanti erano già corrette e assegnate al namespace
dell'inserzione di marketplace `repo-markets` secondo la regola di namespacing di
`ManifestValidationService`; era sbagliata solo la prosa.

### 12. `EwpgRepoVault.totalAssets()` itera l'intero elenco dei mercati (aperto) { #12-ewpgrepovaulttotalassets-iterates-the-full-market-list-open }

`totalAssets()` esegue un ciclo su ogni mercato mai aggiunto (compresi quelli disattivati) a ogni calcolo del
prezzo delle quote — ogni conversione `deposit`/`withdraw`/`mint`/`redeem` paga questo costo. Per la manciata
di mercati che un vault curatore gestisce realisticamente questo è irrilevante, ma una crescita illimitata dei
mercati diventerebbe alla lunga un problema di costo del gas. Limite MVP accettabile; un `totalAssets`
delimitato/paginato (o l'esclusione dei mercati disattivati dal ciclo) è un naturale affinamento v2 se un
vault dovesse mai avvicinarsi a decine di mercati.

---

## Verifica { #verification }

- Contratti: `cd contracts && forge test --match-path "test/lending/*" -vv` — 45 test, tutti superati (0
  regressioni rispetto alla suite di prestito preesistente); suite completa `forge test` — 388 superati, 0
  falliti, 18 saltati.
- Backend: `cd backend && ./mvnw verify` — 436 test unitari + 30 di integrazione superati, tutte le soglie di
  copertura JaCoCo (inclusi i limiti critici per la conformità di `registerstatement`/adiacenti ai prestiti)
  soddisfatte.
