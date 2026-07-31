---
title: Blockchain supportate
description: Tutte le reti blockchain supportate, le loro capacità e come Registerwerk vi si collega.
---

# Blockchain supportate

Registerwerk supporta otto tipi di blockchain, su reti principali e di prova. La connettività verso le chain è gestita dal `BlockchainClientRegistry` del modulo `blockchain`, che per ogni richiesta seleziona il miglior nodo RPC disponibile.

---

## Riferimento rapido

| Tipo di chain | Standard di token | Libreria client | Reti | Stato |
|---|---|---|---|---|
| [Ethereum ed EVM](evm.md) | ERC-20/721/1155/3525/3643/4626/7540 | Web3j | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism | Implementazione presente; maturità per la produzione non verificata |
| [EVM confidenziale](confidential-evm.md) | CONF_ERC20, CONF_ERC3643 | Web3j + SDK Zama | Fhenix, Inco | Implementazione presente; maturità per la produzione non verificata |
| [Solana](solana.md) | SPL, SPL_2022, SPL_2022_BOND, SPL_2022_CONFIDENTIAL | Solanaj | mainnet-beta, devnet | Integrazione presente; maturità per la produzione non verificata |
| [Canton / DAML](canton.md) | DAML_BOND_*, CANTON_TOKEN | Client Java DAML | Canton Network, devnet | Implementazione opzionale (`-Pcanton`); maturità per la produzione non verificata |
| [StarkNet](starknet-stellar.md) | STARKNET_ERC20, STARKNET_ERC3525 | Starknet4j proprietario | mainnet, sepolia | ⚠️ Segnaposto |
| [Stellar](starknet-stellar.md) | STELLAR_ASSET | SDK Java Horizon | mainnet, testnet | ⚠️ Segnaposto |

---

## `BlockchainClientRegistry`

Il `BlockchainClientRegistry` (`blockchain/api/`) è il componente centrale per tutta la connettività alle chain. Per le chain EVM mantiene tre livelli di client:

1. **Pool di nodi** (priorità massima) — popolato dal `RpcNodeHealthService` dopo ogni giro di controlli di salute. Sceglie il nodo più sano e con minore latenza
2. **Client singoli dinamici** — un client per ogni riga `chain_config` abilitata (legacy, aggiornato su `ChainConfigUpdatedEvent`)
3. **Client statici** — caricati all'avvio dalle proprietà di `application.yml`

### Algoritmo di selezione dei nodi

Per il pool di nodi il registry applica questa logica di selezione:

```
1. If any enabled node has exclusive=true → use only exclusive-enabled nodes
2. Otherwise → use all enabled nodes
3. From candidates: prefer healthy nodes with smallest block lag
4. If no healthy candidates → use least-bad (fewest failures, most recent success)
5. If ALL nodes disabled → throw IllegalStateException
```

Questo garantisce un ripiego automatico tra più fornitori RPC senza interventi dell'operatore.

---

## Aggiungere una nuova chain

Per aggiungere una nuova chain compatibile con EVM:

1. Aggiungi la chain all'enum `Chain` in `chain/api/Chain.java`
2. Aggiungi l'URL RPC in `application.yml` sotto `registerwerk.evm.chains.<chainName>.<network>.rpcUrl`
3. Distribuisci i contratti Registerwerk sulla nuova chain (con il servizio di distribuzione esistente)
4. Configura il record `chain_config` tramite l'API di amministrazione

Aggiungere una chain non EVM richiede di implementare la corrispondente interfaccia di factory del client e di registrare il client in `BlockchainConfig`.

---

## Formato dell'identificativo di chain

Nel sistema le chain sono identificate tramite `ChainDescriptor(chain, network)`:

```java
new ChainDescriptor(Chain.ETHEREUM, Network.MAINNET)
// → identifier: "ETHEREUM_MAINNET"
```

La stringa `identifier` fa da chiave nelle mappe dei client dinamici e in `asset_deployment.chain_identifier`, per collegare le distribuzioni alla chain corretta.
