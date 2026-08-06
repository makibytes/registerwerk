---
title: Jetons confidentiels (Zama fhEVM)
description: Jetons ERC-20 et ERC-3643 préservant la confidentialité à l'aide du chiffrement entièrement homomorphe de Zama — le cycle de vie complet de chiffrement/déchiffrement, de bout en bout.
---

# Jetons confidentiels (Zama fhEVM) { #confidential-tokens-zama-fhevm }

Les jetons confidentiels utilisent le **chiffrement entièrement homomorphe (FHE)** pour soustraire
les soldes de jetons et les montants transférés à la vue du public, tout en préservant les
capacités de conformité et d'audit exigées par les régulateurs.

!!! warning "Registerwerk EST le client"
    Des versions antérieures de cette page décrivaient le cycle de vie de chiffrement/déchiffrement
    comme « le problème de quelqu'un d'autre » — le travail du navigateur, un service compagnon que
    vous deviez fournir vous-même. Ce cadrage était erroné : les seules parties autorisées à
    déchiffrer des soldes confidentiels — émetteurs, investisseurs, opérateur du registre et
    auditeur — agissent toutes *via* Registerwerk. Construire l'intégration complète de
    `@zama-fhe/relayer-sdk` relève donc de la responsabilité propre de Registerwerk, et c'est
    désormais chose faite : contrats, un side-car relayer intégré au dépôt, des services backend, et
    une intégration navigateur dans les deux frontends. Voir la [matrice de statut](#status)
    ci-dessous pour savoir précisément ce qui est réel et ce qui nécessite encore un réseau réel
    pour être exercé.

---

## Normes confidentielles prises en charge { #supported-confidential-standards }

| Norme | Basée sur | État chiffré |
|---|---|---|
| `CONF_ERC20` | Jeton fongible confidentiel ERC-7984 | Soldes/allocations `euint64` |
| `CONF_ERC3643` | ERC-3643 (T-REX) + ERC-7984 | Soldes `euint64` + identité/conformité en clair |

Contrats : `contracts/src/confidential/ConfidentialERC20.sol` / `ConfidentialERC3643.sol`,
déployés via `contracts/src/factory/EwpgConfidentialFactory.sol`.

---

## Quelles chaînes font réellement tourner ceci { #which-chains-actually-run-this }

Le coprocesseur fhEVM de Zama tourne sur **Ethereum et Base** (d'après l'annonce produit « fhEVM
Coprocessor » de Zama elle-même) — plus **Sepolia aujourd'hui**, en tant que testnet entièrement
configuré (les véritables adresses ACL/Executor/Payment/KMSVerifier/Gateway sont vendorisées dans
`contracts/lib/fhevm/config/`, et ces mêmes adresses Sepolia réelles sont incluses dans
`@zama-fhe/relayer-sdk` sous le nom `SepoliaConfig`). Les adresses **mainnet** Ethereum propres à
Zama étaient encore en cours de finalisation au moment de la rédaction (objectif T3 2026), et
resteront évolutives par gouvernance même une fois en ligne.

**Fhenix et Inco ne sont PAS des chaînes fhEVM de Zama.** Elles font tourner leurs propres piles FHE
séparées et incompatibles. `ConfidentialERC20`/`ConfidentialERC3643` sont construits spécifiquement
contre l'API `TFHE.sol`/Gateway de Zama et ne fonctionneront sur aucune des deux.

**T-REX Chain** : T-REX Network a annoncé en mars 2026 que Zama devient la couche de confidentialité
du T-REX Ledger — directement pertinent pour `CONF_ERC3643`, qui combine déjà l'identité/conformité
T-REX avec les soldes FHE de Zama. T-REX Chain n'est pas encore représentée comme sa propre valeur
d'énumération `Chain` dans ce backend, et n'a pas (encore, publiquement) partagé ses propres
adresses d'infrastructure FHEVM. Confirmez-les avant de vous appuyer sur cet appariement en
production.

Les adresses d'infrastructure FHEVM ne sont jamais codées en dur par réseau dans les contrats —
elles sont injectées au moment de la construction/configuration de la factory
(`ConfidentialERC20.FhevmInfra`, `EwpgConfidentialFactory.setFhevmInfra`), précisément pour qu'un
nouveau réseau (mainnet, T-REX Chain) puisse être ciblé en configurant de vraies adresses, et non
par un redéploiement de contrat.

---

## Qui peut déchiffrer quoi — le modèle d'ACL viewer { #who-can-decrypt-what-the-viewer-acl-model }

Les autorisations ACL de Zama sont additives et propres à chaque handle de texte chiffré : une fois
qu'une adresse est `allow`ée sur un handle, cette autorisation est permanente pour ce handle précis
(il n'existe pas de révocation — voir le commentaire de documentation de
`ConfidentialERC20.removeViewer`). Registerwerk utilise cette primitive pour obtenir exactement
l'isolation requise par la plateforme, au sein d'un **contrat confidentiel unique par actif** (pas
un contrat par investisseur — voir ci-dessous) :

- **Chaque détenteur ne se voit accorder des droits de déchiffrement que sur son PROPRE handle de
  solde**, à chaque mutation (mint/transfert/burn). Un investisseur ne peut jamais déchiffrer le
  solde d'un autre investisseur, car il n'est jamais `allow`é sur ce handle-là.
- **Un petit ensemble de « viewers »** — l'opérateur du registre, un auditeur et (ajouté après
  déploiement via `addViewer`) le wallet propre de l'émetteur si souhaité — se voit accorder des
  droits de déchiffrement sur **tous** les handles (soldes et offre totale), ce qui satisfait
  l'exigence : « l'opérateur doit pouvoir déchiffrer tous les montants de tous les investisseurs, et
  le rôle auditeur doit pouvoir déchiffrer les montants ».
