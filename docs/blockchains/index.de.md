---
title: Unterstützte Blockchains
description: Alle unterstützten Blockchain-Netze, ihre Fähigkeiten und wie Registerwerk sich mit ihnen verbindet.
---

# Unterstützte Blockchains

Registerwerk unterstützt acht Blockchain-Typen über Mainnet- und Testnet-Netze hinweg. Die Anbindung der Chains verwaltet die `BlockchainClientRegistry` des Moduls `blockchain`, die für jede Anfrage den besten verfügbaren RPC-Knoten auswählt.

---

## Kurzübersicht

| Chain-Typ | Token-Standard(s) | Client-Bibliothek | Netze | Stand |
|---|---|---|---|---|
| [Ethereum & EVM](evm.md) | ERC-20/721/1155/3525/3643/4626/7540 | Web3j | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism | Implementierung vorhanden; Produktionsreife ungeprüft |
| [Vertrauliches EVM](confidential-evm.md) | CONF_ERC20, CONF_ERC3643 | Web3j + Zama SDK | Fhenix, Inco | Implementierung vorhanden; Produktionsreife ungeprüft |
| [Solana](solana.md) | SPL, SPL_2022, SPL_2022_BOND, SPL_2022_CONFIDENTIAL | Solanaj | mainnet-beta, devnet | Integration vorhanden; Produktionsreife ungeprüft |
| [Canton / Daml](canton.md) | DAML_BOND_*; CANTON_TOKEN reserviert | Ledger-API-v2-Java-Client | Canton Network, devnet | Benutzerdefinierter Bond-Lebenszyklus profilgeprüft; Live-/Zahlungskonformität erforderlich |
| [StarkNet](starknet-stellar.md) | STARKNET_ERC20, STARKNET_ERC3525 | Eigenes Starknet4j | mainnet, sepolia | ⚠️ Platzhalter |
| [Stellar](starknet-stellar.md) | STELLAR_ASSET | Horizon Java SDK | mainnet, testnet | ⚠️ Platzhalter |

---

## `BlockchainClientRegistry`

Die `BlockchainClientRegistry` (`blockchain/api/`) ist der zentrale Baustein für sämtliche Chain-Anbindung. Für EVM-Chains hält sie drei Client-Ebenen vor:

1. **Knoten-Pool** (höchste Priorität) — vom `RpcNodeHealthService` nach jeder Prüfrunde befüllt. Wählt den gesündesten Knoten mit der geringsten Latenz
2. **Dynamische Einzel-Clients** — je ein Client pro aktivierter `chain_config`-Zeile (Altbestand, erneuert bei `ChainConfigUpdatedEvent`)
3. **Statische Clients** — beim Start aus den Eigenschaften in `application.yml` geladen

### Algorithmus zur Knotenauswahl

Für den Knoten-Pool wendet die Registry folgende Auswahllogik an:

```
1. If any enabled node has exclusive=true → use only exclusive-enabled nodes
2. Otherwise → use all enabled nodes
3. From candidates: prefer healthy nodes with smallest block lag
4. If no healthy candidates → use least-bad (fewest failures, most recent success)
5. If ALL nodes disabled → throw IllegalStateException
```

Das ermöglicht automatisches Ausweichen zwischen mehreren RPC-Anbietern, ohne dass der Betreiber eingreifen muss.

---

## Eine neue Chain hinzufügen

So fügen Sie eine neue EVM-kompatible Chain hinzu:

1. Die Chain im Enum `Chain` in `chain/api/Chain.java` ergänzen
2. Die RPC-URL in `application.yml` unter `registerwerk.evm.chains.<chainName>.<network>.rpcUrl` eintragen
3. Die Registerwerk-Contracts auf der neuen Chain ausbringen (mit dem vorhandenen Deployment-Service)
4. Den `chain_config`-Datensatz über die Admin-API konfigurieren

Eine Nicht-EVM-Chain hinzuzufügen erfordert, die entsprechende Client-Factory-Schnittstelle zu implementieren und den Client in `BlockchainConfig` zu registrieren.

---

## Format der Chain-Kennung

Chains werden im System über `ChainDescriptor(chain, network)` identifiziert:

```java
new ChainDescriptor(Chain.ETHEREUM, Network.MAINNET)
// → identifier: "ETHEREUM_MAINNET"
```

Die Zeichenkette `identifier` dient als Schlüssel in den Maps dynamischer Clients und in `asset_deployment.chain_identifier`, um Ausbringungen der richtigen Chain zuzuordnen.
