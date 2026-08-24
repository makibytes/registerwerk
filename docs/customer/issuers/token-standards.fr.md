---
title: Choisir une norme de jeton
---

# Choisir une norme de jeton

Le registre eWpG prend en charge cinq normes de jetons. Cette page vous aide à comprendre les différences et à choisir celle qui convient à votre émission.

## ERC-20 — Jeton fongible

ERC-20 est la norme de jeton la plus largement prise en charge sur les chaînes compatibles Ethereum. Tous les jetons d'une même classe sont identiques et interchangeables.

**Avantages**
- Pris en charge par pratiquement tous les portefeuilles, plateformes d'échange et protocoles DeFi
- Simple à déployer et à gérer
- Faible coût en gas pour les transferts

**Inconvénients**
- Aucune conformité appliquée nativement — n'importe qui peut recevoir le jeton
- Aucune prise en charge native des montants partiels pour les titres fractionnés

**Convient le mieux à** : les titres fongibles dont la conformité est entièrement gérée hors chaîne, ou les déploiements de test internes.

---

## ERC-721 — Jeton non fongible (NFT)

Les jetons ERC-721 sont uniques — chaque jeton a un identifiant et un propriétaire distincts. Ils conviennent donc aux titres représentant un actif unique ou une unité déterminée.

**Avantages**
- Chaque jeton est identifiable individuellement (utile pour des titres de créance aux conditions propres)
- Métadonnées riches via `tokenURI`
- Forte prise en charge par les portefeuilles et les places de marché

**Inconvénients**
- Inadapté à de grands nombres d'unités fongibles (une transaction par jeton)
- Coût en gas par transfert plus élevé qu'avec ERC-20

**Convient le mieux à** : les titres uniques, les obligations individuelles ou les produits structurés dont chaque unité a ses propres conditions.

---

## ERC-1155 — Norme multi-jetons

ERC-1155 permet à un seul contrat de gérer simultanément plusieurs types de jetons — fongibles comme non fongibles.

**Avantages**
- Opérations groupées efficaces : transférer plusieurs types de jetons en une transaction
- Peut représenter dans un même contrat des titres fongibles et non fongibles
- Coût en gas plus faible pour les opérations groupées que plusieurs contrats ERC-20/721

**Inconvénients**
- Moins largement pris en charge par les portefeuilles grand public qu'ERC-20 ou ERC-721
- Aucune conformité appliquée nativement

**Convient le mieux à** : les émetteurs gérant plusieurs tranches ou séries de titres et souhaitant réduire la complexité contractuelle.

---

## ERC-3643 (T-REX) — Recommandé pour les titres réglementés

ERC-3643, aussi appelé T-REX (Token for Regulated EXchanges), est une norme ouverte conçue spécifiquement pour les jetons de titres réglementés. C'est la **norme recommandée** pour la plupart des émissions sous eWpG.

**Avantages**
- Conformité on-chain : les transferts sont bloqués automatiquement si l'une des parties échoue aux contrôles
- L'identité de l'investisseur est vérifiée via ONCHAINID, une norme d'identité décentralisée
- Modules de conformité fins (solde maximal, nombre maximal d'investisseurs, restrictions par pays, etc.)
- Séparation des rôles d'agent (agents d'identité, agents de transfert, agents de conformité)
- Pleinement compatible avec les protocoles DeFi prenant en charge l'interface ERC-20

**Inconvénients**
- Mise en place initiale plus complexe (plusieurs contrats à déployer)
- Les investisseurs doivent disposer d'un ONCHAINID et d'attestations KYC/LCB-FT valides avant de recevoir des jetons
- Coût en gas par transfert légèrement supérieur en raison des contrôles de conformité

**Convient le mieux à** : toute émission de titre réglementé dont les restrictions de transfert doivent être appliquées automatiquement on-chain.

Voir l'approfondissement complet sur [ERC-3643 expliqué](../../token-standards/erc3643.md).

---

## ERC-3643 confidentiel — Jetons réglementés préservant la confidentialité

L'ERC-3643 confidentiel étend la norme T-REX au chiffrement totalement homomorphe (FHE), fourni par le fhEVM de Zama. Les soldes de jetons et les montants transférés sont chiffrés on-chain — seules les parties autorisées peuvent les déchiffrer.

**Avantages**
- Les soldes des investisseurs sont soustraits au regard du public tout en restant auditables par les parties autorisées
- La conformité reste pleinement appliquée (le contrat intelligent peut la vérifier sur des données chiffrées)
- Adapté aux cas institutionnels où la taille des positions doit rester confidentielle

**Inconvénients**
- Disponible uniquement sur les réseaux Fhenix et Inco
- Coût en gas supérieur du fait du calcul FHE
- Prise en charge par les portefeuilles et l'outillage plus limitée que pour l'ERC-3643 standard
- Les investisseurs ont besoin d'un outillage de portefeuille compatible FHE pour interagir

**Convient le mieux à** : les titres institutionnels pour lesquels la confidentialité des positions est une exigence réglementaire ou commerciale.

Voir [Les jetons confidentiels expliqués](../../token-standards/confidential.md).

---

## Guide de décision

```
Is on-chain compliance enforcement required?
  YES → Are balances required to be confidential?
            YES → Confidential ERC-3643
            NO  → ERC-3643 (T-REX)
  NO  → Are tokens unique/non-fungible?
            YES → ERC-721
            NO  → Do you need multiple token types in one contract?
                      YES → ERC-1155
                      NO  → ERC-20
```
