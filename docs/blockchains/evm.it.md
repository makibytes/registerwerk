---
title: Catene Ethereum e EVM
description: Supporto blockchain compatibile con EVM: Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism.
---

# Ethereum e catene EVM { #ethereum-evm-chains }

Registerwerk contiene obiettivi di configurazione per le reti EVM elencate di seguito. Il supporto alla produzione
non è stabilito dalla condivisione del bytecode: comportamento di RPC, finalità, codice distribuito/identità amministratore,
indicizzazione, operazioni, tariffe e applicabilità legale richiedono la verifica per rete.

---

## Catene EVM supportate { #supported-evm-chains }

| Catena | Enumerazione catena | Rete | ID catena | Note |
|---|---|---|---|---|
| Ethereum | `ETHEREUM` | MAINNET | 1 | Massima sicurezza, massimo costo del gas |
| Ethereum | `ETHEREUM` | TESTNET | 11155111 (Sepolia) | Sviluppo/test |
| Polygon | `POLYGON` | MAINNET | 137 | Gas basso, finalità rapida |
| Polygon | `POLYGON` | TESTNET | 80002 (Amoy) | Sviluppo/test |
| Base | `BASE` | MAINNET | 8453 | Coinbase L2, gas basso |
| Base | `BASE` | TESTNET | 84532 (Sepolia) | Sviluppo/test |
| Arbitrum | `ARBITRUM` | MAINNET | 42161 | Rollup ottimistico, equivalente a EVM |
| Arbitrum | `ARBITRUM` | TESTNET | 421614 (Sepolia) | Sviluppo/test |
| Avalanche | `AVALANCHE` | MAINNET | 43114 | C-Chain, produttività elevata |
| Avalanche | `AVALANCHE` | TESTNET | 43113 (Fuji) | Sviluppo/test |
| Optimism | `OPTIMISM` | MAINNET | 10 | OP Stack L2 |
| Optimism | `OPTIMISM` | TESTNET | 11155420 (Sepolia) | Sviluppo/test |

---

## Libreria client: Web3j { #client-library-web3j }

Registerwerk utilizza **Web3j** (libreria Java) per tutte le interazioni della catena EVM. Operazioni chiave:

| Operazione | Metodo Web3j | Utilizzato in |
|---|---|---|
| Distribuzione contratto | `web3j.ethSendRawTransaction` | Tutti i servizi di distribuzione |
| Leggi stato | `contract.call()` | `TokenAdminService`, indicizzatore |
| Invia transazione | `contract.send()` | Tutte le operazioni di amministrazione |
| Stima gas | `web3j.ethEstimateGas` | Stima della tariffa |
| Iscriviti agli eventi | `web3j.ethLogFlowable` | Indicizzatore EVM |

Il bean `Web3jClientFactory` avvolge `Web3j.build(new HttpService(rpcUrl))`. Per la produzione, si consiglia di utilizzare gli endpoint WebSocket ove disponibili (sottoscriversi agli eventi senza polling).

---

## Stato del nodo RPC { #rpc-node-health }

Il `RpcNodeHealthService` (`blockchain/internal/`) viene eseguito ogni 60 secondi e controlla ogni nodo RPC registrato:

1. Chiama `eth_blockNumber`: misura il tempo di risposta e il ritardo dal migliore (blocco più alto)
2. Aggiorna `RpcNode.healthy`, `RpcNode.consecutiveFailures`, `RpcNode.lagFromBest`
3. Chiama `BlockchainClientRegistry.refreshFromNodes()` con gli stati aggiornati

Ciò significa che il registro si instrada sempre al nodo più veloce e aggiornato. Quando un nodo resta indietro di oltre una soglia configurabile (`rpcNode.maxLagBlocks`), viene contrassegnato come non integro e il traffico viene deviato verso alternative integre.

---

## Configurazione multi-nodo { #multi-node-configuration }

Per distribuzioni di produzione, configurare più provider RPC per catena per un'elevata disponibilità:

```yaml
# application.yml (example)
registerwerk:
  evm:
    chains:
      ethereum:
        mainnet:
          rpcUrl: https://eth-mainnet.infura.io/v3/${INFURA_KEY}
```

Nodi aggiuntivi vengono aggiunti tramite l'API di amministrazione (`POST /api/v1/chain-configs/{id}/rpc-nodes`). L'impostazione di `exclusive=true` sui nodi premium garantisce che solo tali nodi vengano utilizzati quando sono integri.

---

## Strategia tariffaria gas { #gas-fee-strategy }

Tutte le transazioni EVM utilizzano EIP-1559 (tariffa dinamica) per impostazione predefinita:

- `maxFeePerGas` = `baseFee × 1.2` (20% buffer sopra la base)
- `maxPriorityFeePerGas` = configurabile per catena (impostazione predefinita: 1 Gwei per Ethereum, 30 Gwei per Polygon)
- Limite di gas stimato per tipo di transazione (la distribuzione utilizza `eth_estimateGas`, le operazioni di amministrazione utilizzano limiti fissi con buffer del 20%)

Il wallet dell'operatore deve contenere token nativi sufficienti (ETH, MATIC, ecc.) per pagare le tariffe del gas. `WalletBalanceService` controlla i saldi dei wallet ogni 5 minuti ed emette una notifica se un wallet scende al di sotto del `minGasWarningThreshold` configurabile.
