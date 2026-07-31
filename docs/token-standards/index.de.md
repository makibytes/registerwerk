---
title: Token-Standards
description: In Registerwerk abgebildete Token-Standard-Implementierungen, mit Vergleich, Anwendungsfällen und Umsetzungsstand.
---

# Token-Standards

Registerwerk enthält Implementierungen, Integrationen oder Platzhalter für Token-Standards über EVM-, Solana-, StarkNet-, Stellar- und Canton-Chains hinweg. Die Aufnahme in diese Tabelle begründet weder Produktionsreife noch Eignung für ein Finanzinstrument.

---

## Vergleich auf einen Blick

| Standard | Chain | Typ | Anwendungsfall | Vertrauliche Variante | Stand |
|---|---|---|---|---|---|
| [ERC-20](erc20.md) | EVM | Fungibel | Eigenkapital, Utility | CONF_ERC20 | Implementierung vorhanden; Reife ungeprüft |
| [ERC-721](erc721.md) | EVM | Nicht fungibel | Einzigartige Urkunden, NFT-Anleihen | — | Implementierung vorhanden; Reife ungeprüft |
| [ERC-1155](erc1155.md) | EVM | Multi-Token | Sammelemissionen | — | Implementierung vorhanden; Reife ungeprüft |
| [ERC-3525](erc3525.md) | EVM | Semi-fungibel | Anleihen mit Tranchen, Fondsserien | STARKNET_ERC3525 | Implementierung vorhanden; Reife ungeprüft |
| [ERC-3643](erc3643.md) | EVM | Fungibel + Identität | Regulierte Wertpapiere, zugangsbeschränkt | CONF_ERC3643 | Implementierung vorhanden; Reife ungeprüft |
| [ERC-4626](erc4626.md) | EVM | Vault (synchron) | Geldmarktfonds, tägliche NAV | — | Implementierung vorhanden; Ökonomie/Reife ungeprüft |
| [ERC-7540](erc7540.md) | EVM | Vault (asynchron) | Institutionelle Fonds, T+1/T+2 | — | Implementierung vorhanden; Ökonomie/Reife ungeprüft |
| [DAML BOND FIXED](canton-daml.md) | Canton | Anleihe | Festverzinsliche Anleihen auf privatem Ledger | — | Optionale Implementierung (`-Pcanton`); Reife ungeprüft |
| [DAML BOND FLOATING](canton-daml.md) | Canton | Anleihe | Variabel verzinsliche Anleihen | — | Optionale Implementierung (`-Pcanton`); Reife ungeprüft |
| [DAML BOND ZERO](canton-daml.md) | Canton | Anleihe | Nullkuponanleihen | — | Optionale Implementierung (`-Pcanton`); Reife ungeprüft |
| [CANTON_TOKEN](canton-daml.md) | Canton | Generisch | DAML-basierter digitaler Vermögenswert | — | Optionale Implementierung (`-Pcanton`); Reife ungeprüft |
| [SPL](../blockchains/solana.md) | Solana | Fungibel | Solana-native Token | — | Integration vorhanden; Reife ungeprüft |
| [SPL_2022](spl-2022.md) | Solana | Fungibel + Erw. | Erweiterte Solana-Token | — | Integration vorhanden; Reife ungeprüft |
| [SPL_2022_BOND](spl-2022.md) | Solana | Fungibel + Zins | Verzinsliche Anleihen auf Solana | — | Integration vorhanden; Reife ungeprüft |
| [SPL_2022_CONFIDENTIAL](spl-2022.md) | Solana | Vertraulich | Vertraulicher Solana-Token | — | Integration vorhanden; Reife ungeprüft |
| [STARKNET_ERC20](../blockchains/starknet-stellar.md) | StarkNet | Fungibel | Cairo-Entsprechung zu ERC-20 | — | ⚠️ Platzhalter |
| [STARKNET_ERC3525](../blockchains/starknet-stellar.md) | StarkNet | Semi-fungibel | Cairo-Entsprechung zu ERC-3525 | — | ⚠️ Platzhalter |
| [STELLAR_ASSET](../blockchains/starknet-stellar.md) | Stellar | Asset | Stellar-nativ ausgegebener Vermögenswert | — | ⚠️ Platzhalter |
| [CONF_ERC20](confidential.md) | Fhenix / Inco | Vertraulich fungibel | Eigenkapital mit Privatsphäre | — | Implementierung vorhanden; Reife ungeprüft |
| [CONF_ERC3643](confidential.md) | Fhenix / Inco | Vertraulich reguliert | Reguliertes Wertpapier mit Privatsphäre | — | Implementierung vorhanden; Reife ungeprüft |

---

## Wie das Enum `TokenStandard` die Ausbringung steuert

Das Enum `TokenStandard` im Modul `deployment` ist der zentrale Schalter, der bestimmt, welcher Deployment-Service aufgerufen wird:

```java
// BlockchainTokenDeploymentService — simplified routing
return switch (asset.tokenStandard()) {
    case ERC20         -> erc20DeploymentService.deploy(asset);
    case ERC721        -> erc721DeploymentService.deploy(asset);
    case ERC3525       -> erc3525DeploymentService.deploy(asset);
    case ERC3643       -> erc3643DeploymentService.deploy(asset);
    case ERC4626       -> erc4626DeploymentService.deploy(asset);
    case ERC7540       -> erc7540DeploymentService.deploy(asset);
    case DAML_BOND_FIXED, DAML_BOND_FLOATING, DAML_BOND_ZERO ->
                          cantonBondDeploymentService.deploy(asset);
    case SPL, SPL_2022, SPL_2022_BOND, SPL_2022_CONFIDENTIAL ->
                          solanaTokenService.deploy(asset);
    // ...
};
```

Der gewählte Standard bestimmt außerdem, welche Verwaltungsoperationen im Betreiberportal zur Verfügung stehen und welche Indexer-Ereignistypen abonniert werden.

---

## Den passenden Standard wählen

| Szenario | Empfohlener Standard | Grund |
|---|---|---|
| Einfacher Eigenkapital-/Utility-Token auf EVM | ERC-20 | Breiteste Wallet-Unterstützung |
| KYC-gebundenes Wertpapier auf EVM | ERC-3643 | Eingebaute Identitäts- und Compliance-Module |
| Anleihe mit mehreren Tranchen / Serien | ERC-3525 | Natives semi-fungibles Modell aus Slot + Wert |
| Fondsanteil mit täglicher NAV auf EVM | ERC-4626 | Standardisierte Vault-Schnittstelle |
| Institutioneller Fonds mit T+1/T+2-Rücknahme | ERC-7540 | Asynchrones Antrags-/Abrufmodell |
| Festverzinsliche Anleihe auf privatem DAML-Ledger | DAML_BOND_FIXED | Native Unterstützung für Kuponzahlungen |
| Wertpapier mit Privatsphäre auf EVM | CONF_ERC3643 | Zama fhEVM vertraulich + reguliert |
| Verzinsliche Anleihe auf Solana | SPL_2022_BOND | Token-2022-Erweiterung für Verzinsung |
