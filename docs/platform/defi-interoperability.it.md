# Interoperabilità DeFi { #defi-interoperability }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Questa pagina è una discussione di progettazione tecnica. Registerwerk e gli accordi descritti di DeFi, negoziazione, custodia, intestatario, pagamento e prestito
    non sono rappresentati come legalmente consentiti, conformi a
    o autorizzati e non sono pronti per la produzione. La classificazione e l'effetto giuridico richiedono una revisione specifica dell'operatore,
    dello strumento, del servizio, della controparte, della transazione e della giurisdizione.

Registerwerk è destinato all'uso nei mercati dei capitali regolamentati. Questo documento spiega dove e perché
i ponti verso l'ecosistema DeFi/Ethereum hanno senso e, cosa altrettanto importante, dove non lo fanno, data la realtà normativa dei titoli tokenizzati piuttosto che dei cripto-asset.

## Il punto di partenza normativo { #the-regulatory-starting-point }

La classificazione MiCAR/MiFID II non può essere dedotta da uno standard token, un'etichetta `eWpG` o un flag di
canale di pagamento. Il modulo `payment` memorizza i campi di divulgazione e attestazione immessi dall'operatore;
non stabilisce in modo indipendente che un asset sia uno strumento finanziario, che una stablecoin sia
un EMT o che un emittente o un fornitore di servizi sia autorizzato.

Se un AMM senza autorizzazione, un pool di prestito, un custode, un intestatario o una struttura omnibus possano
detenere o trasferire uno strumento è una questione legale, normativa, di custodia, di insolvenza e di
progettazione del prodotto, non una questione a cui rispondono i contratti intelligenti o la configurazione della
giurisdizione. I modelli seguenti sono opzioni tecniche per la revisione da parte del consulente legale e del
responsabile del controllo.

## Matrice di interoperabilità delle giurisdizioni { #jurisdiction-interoperability-matrix }

Modellato in `backend/.../kyc/api/JurisdictionRequirementConfig.ComplianceMetadata` tramite i nuovi campi
`defiInteropModel` / `permissionlessAmmAllowed` (enumerazione `DefiInteropModel`, stesso pacchetto):

| Competenza | Regolatore | `defiInteropModel` | `permissionlessAmmAllowed` | Base |
|---|---|---|---|---|
| `DE_EWPG` | BaFin | `NOMINEE_POOL` | `false` | eWpG Sammelverwahrung (affidamento collettivo) |
| `LU_CSSF` | CSSF | `NOMINEE_POOL` | `false` | Detenzione omnibus da parte di custode/depositario vigilato dalla CSSF |
| `FR_AMF` | AMF | `NOMINEE_POOL` | `false` | Regime CMF di teneur de compte-conservation (gestore del conto/custode) |
| `LI_TVTG` | FMA | `NOMINEE_POOL` | `false` | TVTG modello contenitore token + fornitore di servizi VT con licenza |

Tutte e quattro le giurisdizioni arrivano allo stesso modello oggi perché tutte e quattro riconoscono già una
struttura omnibus di intermediari con licenza: non c'era ancora alcuna divergenza specifica per giurisdizione da
modellare. `permissionlessAmmAllowed` è `false` ovunque ed è deliberatamente esplicito (non solo l'assenza di
`true`), così che un'aggiunta futura di giurisdizione debba prendere una decisione attiva anziché ereditare
silenziosamente un valore predefinito. L'ambito di questo passaggio è stato intenzionalmente limitato alle quattro
giurisdizioni già integrate (`DE_EWPG`, `LU_CSSF`, `FR_AMF`, `LI_TVTG`); l'aggiunta di regimi extra-UE (ad esempio
la legge DLT della Svizzera, che ha una propria licenza per strutture di negoziazione DLT che copre negoziazione,
regolamento e custodia in modo combinato) è un'estensione naturale una volta che Registerwerk operi effettivamente lì.

