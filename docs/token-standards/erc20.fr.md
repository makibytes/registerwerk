---
title: ERC-20 — Jeton fongible
description: Implémentation standard ERC-20 pour les jetons de sécurité d'équité, d'utilité et simples fongibles.
---

# ERC-20 — Jeton fongible { #erc-20-fungible-token }

ERC-20 est la norme fondamentale de jeton fongible pour les chaînes EVM. Chaque unité est identique et interchangeable. Registerwerk déploie les jetons ERC-20 pour les instruments de capitaux propres, les instruments de dette simples et les jetons utilitaires pour lesquels le contrôle KYC au niveau du contrat n'est pas requis (la conformité est appliquée au niveau de la couche de registre).

---

## Quand utiliser ERC-20 { #when-to-use-erc-20 }

- **Jetons d'actions** — actions d'une société non cotée où les restrictions de transfert sont gérées hors chaîne
- **Obligations simples** — lorsque l'émetteur n'a pas besoin d'appliquer les restrictions de transfert en chaîne
- **Jetons utilitaires** — pour les crédits internes à la plateforme ou les jetons d'incitation
- **Émissions de test** — ERC-20 est le chemin de déploiement le plus simple pour les nouveaux émetteurs qui apprennent la plateforme

Pour les titres réglementés nécessitant un contrôle KYC en chaîne, pensez à [ERC-3643](erc3643.md). Pour les obligations comportant plusieurs tranches, envisagez [ERC-3525](erc3525.md).

---

## Registerwerk ERC-20 extensions { #registerwerk-erc-20-extensions }

Registerwerk déploie un contrat `EwpgERC20` personnalisé qui prolonge la norme ERC-20 avec :

| Extension | Objectif |
|---|---|
| `mintWithCap` | Respecte le `MintControlRule.maxSupply` configuré par l'opérateur |
| `pause` / `unpause` | Disjoncteur d'urgence pour l'opérateur de registre |
| `freeze(address)` | Gel de la couche de registre (mappé sur `HolderBlock` dans la base de données) |
| `setIsin(string)` | Stocke le ISIN en chaîne pour les références croisées |
| `setRegistryRef(string)` | Stocke l'ID d'actif Registerwerk à des fins d'audit |

---

## Flux de déploiement { #deployment-flow }

1. L'opérateur sélectionne `TokenStandard.ERC20` lors de la création d'un `Asset`
2. Après l'approbation de KYC et (éventuellement) une authentification renforcée, appelle `POST /api/v1/assets/{id}/deploy`
3. `Erc20DeploymentService` construit et diffuse la transaction de déploiement
4. À la confirmation de réception, `AssetDeployment` est créé avec `contractAddress` et `deploymentTxHash`
5. `Asset.status` passe à `ISSUED`

---

## Opérations d'administration en chaîne { #on-chain-admin-operations }

| Opération | Point de terminaison | Nécessite |
|---|---|---|
| Émettre des jetons (mint) | `POST /api/v1/assets/{id}/mint` | REGISTRY_ADMIN + step-up (si plafond d'approvisionnement géré) |
| Détruire des jetons (burn) | `POST /api/v1/assets/{id}/burn` | REGISTRY_ADMIN + step-up + 4 yeux |
| Transfert forcé | `POST /api/v1/assets/{id}/force-transfer` | REGISTRY_ADMIN + step-up + 4 yeux |
| Geler l'adresse | `POST /api/v1/assets/{id}/freeze/{address}` | REGISTRY_ADMIN + HolderBlock actif |
| Suspendre le contrat | `POST /api/v1/assets/{id}/pause` | REGISTRY_ADMIN + step-up |

---

## Variante confidentielle { #confidential-variant }

`CONF_ERC20` déploie une variante confidentielle [Zama fhEVM](confidential.md) sur les réseaux Fhenix ou Inco, où les soldes et les montants des transferts sont chiffrés à l'aide du chiffrement entièrement homomorphe (FHE). Utilisez cette variante lorsque l'émetteur exige la confidentialité des positions des investisseurs.
