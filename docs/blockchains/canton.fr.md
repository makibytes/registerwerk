---
title: Canton / DAML Ledger
description: Ledger privé de Canton et intégration DAML Finance pour les instruments obligataires réglementés.
---

# Canton / DAML Ledger { #canton-daml-ledger }

Canton est un **ledger distribué axé sur la confidentialité** développé par Digital Asset. Contrairement aux blockchains publiques, Canton met en œuvre la **confidentialité par sous-transaction** : chaque participant ne voit que les contrats auxquels il est partie. Cela rend Canton attrayant pour les instruments institutionnels où les positions ne doivent pas être visibles pour les autres acteurs du marché.

---

## Concepts d'architecture de Canton { #canton-architecture-concepts }

| Concept | Canton | Correspondance dans Registerwerk |
|---|---|---|
| **Ledger** | Le ledger distribué de Canton | Un nœud participant au réseau Canton par opérateur |
| **Party** | Une identité cryptographique unique sur le ledger | `LegalEntity.cantonPartyId` |
| **Contrat** | Une instance de contrat DAML | Un par obligation ou position d'actif |
| **Choix** | Une action exerçable sur un contrat | OST (coupon, remboursement) |
| **Synchroniseur** | La composante consensus | Synchroniseur global Canton Network |
| **Ledger API** | API gRPC pour interagir avec Canton | `CantonLedgerEndpoint` |

---

## Types d'obligations DAML Finance { #daml-finance-bond-types }

Voir [DAML Obligations financières](../token-standards/canton-daml.md) pour le traitement complet de la configuration des modalités obligataires et des paiements de coupon.

---

## Configuration de la connexion { #connection-configuration }

Le `CantonLedgerEndpoint` se connecte à un nœud participant de Canton via son **Ledger API** (gRPC) :

```yaml
registerwerk:
  canton:
    mainnet:
      ledgerApiUrl: "participant.example.com:5001"
      synchronizerId: "global-synchronizer"
      applicationId: "registerwerk"
      authToken: "${CANTON_MAINNET_TOKEN}"  # JWT for participant auth
    devnet:
      ledgerApiUrl: "localhost:5001"
      synchronizerId: "dev-synchronizer"
```

Pour le Canton Network (Canton public) : obtenez un nœud participant auprès de l'opérateur du Canton Network, enregistrez votre application et fournissez l'URL de l'API Ledger.

Pour le développement : un bac à sable local de Canton est disponible via `docker compose -f indexer/canton/docker-compose.yml up`.

---

## Attribution de Party { #party-allocation }

Avant qu'un client puisse participer à des instruments basés sur Canton, il doit se voir attribuer une **Party** Canton. Ceci est géré par `CantonPartyAllocator.allocate(entityId)`:

1. Appelle l'API Ledger `PartyManagementService.allocateParty()`
2. Stocke l'identifiant de partie renvoyé dans `LegalEntity.cantonPartyId`
3. L'identifiant de partie est utilisé dans toutes les références de contrat DAML pour cette entité

Les parties sont immuables une fois attribuées ; une partie ne peut jamais être réutilisée pour une entité différente.

---

## Modèle de confidentialité { #privacy-model }

La confidentialité de Canton est appliquée au niveau du ledger :

- **L'émetteur** voit : tous les contrats relatifs à ses instruments
- **L'investisseur** voit : uniquement les contrats de ses propres positions
- **L'opérateur du registre** voit : tous les contrats (en tant qu'observateur DAML)
- **Les autres investisseurs** : ne peuvent pas voir les positions des autres investisseurs

Il s'agit d'une confidentialité native sans chiffrement — l'infrastructure du ledger garantit que les données contractuelles ne sont transmises qu'aux parties prenantes de ce contrat.

---

## Le profil Maven `-Pcanton` { #the-pcanton-maven-profile }

Le SDK DAML et les JAR associés étant volumineux et absents de Maven Central, le support de Canton est protégé derrière le profil `-Pcanton` :

```bash
./mvnw verify -Pcanton          # includes Canton
./mvnw verify                   # Canton disabled, stub injected
```

En l’absence de `-Pcanton`, `CantonBondDisabledStub` est utilisé. Les appels API vers les instruments basés sur Canton renvoient `503 Service Unavailable` avec un message expliquant que la prise en charge de Canton nécessite le profil `-Pcanton` et un nœud participant en cours d'exécution.

---

## Indexeur { #indexer }

L'indexeur de Canton utilise le **service de transaction** de l'API Ledger pour diffuser toutes les transactions validées. Il traite :
- Les contrats d'émission d'obligations → crée des enregistrements `AssetHolder`
- Les événements de paiement de coupon → crée des enregistrements `token_transfer` de type `COUPON`
- Les événements de transfert → met à jour `AssetHolder.nominalAmount`

La disponibilité de l'indexeur Canton est surveillée par `IndexerMonitorService`, comme pour les indexeurs EVM et Solana.
