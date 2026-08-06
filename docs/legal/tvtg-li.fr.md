---
title: Liechtenstein — TVTG
description: Comment Registerwerk met en œuvre les obligations de diligence raisonnable du Liechtenstein TVTG (Token Act) et SPG.
---

# Liechtenstein — TVTG (Loi sur les jetons) {#liechtenstein-tvtg-token-act}

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page enregistre les mappages de contrôle prévus et les hypothèses configurées. Elle ne constitue pas
    un avis juridique du Liechtenstein ni une preuve de classification de l'instrument, d'enregistrement,
    d'autorisation réglementaire, de conformité ou d'effet juridique. Obtenez un examen actuel spécifique aux
    instruments, aux opérateurs, aux services et au déploiement.

Le Liechtenstein a été le premier pays européen à adopter une législation complète spécifique aux jetons. La
**Loi sur les fournisseurs de services de jetons et de technologies de confiance** (TVTG, en vigueur depuis le
1er janvier 2020) a créé un cadre juridique neutre et indépendant de la technologie, traitant les jetons comme
des conteneurs de droits de toute nature — y compris les instruments financiers.

---

## Le modèle du TVTG {#the-tvtg-model}

Le TVTG établit le concept de **Token** en tant qu'enregistrement de données dans un système TT (Trusted
Technology), c'est-à-dire un grand livre distribué ou un système équivalent sécurisé par cryptographie. Les
droits sont attachés aux jetons plutôt qu'à l'actif sous-jacent directement, créant une séparation juridique
nette entre le droit (le jeton) et sa représentation technique (la blockchain).

Cela s'aligne bien avec le modèle de registre canonique de Registerwerk : l'inscription au registre est
l'instrument juridique ; la blockchain n'en est qu'une représentation.

---

## Cadre réglementaire applicable {#applicable-regulatory-framework}

| Réglementation | Portée |
|---|---|
| TVTG (LGBl. 2019 Nr. 301) | Classification des jetons, licence des prestataires de services |
| SPG (Sorgfaltspflichtgesetz) | Diligence raisonnable / AML pour les prestataires de services TT |
| VPG (Vermögensverwaltungsgesetz) | Obligations de gestion de patrimoine |
| FMA-Wegleitung TVTG | Directives de surveillance de la FMA du Liechtenstein |
| MiCAR (UE) 2023/1114 | S'applique via l'accord EEE |
| DORA (UE) 2022/2554 | Résilience ICT via l'accord EEE |

---

## Licence de prestataire de services TT {#tt-service-provider-licence}

Les entités exploitant un système TT pour des instruments financiers doivent obtenir une licence
**prestataire de services TT** auprès de la **Finanzmarktaufsicht (FMA)**. La configuration `LI_TVTG` de
Registerwerk stocke le numéro de licence de l'opérateur. Le type de licence détermine les services pouvant être
fournis ; Registerwerk cible les catégories de services **TT Token Issuer** et **TT Register Operator**.

---

## TVTG §9 — Obligation de whitepaper du jeton {#tvtg-9-token-whitepaper-obligation}

