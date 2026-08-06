---
title: Ce qu'est Registerwerk
description: Une explication simple de ce que fait la plateforme, de ce qu'elle ne fait pas, et de ce que vous pouvez en attendre.
---

# Ce qu'est Registerwerk

**C'est un registre.** Un enregistrement de qui possède quels titres, tenu par un opérateur, ces titres étant en outre représentés par des jetons sur une blockchain.

C'est toute l'idée. Le reste en découle.

---

## Le problème qu'il résout

Un titre financier était autrefois un document. Le posséder signifiait le détenir physiquement, ou le faire détenir par un conservateur. Vendre signifiait le remettre.

Cela fonctionnait, et c'était coûteux : coffres, coursiers, rapprochements, et des jours entre l'accord sur une transaction et son achèvement.

Les titres électroniques suppriment le document. La propriété devient une inscription au registre. En Allemagne, l'**eWpG**, en vigueur depuis juin 2021, rend cela juridiquement possible : un titre peut exister comme inscription dans un registre plutôt que comme certificat.

Registerwerk met en œuvre un tel registre et y ajoute une seconde couche — les mêmes positions représentées par des jetons sur une blockchain, afin que les transferts puissent être exécutés et vérifiés indépendamment sans qu'aucune des parties n'ait à se fier aux écritures de l'autre.

---

## Les deux enregistrements

C'est la seule idée structurelle qui mérite d'être comprise, car la plupart des surprises en découlent.

<div class="grid" markdown>

!!! abstract "Le registre"
    Une base de données, tenue par l'opérateur. Elle nomme le titulaire, le montant, les restrictions.

    **L'enregistrement qui a une portée juridique.**

!!! abstract "Le jeton"
    Un solde dans un contrat intelligent sur une blockchain. Public et vérifiable indépendamment.

    **L'enregistrement qui exécute.**

</div>

Un logiciel surveille la chaîne et maintient le registre en phase. La plupart du temps, ils concordent. Quand ce n'est pas le cas, le registre fait foi et l'écart est traité par un humain.

[:octicons-arrow-right-24: Détention et conservation](lifecycle/holding.md) approfondit la question.

---

## Ce que vous pouvez faire

| | |
|---|---|
| **Émettre** | Créer un titre, le faire approuver, le déployer, admettre des investisseurs et l'administrer toute sa vie. |
| **Détenir** | Posséder des titres, voir vos positions, recevoir relevés et versements. |
| **Négocier** | Vendre avant l'échéance, ou acheter à d'autres titulaires. |
| **Emprunter** | Donner des positions en garantie et prendre un prêt en contrepartie, là où c'est activé. |
| **Publier** | Construire des applications sur le cadre de permissions de l'écosystème et les référencer. |
| **Auditer** | Lire tout le registre sans pouvoir rien y changer. |

[:octicons-arrow-right-24: Trouver votre espace de travail](workspaces/index.md)

---

## Où les titres peuvent résider

Le registre prend en charge plusieurs blockchains, choisies pour chaque émission. Chacune dispose d'un réseau principal et d'un réseau de test.

| Famille | |
|---|---|
| **EVM** | Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism |
| **EVM confidentiel** | Fhenix, Inco — montants chiffrés on-chain |
| **Solana** | SPL et SPL-2022 |
| **Canton** | Un registre privé où les contreparties ne voient que leurs propres transactions |
| **Autres** | StarkNet, Stellar |

Le choix compte plus qu'il n'y paraît : il détermine qui peut voir vos transactions, ce que coûte un transfert, la rapidité du règlement, et quelles normes de jetons sont disponibles. [Blockchains prises en charge](../blockchains/index.md) les compare.

---

## Ce qu'il ne fait pas

Être clair là-dessus est plus utile qu'une liste de fonctionnalités.

!!! warning "Registerwerk est une implémentation de référence"
    Un logiciel qui fonctionne, modélisant comment un registre de titres électroniques peut être construit — afin que la conception puisse être examinée, critiquée et réutilisée.

    **L'utiliser ne rend personne conforme à l'eWpG ni à aucune autre loi.** Il ne confère aucun agrément réglementaire et ne donne à un jeton aucun effet juridique de titre financier. Cela dépend de l'agrément de l'opérateur, de l'instrument, de l'offre, des parties et de l'installation.

    Vous pourriez rencontrer d'anciens documents affirmant que les jetons émis ici sont « juridiquement équivalents aux obligations au porteur et aux actions traditionnelles ». **Cette affirmation est fausse** et a été retirée. Qu'un instrument produise un effet juridique se détermine par la loi et par la manière dont il a réellement été émis — jamais par le logiciel qui l'a enregistré.

Plus précisément, ce n'est pas :

- **Un service de valorisation.** Le registre consigne des montants nominaux, pas des prix de marché.
- **Un conservateur de vos clés.** C'est vous qui détenez la clé privée de votre portefeuille. Personne ne peut la récupérer.
- **Une plateforme de négociation.** Il se raccorde à des plateformes ; il n'anime pas de marché.
- **Un système de paiement.** Il prend en charge plusieurs dispositifs de paiement ; l'argent circule sur ceux-ci, pas ici.
- **Un garant.** Si un émetteur fait défaut, la plateforme le consigne. Elle n'indemnise pas les titulaires.

---

## Le contexte réglementaire, en bref

L'**eWpG** (*Gesetz über elektronische Wertpapiere*) autorise les titres électroniques sans document physique et exige leur inscription dans un registre de titres. Les articles que vous croiserez le plus :

| | |
|---|---|
| **§16** | Ce que contient le registre et ce que signifie une inscription. |
| **§17(2)** | Contenu supplémentaire exigé pour les inscriptions individuelles. |
| **§19(2)** | Les relevés de registre dus aux titulaires consommateurs. |
| **§24** | La correction du registre. |

Registerwerk modélise également le Luxembourg (CSSF), la France (AMF) et le Liechtenstein (TVTG), et touche à la lutte contre le blanchiment, la Travel Rule, la déclaration MiFIR, DAC8/CARF, DORA, MiCAR et le RGPD.

[:octicons-arrow-right-24: Cadres juridiques](../legal/index.md)

!!! note "Toute émission en production est d'abord approuvée par l'opérateur"
    L'opérateur contrôle les émissions au regard de ses propres critères d'admission avant tout déploiement. C'est un contrôle opérationnel, pas un avis juridique sur votre instrument.

---

## Et ensuite

<div class="grid cards" markdown>

-   **Comprendre le métier**

    ---

    [La vie d'un titre financier](lifecycle/index.md) — une obligation, de l'idée au remboursement. Quarante minutes, aucun prérequis.

-   **S'installer**

    ---

    [Obtenir votre compte](onboarding.md) → [Se faire vérifier](kyc.md) → [Connecter un portefeuille](investors/wallet-setup.md)

-   **Faire votre travail**

    ---

    [Investisseur](workspaces/investor.md) · [Trader](workspaces/trader.md) · [Émetteur](workspaces/issuer.md) · [Auditeur](workspaces/auditor.md)

-   **Chercher une information**

    ---

    [Glossaire](glossary.md) · [Questions et réponses](faq.md)

</div>
