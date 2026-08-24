---
title: Token confidenziali (Zama fhEVM)
description: Token ERC-20 e ERC-3643 che preservano la privacy utilizzando la crittografia completamente omomorfica di Zama: il ciclo di vita completo di crittografia/decrittografia, end-to-end.
---

# Token confidenziali (Zama fhEVM) { #confidential-tokens-zama-fhevm }

I token confidenziali utilizzano la **Crittografia completamente omomorfica (FHE)** per proteggere i saldi dei token e
gli importi trasferiti dalla vista del pubblico, preservando al contempo le funzionalità di conformità e controllo
richieste dai regolatori.

!!! warning "Registerwerk è il cliente"
    Le revisioni precedenti di questa pagina descrivevano il ciclo di vita di crittografia/decrittografia come "problema
    di qualcun altro": il lavoro del browser, un servizio complementare che devi fornire tu stesso. Questo era l'inquadramento sbagliato: le uniche parti autorizzate a decriptare i saldi confidenziali — emittenti, investitori, l'operatore del registro
    e un revisore — agiscono tutte *attraverso* Registerwerk. Costruire l'integrazione completa di
    `@zama-fhe/relayer-sdk` è quindi responsabilità di Registerwerk stesso, ed è ora realtà: contratti, un sidecar
    relayer in-repo, servizi di backend e integrazione nel browser in entrambi i frontend. Consulta la
    [matrice di stato](#status) più sotto per sapere esattamente cosa è già reale e cosa richiede ancora una rete live
    per essere verificato.


---

## Standard confidenziali supportati { #supported-confidential-standards }

| Standard | Basato su | Stato crittografato |
|---|---|---|
| `CONF_ERC20` | ERC-7984 token fungibile confidenziale | `euint64` saldi/allowance |
| `CONF_ERC3643` | ERC-3643 (T-REX) + ERC-7984 | Saldi `euint64` + identità/conformità in chiaro |

Contratti: `contracts/src/confidential/ConfidentialERC20.sol` / `ConfidentialERC3643.sol`,
distribuiti tramite `contracts/src/factory/EwpgConfidentialFactory.sol`.

---

## Quali catene eseguono effettivamente questo { #which-chains-actually-run-this }

Il coprocessore fhEVM di Zama funziona su **Ethereum e Base** (secondo l'annuncio di prodotto "fhEVM Coprocessor" di
Zama) — più **Sepolia** oggi come testnet pienamente configurata (gli indirizzi reali di ACL/Executor/
Payment/KMSVerifier/Gateway sono inclusi in `contracts/lib/fhevm/config/`, e gli stessi indirizzi Sepolia reali
sono racchiusi in `@zama-fhe/relayer-sdk` come `SepoliaConfig`). Gli indirizzi **mainnet** di Zama su Ethereum
erano ancora in fase di finalizzazione al momento della stesura di questo articolo (obiettivo Q3 2026) e sono
aggiornabili dalla governance anche una volta live.

**Fhenix e Inco NON sono chain Zama fhEVM.** Gestiscono i propri stack FHE separati e incompatibili.
`ConfidentialERC20`/`ConfidentialERC3643` sono costruiti specificatamente contro
`TFHE.sol`/Gateway API di Zama e non funzionano su nessuna delle due.

**T-REX Chain**: nel marzo 2026 T-REX Network ha annunciato che Zama sta diventando lo strato di confidenzialità
per il T-REX Ledger — direttamente rilevante per `CONF_ERC3643`, che già combina identità/conformità T-REX
con i saldi FHE di Zama. T-REX Chain non è ancora rappresentata come proprio valore dell'enum
`Chain` in questo backend e non ha (ancora, pubblicamente) condiviso i propri indirizzi dell'infrastruttura FHEVM.
Confermarli prima di fare affidamento su questo abbinamento in produzione.

Gli indirizzi dell'infrastruttura FHEVM non sono mai codificati per rete nei contratti — vengono
iniettati al momento della costruzione/configurazione della factory (`ConfidentialERC20.FhevmInfra`,
`EwpgConfidentialFactory.setFhevmInfra`), proprio in modo che una nuova rete (mainnet, T-REX Chain) possa essere
raggiunta configurando indirizzi reali, non tramite una nuova distribuzione del contratto.

---

## Chi può decrittografare cosa — il modello ACL dei visualizzatori { #who-can-decrypt-what-the-viewer-acl-model }

Le concessioni ACL di Zama sono additive e specifiche per singolo handle di ciphertext: una volta che un indirizzo
viene `allow`ed su un handle, tale concessione è permanente per quello specifico handle (non esiste una revoca —
vedere il commento del codice di `ConfidentialERC20.removeViewer`). Registerwerk utilizza quella primitiva per
fornire esattamente l'isolamento richiesto dalla piattaforma, all'interno di un **singolo contratto confidenziale
per asset** (non un contratto per investitore — vedere di seguito):

- **A ciascun titolare vengono concessi i diritti di decrittografia solo sul proprio saldo**, ogni volta che
  muta (conio/trasferimento/distruzione). Un investitore non può mai decrittografare il saldo di un altro
  investitore, perché non viene mai `allow`ed su quell'altro handle.
- **Un piccolo insieme di "visualizzatori"** — l'operatore del registro, un revisore e (aggiunto dopo il
  deployment tramite `addViewer`) il wallet dell'emittente, se lo si desidera — riceve diritti di decrittografia
  su **ogni** handle (saldo e offerta totale), soddisfacendo il requisito per cui "l'operatore deve poter
  decrittografare tutti gli importi di tutti gli investitori e il ruolo di revisore deve poter decrittografare
  gli importi".
