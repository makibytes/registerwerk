---
title: Normes de jetons
description: Les implémentations de normes de jetons représentées dans Registerwerk, avec comparaison, cas d'usage et état d'avancement.
---

# Normes de jetons

Registerwerk contient des implémentations, des intégrations ou des espaces réservés pour des normes de jetons sur les chaînes EVM, Solana, StarkNet, Stellar et Canton. La présence dans ce tableau n'établit ni la maturité pour la production ni l'adéquation à un instrument financier.

---

## Comparaison de référence

| Norme | Chaîne | Type | Cas d'usage | Variante confidentielle | État |
|---|---|---|---|---|---|
| [ERC-20](erc20.md) | EVM | Fongible | Capital, utilitaire | CONF_ERC20 | Implémentation présente ; maturité non vérifiée |
| [ERC-721](erc721.md) | EVM | Non fongible | Certificats uniques, obligations NFT | — | Implémentation présente ; maturité non vérifiée |
| [ERC-1155](erc1155.md) | EVM | Multi-jeton | Émission par lots | — | Implémentation présente ; maturité non vérifiée |
| [ERC-3525](erc3525.md) | EVM | Semi-fongible | Obligations à tranches, séries de fonds | STARKNET_ERC3525 | Implémentation présente ; maturité non vérifiée |
| [ERC-3643](erc3643.md) | EVM | Fongible + identité | Titres réglementés, accès restreint | CONF_ERC3643 | Implémentation présente ; maturité non vérifiée |
| [ERC-4626](erc4626.md) | EVM | Coffre (synchrone) | Fonds monétaires, VL quotidienne | — | Implémentation présente ; modèle économique/maturité non vérifiés |
| [ERC-7540](erc7540.md) | EVM | Coffre (asynchrone) | Fonds institutionnels, T+1/T+2 | — | Implémentation présente ; modèle économique/maturité non vérifiés |
| [DAML BOND FIXED](canton-daml.md) | Canton | Obligation | Obligations à taux fixe sur registre privé | — | Implémentation optionnelle (`-Pcanton`) ; maturité non vérifiée |
| [DAML BOND FLOATING](canton-daml.md) | Canton | Obligation | Obligations à taux variable | — | Implémentation optionnelle (`-Pcanton`) ; maturité non vérifiée |
| [DAML BOND ZERO](canton-daml.md) | Canton | Obligation | Obligations à coupon zéro | — | Implémentation optionnelle (`-Pcanton`) ; maturité non vérifiée |
| [CANTON_TOKEN](canton-daml.md) | Canton | Générique | Actif numérique fondé sur DAML | — | Implémentation optionnelle (`-Pcanton`) ; maturité non vérifiée |
| [SPL](../blockchains/solana.md) | Solana | Fongible | Jetons natifs Solana | — | Intégration présente ; maturité non vérifiée |
| [SPL_2022](spl-2022.md) | Solana | Fongible + ext. | Jetons Solana étendus | — | Intégration présente ; maturité non vérifiée |
| [SPL_2022_BOND](spl-2022.md) | Solana | Fongible + intérêts | Obligations productives d'intérêts sur Solana | — | Intégration présente ; maturité non vérifiée |
| [SPL_2022_CONFIDENTIAL](spl-2022.md) | Solana | Confidentiel | Jeton Solana confidentiel | — | Intégration présente ; maturité non vérifiée |
| [STARKNET_ERC20](../blockchains/starknet-stellar.md) | StarkNet | Fongible | Équivalent Cairo d'ERC-20 | — | ⚠️ Espace réservé |
| [STARKNET_ERC3525](../blockchains/starknet-stellar.md) | StarkNet | Semi-fongible | Équivalent Cairo d'ERC-3525 | — | ⚠️ Espace réservé |
| [STELLAR_ASSET](../blockchains/starknet-stellar.md) | Stellar | Actif | Actif émis nativement sur Stellar | — | ⚠️ Espace réservé |
| [CONF_ERC20](confidential.md) | Fhenix / Inco | Fongible confidentiel | Capital préservant la confidentialité | — | Implémentation présente ; maturité non vérifiée |
| [CONF_ERC3643](confidential.md) | Fhenix / Inco | Réglementé confidentiel | Titre réglementé préservant la confidentialité | — | Implémentation présente ; maturité non vérifiée |

---

## Comment l'énumération `TokenStandard` pilote le déploiement

L'énumération `TokenStandard` du module `deployment` est l'aiguillage central déterminant quel service de déploiement est invoqué :

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

La norme retenue détermine aussi quelles opérations d'administration sont disponibles dans le portail opérateur et quels types d'événements l'indexeur souscrit.

---

## Choisir la bonne norme

| Scénario | Norme recommandée | Raison |
|---|---|---|
| Jeton de capital / utilitaire simple sur EVM | ERC-20 | Prise en charge la plus large par les portefeuilles |
| Titre soumis au KYC sur EVM | ERC-3643 | Modules d'identité et de conformité intégrés |
| Obligation à plusieurs tranches / séries | ERC-3525 | Modèle semi-fongible natif slot + valeur |
| Part de fonds à VL quotidienne sur EVM | ERC-4626 | Interface de coffre normalisée |
| Fonds institutionnel à rachat T+1/T+2 | ERC-7540 | Modèle asynchrone demande/retrait |
| Obligation à taux fixe sur registre DAML privé | DAML_BOND_FIXED | Prise en charge native du versement des coupons |
| Titre préservant la confidentialité sur EVM | CONF_ERC3643 | Zama fhEVM confidentiel + réglementé |
| Obligation productive d'intérêts sur Solana | SPL_2022_BOND | Extension Token-2022 productive d'intérêts |
