---
title: StarkNet e Stellar
description: Stato e configurazione del supporto blockchain di StarkNet (Cairo ERC-3525) e Stellar (asset nativo).
---

# StarkNet e Stellar { #starknet-stellar }

Registerwerk contiene integrazioni Starknet e Stellar funzionanti con limiti operativi espliciti.
Nessuna delle due va considerata validata per la produzione senza test specifici della rete.

---

## StarkNet { #starknet }

StarkNet è un ZK-rollup su Ethereum che utilizza il linguaggio del contratto intelligente **Cairo**. Offre una sicurezza equivalente a quella di Ethereum con costi di transazione notevolmente inferiori.

### Tipi di token supportati { #supported-token-types }

| Enumerazione token | Descrizione |
|---|---|
| `STARKNET_ERC20` | Cairo ERC-20 equivalente |
| `STARKNET_ERC3525` | Cairo ERC-3525 semi-fungibile — obbligazioni tranched |

### Stato { #status }

`StarknetTokenService` invia transazioni Invoke v3 firmate tramite l'Universal Deployer Contract.
La conferma attende `ACCEPTED_ON_L1` e `StarknetTransferSyncService` indicizza gli eventi di
trasferimento ERC-20/ERC-3525.

Gli hash di classe predefiniti per ERC-20 ed ERC-3525 sono zero e causano un errore immediato.
Prima del deployment:

1. Compila i contratti Cairo sotto `contracts/cairo/`
2. Dichiara la classe del contratto: `starkli declare target/dev/EwpgERC3525.json`
3. Configura `registerwerk.chains.starknet.erc20-class-hash` e/o
   `registerwerk.chains.starknet.erc3525-class-hash`

L'integrazione usa Starknet JSON-RPC e il wallet operatore configurato per
`Chain.STARKNET` e `Network.MAINNET/TESTNET`.

### Reti { #networks }

| Rete | Enumerazione rete | Note |
|---|---|---|
| Rete principale StarkNet | `MAINNET` | Produzione: hash della classe richiesto |
| StarkNet Sepolia | `TESTNET` | Sviluppo/test |

---

## Stellar { #stellar }

Stellar è una blockchain incentrata sui pagamenti con supporto nativo per **Stellar Assets** — rappresentazioni on-chain di qualsiasi valuta o strumento.

### Tipo di token supportato { #supported-token-type }

| Enumerazione token | Descrizione |
|---|---|
| `STELLAR_ASSET` | Asset emesso nativo di Stellar |

### Modello di asset Stellar { #stellar-asset-model }

A differenza di EVM o Solana, Stellar ha un tipo di asset integrato a livello di protocollo. Non è necessaria alcuna distribuzione del contratto:

1. Il titolare crea una linea di fiducia per l'emittente e il codice dell'asset
2. Il conto di emissione invia il bene al conto del titolare tramite un'operazione `Payment`
3. I saldi vengono archiviati in modo nativo nelle voci del registro degli account Stellar

In Registerwerk:
- `AssetDeployment.contractAddress` memorizza l'**indirizzo dell'account di emissione** Stellar (chiave pubblica Stellar)
- `StellarAssetService` costruisce e firma l'XDR e lo invia tramite l'**API Horizon**

### Stato { #status }

`StellarAssetService` registra l'ID dell'asset con una transazione `ManageData` firmata e implementa
clawback e autorizzazione delle linee di fiducia. Non crea linee di fiducia dei titolari né
distribuisce un saldo iniziale. `StellarTransferSyncService` indicizza i pagamenti che coinvolgono
il conto emittente; i trasferimenti diretti tra titolari non sono coperti. I deployment Stellar
non hanno inoltre una conferma automatica in `AssetDeploymentService`.