- I visualizzatori vengono forniti come `initialViewers` al momento del deployment
  (`EwpgConfidentialFactory.deployConfidentialErc20/deployConfidentialErc3643`, presi da
  `registerwerk.contracts.confidential-operator-viewer.*` / `.confidential-auditor-viewer.*`) oppure
  aggiunti/rimossi in seguito tramite `TokenAdminService.confidentialAddViewer`/`confidentialRemoveViewer`
  (`POST .../admin/confidential-add-viewer` / `-remove-viewer`).

Perché un contratto con un ACL, non un contratto per investitore: identica garanzia di isolamento, al normale
costo di implementazione/gas, senza la complessità di riconciliazione dell'offerta per investitore.

---

## Cosa fanno effettivamente i contratti { #what-the-contracts-actually-do }

- `confidentialTransfer` / `confidentialTransferFrom` / `confidentialApprove` — trasferimento/allowance
  crittografati ERC-7984, con semantica di fallimento silenzioso basata su `TFHE.select` in caso di saldo
  insufficiente (corrisponde alla convenzione ERC-7984, non è un bug).
- `confidentialMint` / `confidentialBurn` — riservati a proprietario/agente, concedono il set di visualizzatori
  (sopra) su ogni handle modificato. Su `ConfidentialERC3643`, `confidentialBurn` è anche la primitiva di
  cancellazione obbligatoria (eWpG §26 Einziehung) per gli importi crittografati.
- `ConfidentialERC3643` impone inoltre la verifica dell'identità T-REX, il freeze, la pausa e un modulo
  `IConfidentialCompliance` collegabile prima di qualsiasi trasferimento.
- `requestSupplyDisclosure` / `callbackSupplyDisclosure` — il percorso di **decrittazione pubblica/oracle**:
  il contratto stesso chiede al Gateway di Zama di decrittografare l'offerta totale e riceve il testo in chiaro
  tramite un callback firmato, per una divulgazione attivata dal regolatore — distinta da un titolare/visualizzatore
  che decrittografa il proprio saldo o quello di un altro tramite il Relayer (di seguito).

---

## Il ciclo di vita di crittografia/decrittografia: chi fa cosa { #status }

