---
title: StarkNet & Stellar
description: Status und Konfiguration der Blockchain-Unterstützung für StarkNet (Cairo ERC-3525) und Stellar (natives Asset).
---

# StarkNet & Stellar { #starknet-stellar }

Registerwerk enthält funktionsfähige Starknet- und Stellar-Integrationen mit klaren betrieblichen
Grenzen. Ohne netzwerkspezifische Tests gelten sie nicht als produktionsvalidiert.

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

`StarknetTokenService` sendet signierte Invoke-v3-Transaktionen über den Universal Deployer
Contract. Die Deployment-Bestätigung wartet auf `ACCEPTED_ON_L1`; der
`StarknetTransferSyncService` indexiert ERC-20-/ERC-3525-Transfers.

Die Standard-Class-Hashes für ERC-20 und ERC-3525 sind null und führen zu einem sofortigen Fehler.
Vor einem Deployment:

1. Die Cairo-Verträge unter `contracts/cairo/` kompilieren
2. Die Vertragsklasse deklarieren: `starkli declare target/dev/EwpgERC3525.json`
3. `registerwerk.chains.starknet.erc20-class-hash` und/oder
   `registerwerk.chains.starknet.erc3525-class-hash` setzen

Die Integration verwendet Starknet JSON-RPC und das für `Chain.STARKNET` sowie
`Network.MAINNET/TESTNET` konfigurierte Operator-Wallet.

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

1. Ein Inhaber richtet eine Trustline für Emittent und Asset-Code ein
2. Das ausgebende Konto sendet das Asset per `Payment`-Operation an das Inhaberkonto
3. Salden werden nativ in den Ledger-Einträgen des Stellar-Kontos gespeichert

In Registerwerk:

- `AssetDeployment.contractAddress` speichert die Stellar-**Adresse des ausgebenden Kontos**
  (Stellar-Public-Key)
- `StellarAssetService` erzeugt und signiert das XDR und reicht es über die **Horizon-API** ein

### Status { #status }

`StellarAssetService` speichert die Registerwerk-Asset-ID mit einer signierten
`ManageData`-Transaktion und unterstützt Clawback sowie Trustline-Autorisierung. Der Service
erstellt keine Inhaber-Trustlines und verteilt keinen Anfangsbestand. Der
`StellarTransferSyncService` indexiert nur Zahlungen mit Beteiligung des Emittentenkontos;
direkte Inhaber-zu-Inhaber-Transfers sind nicht abgedeckt. Für Stellar-Deployments gibt es in
`AssetDeploymentService` außerdem keinen automatischen Bestätigungspfad.
