---
title: La vie d'un titre financier
description: Une obligation, suivie de l'idée initiale jusqu'au remboursement, avec chaque fonctionnalité de Registerwerk expliquée là où elle sert réellement.
---

# La vie d'un titre financier

La plupart des documentations expliquent des fonctionnalités. Cette section raconte une *histoire* et laisse les fonctionnalités apparaître là où elles ont leur place.

L'histoire est celle d'une obligation. Nous la suivons depuis le moment où quelqu'un veut emprunter de l'argent, à travers les formalités, sur une blockchain, entre les mains des investisseurs, sur une plateforme de négociation, dans un marché de financement comme garantie, et enfin hors d'existence lorsque la dette est remboursée.

**Si vous lisez cette section en entier, vous comprendrez le métier de Registerwerk.** Comptez quarante minutes.

---

## Nordwind Energie

!!! example "L'exemple fil rouge"

    **Nordwind Energie GmbH** construit des parcs éoliens dans le Schleswig-Holstein. Elle a besoin de **50 millions d'euros** pour financer un nouveau site, et ne veut pas passer par une banque.

    Elle décide donc d'emprunter directement auprès d'investisseurs, en émettant une **obligation** : la promesse de rembourser la somme à une date fixe, avec des intérêts entre-temps.

    Les conditions envisagées :

    | | |
    |---|---|
    | Montant | 50 000 000 € |
    | Coupure | 1 000 € par titre, soit 50 000 titres |
    | Intérêt | 4,5 % par an, payés semestriellement |
    | Échéance | 5 ans |
    | Remboursement | valeur nominale intégrale à l'échéance |

    Voilà tout le produit financier. Tout le reste est la machinerie qui rend cette promesse effective, négociable et opposable — et qui démontre à un régulateur que tout s'est fait dans les règles.

??? note "Pour les lecteurs non financiers : ce qu'est vraiment une obligation"

    Une obligation est un prêt découpé en parts égales pour que plusieurs prêteurs puissent en prendre chacun une.

    Nordwind veut 50 millions. Plutôt que de trouver un unique prêteur pour la totalité, elle découpe le prêt en 50 000 parts de 1 000 €. Un investisseur en achète autant qu'il souhaite. Chaque part donne droit à sa quote-part d'intérêts et à 1 000 € à la fin.

    Trois mots que vous croiserez sans cesse :

    - **Valeur nominale** (ou *pair*) : le montant inscrit sur le titre — ici 1 000 €. C'est ce qui est remboursé à la fin, quel qu'ait été le prix payé entre-temps.
    - **Coupon** : le taux d'intérêt, ici 4,5 % par an. Le nom vient de l'époque où les obligations étaient en papier et où l'on détachait physiquement un coupon du certificat pour réclamer chaque paiement.
    - **Échéance** : la date à laquelle le prêt prend fin et la valeur nominale est remboursée.

    Le point crucial et contre-intuitif : **le prix d'une obligation et sa valeur nominale sont deux nombres différents, et le prix bouge.** Si les taux montent après l'émission, une obligation à 4,5 % devient moins attractive et on ne la vendra qu'avec une décote — peut-être 960 € pour un titre de 1 000 €. La valeur nominale n'a pas changé. Ce qui a changé, c'est ce que quelqu'un est prêt à payer pour le droit de la percevoir.

---

## Les six étapes

<div class="grid cards" markdown>

-   **1. [Conception et approbation](design.md)**

    ---

    Nordwind décrit l'obligation dans le portail, choisit comment elle existera sur une blockchain, puis la soumet. L'opérateur vérifie et approuve. Rien n'est encore on-chain.

-   **2. [Émission primaire](primary-issuance.md)**

    ---

    Le contrat est déployé, les investisseurs sont admis, et les 50 000 titres naissent entre leurs mains. L'argent va dans un sens, les titres dans l'autre.

-   **3. [Détention et conservation](holding.md)**

    ---

    Les investisseurs possèdent quelque chose. Où cela se trouve-t-il réellement, qui est inscrit comme titulaire, et que se passe-t-il quand le registre et la blockchain divergent ?

-   **4. [Marché secondaire](secondary-market.md)**

    ---

    Un investisseur veut sortir avant l'échéance. Un autre veut entrer. Comment les deux se trouvent, et comment l'échange est sécurisé.

-   **5. [Pension livrée et financement](repo-lending.md)**

    ---

    Un investisseur veut des liquidités mais souhaite garder l'obligation. Il la met en garantie et emprunte contre elle — le plus vieux mécanisme des marchés financiers, reconstruit on-chain.

-   **6. [Opérations sur titres et remboursement](redemption.md)**

    ---

    Des intérêts versés deux fois par an pendant cinq ans. Puis le prêt s'achève, l'argent repart, et le titre est détruit.

</div>

---

## Les deux erreurs à éviter

Deux idées fausses provoquent l'essentiel de la confusion chez les nouveaux venus. Les nommer maintenant évite bien des relectures.

**« Le jeton *est* le titre financier. »** Non. Le jeton est la manière dont le titre est transféré et attesté sur une blockchain. Le titre est la créance juridique sur Nordwind. Le registre est l'enregistrement de qui la détient. Si toutes les blockchains du monde s'éteignaient demain, les investisseurs seraient toujours créanciers de 50 millions d'euros — ils auraient simplement beaucoup plus de mal à prouver qui doit quoi à qui. Le jeton est le mécanisme, pas la chose.

**« Sur une blockchain, n'importe qui peut envoyer n'importe quoi à n'importe qui. »** Vrai pour une cryptomonnaie. Catégoriquement faux ici. Un titre réglementé ne peut être détenu que par ceux qui y sont autorisés, et cette restriction doit survivre au contact d'une blockchain publique où quiconque peut appeler n'importe quelle fonction. Résoudre ce problème constitue l'essentiel de ce qui rend les jetons de titres plus difficiles que les jetons ordinaires, et c'est le sujet de [Conception et approbation](design.md).

---

[Commencer par l'étape 1 : Conception et approbation :octicons-arrow-right-24:](design.md){ .md-button .md-button--primary }