Un terzo modello, `ORACLE_ONLY`, esiste per lo schema (già distribuibile, a rischio di custodia zero) in cui un
protocollo esterno *legge soltanto* le attestazioni (claim) di `PermissionOracle` — vedere
[dapp-development.md § Componibilità DeFi esterna](./dapp-development.md#external-defi-composability).
Nessuna giurisdizione è oggi limitata a `ORACLE_ONLY`, poiché `NOMINEE_POOL` è un sottoinsieme rigorosamente più
ampio di ciò che esso consente.

## Il ponte intestatario/omnibus (`NOMINEE_POOL`) { #the-nomineeomnibus-bridge-nominee_pool }

Il nuovo argomento di attestazione ONCHAINID, `NOMINEE` (argomento 4, accanto agli esistenti 1=KYC/2=AML/
3=Accreditamento), viene rilasciato da un emittente attendibile all'ONCHAINID proprio di un custode/CASP con
licenza — usando esattamente lo stesso meccanismo `ClaimIssuanceService`/`EcosystemTrustedIssuersRegistry` già in
uso per le attestazioni KYC/AML. L'indirizzo del contratto del pool del custode viene quindi contrassegnato come
pool intestatario sull'`EwpgComplianceModule` specifico del token
(`contracts/src/compliance/EwpgModularCompliance.sol`, `setNomineePool`), il quale:

- **Esenta** l'indirizzo del pool da `maxBalancePerInvestor` e `maxInvestors` — l'intero punto
, dal momento che un pool raccoglie l'esposizione economica di molti LP dietro un indirizzo e un limite per investitore
su quel singolo indirizzo vanifica l'intento normativo del limite (bloccherebbe completamente tutti i pool
o lascerebbe silenziosamente che il limite venga aggirato compensando molti investitori dietro
un "investitore").
- **Mantiene** incondizionatamente i controlli di blocco del paese e di raffreddamento del trasferimento: l'operatore del pool
stesso non deve comunque essere domiciliato in una giurisdizione bloccata.
- **Si applica solo agli indirizzi contrattuali** (`to.code.length > 0`) — contrassegnare un EOA come pool
intestatario non ha alcun effetto (no-op), poiché l'intera esenzione ha senso solo per un vero contratto di
custodia in pool, non per il portafoglio di un singolo individuo.

La responsabilità del controllo KYC/AML look-through degli LP sottostanti del pool ricade interamente
sull'operatore intestatario/custode, fuori catena — esattamente come avviene oggi per il conto omnibus di una
banca depositaria tradizionale. Contrassegnare il manifest di una dApp con `requiredClaimTopics: [4]` viene
presentato all'operatore che effettua la revisione durante l'approvazione del marketplace
(`ManifestValidationService`) come un flag che richiede la revisione umana della licenza da custode dell'editore,
non come una dichiarazione approvata automaticamente.

## Cosa attira effettivamente fornitori di liquidità e market maker { #what-actually-attracts-liquidity-providers-and-market-makers }

Prima di decidere cosa costruire, vale la pena essere onesti su ciò per cui un trader o un market maker
ottimizza e come questo viene mappato, o meno, su un registro dei titoli conforme:

