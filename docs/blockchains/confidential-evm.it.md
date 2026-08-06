---
title: EVM confidenziale (Zama fhEVM)
description: Quali blockchain eseguono davvero i contratti confidenziali di Registerwerk e di quale infrastruttura hanno bisogno.
---

# EVM confidenziale (Zama fhEVM)

I contratti confidenziali di Registerwerk (`ConfidentialERC20`, `ConfidentialERC3643`) sono
sviluppati sull'fhEVM di **Zama** — nello specifico sull'API `TFHE.sol`/Gateway inclusa nel
repository sotto `contracts/lib/fhevm` (il submodule `zama-ai/fhevm-solidity`) sul lato contratti, e
sul pacchetto reale `@zama-fhe/relayer-sdk` sia sul lato backend (sidecar `zama-relayer`) sia sul
lato browser (`frontend-customer`/`frontend-operator`).

---

## Blockchain che eseguono davvero l'fhEVM di Zama

| Blockchain | Stato | Fonte |
|---|---|---|
| Ethereum Sepolia | Indirizzi reali e documentati (ACL, TFHEExecutor, FHEPayment, KMSVerifier, Gateway) — vedi `contracts/lib/fhevm/config/ZamaFHEVMConfig.sol` e `SepoliaConfig`, incluso nello stesso `@zama-fhe/relayer-sdk` | Libreria inclusa nel repository / pacchetto npm |
| Ethereum mainnet | Obiettivo dichiarato, indirizzi non definitivi al momento della stesura (obiettivo terzo trimestre 2026) e aggiornabili tramite governance una volta attivi | Roadmap pubblica di Zama / forum della community |
| Base | L'annuncio del "fhEVM Coprocessor" di Zama cita Base accanto a Ethereum | Annuncio di prodotto di Zama |
| T-REX Chain | Zama ha annunciato (marzo 2026) che sta diventando il livello di confidenzialità di T-REX Ledger — aspetto direttamente rilevante per `CONF_ERC3643` — ma T-REX Chain non ha ancora una voce nell'enum `Chain` qui e non ha pubblicato i propri indirizzi FHEVM | Comunicato stampa pubblico T-REX/Zama |

`AssetDeploymentService.FHEVM_CHAINS` limita il deployment confidenziale a `Chain.ETHEREUM` e
`Chain.BASE` esattamente per questo motivo. **Fhenix e Inco sono esclusi deliberatamente**: restano
elencati come normali blockchain EVM nell'enum `Chain` (con i propri nodi RPC a fini informativi/di
tracciamento), ma non sono destinazioni valide per il deployment confidenziale.

---

## Configurazione dell'infrastruttura

Ogni indirizzo dei contratti host FHEVM viene iniettato, mai scritto in modo fisso per singola
blockchain:

```java
// ConfidentialERC20.FhevmInfra — passed to the constructor via EwpgConfidentialFactory
struct FhevmInfra {
    address aclAddress;
    address tfheExecutorAddress;
    address fhePaymentAddress;
    address kmsVerifierAddress;
    address gatewayAddress;
}
```

1. Fai il deployment di `EwpgConfidentialFactory` (o riutilizzane uno) sulla blockchain di
   destinazione, chiamando `setFhevmInfra` con gli indirizzi Zama reali di quella blockchain.
2. Imposta `registerwerk.contracts.confidential-factory.<chain-identifier>` sull'indirizzo della
   factory.
3. Per `CONF_ERC3643`, imposta
   `registerwerk.contracts.confidential-identity-registry.<chain-identifier>` su un
   `IdentityRegistry` T-REX reale e già predisposto — è obbligatorio; la factory fa revert del
   deployment se non è impostato, invece di eseguire silenziosamente il deployment con un identity
   registry a indirizzo zero.
4. Imposta `registerwerk.contracts.confidential-operator-viewer.<chain-identifier>` e
   `.confidential-auditor-viewer.<chain-identifier>` sugli indirizzi viewer dedicati, abilitati alla
   sola decifratura, dell'operatore e di un auditor — vedi il modello ACL dei viewer più sotto.
   Questi vengono passati come `initialViewers` al momento del deployment, così ogni token
   confidenziale su quella blockchain li autorizza fin dal primo blocco.

---

## Chi può decifrare — il modello ACL dei viewer

Vedi [Token confidenziali](../token-standards/confidential.md#who-can-decrypt-what-the-viewer-acl-model)
per la spiegazione completa. In breve: ogni titolare può decifrare solo il proprio handle di saldo;
un piccolo insieme di "viewer" (operatore/auditor/emittente) può decifrare qualsiasi handle. Tutto
questo vive interamente in `isViewer`/`addViewer`/`removeViewer` di `ConfidentialERC20` — nessun
contratto separato per singolo investitore.

---

## Decifratura — tre percorsi, tutti reali

- **Decifratura utente** (un titolare che rivela il proprio saldo, oppure un viewer che rivela un
  saldo qualsiasi): interamente lato client. Il wallet connesso firma il payload EIP-712
  `UserDecryptRequestVerification` del KMS e l'istanza di `@zama-fhe/relayer-sdk` del browser stesso
  completa `userDecrypt` direttamente contro il relayer di Zama — vedi il `FheClientService` di
  `frontend-customer`/`frontend-operator`. In questo percorso il backend non vede mai il valore in
  chiaro.
- **Decifratura headless dell'operatore** (report/riconciliazione, senza browser coinvolto): il
  sidecar `zama-relayer` del backend detiene una chiave dedicata abilitata alla sola decifratura
  (`OPERATOR_DECRYPT_PRIVATE_KEY` — deliberatamente NON un wallet on-chain per la firma di
  transazioni) e autofirma la stessa richiesta EIP-712, per poi completare `userDecrypt` in un solo
  round trip. Vedi `ConfidentialBalanceReconciliationService` e
  `ZamaRelayerClient.requestOperatorDecrypt`.
- **Decifratura pubblica/oracolo** (`ConfidentialERC20.requestSupplyDisclosure`): è il contratto
  stesso a chiedere al Gateway di decifrare un valore (ad esempio la supply totale) e a ricevere il
  testo in chiaro tramite una callback firmata. L'implementazione nel repository e i test Foundry
  sono presenti, ma l'integrazione con un coprocessore live e la maturità per la produzione restano
  non verificate.

`zama-relayer` (nella radice del repository, `zama-relayer/`) è un sidecar sviluppato da
Registerwerk che incapsula la build Node del vero `@zama-fhe/relayer-sdk` — esiste solo perché Zama
non pubblica alcun client Java/JVM; tutti i flussi avviati dal browser descritti sopra parlano
direttamente con Zama e non toccano mai questo sidecar. Attivalo con
`docker compose --profile confidential up`; per la configurazione vedi i commenti nel codice
sorgente di `zama-relayer` e la sezione "Confidential tokens" di `.env.example`.

Vedi [Token confidenziali](../token-standards/confidential.md) per la matrice di stato completa e
[SPL-2022 Confidential Transfer](../token-standards/spl-2022.md) per l'equivalente Solana, basato su
ElGamal e non correlato — i due sono facili da confondere, ma usano crittografie diverse e non hanno
alcun codice in comune.