- Les viewers sont provisionnés comme `initialViewers` au déploiement
  (`EwpgConfidentialFactory.deployConfidentialErc20/deployConfidentialErc3643`, sourcés depuis
  `registerwerk.contracts.confidential-operator-viewer.*` / `.confidential-auditor-viewer.*`), ou
  ajoutés/retirés plus tard via `TokenAdminService.confidentialAddViewer`/`confidentialRemoveViewer`
  (`POST .../admin/confidential-add-viewer` / `-remove-viewer`).

Pourquoi un seul contrat avec une ACL plutôt qu'un contrat par investisseur : garantie d'isolation
identique, à un coût normal de déploiement/gas, sans la complexité d'un rapprochement de l'offre
par investisseur.

---

## Ce que les contrats font réellement { #what-the-contracts-actually-do }

- `confidentialTransfer` / `confidentialTransferFrom` / `confidentialApprove` — transfert/allocation
  chiffrés ERC-7984, avec une sémantique d'échec silencieux basée sur `TFHE.select` en cas de solde
  insuffisant (conforme à la convention ERC-7984, ce n'est pas un bug).
- `confidentialMint` / `confidentialBurn` — restreints au owner/agent, accordant le jeu de viewers
  (ci-dessus) sur chaque handle muté. Sur `ConfidentialERC3643`, `confidentialBurn` est aussi la
  primitive d'annulation obligatoire (eWpG §26 Einziehung) pour les montants chiffrés.
- `ConfidentialERC3643` applique en outre la vérification d'identité T-REX, le gel, la pause, et un
  module `IConfidentialCompliance` enfichable avant tout transfert.
- `requestSupplyDisclosure` / `callbackSupplyDisclosure` — le chemin de **déchiffrement
  public/oracle** : le contrat lui-même demande à la Gateway de Zama de déchiffrer l'offre totale et
  reçoit le texte en clair via un callback signé, pour une divulgation déclenchée par le régulateur
  — distinct d'un détenteur/viewer déchiffrant son propre solde ou celui d'un autre via le Relayer
  (ci-dessous).

---

## Le cycle de vie chiffrement/déchiffrement — qui fait quoi { #status }

| Acteur | Action | Comment | Statut |
|---|---|---|---|
| Investisseur | Révéler son propre solde | Navigateur : `FheClientService.userDecrypt` (le wallet connecté signe la requête EIP-712 du KMS, déchiffre directement auprès du relayer de Zama) | ✅ Réel — `frontend-customer` |
| Investisseur | Transfert confidentiel | Navigateur : `FheClientService.encrypt64` côté client, puis le wallet soumet `confidentialTransfer` | ✅ Réel — `frontend-customer` |
| Émetteur | Émission confidentielle (mint) | Le backend chiffre côté serveur (aucun navigateur dans ce flux) via le side-car `zama-relayer`, puis soumet | ✅ Réel — `TokenAdminService.confidentialMint`, `POST .../issuer/mint-confidential` |
| Émetteur | Révéler le solde de n'importe quel détenteur | Navigateur, en tant que viewer enregistré (même chemin `FheClientService.userDecrypt`) | ✅ Réel — panneau des soldes confidentiels émetteur de `frontend-customer` |
| Opérateur | Déchiffrement sans interface pour rapports/rapprochement | Clé de déchiffrement dédiée de l'opérateur côté backend via `zama-relayer`, sans wallet | ✅ Réel — `ConfidentialBalanceReconciliationService`, `GET .../confidential-reconciliation` |
| Opérateur / Auditeur | Révéler + rapprocher via son propre wallet | Navigateur : onglet Soldes confidentiels de `frontend-operator` (`ConfidentialViewerPanelComponent`) | ✅ Réel |
| Opérateur | Destruction forcée confidentielle / force-burn (§26 Einziehung) | Le backend chiffre côté serveur via `zama-relayer`, puis soumet | ✅ Réel — `TokenAdminService.confidentialForceBurn`, `POST .../force-burn-confidential` |
| Régulateur | Divulgation publique/oracle de l'offre totale | On-chain : `requestSupplyDisclosure`/`callbackSupplyDisclosure` | ✅ Réel, testé avec Foundry |
| Gel/pause/transfert forcé ERC-3643 confidentiel via l'API opérateur | — | `Erc3643Controller` cible l'ABI en clair d'`EwpgERC3643` ; l'appeler contre `ConfidentialERC3643` envoie un calldata non concordant | ❌ Non câblé — seul le force-burn dispose aujourd'hui d'un chemin propre au confidentiel |
| Rail de paiement confidentiel (montants stablecoin chiffrés dans la jambe cash de la DvP) | — | — | ❌ Non construit |

**Ce qui n'est réellement pas vérifié ici** : ce bac à sable ne dispose pas de Docker/Kong en
direct ni d'un compte Sepolia approvisionné pour soumettre de vraies transactions ; l'aller-retour
soumission on-chain → minage → déchiffrement n'a donc pas été exécuté de bout en bout dans cet
environnement. Ce qui **a** été vérifié contre la véritable infrastructure Sepolia en direct de
Zama pendant le développement : le point de terminaison `/v1/encrypt-input` de `zama-relayer` a
produit un véritable handle de texte chiffré et une preuve d'entrée ZK à partir d'une connexion
`createInstance` en direct au relayer réel de Zama (`https://relayer.testnet.zama.org`) et à un RPC
Sepolia public — pas une simulation. Chaque composant ici est construit, testé en tests
unitaires/Foundry, et (là où c'était vérifiable) vérifié en réseau réel au niveau de chaque appel
individuel ; seul l'aller-retour transactionnel complet en plusieurs étapes nécessite un compte
approvisionné et un actif déployé pour être mené à bien.

---

## Déployer un actif confidentiel { #deploying-a-confidential-asset }

1. Déployez `EwpgConfidentialFactory` sur une chaîne avec de vraies adresses Zama FHEVM configurées
   (Sepolia aujourd'hui), ou configurez une factory existante via `setFhevmInfra`.
2. Pour `CONF_ERC3643`, provisionnez un `IdentityRegistry` T-REX partagé pour les actifs
   confidentiels sur cette chaîne et définissez
   `registerwerk.contracts.confidential-identity-registry.<chain>` — déployer avec un registre
   d'identité non configuré/à l'adresse zéro échoue bruyamment (`EwpgConfidentialFactory` annule la
   transaction).
3. Définissez `registerwerk.contracts.confidential-factory.<chain>` sur l'adresse de la factory
   déployée, et `registerwerk.contracts.confidential-operator-viewer.<chain>` /
   `.confidential-auditor-viewer.<chain>` sur les adresses de viewer dédiées au déchiffrement
   uniquement, de l'opérateur/de l'auditeur (voir [EVM confidentielle](../blockchains/confidential-evm.md)).
4. Déployez `zama-relayer` (`docker compose --profile confidential up`) avec
   `OPERATOR_DECRYPT_PRIVATE_KEY` réglée sur la clé privée correspondant à l'adresse
   operator-viewer ci-dessus, et pointez le backend vers lui via `registerwerk.zama.relayer-url`.
5. Émettez l'actif en `CONF_ERC20`/`CONF_ERC3643` — le déploiement est limité aux chaînes réelles à
   coprocesseur Zama (`Chain.ETHEREUM`, `Chain.BASE`), pas Fhenix/Inco.

Voir [EVM confidentielle](../blockchains/confidential-evm.md) pour le détail de la configuration
par chaîne, et [Opérateur : jetons confidentiels](../operator/blockchain/confidential-tokens.md)
pour le flux de travail quotidien de l'opérateur.