- **I mercati obbligazionari TradFi non sono principalmente liquidi a causa del trading secondario.** Sono
liquidi grazie al **repo** (un accordo di riacquisto: vendi l'obbligazione adesso, accetta di riacquistarlo
più tardi a un prezzo fisso) e al **prestito di titoli**. Un dealer che detiene una posizione illiquida
non la vende per raccogliere denaro: la riacquista durante la notte o a termine, mantiene l'esposizione economica,
e ridistribuisce il denaro. I mercati dei pronti contro termine muovono migliaia di miliardi ogni giorno, facendo impallidire il volume degli scambi di obbligazioni monetarie secondarie, proprio perché consentono al detentore di accedere alla liquidità *senza* una vendita definitiva (nessun prezzo realizzato, nessun rialzo perso, nessuno sconto imposto dal venditore).
- **I mercati monetari DeFi (Aave, Compound) sono lo stesso meccanismo, in pool e algoritmico**:
depositi come garanzia, prestiti a fronte di essa, a un tasso fissato dall'utilizzo in tempo reale invece che da
una negoziazione bilaterale. Ciò che in realtà attrae i fornitori di liquidità (LP) verso un mercato monetario è
un tasso trasparente e basato sull'utilizzo, un accesso senza autorizzazione dal lato dell'offerta, e un
meccanismo di escussione credibile che mantiene indenni i depositanti.
- **Gli AMM in stile Uniswap attraggono gli LP tramite i proventi delle commissioni e la creazione di pool senza
autorizzazione** — ma quel modello presuppone che l'asset scambiato sia fungibile, prezzato in modo continuo e
sicuro da lasciare in gestione a un contratto anonimo per conto di molte parti. Niente di tutto ciò vale per un
titolo con prezzo NAV e idoneità vincolata, che è esattamente il motivo per cui questo repository esclude un
AMM/order-book per la gamba del security token (vedere la sezione sui meccanismi di trading di seguito).
- **Le piattaforme di asset del mondo reale hanno già imparato questa lezione.** Ondo, Centrifuge, Maple e il
BUIDL di BlackRock traggono tutti la maggior parte della loro utilità DeFi dall'essere impegnati come
**garanzia collaterale** in un mercato dei prestiti, non dalla liquidità del trading spot: l'RWA tokenizzato
resta in un'unica posizione custodita/in pool, mentre la liquidità in stablecoin si muove attorno ad esso.
- **Cosa vuole in particolare un market maker**: un modo per assumere posizioni sia lunghe che corte, per
coprirsi, per riutilizzare lo stesso capitale su più posizioni (efficienza del capitale) e la certezza di
esecuzione. Il prestito garantito offre al detentore esattamente questo — leva finanziaria ed efficienza del
capitale — senza che Registerwerk debba mai gestire un motore di abbinamento (matching engine).

La conclusione: **una struttura di riferimento per il prestito garantito è una potenziale funzionalità di
liquidità per Registerwerk, non un prodotto legalmente approvato.** Si adatta perfettamente anche al vincolo "non
costruire un DEX", dal momento che il prestito garantito non è mai stato un order book, fin dall'inizio.

## `EwpgRepoFacility` — il meccanismo primario di uscita della liquidità { #ewpgrepofacility-the-primary-exit-liquidity-mechanism }

`contracts/src/examples/EwpgRepoFacility.sol` è una struttura di riferimento per pronti contro termine/prestiti
collateralizzati con gating deliberatamente asimmetrico. L'utilizzo in produzione è bloccato in attesa di:
caratterizzazione legale, custodia/controllo, escussione della garanzia, oracolo, insolvenza e approvazione dello
smart contract:

- **Il lato prestatore (`deposit`/`withdraw`) è aperto a qualsiasi detentore di stablecoin** — nessun controllo
`RegisterwerkGated`. I depositanti detengono sempre e solo un diritto sulle stablecoin in pool; non toccano mai
il security token soggetto a restrizioni, quindi non c'è motivo, ai sensi del diritto dei titoli, di limitarli
(gate). Questa è la leva singola più importante per "l'attrattiva di Registerwerk nel generare liquidità di
mercato": minori sono gli ostacoli alla *fornitura* di capitale, più profondo è il pool, poiché il rischio
prezzato ricade interamente sul lato mutuatario (gated).
- **Il lato mutuatario (`pledgeAndBorrow`) è gated** — permesso `repo-facility.borrow` più una valida attestazione
KYC — poiché solo un investitore verificato può impegnare in garanzia l'asset soggetto a restrizioni. Un
mutuatario impegna ad es. una posizione obbligazionaria `EwpgERC3643` e preleva stablecoin fino a un rapporto
prestito/valore configurato, mantenendo intatti la posizione obbligazionaria e i relativi diritti di
cedola/rimborso. Questa è l'operazione "repo": liquidità senza vendita.
- **`repay` e `liquidate` sono intenzionalmente lasciati senza gate (ungated).** Il trasferimento della garanzia
all'indietro verso il chiamante è a sua volta soggetto al controllo del registro delle identità T-REX del token —
la transazione di un chiamante non verificato viene semplicemente annullata (revert) a livello di token. Ciò
significa che l'escussione può essere tecnicamente senza permessi per i destinatari idonei, ma ciò non stabilisce
la conformità legale o normativa. Il muro `isVerified()` esistente fornisce soltanto un gate a livello di
contratto. Il rimborso resta invece aperto per principio — ridurre il proprio rischio e recuperare la garanzia in
precedenza impegnata non dovrebbe mai essere bloccato da una modifica amministrativa dei permessi.
- **Gli interessi sono basati sull'utilizzo** (`liquidityIndex`/`borrowIndex` in stile Aave, WAD-scalato), così
che entrambe le parti si regolino in O(1) indipendentemente dal numero dei partecipanti, e i depositanti vedano
un rendimento trasparente e di mercato piuttosto che un tasso fisso.
- Come `CompliantSecondaryMarket`, l'indirizzo stesso della struttura raggruppa le garanzie di molti mutuatari
dietro un unico indirizzo, quindi qualsiasi asset collaterale `EwpgERC3643` necessita dello stesso flag
`EwpgComplianceModule.setNomineePool(token, address(facility), true)` prima che gli impegni oltre il limite
individuale del primo investitore possano avere successo.

