---
title: EVM confidentielle (Zama fhEVM)
description: Quelles chaînes gèrent réellement les contrats confidentiels de Registerwerk et de quelle infrastructure elles ont besoin.
---

# EVM confidentielle (Zama fhEVM) { #confidential-evm-zama-fhevm }

Les contrats confidentiels de Registerwerk (`ConfidentialERC20`, `ConfidentialERC3643`) sont construits
sur le fhEVM de **Zama** — plus précisément l'API `TFHE.sol`/Gateway vendorisée sous
`contracts/lib/fhevm` (le sous-module `zama-ai/fhevm-solidity`) côté contrat, et le véritable
package `@zama-fhe/relayer-sdk` à la fois côté backend (side-car `zama-relayer`) et côté
navigateur (`frontend-customer`/`frontend-operator`).

---

## Chaînes qui exécutent réellement le fhEVM de Zama { #chains-that-actually-run-zamas-fhevm }

| Chaîne | Statut | Source |
|---|---|---|
| Ethereum Sepolia | Adresses réelles et documentées (ACL, TFHEExecutor, FHEPayment, KMSVerifier, Gateway) — voir `contracts/lib/fhevm/config/ZamaFHEVMConfig.sol`, et `SepoliaConfig` embarquée dans `@zama-fhe/relayer-sdk` lui-même | Bibliothèque vendorisée / package npm |
| Ethereum mainnet | Ciblé, adresses non finalisées au moment de la rédaction (objectif T3 2026) et évolutives par gouvernance une fois en ligne | Feuille de route publique de Zama / forum communautaire |
| Base | L'annonce du « fhEVM Coprocessor » de Zama cite Base aux côtés d'Ethereum | Annonce produit de Zama |
| T-REX Chain | Zama a annoncé (mars 2026) qu'elle deviendrait la couche de confidentialité de T-REX Ledger — directement pertinent pour `CONF_ERC3643` — mais T-REX Chain n'a pas encore d'entrée dans l'énumération `Chain` ici et n'a pas publié ses propres adresses FHEVM | Communiqué de presse public T-REX/Zama |

`AssetDeploymentService.FHEVM_CHAINS` limite le déploiement confidentiel à `Chain.ETHEREUM` et
`Chain.BASE` précisément pour cette raison. **Fhenix et Inco sont délibérément exclues** — elles restent
répertoriées comme chaînes EVM ordinaires dans l'énumération `Chain` (avec leurs propres nœuds RPC à des fins
d'information/de suivi) mais ne sont pas des cibles valides de déploiement confidentiel.

---

## Configuration de l'infrastructure { #configuring-the-infrastructure }

Chaque adresse de contrat hôte FHEVM est injectée, jamais codée en dur par chaîne :

```java
// ConfidentialERC20.FhevmInfra — passed to the constructor via EwpgConfidentialFactory
struct FhevmInfra {
    address aclAddress;
    address tfheExecutorAddress;
    address fhePaymentAddress;
    address kmsVerifierAddress;
    address gatewayAddress;
}
```

1. Déployez `EwpgConfidentialFactory` (ou réutilisez-en une) sur la chaîne cible, en appelant `setFhevmInfra`
   avec les adresses Zama réelles de cette chaîne.
2. Réglez `registerwerk.contracts.confidential-factory.<chain-identifier>` sur l'adresse de la factory.
3. Pour `CONF_ERC3643`, définissez `registerwerk.contracts.confidential-identity-registry.<chain-identifier>`
   sur un `IdentityRegistry` T-REX réel et provisionné — requis ; la factory annule le déploiement si
   il n'est pas défini plutôt que de déployer silencieusement avec un registre d'identité à l'adresse zéro.
4. Définissez `registerwerk.contracts.confidential-operator-viewer.<chain-identifier>` et
   `.confidential-auditor-viewer.<chain-identifier>` sur les adresses dédiées « viewer » (déchiffrement
   uniquement) de l'opérateur et d'un auditeur — voir le modèle d'ACL viewer ci-dessous. Elles sont
   transmises comme `initialViewers` au déploiement, de sorte que chaque jeton confidentiel de cette
   chaîne leur accorde l'accès dès le premier bloc.

---

## Qui peut déchiffrer — le modèle d'ACL viewer { #who-can-decrypt-the-viewer-acl-model }

Voir [Jetons confidentiels](../token-standards/confidential.md#who-can-decrypt-what-the-viewer-acl-model)
pour l'explication complète. En bref : chaque détenteur ne peut déchiffrer que son PROPRE handle de solde ;
un petit ensemble de « viewers » opérateur/auditeur/émetteur peut déchiffrer n'importe quel handle. Ceci
vit entièrement dans `isViewer`/`addViewer`/`removeViewer` de `ConfidentialERC20` — pas de contrats
distincts par investisseur.

---

## Déchiffrement — trois chemins, tous réels { #decryption-three-paths-all-real }

- **Déchiffrement par l'utilisateur** (un détenteur qui révèle son propre solde, ou un viewer qui révèle
  n'importe quel solde) : entièrement côté client. Le wallet connecté signe la charge utile EIP-712
  `UserDecryptRequestVerification` du KMS, et l'instance `@zama-fhe/relayer-sdk` propre au navigateur
  exécute directement `userDecrypt` auprès du relayer de Zama — voir `FheClientService` dans
  `frontend-customer`/`frontend-operator`. Le backend ne voit jamais la valeur en clair sur ce chemin.
- **Déchiffrement opérateur sans interface** (rapports/rapprochement, aucun navigateur dans la boucle) :
  le side-car `zama-relayer` du backend détient une clé dédiée au déchiffrement uniquement
  (`OPERATOR_DECRYPT_PRIVATE_KEY` — délibérément PAS un wallet de signature de transactions on-chain)
  et signe lui-même la même requête EIP-712, puis exécute `userDecrypt` en un aller-retour. Voir
  `ConfidentialBalanceReconciliationService` et `ZamaRelayerClient.requestOperatorDecrypt`.
- **Déchiffrement public/oracle** (`ConfidentialERC20.requestSupplyDisclosure`) : le contrat lui-même
  demande à la Gateway de déchiffrer une valeur (par ex. l'offre totale) et reçoit le texte en clair
  via un callback signé. L'implémentation est présente dans le dépôt et des tests Foundry existent,
  mais l'intégration avec un coprocesseur en production et la maturité pour la production restent
  non vérifiées.

`zama-relayer` (racine du dépôt `zama-relayer/`) est le side-car propre à Registerwerk qui encapsule le
véritable build Node de `@zama-fhe/relayer-sdk` — il n'existe que parce que Zama ne publie aucun client
Java/JVM ; chaque flux initié par le navigateur ci-dessus parle directement à Zama et ne passe jamais
par ce side-car. Activez-le avec `docker compose --profile confidential up` ; voir les commentaires du
code source de `zama-relayer` et la section « Confidential tokens » de `.env.example` pour la
configuration.

Voir [Jetons confidentiels](../token-standards/confidential.md) pour la matrice de statut complète et
[Transfert confidentiel SPL-2022](../token-standards/spl-2022.md) pour l'équivalent Solana, non lié,
basé sur ElGamal — les deux se confondent facilement mais utilisent une cryptographie différente et
n'ont aucun code en commun.
