---
title: Ethereum- und EVM-Chains
description: EVM-kompatible Blockchain-Unterstützung – Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism.
---

# Ethereum & EVM Chains { #ethereum-evm-chains }

Registerwerk enthält Konfigurationsziele für die unten aufgeführten EVM-Netzwerke. Produktionsreife
Unterstützung ergibt sich nicht schon aus der gemeinsamen Nutzung von Bytecode: RPC-Verhalten,
Finalität, bereitgestellter Code/Administratoridentität, Indexierung, Betrieb, Gebühren und rechtliche
Anwendbarkeit erfordern eine Prüfung je Netzwerk.

---

## Unterstützte EVM-Chains { #supported-evm-chains }

| Chain | Chain-Enum | Netzwerk | Chain-ID | Hinweise |
|---|---|---|---|---|
| Ethereum | `ETHEREUM` | MAINNET | 1 | Höchste Sicherheit, höchste Gaskosten |
| Ethereum | `ETHEREUM` | TESTNET | 11155111 (Sepolia) | Entwicklung/Test |
| Polygon | `POLYGON` | MAINNET | 137 | Niedriges Gas, schnelle Finalität |
| Polygon | `POLYGON` | TESTNET | 80002 (Amoy) | Entwicklung/Test |
| Base | `BASE` | MAINNET | 8453 | Coinbase-L2, niedriges Gas |
| Base | `BASE` | TESTNET | 84532 (Sepolia) | Entwicklung/Test |
| Arbitrum | `ARBITRUM` | MAINNET | 42161 | Optimistic Rollup, EVM-äquivalent |
| Arbitrum | `ARBITRUM` | TESTNET | 421614 (Sepolia) | Entwicklung/Test |
| Avalanche | `AVALANCHE` | MAINNET | 43114 | C-Chain, hoher Durchsatz |
| Avalanche | `AVALANCHE` | TESTNET | 43113 (Fuji) | Entwicklung/Test |
| Optimism | `OPTIMISM` | MAINNET | 10 | OP-Stack-L2 |
| Optimism | `OPTIMISM` | TESTNET | 11155420 (Sepolia) | Entwicklung/Test |

---

## Client-Bibliothek: Web3j { #client-library-web3j }

Registerwerk verwendet **Web3j** (Java-Bibliothek) für alle EVM-Chain-Interaktionen. Kernoperationen:

| Operation | Web3j-Methode | Verwendet in |
|---|---|---|
| Vertrag bereitstellen | `web3j.ethSendRawTransaction` | Alle Deployment-Services |
| Status lesen | `contract.call()` | `TokenAdminService`, Indexer |
| Transaktion senden | `contract.send()` | Alle Admin-Operationen |
| Gas schätzen | `web3j.ethEstimateGas` | Gebührenschätzung |
| Events abonnieren | `web3j.ethLogFlowable` | EVM-Indexer |

Die `Web3jClientFactory`-Bean umschließt `Web3j.build(new HttpService(rpcUrl))`. Für die Produktion
wird empfohlen, wo verfügbar WebSocket-Endpunkte zu verwenden (Events ohne Polling abonnieren).

---

## RPC-Node-Gesundheit { #rpc-node-health }

Der `RpcNodeHealthService` (`blockchain/internal/`) läuft alle 60 Sekunden und prüft jeden
registrierten RPC-Node:

1. Ruft `eth_blockNumber` auf – misst Antwortzeit und Rückstand zum besten (höchsten) Block
2. Aktualisiert `RpcNode.healthy`, `RpcNode.consecutiveFailures`, `RpcNode.lagFromBest`
3. Ruft `BlockchainClientRegistry.refreshFromNodes()` mit den aktualisierten Zuständen auf

Das bedeutet, dass die Registrierung stets zum schnellsten, aktuellsten Node weiterleitet. Fällt ein
Node um mehr als einen konfigurierbaren Schwellenwert (`rpcNode.maxLagBlocks`) zurück, wird er als
unhealthy markiert und der Datenverkehr auf gesunde Alternativen umgeleitet.

---

## Multi-Node-Konfiguration { #multi-node-configuration }

Für Produktionsbereitstellungen konfigurieren Sie je Chain mehrere RPC-Provider für Hochverfügbarkeit:

```yaml
# application.yml (example)
registerwerk:
  evm:
    chains:
      ethereum:
        mainnet:
          rpcUrl: https://eth-mainnet.infura.io/v3/${INFURA_KEY}
```

Weitere Nodes werden über die Admin-API hinzugefügt (`POST /api/v1/chain-configs/{id}/rpc-nodes`).
Wird `exclusive=true` auf Premium-Nodes gesetzt, werden ausschließlich diese Nodes verwendet, solange
sie gesund sind.

---

## Gas-Gebührenstrategie { #gas-fee-strategy }

Alle EVM-Transaktionen verwenden standardmäßig EIP-1559 (dynamische Gebühr):

- `maxFeePerGas` = `baseFee × 1.2` (20 % Puffer über der Basisgebühr)
- `maxPriorityFeePerGas` = konfigurierbar je Chain (Standard: 1 Gwei für Ethereum, 30 Gwei für Polygon)
- Gaslimit wird je Transaktionstyp geschätzt (Deployment verwendet `eth_estimateGas`, Admin-Operationen
  verwenden feste Limits mit 20 % Puffer)

Die Betreiber-Wallet muss über ausreichend nativen Token (ETH, MATIC usw.) verfügen, um Gasgebühren zu
zahlen. Der `WalletBalanceService` prüft alle 5 Minuten die Wallet-Guthaben und löst eine
Benachrichtigung aus, wenn eine Wallet unter den konfigurierbaren `minGasWarningThreshold` fällt.
