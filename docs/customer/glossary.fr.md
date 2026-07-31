---
title: Glossaire
description: Chaque terme employé dans cette documentation, défini simplement.
---

# Glossaire

Des définitions simples. Lorsqu'un terme a un sens technique précis qui diffère de l'usage courant, la différence est indiquée.

---

## Finance

**Conservateur**
: Un établissement qui détient des titres pour le compte d'autrui. Dans une inscription collective, le conservateur est le titulaire inscrit.

**Coupon**
: L'intérêt que verse une obligation. Le nom vient des obligations papier, où l'on détachait un coupon du certificat pour réclamer chaque versement.

**Date de détachement**
: À partir de cette date, un titre se négocie *sans* un versement à venir. Achetez après elle et le versement appartient au vendeur.

**Date d'enregistrement**
: L'instant où le registre est photographié pour décider qui a droit à un versement. Détenez à cette date et le versement est à vous, même si vous vendez demain.

**Échéance**
: La date à laquelle une obligation prend fin et où sa valeur nominale est remboursée.

**Émetteur**
: L'organisation qui crée un titre et doit ce qu'elle promet.

**Garantie** (aussi *collatéral*)
: Un bien de valeur donné pour garantir un prêt. Si l'emprunteur ne rembourse pas, le prêteur peut le vendre.

**Liquidité**
: La facilité avec laquelle une chose se transforme en argent sans faire bouger son prix. Un titre que personne ne veut acheter est illiquide.

**Livraison contre paiement (LCP)**
: Régler de sorte que le titre se déplace si et seulement si le paiement se déplace. Supprime le risque qu'une partie exécute et pas l'autre.

**LLTV**
: Le seuil de quotité de financement au-delà duquel un prêt peut faire l'objet d'une réalisation de la garantie.

**Marché primaire**
: L'émetteur qui vend aux investisseurs. L'argent parvient à l'émetteur. Cela n'arrive qu'une fois.

**Marché secondaire**
: Les investisseurs qui se vendent entre eux. L'émetteur n'est pas partie et ne reçoit rien.

**Montant nominal**
: La valeur nominale que détient un titulaire. Ce que le registre consigne. Pas la valeur de marché.

**Obligation**
: Un prêt divisé en parts égales afin que de nombreux prêteurs puissent en prendre chacun une. L'emprunteur verse des intérêts et rembourse la valeur nominale à l'échéance.

**Obligation à coupon zéro**
: Une obligation qui ne verse pas d'intérêts et qui est vendue sous sa valeur nominale à la place. Achetez à 800 €, recevez 1 000 € à l'échéance.

**Opération sur titres (OST)**
: Tout ce que fait un émetteur et qui touche les titulaires en tant que titulaires — verser un coupon, diviser les unités, rembourser le principal.

**Pension livrée** (*repo*)
: Une vente assortie d'un rachat convenu à un prix supérieur. Économiquement un prêt garanti ; l'écart de prix est l'intérêt. Structurée comme une vente parce qu'une propriété pleine résiste mieux à l'insolvabilité qu'une sûreté.

**Point de base (pb)**
: Un centième de pour cent. 100 pb = 1 %. On l'emploie parce que « le taux a augmenté de 1 % » est ambigu — de 4 % à 5 %, ou de 4 % à 4,04 % ? Les points de base lèvent l'ambiguïté.

**Quotité de financement (LTV)**
: Ce que vous avez emprunté en pourcentage de la valeur de votre garantie. Empruntez 50 000 € contre 100 000 € de garantie et votre LTV est de 50 %.

**Règlement-livraison**
: L'achèvement d'une transaction — les titres et l'argent changeant effectivement de mains. À distinguer du fait de la conclure.

**Remboursement**
: Rembourser le principal d'un titre et le retirer.

**Valeur nominale** (aussi *nominal*, *pair*)
: Le montant inscrit sur l'instrument — ce qui est remboursé à l'échéance. **Pas** le prix. Une obligation de 1 000 € peut se négocier à 960 €.

---

## Blockchain

**Adresse de contrat**
: L'endroit où un contrat intelligent réside sur une chaîne. Publique ; n'importe qui peut l'inspecter.

**Blockchain**
: Un registre partagé, tenu par de nombreuses parties, où les entrées ne peuvent pas être discrètement modifiées une fois consignées.

**Clé privée**
: Le secret qui autorise les actions depuis une adresse de portefeuille. Elle ne peut être ni réinitialisée, ni récupérée, ni réémise. La perdre, c'est perdre la faculté de déplacer les jetons.

**Contrat intelligent** (*smart contract*)
: Un programme sur une blockchain. Il s'exécute exactement comme il est écrit, lorsqu'il est appelé, sans que personne ne décide de l'y autoriser.

**Destruction** (*burning*)
: Détruire des jetons. L'encours diminue. Irréversible.

**Émission** (*minting*)
: Créer des jetons qui n'existaient pas. L'inverse de la destruction.

**ERC-20**
: La norme courante de jeton fongible. Simple et universellement prise en charge, **sans** aucune notion de qui peut le détenir.

**ERC-3643** (aussi *T-REX*)
: Une norme de jeton pour titres réglementés. Contrôle l'éligibilité avant chaque transfert et fait échouer on-chain ceux qui ne sont pas conformes.

**Gas**
: Les frais payés pour faire traiter une transaction.

**Hachage de transaction**
: L'identifiant d'une transaction. Votre reçu ; consultez-le sur un explorateur de blocs.

