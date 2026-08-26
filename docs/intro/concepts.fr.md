---
title: Concepts fondamentaux
description: Glossaire des termes juridiques, financiers et techniques employés dans tout Registerwerk.
---

# Concepts fondamentaux

Ce glossaire définit les termes employés dans la documentation, le code et les interfaces de Registerwerk. Les termes sont regroupés par domaine ; les renvois pointent vers les pages détaillées lorsqu'il y en a.

---

## Titres financiers et émission

**Jeton de titre financier (security token)**
: Un jeton blockchain représentant un instrument financier — obligation, action, part de fonds ou autre actif réglementé. Registerwerk gère des jetons de titres soumis au droit financier des [juridictions prises en charge](../legal/index.md).

**Titre financier électronique (elektronisches Wertpapier)**
: Un titre qui n'existe que sous la forme d'une inscription dans un registre électronique centralisé ou décentralisé, sans document papier. Défini en Allemagne par le [§2 eWpG](../legal/ewpg.md) ; des équivalents existent en droit luxembourgeois, français et liechtensteinois.

**Émetteur**
: L'entité juridique qui crée et propose un jeton de titre. Dans Registerwerk, un émetteur est une entité juridique [cliente](#entites-clientes) portant le rôle `ISSUER` et ayant obtenu l'approbation [KYC/LCB-FT](../compliance/kyc-aml.md).

**Investisseur / titulaire**
: Une entité juridique ou une personne physique détenant une position sur un jeton de titre. Suivi dans le système par un enregistrement `AssetHolder` relié via une `HolderIdentity` à une `LegalEntity` ou à une `NaturalPerson`.

**ISIN** (International Securities Identification Number)
: Un code alphanumérique de 12 caractères identifiant un titre de façon unique dans le monde. Registerwerk stocke l'ISIN sur l'entité `Asset` et l'intègre aux métadonnées du jeton.

**Numéro d'actif**
: L'identifiant séquentiel interne de Registerwerk pour un titre, distinct de l'ISIN. Utilisé dans les processus internes et les références d'audit.

**Émission / déploiement**
: L'acte de créer un contrat de jeton sur une blockchain. Dans Registerwerk, le déploiement est suivi par un enregistrement `AssetDeployment` reliant l'`Asset` hors chaîne à son adresse de contrat on-chain.

---

## Concepts blockchain

**Blockchain / chaîne**
: Un réseau de registre distribué. Registerwerk prend en charge Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism (EVM), Solana, StarkNet, Stellar et Canton. Voir [Blockchains prises en charge](../blockchains/index.md).

**Norme de jeton**
: Une spécification définissant l'interface d'un jeton (comment il peut être transféré, interrogé et administré). Exemples : ERC-20, ERC-3643, SPL-2022. Voir [Normes de jetons](../token-standards/index.md).