Contrairement à l'Allemagne (aucun whitepaper requis pour les titres électroniques en tant que tels) et à la
France (document d'information AMF), le §9 du TVTG du Liechtenstein exige un **whitepaper du jeton** pour
chaque offre publique de jetons. Le whitepaper doit décrire :

- les droits représentés par le jeton ;
- la spécification technique ;
- les risques pour les détenteurs du jeton ;
- les conditions générales.

**Mise en œuvre :** Registerwerk stocke le document de whitepaper du jeton dans la table `kyc_document` sous le
type `TOKEN_WHITEPAPER`. Pour les émetteurs `LI_TVTG`, le flux de déploiement bloque l'émission de jetons tant
qu'un document `TOKEN_WHITEPAPER` avec `status = APPROVED` n'est pas associé à l'actif.

---

## Exigence d'audit du smart contract {#smart-contract-audit-requirement}

Les orientations de la FMA recommandent (et, pour certaines catégories de licence, exigent) un audit
indépendant du code du smart contract avant toute émission publique. Registerwerk stocke le rapport d'audit
sous forme de `kyc_document` de type `SMART_CONTRACT_AUDIT`.

---

## SPG — Obligations de diligence raisonnable {#spg-due-diligence-obligations}

La **Sorgfaltspflichtgesetz** impose aux prestataires de services TT des obligations de diligence raisonnable
AML/CFT équivalentes aux exigences AMLD5/AMLD6. Principales différences par rapport au GwG allemand :

| Aspect | DE (GwG) | LI (SPG) |
|---|---|---|
| Seuil UBO | 25 % | 25 % |
| Filtrage PPE | Obligatoire | Obligatoire |
| Période de conservation | 6 ans (GwG §8) | 10 ans (TVTG art. 10) |
| Personnes politiquement exposées | Diligence renforcée complète | Diligence renforcée complète + notification à la FMA |
| Registre des bénéficiaires effectifs | Transparenzregister | Handelsregister du Liechtenstein (section UBO) |

---

## Exigences documentaires KYC pour `LI_TVTG` {#kyc-document-requirements-for-litvtg}

Le profil de juridiction `LI_TVTG` exige :

- **Handelsregisterauszug** (extrait du registre du commerce du Liechtenstein, ≤ 3 mois)
- **Déclaration des bénéficiaires effectifs** alignée sur le format du registre du Liechtenstein
- Pièces d'identité pour les administrateurs et les bénéficiaires effectifs
- **Whitepaper du jeton** (`TOKEN_WHITEPAPER`) — obligatoire, doit être approuvé avant le déploiement
- **Rapport d'audit du smart contract** (`SMART_CONTRACT_AUDIT`) — obligatoire pour les offres publiques
- Copie ou confirmation de la **licence de prestataire de services TT**
- États financiers annuels (2 dernières années)

---

## Conservation : 10 ans {#retention-10-years}

Le Liechtenstein exige une conservation de 10 ans pour tous les enregistrements liés aux transactions sur
jetons, ce qui correspond à l'Allemagne mais dépasse le Luxembourg et la France. Le profil de juridiction
`LI_TVTG` définit `retentionYears = 10`.

---

## Reporting MiFIR pour le Liechtenstein {#mifir-reporting-for-liechtenstein}

L'applicabilité du MiFIR, la capacité de déclaration, l'autorité compétente et le canal nécessitent un examen
externe actuel. Il n'existe pas de stratégie de dépôt `LI_TVTG` dans `MifirReportingService` ; le service
actuel produit uniquement le prototype `DRAFT_UNVALIDATED` décrit dans [MiFIR](../compliance/mifir.md).

---

## Déclaration d'incidents à la FMA {#fma-incident-reporting}

L'applicabilité de DORA/EEE, l'autorité compétente et les délais nécessitent un examen externe actuel. Le
module `dora` n'achemine ni ne transmet les notifications d'incidents `LI_TVTG` à la FMA.

---

## Pourquoi le Liechtenstein pour les émetteurs natifs de la blockchain {#why-liechtenstein-for-blockchain-native-issuers}

Le Liechtenstein propose le cadre juridique le plus natif de la blockchain en Europe :

- les jetons sont légalement reconnus quelle que soit la technologie sous-jacente ;
- tout droit peut être tokenisé — instruments financiers, immobilier, droits de propriété intellectuelle ;
- le TVTG est technologiquement neutre (EVM, UTXO et DAG sont tous admissibles) ;
- aucune désignation distincte de « titre cryptographique » n'est nécessaire — le jeton lui-même porte le droit.

Cela rend `LI_TVTG` attractif pour des types d'instruments innovants tels que
[les obligations semi-fongibles ERC-3525](../token-standards/erc3525.md),
[les jetons de coffre ERC-4626](../token-standards/erc4626.md) et
[les instruments DAML Finance](../token-standards/canton-daml.md), pour lesquels aucun type d'instrument
national équivalent n'existe encore.
