---
title: Jetons confidentiels (Zama fhEVM)
---

# Configuration des jetons confidentiels (Zama fhEVM)

Ce guide couvre le déploiement et l'administration des jetons confidentiels ERC-20/ERC-3643 à l'aide de
fhEVM.

## Conditions préalables

1. Une chaîne avec une **véritable infrastructure Zama fhEVM** — Ethereum Sepolia aujourd'hui (les adresses documentées
sont vendues dans `contracts/lib/fhevm/config/` et regroupées dans `@zama-fhe/relayer-sdk` sous le nom de
`SepoliaConfig`), ou le réseau principal Ethereum/Base une fois que Zama y publie les adresses finales.
Le déploiement confidentiel est limité à `Chain.ETHEREUM`/`Chain.BASE` — **pas** Fhenix/Inco.
2. `EwpgConfidentialFactory` déployé et configuré avec les adresses FHEVM réelles de cette chaîne
(`setFhevmInfra`) — voir `docs/blockchains/confidential-evm.md` dans le référentiel.
3. Pour `CONF_ERC3643` uniquement : un véritable T-REX `IdentityRegistry` provisionné pour les actifs confidentiels sur
cette chaîne, configuré via `registerwerk.contracts.confidential-identity-registry.<chain>`. Le déploiement échoue bruyamment s'il n'est pas défini.
4. Les adresses viewer (déchiffrement uniquement) dédiées de l'opérateur et d'un auditeur, configurées via
`registerwerk.contracts.confidential-operator-viewer.<chain>` /
`.confidential-auditor-viewer.<chain>` — celles-ci deviennent des viewers sur chaque jeton confidentiel
déployé sur cette chaîne à partir du bloc un.
5. `zama-relayer` en cours d'exécution (`docker compose --profile confidential up`) avec
`OPERATOR_DECRYPT_PRIVATE_KEY` défini sur la clé privée correspondant à l'adresse operator-viewer
ci-dessus et le `registerwerk.zama.relayer-url` du backend pointé dessus.

## Déploiement

Flux de déploiement d'actifs standard, identique à toute autre norme :

```bash
curl -X POST http://localhost:48080/api/v1/assets/{assetId}/deploy \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  -d '{ "chain": "ETHEREUM", "network": "TESTNET" }'
```

Le backend achemine `CONF_ERC20`/`CONF_ERC3643` vers `ConfidentialErc20Service`/
`ConfidentialErc3643Service`, qui appellent `EwpgConfidentialFactory.deployConfidentialErc20`/
`deployConfidentialErc3643` — de vraies transactions Web3j, en transmettant les adresses viewer configurées de l'opérateur/auditeur comme `initialViewers`.

## Actions de l'opérateur disponibles aujourd'hui

| Actions | Point de terminaison | Remarques |
|---|---|---|
| Émission confidentielle (mint) (émission par l'émetteur/l'opérateur) | `POST /api/v1/assets/{id}/deployments/{depId}/issuer/mint-confidential` | Chiffre le montant côté serveur via le side-car `zama-relayer` — aucun navigateur/portefeuille requis |
| Destruction forcée confidentielle (burning) (§26 Einziehung) | `POST .../admin/force-burn-confidential` | Même chemin de chiffrement côté serveur ; déjà soumis à un contrôle agent/propriétaire — ce contrôle EST l'autorité de destruction forcée |
| Ajouter un viewer confidentiel | `POST .../admin/confidential-add-viewer` | Accorde des droits de déchiffrement sur le solde de chaque titulaire à l'avenir — par ex. ajout d'un auditeur ou du propre portefeuille de l'émetteur après le déploiement |
| Supprimer un viewer confidentiel | `POST .../admin/confidential-remove-viewer` | Arrête les octrois futurs — ne révoque pas rétroactivement les handles historiques déjà déchiffrables (l'ACL de Zama n'a pas de primitive de révocation) |
| Réconciliation entre registre et chaîne | `GET /api/v1/assets/{id}/confidential-reconciliation` | Sans interface (headless) : déchiffre le solde en chaîne de chaque détenteur via la propre clé de déchiffrement de l'opérateur du backend et le compare au texte en clair `nominalAmount` du registre. Rôle `REGISTRY_ADMIN` ou `AUDIT`. |
| Révéler + réconcilier via votre propre portefeuille | Portail de l'opérateur → Actif → onglet **Soldes confidentiels** | Connectez un portefeuille viewer dans le navigateur et déchiffrez directement avec le relais de Zama — une vérification croisée indépendante de la réconciliation sans interface (headless) ci-dessus |
| Divulgation publique/par oracle de l'offre (supply) | `ConfidentialERC20.requestSupplyDisclosure()` (appel en chaîne ; aucun point de terminaison API de l'opérateur ne l'encapsule encore) | Pour une divulgation globale déclenchée par le régulateur, pas le solde d'un détenteur spécifique |

Le gel/pause/transfert forcé sur `CONF_ERC3643` ne sont **pas encore câblés** via l'opérateur API —
le contrôleur administrateur ERC-3643 existant cible le texte en clair ABI du contrat `EwpgERC3643`, qui
ne correspond pas aux signatures de montant crypté de `ConfidentialERC3643`.

## Le side-car de relais

`zama-relayer` (racine du dépôt `zama-relayer/`) est le propre service de Registerwerk qui encapsule le véritable
`@zama-fhe/relayer-sdk` — construit et livré dans ce monorepo, pas quelque chose que vous devez écrire.
Zama ne publie aucun client Java/JVM, ce qui est la seule raison pour laquelle ce side-car existe ; chaque action confidentielle
initiée par le navigateur (investisseur/émetteur/auditeur révélant un solde, transfert confidentiel
d'un investisseur) communique avec le relais de Zama directement depuis le navigateur et ne touche jamais ce side-car. Activez-le avec :

```bash
docker compose --profile confidential up
```

Voir sa section `.env.example` (« Jetons confidentiels (Zama fhEVM) ») pour les variables
d'environnement — `ZAMA_CONFIG_PRESET=sepolia`, `ZAMA_OPERATOR_DECRYPT_PRIVATE_KEY` et
`REGISTERWERK_ZAMA_RELAYER_URL` côté backend.

## Décryptage du solde investisseur/émetteur/auditeur

La révélation d'un solde confidentiel (ou le cryptage d'un montant de transfert confidentiel) est une opération **côté client**
dans les deux frontaux : le portefeuille connecté signe une requête EIP-712 et la propre instance
`@zama-fhe/relayer-sdk` du navigateur communique directement avec le relais de Zama — voir `FheClientService` dans
`frontend-customer` (auto-révélation de l'investisseur + transfert confidentiel ; divulgation par l'émetteur de tous les détenteurs) et
`frontend-operator` (opérateur/auditeur `ConfidentialViewerPanelComponent`). Rien de tout cela ne route
via ce backend.