**Jeton** (*token*)
: Une unité consignée dans un contrat intelligent. Ici, la représentation on-chain d'un titre — le mécanisme, pas le titre lui-même.

**ONCHAINID**
: Un contrat d'identité on-chain contenant les attestations vérifiées d'une partie sous ERC-3643.

**Portefeuille** (*wallet*)
: Un logiciel détenant une clé privée. Il ne contient pas de jetons — c'est le contrat qui consigne un solde en regard de votre adresse.

**Réseau principal / réseau de test**
: Le vrai réseau, où la valeur est réelle. Et le réseau d'essai, où elle ne l'est pas.

**Revert** (annulation)
: Une transaction qui échoue et se défait entièrement. Un contrôle de conformité qui échoue provoque un revert — rien de partiel ne se produit.

**Stablecoin**
: Un jeton destiné à conserver une valeur stable face à une devise.

---

## Registerwerk

**Actif** (*asset*)
: Un titre dans le registre. Formellement : l'enregistrement d'un instrument par le registre.

**Authentification renforcée** (*step-up*)
: Exiger une preuve d'identité fraîche pour une action sensible, au-delà d'une session déjà ouverte.

**Dispositif de paiement**
: Un moyen pris en charge pour déplacer la jambe espèces — stablecoin, API de paiement instantané, règlement LCP, ou virement bancaire.

**Double validation** (principe des quatre yeux)
: Exiger deux personnes différentes. Appliquée aux opérations les plus tranchantes.

**Entité juridique**
: Une organisation dans le registre. Les utilisateurs en relèvent ; la vérification et les permissions s'y rattachent.

**Espace de travail**
: Une vue du portail client regroupant les outils d'un métier — Investor, Trader ou Issuer. De la navigation, **pas** une permission.

**Indexeur**
: Un logiciel qui surveille les blockchains et écrit dans le registre ce qu'il y voit.

**Manifeste**
: Le JSON signé décrivant une dApp de la place de marché. Son hachage est ancré on-chain à l'approbation.

**Mode support** (*impersonation*)
: Un opérateur agissant à l'intérieur du portail d'un client à des fins d'assistance. Chaque action est imputée à l'**opérateur**, jamais au client.

**Opérateur**
: L'organisation qui exploite le registre. Elle approuve les entités et les émissions, et détient les pouvoirs de correction du registre.

**Piste d'audit**
: L'enregistrement inviolable de toute opération modifiant l'état. Chaîné par hachage, de sorte qu'une altération est détectable.

**Point de réception**
: Une adresse de portefeuille que vous avez enregistrée auprès du registre, avec un libellé.

**Registre**
: La base de données de l'opérateur consignant qui détient quoi. **L'enregistrement juridiquement significatif**, distinct du jeton.

**Rejet par défaut** (*fail closed*)
: Lorsqu'un contrôle ne peut pas s'exécuter, refuser plutôt qu'autoriser. Le filtrage des sanctions fonctionne ainsi — une panne signifie que les transferts sont refusés, pas laissés passer sans contrôle.

**Relevé de registre** (*Registerauszug*)
: Un état du contenu du registre concernant un titulaire. Au titre du §19(2) eWpG, dû aux titulaires consommateurs en inscription individuelle. Un document de registre conservé, pas une notification.

**Sperrvermerk**
: Une restriction portée sur une inscription au titre du §16 eWpG. Tant qu'elle subsiste, la position ne peut pas être transférée. Elle vous appartient toujours.

**Titulaire**
: Une inscription au registre consignant que quelqu'un détient un montant d'un titre.

**Transfert forcé**
: Une correction exécutée par l'opérateur qui déplace une position d'un portefeuille à un autre, au titre du §24 eWpG. Le remède en cas de clé perdue ou de décision de justice. Exige la double validation.

**Type d'inscription**
: Si une inscription est *collective* (un conservateur détient pour plusieurs) ou *individuelle* (l'investisseur est nommé directement).

---

## Réglementation

**DORA**
: Règlement européen sur le risque informatique et la résilience opérationnelle des entités financières.

**eWpG**
: La loi allemande sur les titres électroniques, en vigueur depuis juin 2021. Elle permet à un titre d'exister comme inscription au registre plutôt que comme certificat papier.

**KYC**
: *Know Your Customer*, la connaissance du client. Vérifier avec qui l'on traite.

**LCB-FT** (*AML*)
: Lutte contre le blanchiment de capitaux et le financement du terrorisme. Les règles empêchant le système financier de dissimuler les produits du crime.

**MiCAR**
: Règlement européen couvrant les émetteurs de crypto-actifs et les prestataires de services associés.

**MiFIR**
: Règlement européen à l'origine de la déclaration des transactions.

**Prospectus**
: Le document d'information pour une offre au public de titres. Des exemptions existent — couramment pour les offres réservées aux investisseurs professionnels.

**RGPD / DSGVO**
: Le droit européen de la protection des données.

**Travel Rule**
: L'exigence que les informations sur le donneur d'ordre et le bénéficiaire accompagnent un transfert. L'équivalent crypto de ce qu'une banque transmet avec un virement.

**§16 eWpG**
: Le contenu du registre et son effet juridique.

**§17(2) eWpG**
: Le contenu supplémentaire requis pour les inscriptions individuelles.

**§19(2) eWpG**
: L'obligation de fournir des relevés de registre aux titulaires consommateurs.

**§24 eWpG**
: La correction du registre — le fondement des transferts forcés.