| Attore | Azione | Come | Stato |
|---|---|---|---|
| Investitore | Rivela il proprio saldo | Browser: `FheClientService.userDecrypt` (il wallet connesso firma la richiesta KMS EIP-712, decrittografa direttamente contro il relayer di Zama) | ✅ Reale — `frontend-customer` |
| Investitore | Trasferimento confidenziale | Browser: `FheClientService.encrypt64` lato client, quindi il wallet invia `confidentialTransfer` | ✅ Reale — `frontend-customer` |
| Emittente | Conio confidenziale | Il backend crittografa lato server (nessun browser in questo flusso) tramite il sidecar `zama-relayer`, quindi invia | ✅ Reale — `TokenAdminService.confidentialMint`, `POST .../issuer/mint-confidential` |
| Emittente | Rivelare il saldo di qualsiasi titolare | Browser, come visualizzatore registrato (stesso percorso `FheClientService.userDecrypt`) | ✅ Reale — pannello dei saldi confidenziali dell'emittente in `frontend-customer` |
| Operatore | Decrittografia headless per report/riconciliazione | Chiave di decrittografia dell'operatore dedicata del backend tramite `zama-relayer`, senza wallet | ✅ Reale — `ConfidentialBalanceReconciliationService`, `GET .../confidential-reconciliation` |
| Operatore/Revisore | Rivela + riconcilia tramite il proprio wallet | Browser: scheda **Confidential Balances** di `frontend-operator` (`ConfidentialViewerPanelComponent`) | ✅ Reale |
| Operatore | Distruzione forzata confidenziale (§26 Einziehung) | Il backend crittografa lato server tramite `zama-relayer`, quindi invia | ✅ Reale — `TokenAdminService.confidentialForceBurn`, `POST .../force-burn-confidential` |
| Regolatore | Divulgazione dell'offerta totale pubblica/oracle | On-chain: `requestSupplyDisclosure`/`callbackSupplyDisclosure` | ✅ Reale, testato con Foundry |
| Blocco/pausa/trasferimento forzato ERC-3643 confidenziale tramite l'API dell'operatore | — | `Erc3643Controller` prende di mira l'ABI in chiaro di `EwpgERC3643`; chiamandolo contro `ConfidentialERC3643` vengono inviati dati di chiamata non corrispondenti | ❌ Non cablato — oggi solo la distruzione forzata ha un percorso specifico per la confidenzialità |
| Canale di pagamento confidenziale (importi di stablecoin crittografati nella gamba contante DvP) | — | — | ❌ Non costruito |

**Ciò che è veramente non verificato qui**: questa sandbox non ha Docker/Kong live né un account Sepolia
finanziato per inviare transazioni reali, quindi il percorso on-chain invio → minatura → decrittografia non è
stato eseguito end-to-end in questo ambiente. Ciò che **è** stato verificato rispetto alla reale infrastruttura
live Sepolia di Zama durante lo sviluppo: l'endpoint `/v1/encrypt-input` di `zama-relayer` ha prodotto un
autentico handle di testo cifrato e una prova di input ZK da una connessione `createInstance` live al vero
relayer di Zama (`https://relayer.testnet.zama.org`) e un Sepolia RPC pubblico — non un finto. Ogni componente
qui è costruito, testato a livello di unità/Foundry e (dove verificato) confermato in rete live a livello di
singola chiamata; solo l'intero percorso di andata e ritorno della transazione multi-fase richiede un account
finanziato e un asset distribuito per essere completato.

---

## Distribuzione di un asset confidenziale { #deploying-a-confidential-asset }

1. Distribuisci `EwpgConfidentialFactory` su una chain con indirizzi FHEVM reali di Zama configurati (Sepolia
   oggi), oppure configura una factory esistente tramite `setFhevmInfra`.
2. Per `CONF_ERC3643`, fornisci un `IdentityRegistry` T-REX condiviso per gli asset confidenziali su quella chain
   e imposta `registerwerk.contracts.confidential-identity-registry.<chain>`: la distribuzione con un registro
   di identità non configurato/azzerato fallisce in modo rumoroso (`EwpgConfidentialFactory` va in revert).
3. Imposta `registerwerk.contracts.confidential-factory.<chain>` sull'indirizzo della factory distribuita e
   `registerwerk.contracts.confidential-operator-viewer.<chain>` /
   `.confidential-auditor-viewer.<chain>` sugli indirizzi di visualizzazione dedicati di sola decrittografia
   dell'operatore/revisore (vedere [Confidential EVM](../blockchains/confidential-evm.md)).
4. Distribuisci `zama-relayer` (`docker compose --profile confidential up`) con
   `OPERATOR_DECRYPT_PRIVATE_KEY` impostato sulla chiave privata corrispondente all'indirizzo operatore-visualizzatore
   di cui sopra, e punta il backend a esso tramite `registerwerk.zama.relayer-url`.
5. Emetti l'asset come `CONF_ERC20`/`CONF_ERC3643` — la distribuzione è vincolata alle chain reali del
   coprocessore Zama (`Chain.ETHEREUM`, `Chain.BASE`), non a Fhenix/Inco.

Vedi [Confidential EVM](../blockchains/confidential-evm.md) per i dettagli della configurazione della chain e
[Operatore: token confidenziali](../operator/blockchain/confidential-tokens.md) per il flusso di lavoro
quotidiano dell'operatore.
