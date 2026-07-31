---
title: StarkNet & Stellar
description: Status und Konfiguration der Blockchain-Unterstützung für StarkNet (Cairo ERC-3525) und Stellar (natives Asset).
---

# StarkNet & Stellar { #starknet-stellar }

StarkNet und Stellar werden in Registerwerk teilweise unterstützt. Die Infrastruktur-Verdrahtung
(Client-Anbindung, Deployment-Service-Gerüste, Token-Standard-Enums) ist vorhanden, aber beide Chains
haben **Platzhalterwerte**, die vor dem Produktionseinsatz ersetzt werden müssen.

---

## StarkNet { #starknet }

StarkNet ist ein ZK-Rollup auf Ethereum, der die Smart-Contract-Sprache **Cairo** verwendet. Es bietet
Ethereum-äquivalente Sicherheit bei deutlich geringeren Transaktionskosten.

### Unterstützte Token-Typen { #supported-token-types }

| Token-Enum | Beschreibung |
|---|---|
| `STARKNET_ERC20` | Cairo-Äquivalent zu ERC-20 |
| `STARKNET_ERC3525` | Cairo-Äquivalent zu ERC-3525, semi-fungibel – Tranchenanleihen |

### Status { #status }

⚠️ **Der StarkNet-Class-Hash ist ein Nullplatzhalter.** Vor der Bereitstellung von StarkNet-Token in
der Produktion:

1. Die Cairo-Verträge unter `contracts/cairo/` kompilieren
2. Die Vertragsklasse deklarieren: `starkli declare target/dev/EwpgERC3525.json`
3. Den Class-Hash in der Konfiguration von `StarknetTokenService` durch den deklarierten Class-Hash
   ersetzen

Der `StarknetTokenService` verwendet einen eigenen Java-Client (Starknet4j), konfiguriert über
`Chain.STARKNET` + `Network.MAINNET/TESTNET`.

### Netzwerke { #networks }

| Netzwerk | Network-Enum | Hinweise |
|---|---|---|
| StarkNet Mainnet | `MAINNET` | Produktion – Class-Hash erforderlich |
| StarkNet Sepolia | `TESTNET` | Entwicklung/Test |

---

## Stellar { #stellar }

Stellar ist eine zahlungsorientierte Blockchain mit nativer Unterstützung für **Stellar Assets** –
On-Chain-Darstellungen beliebiger Währungen oder Instrumente.

### Unterstützter Token-Typ { #supported-token-type }

| Token-Enum | Beschreibung |
|---|---|
| `STELLAR_ASSET` | Stellar-nativ ausgegebenes Asset |

### Stellar-Asset-Modell { #stellar-asset-model }

Anders als bei EVM oder Solana verfügt Stellar auf Protokollebene über einen eingebauten Asset-Typ.
Es ist keine Vertragsbereitstellung nötig:

1. Das **ausgebende Konto** richtet vom Inhaberkonto aus eine Trustline ein
2. Das ausgebende Konto sendet das Asset per `Payment`-Operation an das Inhaberkonto
3. Salden werden nativ in den Ledger-Einträgen des Stellar-Kontos gespeichert

In Registerwerk:

- `AssetDeployment.contractAddress` speichert die Stellar-**Adresse des ausgebenden Kontos**
  (Stellar-Public-Key)
- `StellarAssetService` nutzt die **Horizon-API** (Java-SDK), um Transaktionen einzureichen

### Status { #status }

⚠️ **Die Stellar-Unterstützung ist ein Platzhalter.** Die Gerüste von `StellarAssetService` sind
vorhanden, aber die vollständige Implementierung (Trustline-Management, Compliance, Indexer) ist noch
nicht abgeschlossen.

---

## Roadmap-Hinweis { #roadmap-note }

Sowohl StarkNet als auch Stellar sind aktive Entwicklungsbereiche. Die Infrastruktur ist vorhanden, um
Beiträge zu ermöglichen. Prioritätsüberlegungen:

- **StarkNet ERC-3525**: Hoher Nutzen für [Liechtenstein-TVTG](../legal/tvtg-li.md)-Emittenten, die
  ZK-nachgewiesene Abwicklung gegenüber optimistischen Rollups bevorzugen
- **Stellar**: Nützlich für grenzüberschreitende Zahlungswertpapiere und Stablecoins in
  Schwellenländern

Um eine Implementierung beizutragen, folgen Sie dem Muster der EVM-Deployment-Services
(`Erc20DeploymentService`, `Erc3525DeploymentService`) und implementieren Sie dieselbe
`TokenDeploymentPort`-Schnittstelle.