### Meccanismo di trading, diviso per tipo di coppia { #trading-mechanism-split-by-pair-type }

- **Gambe di security token: RFQ/abbinamento bilaterale su `DvpSettlement`**
(`contracts/src/examples/CompliantSecondaryMarket.sol`). Nessuna bonding curve condivisa — le quotazioni vengono
abbinate fuori catena (o tramite una semplice funzione di pubblicazione delle quote on-chain) e regolate in
un'unica transazione riuscita tramite le primitive `lockAsset`/`lockPayment`/`settle` esistenti. Il comportamento
esatto di ciascuna gamba presuppone token senza commissioni di trasferimento/rebase; finalità e iscrizione nel
registro legale restano questioni separate. Questo evita l'esposizione a perdite impermanenti e manipolazione
dell'oracolo su un'obbligazione potenzialmente illiquida e con prezzo NAV — lo stesso motivo per cui le sedi
regolamentate reali (SDX, EU DLT Pilot Regime MTF) usano un pricing a order-book/RFQ piuttosto che curve a
prodotto costante per i titoli. **Il suo ruolo va oggi inteso meglio come scoperta del prezzo che alimenta
`EwpgRepoFacility.updatePrice`** (l'ultima esecuzione eseguita è una valutazione legittima della garanzia)
piuttosto che come sede primaria di liquidità — esattamente come il trading obbligazionario secondario serve
soprattutto alla scoperta del prezzo in TradFi, mentre è il repo a fare il lavoro pesante sul fronte della
liquidità. Più operatori intestatari concorrenti possono ciascuno distribuire la propria istanza ed essere
contrassegnati sullo stesso token, quindi questa è una concorrenza in stile dealer-to-client tra market maker,
non un unico sportello monopolistico.
- **Gambe solo stablecoin: un semplice AMM a prodotto costante**
(`contracts/src/examples/StablecoinAmm.sol`). Riservato alle coppie in cui nessuna delle due gambe è un titolo
(ad esempio AUEUR/USDC, entrambi dichiarati tramite il catalogo dei canali `PaymentRailType.STABLECOIN` del
modulo `payment`) — l'unico caso in cui un familiare AMM nativo della DeFi è davvero la scelta a rischio più
basso, poiché qui non esiste alcuna preoccupazione di integrità del prezzo dei titoli.

Tutte e tre le dApp di riferimento ereditano `RegisterwerkGated` allo stesso modo di `BoardroomGovernance`/
`EwpgBondDesk`. `EwpgRepoFacility` fornisce inoltre un manifest completo, un README e un seeding demo, come gli
altri due esempi di punta — vedere
[dapp-development.md § Esempio di riferimento dApps](./dapp-development.md#reference-example-dapps).
`CompliantSecondaryMarket` e `StablecoinAmm` rimangono testati solo in Solidity (nessun manifest, non inseriti
come elenchi di mercato).

## `EwpgRepoMarket` / `EwpgRepoVault` — l'evoluzione del mercato isolato { #ewpgrepomarket-ewpgrepovault-the-isolated-market-evolution }

`contracts/src/lending/` è l'evoluzione in stile Morpho-Blue di `EwpgRepoFacility`, additiva rispetto ad essa
(entrambi possono funzionare sullo stesso ecosistema — vedi `script/DeployRepoMarkets.s.sol`). Laddove la
struttura raggruppa ogni tipo di garanzia dietro un cash pool condiviso e una coppia di indici condivisi, ciascun
`EwpgRepoMarket` isola il rischio esattamente su una coppia `{loanToken, collateralToken}`, distribuita tramite
`EwpgRepoMarketFactory` (CREATE2, con accesso riservato all'operatore). `EwpgRepoVault` è il livello curator in
stile MetaMorpho, che instrada i depositi dei prestatori su più mercati con limiti per singolo mercato.
`RegisterwerkNavOracle`/`IRepoOracle` formalizzano il modello push NAV della struttura come un'interfaccia
autonoma e sostituibile. Stesso gating asimmetrico della struttura (lato prestatore aperto, lato mutuatario con
gate KYC+permesso, rimborso/escussione senza gate a questo livello — il muro T-REX del token è il vero gate);
vedere il NatSpec di ciascun contratto per i dettagli tecnici.

Questa evoluzione risolve due delle tre semplificazioni indicate più sotto per la struttura: un fattore di
riserva (limitato al 25%, impostabile dall'operatore) e l'escussione parziale (close factor del 50%, in stile
Aave) esistono ora entrambi in `EwpgRepoMarket` — la struttura originaria resta invariata e rimane
un'implementazione di riferimento più semplice. Il terzo punto — la revisione legale sul prestito a margine
specifica per giurisdizione — si applica in modo identico a entrambe ed è **ancora aperto**; vedere la revisione
più sotto.

## Revisione della conformità (2026-07-21): risultati e rafforzamento { #compliance-review-2026-07-21-findings-and-hardening }

Un controllo di conformità completo su `EwpgRepoFacility`, sullo stack `EwpgRepoMarket`/`Vault`/oracolo e sul
read-model backend `lending` ha rilevato gli elementi seguenti. Dettagli completi, mappatura per giurisdizione e
classificazione della gravità: `docs/compliance/lending-facility-review.md`. Riepilogo di ciò che è stato
rilasciato in questo passaggio rispetto a ciò che resta aperto:

**Rafforzato (questo passaggio):**
- **Interruttore automatico (circuit breaker) di deviazione del prezzo dell'oracolo** — `RegisterwerkNavOracle.pushPrice`
ora rifiuta un push che devia più del valore configurabile dall'operatore `maxDeviationBps` (predefinito 20%)
rispetto alla precedente valutazione (mark); esiste uno `pushPriceWithOverride` con permesso separato per le
rivalutazioni legittime di grande entità. Limita il raggio d'impatto di una singola chiave del feed NAV
compromessa o inserita per errore di battitura.
- **Controllo di freschezza (staleness) obbligatorio per i mercati distribuiti** — `EwpgRepoMarket` di per sé
consente ancora `maxPriceAgeSeconds == 0` (controllo di obsolescenza disabilitato) per i test unitari a
costruzione diretta, ma `EwpgRepoMarketFactory.createMarket` ora rifiuta `0` — ogni mercato distribuito
dall'operatore ha un limite di freschezza reale.
- **Protezione da reentrancy per `EwpgRepoVault`** — il vault era l'unico contratto dello stack di prestito
privo di `ReentrancyGuard` sui propri punti di ingresso che movimentano fondi (`deposit`/`mint`/`withdraw`/
`redeem`/`allocate`/`deallocate`); ora è protetto come ogni altro contratto di prestito Ewpg*.
- **Riconciliazione del registro delle garanzie** (eWpG §24 Berichtigung) — un nuovo metodo
`EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)`, vincolato al permesso `CONFIGURE`,
consente all'operatore di correggere la garanzia registrata di una posizione **solo verso il basso** (mai verso
l'alto) dopo che un agente ha spostato la garanzia fuori dal pool tramite `forcedTransfer`/`forceBurn`
indipendentemente da `repay`/`liquidate`, chiudendo una lacuna in cui il registro interno potrebbe altrimenti
disallinearsi dal saldo effettivo del token.
- **Lacuna di autorizzazione nel backend `LendingPositionController`** — `/api/v1/lending/my-positions` e
`/supply-positions` non avevano alcuna annotazione `@PreAuthorize`; ora richiedono l'autenticazione.
- **`borrowPaused` on-chain ora raggiunge il backend/frontend** — `LendingMarketService` legge il flag in tempo
reale (best-effort; un errore di lettura on-chain ricade sullo stato persistente anziché far fallire l'elenco) e
lo riflette come `PAUSED`, colmando la lacuna in cui lo stato esisteva nel modello ma non emergeva mai. Lo
stepper di prestito ora mostra uno stato esplicito di "mercato in pausa" invece di lasciare che un tentativo di
prestito vada in revert on-chain.

**Ancora aperto (vedere il documento di revisione per tutti i dettagli):**
- **Revisione legale sui prestiti a margine specifica per giurisdizione** — invariata rispetto alla struttura
(vedere sopra): la segregazione della custodia, le licenze di prestito a margine e le restrizioni al reimpegno
sono una questione normativa indipendente dal contratto e rimangono non esaminate per `DE_EWPG`/`LU_CSSF`/
`FR_AMF`/`LI_TVTG`.
- **L'escussione non è veramente senza permessi quando l'insieme di escussori verificati è ridotto** — la garanzia
sequestrata viene consegnata a chi esegue l'escussione, quindi il muro T-REX blocca anche lui; senza un
soggetto idoneo, una posizione non sana non può essere chiusa. Non esiste ancora alcun percorso di escussione di
riserva (ad esempio un agente di ultima istanza).
- **Lo stato on-chain del pool intestatario viene attestato solo fuori catena** — nulla sulla catena verifica che
un mercato sia stato effettivamente contrassegnato come pool intestatario prima di accettare impegni oltre il
limite massimo per investitore; oggi il primo impegno che supera il limite viene semplicemente annullato
(revert) a livello di token.
- **Il congelamento del portafoglio del mutuatario non raggiunge la garanzia già impegnata** — una volta che la
garanzia è nel pool, un successivo congelamento del portafoglio del mutuatario non lo blocca più, poiché da quel
momento in poi è il contratto del pool ad essere il detentore registrato del token.

## Prestito contro titoli come garanzia collaterale: cosa è stato effettivamente implementato rispetto a cosa necessita ancora dell'approvazione legale { #lending-against-securities-as-collateral-whats-actually-implemented-vs-what-still-needs-legal-sign-off }

`EwpgRepoFacility` è implementato e testato (`contracts/test/examples/EwpgRepoFacility.t.sol`)
come **implementazione di riferimento**: i meccanismi di prestito collateralizzato, il gating e la logica di
escussione sono reali e corretti, ma quanto segue resta una semplificazione deliberata o una domanda aperta
prima di un'implementazione in produzione:

- **Nessun fattore di riserva del protocollo** — oggi il 100% degli interessi del mutuatario fluisce ai
depositanti, mantenuto così per una contabilità esattamente verificabile in un'implementazione di riferimento.
Un taglio di riserva è una modifica isolata e additiva. (`EwpgRepoMarket` ne aggiunge già uno — vedere sopra.)
- **Solo escussione a close-factor completo** — una posizione non sana viene escussa con un'unica chiamata per
l'intero debito in sospeso, non parzialmente. I mercati monetari reali spesso supportano l'escussione parziale
per ridurre i requisiti patrimoniali di chi esegue l'escussione. (`EwpgRepoMarket` aggiunge già l'escussione a
close-factor parziale — vedere sopra.)
- **I titoli come garanzia collaterale attivano comunque un proprio livello normativo, indipendente dalla
progettazione dello smart contract**: regole di segregazione della custodia, licenze di prestito a margine e (a
seconda della giurisdizione) restrizioni al reimpegno che non si applicano a un semplice prestito garantito in
contanti. Ottenere una revisione legale specifica per giurisdizione delle regole di prestito a margine per
`DE_EWPG`/`LU_CSSF`/`FR_AMF`/`LI_TVTG` prima di utilizzare questa struttura contro titoli reali in produzione —
questo è uno degli aspetti più strettamente regolamentati del diritto nazionale dei titoli/MiFID II, e la
correttezza del contratto non sostituisce tale revisione. **Ancora non esaminato al momento del passaggio di
conformità del 2026-07-21 di cui sopra** — si tratta di un lavoro per il consulente legale, non di qualcosa che
ulteriori modifiche al contratto possano sostituire.
