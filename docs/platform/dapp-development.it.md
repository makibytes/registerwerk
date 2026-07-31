# Creazione di dApp per l'ecosistema Registerwerk { #building-dapps-for-the-registerwerk-ecosystem }

Registerwerk fornisce un **quadro di identità e autorizzazioni onchain** su cui gli istituti finanziari
costruiscono dApp di tokenizzazione, oltre a un **mercato** in cui tali dApp vengono
revisionate, ancorate onchain e offerte ad altri partecipanti. Questa guida copre il flusso di lavoro dello sviluppatore
dall'inizio alla fine.

## Gli elementi costitutivi { #the-building-blocks }

| Contratto | Scopo |
|---|---|
| `OrgRegistry` | Lega i portafogli dei membri alle organizzazioni (un'organizzazione = il suo indirizzo ONCHAINID). Ogni portafoglio appartiene al massimo a un'organizzazione per catena. |
| `PermissionRegistry` | L'operatore concede autorizzazioni alle organizzazioni; gli amministratori dell'organizzazione li delegano ai ruoli membro e possono contrassegnarli con limitazioni di ruolo. |
| `EcosystemTrustedIssuersRegistry` | Emittenti di attestazioni attendibili in base all'argomento della attestazione ONCHAINID (1 = KYC, 2 = AML, 3 = Accreditamento). |
| `DappRegistry` | Ancora i manifesti del marketplace approvati (keccak256) e le attestazioni di istanza facoltative. |
| `PermissionOracle` | **L'unico indirizzo che la tua dApp memorizza.** Compone tutto quanto sopra dietro una facciata di query stabile. |

La tua dApp non comunica mai direttamente con i registri, ma solo con `PermissionOracle`
(`IPermissionOracle`), che l'operatore può reindirizzare ai registri aggiornati senza
interrompere le dApp distribuite.

## Scrivere un contratto gated { #writing-a-gated-contract }

Eredita `RegisterwerkGated` (in `contracts/src/ecosystem/RegisterwerkGated.sol`) e passa
l'indirizzo oracle nel tuo costruttore:

```solidity
import "@registerwerk/ecosystem/RegisterwerkGated.sol";

contract LoanDesk is RegisterwerkGated {
    bytes32 public constant OPEN_LOAN = keccak256("loandesk.open");

    constructor(IPermissionOracle oracle_) RegisterwerkGated(oracle_) {}

    function openLoan() external requiresPermission(OPEN_LOAN) requiresClaim(1) {
        // caller's wallet belongs to an active org holding "loandesk.open",
        // and the org's ONCHAINID carries a valid KYC claim
    }
}
```

Modificatori disponibili:

- `requiresPermission(bytes32 permission)` — concessione a livello di organizzazione (più delega del ruolo quando
  l'organizzazione ha contrassegnato l'autorizzazione come limitata al ruolo).
- `requiresClaim(uint256 topic)` — un'attestazione valida dell'argomento sull'ONCHAINID dell'organizzazione
  chiamante, firmata da un emittente affidabile dell'ecosistema.
- `requiresActiveMember` — il portafoglio del chiamante è vincolato a un'organizzazione non sospesa.

Gli ID di autorizzazione sono `keccak256("<your-slug>.<action>")`. Lo slug del tuo marketplace è il tuo spazio dei
nomi: i manifest che dichiarano autorizzazioni al di fuori di `<slug>.*` vengono rifiutati, a meno che il codice
non esista già come autorizzazione della piattaforma.

Un esempio minimo ed eseguibile si trova in `contracts/test/ecosystem/SampleGatedDapp.t.sol`. Per due dApp di
riferimento completamente confezionate e pronte per il mercato — inclusa un'integrazione ERC-3643 (T-REX) reale —
vedere [Esempio di riferimento dApps](#reference-example-dapps) di seguito.

## Il manifest { #the-manifest }

Il marketplace memorizza **solo metadati**: i tuoi contenitori rimangono nel tuo registro OCI,
appuntato da digest. Il manifest (JSON, schema:
`backend/src/main/resources/schemas/dapp-manifest.schema.json`) descrive:

```json
{
  "slug": "loandesk",
  "name": "Loan Desk",
  "version": "1.0.0",
  "description": "Institutional loan origination on Registerwerk rails.",
  "category": "lending",
  "contracts": [
    { "name": "LoanDesk", "abiSha256": "<sha256 of the ABI json>" }
  ],
  "requiredPermissions": [
    { "code": "loandesk.open", "rationale": "Open loan requests on behalf of the org" }
  ],
  "requiredClaimTopics": [1],
  "images": [
    { "name": "backend",  "role": "backend",
      "ref": "registry.bank.example/loandesk/backend@sha256:…" },
    { "name": "frontend", "role": "frontend",
      "ref": "registry.bank.example/loandesk/frontend@sha256:…" }
  ],
  "deployment": { "composeUrl": "https://…/docker-compose.yml", "composeSha256": "…" },
  "docsUrl": "https://docs.bank.example/loandesk",
  "contact": "dapps@bank.example",
  "license": "commercial",
  "pricingNote": "Contact publisher"
}
```

Regole applicate alla convalida:

- **Il blocco del digest è obbligatorio** — `images[].ref` deve corrispondere a `…@sha256:<64 hex>`; i riferimenti
  con solo tag vengono rifiutati.
- Il manifest `slug` deve corrispondere allo slug dell'elenco.
- `requiredPermissions[].code` deve trovarsi nel tuo spazio dei nomi o in un'autorizzazione
della piattaforma esistente.

## Dichiarare i metodi di pagamento { #declaring-payment-methods }

Emettere un token asset è solo metà della storia: la maggior parte delle dApp necessita anche di una gamba in contanti
(pagamenti di sottoscrizione, pagamenti di cedole/dividendi, rimborsi). Piuttosto che ogni editore costruisca e
controlli i propri canali di pagamento, l'operatore del registro cura un catalogo di canali già pronti — stablecoin
con campi di informativa e attestazione relativi a MiCAR inseriti dall'operatore, l'API di pagamento istantaneo
Pontes, il regolamento consegna contro pagamento in stile ERC-7573 e il classico SEPA fuori catena — e il tuo
manifest può semplicemente farvi riferimento tramite codice:

```json
"paymentMethods": [
  { "rail": "aueur", "note": "Primary-market subscription plus coupon and redemption payouts" },
  { "rail": "usdc" },
  { "rail": "erc7573-dvp", "note": "Same-transaction DvP; exact-leg, finality, and legal-register checks remain external" }
]
```

Recupera il catalogo corrente dei canali abilitati su `GET /api/v1/payment-rails/catalog` (visualizzato anche nel
passaggio "Metodi di pagamento" della procedura guidata di pubblicazione) e copia un `code`. Ogni voce di canale
viene convalidata all'invio **e di nuovo all'approvazione** — un canale disabilitato dall'operatore nel frattempo
blocca l'approvazione della versione finché il manifest non viene aggiornato.

Si tratta di un'indicazione, non di una whitelist: la tua dApp può sempre implementare la propria logica di
pagamento. Dichiarala come voce `custom` invece che come riferimento `rail`:

```json
"paymentMethods": [
  { "custom": { "name": "Own SEPA collection account", "description": "Publisher-run SEPA rail, settled off-chain", "currency": "EUR" } }
]
```

Le voci personalizzate superano la convalida incondizionatamente ma vengono contrassegnate in modo ben visibile
all'operatore durante la revisione (e agli investitori nella pagina dei dettagli del catalogo): il mercato può
vedere esattamente cosa è uscito dal comodo percorso dei "canali forniti dal registro".

Per le dApp che vogliono offrire consegna contro pagamento atomica (ad esempio uno sportello del mercato
secondario), il contratto `DvpSettlement` dell'operatore (`contracts/src/settlement/DvpSettlement.sol`) implementa
un DvP sulla stessa catena in stile ERC-7573: una parte blocca in deposito a garanzia l'asset o la gamba di
pagamento, la controparte regola entrambe le gambe atomicamente, oppure lo scambio scade e chi ha effettuato il
blocco lo recupera. Consulta il suo NatSpec per l'avvertenza sul deposito a garanzia ERC-3643 (i token T-REX
richiedono che l'ONCHAINID del contratto di regolamento sia verificato nel registro delle identità prima di poter
essere depositati a garanzia — bloccare invece la gamba di pagamento aggira questo problema per i security token).

## Flusso di lavoro di pubblicazione { #publication-workflow }

1. **Prerequisito:** la tua azienda è registrata come organizzazione onchain
(lato operatore) e il tuo portafoglio di pubblicazione è vincolato ad essa
(Portale clienti → Amministrazione aziendale → Organizzazione).
2. Portale clienti → **Le mie dApp** → *Nuova dApp* (slug + catena di ancoraggio).
3. Incolla il manifest nella procedura guidata di pubblicazione → la convalida lato server restituisce gli errori
e `manifestHash = keccak256(manifest_raw_bytes)` come stringa esadecimale con prefisso 0x.
4. **Firma** con un portafoglio org vincolato: `personal_sign` (EIP-191) viene chiamato con
**la *stringa* esadecimale con prefisso 0x-hex dell'hash** come messaggio, non con i 32 byte hash grezzi. Questa è
una scelta deliberata, così che qualsiasi interfaccia utente del portafoglio mostri la stringa esadecimale leggibile
firmata; i verificatori devono recuperare la firma rispetto alla stessa stringa (vedere [Verifica integrità
](#integrity-verification-consumers) di seguito).
5. **Invia**: l'operatore del registro esamina con step-up + 4 occhi.
6. Dopo l'approvazione il backend chiama `DappRegistry.registerDapp(keccak256(slug), publisherOrg,
manifestHash, …)`; una volta confermata la transazione, l'elenco è attivo nel catalogo.

Gli aggiornamenti della versione ripetono i passaggi 3–6; il nuovo hash è ancorato a
`DappRegistry.updateManifest` e la versione precedente è contrassegnata come sostituita.

## Verifica dell'integrità (consumatori) { #integrity-verification-consumers }

Tutto il necessario per verificare un'inserzione in modo indipendente è nei dettagli del catalogo:

```bash
# 1. The manifest hash must match the onchain anchor
MANIFEST_HASH=$(cast keccak "$(cat manifest.json)")
cast call $DAPP_REGISTRY "getDapp(bytes32)" $(cast keccak "loandesk") --rpc-url $RPC

# 2. The signature must recover to the declared publisher wallet, which must be a bound
#    member wallet of the publisher org. Recovery is over the hex *string* $MANIFEST_HASH
#    (EIP-191 personal_sign), not the raw 32 hash bytes:
cast wallet verify --address $PUBLISHER_WALLET "$MANIFEST_HASH" $SIGNATURE

# 3. Pull images only by the digests listed in the manifest
```

## Attestazione dell'istanza (facoltativo) { #instance-attestation-optional }

Le istanze del contratto distribuito della tua dApp possono essere attestate in `DappRegistry`
(`attestInstance`) dall'amministratore dell'organizzazione. Altri contratti potrebbero quindi richiedere
`oracle.isApprovedInstance(caller)`: un livello di composizione opt-in; è deliberatamente
non ripiegato in `hasPermission`, poiché le distribuzioni self-hosted controllano i propri chiamanti.

## Componibilità DeFi esterna { #external-defi-composability }

`PermissionOracle` e `DvpSettlement` sono entrambi richiamabili liberamente e senza autorizzazione da **qualsiasi**
contratto esterno — non solo dApp del mercato Registerwerk. Non esiste alcun `onlyRole` né whitelist su
nessuno dei due:

- **`PermissionOracle`** — un protocollo DeFi esterno (il proprio pool, caveau o mercato di prestito) può chiamare
`hasPermission`/`hasClaimTopic`/`isActiveMember` su qualsiasi indirizzo del portafoglio per vincolare (gate) la
propria logica ai soli investitori verificati da Registerwerk, senza mai toccare un security token Registerwerk
né detenere fondi di cui l'oracolo sia a conoscenza. Questo è il modello di interoperabilità `ORACLE_ONLY`
(vedi `DefiInteropModel` nel modulo backend `kyc`): rischio di custodia zero, poiché l'oracolo non custodisce mai
nulla; risponde solo alla domanda "questo portafoglio ha superato il KYC per l'argomento X?". Un vincolo che vale
la pena interiorizzare: l'oracolo controlla l'appartenenza all'org del portafoglio **interrogato**, non quella del
chiamante. Se il tuo contratto stesso deve *essere* l'identità verificata (ad esempio per chiamare una funzione
gated di una dApp Registerwerk come `msg.sender`), l'indirizzo del tuo contratto deve essere esso stesso integrato
tramite `OrgRegistry` come qualsiasi altro portafoglio membro: non esiste una scorciatoia generica del tipo
"qualsiasi smart contract va bene".
- **`DvpSettlement`** — un deposito a garanzia generico, senza gate, in stile ERC-7573, utilizzabile da qualsiasi protocollo esterno
per scambi di asset atomici↔stablecoin, completamente indipendente dal framework di autorizzazione
dell'ecosistema. Leggere attentamente l'avvertenza NatSpec prima di integrare: il deposito in garanzia di una risorsa
ERC-3643 tramite `lockAsset` richiede che `DvpSettlement` stesso passi `isVerified()` nel registro delle identità del token
(un passaggio di onboarding una tantum da parte dell'agente di registro del token); chiamando
`lockPayment` invece lo evita del tutto, poiché la gamba del security token si sposta direttamente
venditore→acquirente come trasferimento diretto a livello di contratto anziché rimanere in deposito a garanzia. Il
superamento dei controlli tecnici del token non stabilisce la conformità legale o normativa, né il regolamento.

Per la domanda più difficile — un protocollo esterno può *detenere* un security token Registerwerk come saldo
aggregato in un pool (un pool AMM, un mercato di prestito) — vedere
[`docs/platform/defi-interoperability.md`](./defi-interoperability.md), che spiega perché ciò richiede una
struttura intestataria/custode autorizzata (il modello `NOMINEE_POOL`) anziché un pool anonimo e senza
autorizzazione, e come viene applicata l'esenzione dell'intestatario di `EwpgComplianceModule`.

## Esempio di riferimento dApps { #reference-example-dapps }

Tre esempi di riferimento tecnico vengono forniti in questo repository con manifest, origine Solidity,
test e `README`. Si tratta di esempi piuttosto che di modelli di prodotto approvati e vengono inseriti come elenchi di marketplace dimostrativi
`PUBLISHED` da
`EcosystemDemoDataSeeder` quando `registerwerk.seed-demo-data=true`:

| dApp | Slug | Punti salienti |
|---|---|---|
| **Governance del consiglio di amministrazione** | `boardroom` | Il framework completo di gestione delle autorizzazioni: proposta/voto/conteggio controllato in base alle autorizzazioni + attestazioni ONCHAINID (KYC, Accreditamento) e il flusso di **limitazione dei ruoli/delega dell'amministratore dell'organizzazione** su `boardroom.tally`. |
| **Sportello obbligazionario eWpG** | `bond-desk` | Un esempio tecnico ERC-3643/T-REX con una gamba di pagamento in token configurata. `subscribe` esegue il trasferimento del pagamento e il conio in un'unica transazione; `payCoupon`/`redeem` esercitano controlli di tempistica/idempotenza. Non si tratta di un'obbligazione classificata legalmente, di un accordo di pagamento verificato o di una prova di regolamento legale. |
| **eWpG Repo e strumento di prestito** | `repo-facility` | Un esempio tecnico di prestito collateralizzato con un lato prestatore di stablecoin aperto e un lato mutuatario vincolato dal contratto. L'utilizzo in produzione è bloccato in attesa della caratterizzazione legale, della custodia/controllo, dell'escussione della garanzia, dell'oracolo, dell'insolvenza, dell'ammissibilità e dell'approvazione in materia di sicurezza. I soli controlli sull'identità dei token non rendono conforme l'escussione della garanzia. Vedi [Interoperabilità DeFi](./defi-interoperability.md#ewpgrepofacility-the-primary-exit-liquidity-mechanism). |

| | Percorso |
|---|---|
| Contratti | `contracts/src/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility,MockStablecoin}.sol`, `contracts/src/settlement/DvpSettlement.sol` |
| Prove | `contracts/test/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility}.t.sol`, `contracts/test/settlement/DvpSettlementTest.t.sol` |
| T-REX Assistente bootstrap | `contracts/test/helpers/TrexSuiteDeployer.sol`: il Bring-up completo di T-REX + ONCHAINID (autorità di implementazione, identità di fabbrica, modulo di conformità) riutilizzato dallo script di test e distribuzione del bond desk |
| Script di distribuzione | `contracts/script/DeployEwpgTrexBond.s.sol`, `contracts/script/DeployExampleDapps.s.sol` (boardroom, bond desk), `contracts/script/DeployLiquidityDapps.s.sol` (repo facility, più `EwpgPaymaster` — mantenuti in uno script separato poiché entrambi usano pragma `^0.8.36` e non possono condividere un'unità di compilazione con i contratti dipendenti da erc3643 di cui sopra; vedere il NatSpec dello script) |
| Manifest | `backend/src/main/resources/demo/dapps/{boardroom,bond-desk,repo-facility}.manifest.json` — letto anche direttamente dal seeder di dati demo (`registerwerk.seed-demo-data=true`), che li pubblica tutti e tre come elenchi di mercato attivi con firme reali e verificabili in modo indipendente |
| Guide | `examples/dapps/{boardroom,bond-desk,repo-facility}/README.md` |

Esegui `forge test --match-path 'test/examples/*'` per visualizzare tutti e tre gli esercizi dall'inizio alla fine,
incluse, per il bond desk, le identità ONCHAINID reali e le identità firmate ECDSA KYC/AML
richieste tramite un onchain-id `ClaimIssuer`.

Due ulteriori contratti dimostrano il bridge `NOMINEE_POOL` e il pattern AMM-for-stablecoins
da [Interoperabilità DeFi](./defi-interoperability.md) — a differenza delle tre dApp di cui sopra,
vengono spedite solo come Solidity testata (nessun manifest, non inserite come elenchi di marketplace dal vivo):

- `contracts/src/examples/CompliantSecondaryMarket.sol` — uno sportello del mercato secondario
intestatario/omnibus, vincolato da `secondary-market.trade` + l'argomento di attestazione `NOMINEE` (4); regola
ogni operazione tramite il `DvpSettlement` non modificato e senza gate riportato sopra, e le sue esecuzioni (fills)
fungono anche da feed di prezzo per `EwpgRepoFacility.updatePrice`. Test:
`contracts/test/examples/CompliantSecondaryMarket.t.sol`.
- `contracts/src/examples/StablecoinAmm.sol` — un prodotto minimo costante AMM limitato alle sole coppie stablecoin
, deliberatamente **non** `RegisterwerkGated` (vedere il suo NatSpec per perché).
Test: `contracts/test/examples/StablecoinAmm.t.sol`.
