---
title: Standard di token
description: Le implementazioni degli standard di token rappresentate in Registerwerk, con confronto, casi d'uso e stato di realizzazione.
---

# Standard di token

Registerwerk contiene implementazioni, integrazioni o segnaposto per standard di token sulle chain EVM, Solana, StarkNet, Stellar e Canton. La presenza in questa tabella non stabilisce né la maturità per la produzione né l'idoneità a uno strumento finanziario.

---

## Confronto rapido

| Standard | Chain | Tipo | Caso d'uso | Variante confidenziale | Stato |
|---|---|---|---|---|---|
| [ERC-20](erc20.md) | EVM | Fungibile | Capitale, utility | CONF_ERC20 | Implementazione presente; maturità non verificata |
| [ERC-721](erc721.md) | EVM | Non fungibile | Certificati unici, obbligazioni NFT | — | Implementazione presente; maturità non verificata |
| [ERC-1155](erc1155.md) | EVM | Multi-token | Emissione in blocco | — | Implementazione presente; maturità non verificata |
| [ERC-3525](erc3525.md) | EVM | Semi-fungibile | Obbligazioni con tranche, serie di fondi | STARKNET_ERC3525 | Implementazione presente; maturità non verificata |
| [ERC-3643](erc3643.md) | EVM | Fungibile + identità | Strumenti regolamentati, ad accesso limitato | CONF_ERC3643 | Implementazione presente; maturità non verificata |
| [ERC-4626](erc4626.md) | EVM | Vault (sincrono) | Fondi monetari, NAV giornaliero | — | Implementazione presente; modello economico/maturità non verificati |
| [ERC-7540](erc7540.md) | EVM | Vault (asincrono) | Fondi istituzionali, T+1/T+2 | — | Implementazione presente; modello economico/maturità non verificati |
| [DAML BOND FIXED](canton-daml.md) | Canton | Obbligazione | Obbligazioni a tasso fisso su ledger privato | — | Implementazione opzionale (`-Pcanton`); maturità non verificata |
| [DAML BOND FLOATING](canton-daml.md) | Canton | Obbligazione | Obbligazioni a tasso variabile | — | Implementazione opzionale (`-Pcanton`); maturità non verificata |
| [DAML BOND ZERO](canton-daml.md) | Canton | Obbligazione | Obbligazioni zero coupon | — | Implementazione opzionale (`-Pcanton`); maturità non verificata |
| [CANTON_TOKEN](canton-daml.md) | Canton | Generico | Attività digitale basata su DAML | — | Implementazione opzionale (`-Pcanton`); maturità non verificata |
| [SPL](../blockchains/solana.md) | Solana | Fungibile | Token nativi Solana | — | Integrazione presente; maturità non verificata |
| [SPL_2022](spl-2022.md) | Solana | Fungibile + est. | Token Solana estesi | — | Integrazione presente; maturità non verificata |
| [SPL_2022_BOND](spl-2022.md) | Solana | Fungibile + interessi | Obbligazioni fruttifere su Solana | — | Integrazione presente; maturità non verificata |
| [SPL_2022_CONFIDENTIAL](spl-2022.md) | Solana | Confidenziale | Token Solana confidenziale | — | Integrazione presente; maturità non verificata |
| [STARKNET_ERC20](../blockchains/starknet-stellar.md) | StarkNet | Fungibile | Equivalente Cairo di ERC-20 | — | ⚠️ Segnaposto |
| [STARKNET_ERC3525](../blockchains/starknet-stellar.md) | StarkNet | Semi-fungibile | Equivalente Cairo di ERC-3525 | — | ⚠️ Segnaposto |
| [STELLAR_ASSET](../blockchains/starknet-stellar.md) | Stellar | Attività | Attività emessa nativamente su Stellar | — | ⚠️ Segnaposto |
| [CONF_ERC20](confidential.md) | Fhenix / Inco | Fungibile confidenziale | Capitale con tutela della riservatezza | — | Implementazione presente; maturità non verificata |
| [CONF_ERC3643](confidential.md) | Fhenix / Inco | Regolamentato confidenziale | Strumento regolamentato con tutela della riservatezza | — | Implementazione presente; maturità non verificata |

---

## Come l'enum `TokenStandard` guida la distribuzione

L'enum `TokenStandard` nel modulo `deployment` è lo switch centrale che determina quale servizio di distribuzione viene invocato:

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

Lo standard scelto determina anche quali operazioni di amministrazione sono disponibili nel portale operatore e a quali tipi di evento si sottoscrive l'indicizzatore.

---

## Scegliere lo standard giusto

| Scenario | Standard consigliato | Motivo |
|---|---|---|
| Token semplice di capitale / utility su EVM | ERC-20 | Supporto più ampio da parte dei wallet |
| Strumento soggetto a KYC su EVM | ERC-3643 | Moduli di identità e conformità integrati |
| Obbligazione con più tranche / serie | ERC-3525 | Modello semi-fungibile nativo slot + valore |
| Quota di fondo con NAV giornaliero su EVM | ERC-4626 | Interfaccia vault standardizzata |
| Fondo istituzionale con rimborso T+1/T+2 | ERC-7540 | Modello asincrono richiesta/riscossione |
| Obbligazione a tasso fisso su ledger DAML privato | DAML_BOND_FIXED | Supporto nativo al pagamento delle cedole |
| Strumento con tutela della riservatezza su EVM | CONF_ERC3643 | Zama fhEVM confidenziale + regolamentato |
| Obbligazione fruttifera su Solana | SPL_2022_BOND | Estensione Token-2022 fruttifera di interessi |