**Contrat intelligent**
: Du code exécutable déployé sur une blockchain. Registerwerk déploie les contrats avec [Web3j](https://web3j.io/) (EVM) et Solanaj (Solana). Les adresses de contrat sont stockées dans `AssetDeployment`.

**Transaction (on-chain)**
: Une opération signée cryptographiquement et soumise à une blockchain. Chaque changement d'état est consigné comme `BlockchainTransaction` et relié à l'événement d'audit correspondant.

**Dérive de chaîne**
: Un écart entre le solde de jetons on-chain et le champ `AssetHolder.nominalAmount` de la base de données Registerwerk. Le `ChainDriftDetectionJob` vérifie la dérive toutes les 15 minutes pour chaque actif émis.

**Registre canonique**
: Registerwerk conserve un enregistrement opérationnel des titulaires dans PostgreSQL et projette ou rapproche certains états on-chain. Savoir quel enregistrement fait juridiquement foi dépend de l'instrument, du modèle de registre, de l'opérateur et de la juridiction, et requiert une décision de périmètre approuvée. Ni la base de données ni la blockchain ne fait foi universellement.

**Portefeuille**
: Une paire de clés cryptographiques servant à signer des transactions on-chain. Registerwerk gère les portefeuilles de l'opérateur (matériel de clé chiffré au repos) via le module `wallet`.

---

## Réglementation et conformité

**KYC** (Know Your Customer)
: Le processus de vérification de l'identité d'un client — y compris son activité, ses propriétaires et ses bénéficiaires effectifs — avant l'entrée en relation d'affaires. Voir [KYC et LCB-FT](../compliance/kyc-aml.md).

**KYB** (Know Your Business)
: L'équivalent du KYC au niveau de l'entreprise, centré sur la vérification de la légitimité et de la structure de propriété d'une entité juridique.

**LCB-FT** (lutte contre le blanchiment)
: L'ensemble des règles imposant aux entreprises de détecter et de prévenir le blanchiment de capitaux. En Allemagne : la GwG ; au niveau de l'UE : AMLD6 et le futur AMLR.

**PPE** (personne politiquement exposée)
: Une personne qui exerce ou a exercé une fonction publique importante. Les PPE requièrent une vigilance renforcée au titre du [§10(2) GwG](../compliance/kyc-aml.md).

**BE** (bénéficiaire effectif ultime)
: La ou les personnes physiques qui, en dernier ressort, possèdent ou contrôlent une entité juridique, généralement au-delà d'un seuil de 25 %. Suivi dans Registerwerk comme `BeneficialOwner` relié à une `NaturalPerson`.

**Filtrage des sanctions**
: Le rapprochement d'une personne ou d'une entité avec les listes internationales de sanctions (OFAC SDN, PESC de l'UE, ONU 1267, HMT du Royaume-Uni, SECO suisse). Voir [Filtrage des sanctions](../compliance/sanctions-screening.md).

**Travel Rule (TFR)**
: Le règlement (UE) 2023/1113 exigeant que les informations sur le donneur d'ordre et le bénéficiaire accompagnent les transferts de crypto-actifs supérieurs à 1 000 € entre PSAN. Mis en œuvre au moyen du [standard de données IVMS-101](../compliance/travel-rule.md).

**VASP** (prestataire de services sur actifs virtuels)
: Une entreprise réglementée fournissant des services relatifs aux actifs virtuels (plateformes d'échange, conservateurs). Registerwerk agit lui-même comme VASP/PSCA lorsqu'il émet des jetons pour le compte de tiers.

**PSCA** (prestataire de services sur crypto-actifs)
: Le terme employé par MiCAR pour VASP en droit de l'UE.

**Sperrvermerk**
: Terme juridique allemand désignant une mention de blocage sur une inscription au registre de titres, restreignant le transfert ou grevant un actif. Imposé par le [§16 eWpG](../legal/ewpg.md). Voir [Sperrvermerk](../compliance/sperrvermerk.md).

**DORA** (Digital Operational Resilience Act)
: Le règlement (UE) 2022/2554 imposant aux entités financières de gérer les risques informatiques, de déclarer les incidents majeurs et de tenir un registre des prestataires informatiques tiers. Voir [DORA](../compliance/dora.md).

**LEI** (identifiant d'entité juridique)
: Un code de 20 caractères conforme à l'ISO 17442 identifiant une entité juridique de façon unique dans le monde. Stocké sur `LegalEntity` dans Registerwerk ; recommandé pour tous les émetteurs.

---

## Entités clientes

**Opérateur**
: L'organisation qui exploite une installation Registerwerk. Les opérateurs accèdent à l'interface opérateur (:44200) et peuvent administrer l'ensemble des clients, actifs et données de conformité.

**Client**
: Un émetteur ou un investisseur intégré par un opérateur. Les clients accèdent à l'interface client (:44201) au travers de la passerelle d'API Kong.

**Entité juridique (`LegalEntity`)**
: Le modèle de données central pour la société d'un client. Il porte la juridiction, le numéro d'immatriculation, le LEI, le statut KYC, et les liens vers les bénéficiaires effectifs et les documents KYC.

**Personne physique (`NaturalPerson`)**
: Un individu — dirigeant, bénéficiaire effectif ou investisseur. L'entité actuelle place les données personnelles telles que nom, date de naissance, nationalité et identifiant fiscal dans des colonnes de base de données ordinaires ; le chiffrement de champs au niveau applicatif n'est pas implémenté.

**Bénéficiaire effectif (`BeneficialOwner`)**
: Fait le pont entre une `LegalEntity` et une `NaturalPerson`, avec le pourcentage de détention et le type de contrôle.

---

## Termes propres à la plateforme

**Module**
: Un contexte délimité Spring Modulith. Registerwerk compte 34 modules, chacun doté d'un paquet `api/` (types publics) et d'un paquet `internal/` (implémentation privée). Voir [Architecture modulaire](../platform/modules.md).

**Authentification renforcée (step-up)**
: Un second défi d'authentification exigé avant l'exécution d'opérations à haut risque (transfert forcé, destruction forcée, dérogation KYC). Appliqué par l'annotation `@RequiresStepUp`. Voir [Authentification renforcée (step-up)](../compliance/step-up-mfa.md).

**Principe des quatre yeux (Vier-Augen-Prinzip)**
: Une exigence de double contrôle où un second approbateur habilité doit confirmer une action avant qu'elle ne prenne effet. Implémenté par le module `stepup`.

**Chaîne d'audit**
: La séquence inviolable d'événements d'audit, chacun contenant un hachage de l'entrée précédente. Elle fournit une preuve cryptographique de l'exhaustivité et de l'intégrité de la piste d'audit. Voir [Piste d'audit](../platform/audit-log.md).
