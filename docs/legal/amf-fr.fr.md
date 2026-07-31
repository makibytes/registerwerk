---
title: France — AMF
description: Comment Registerwerk met en œuvre les exigences réglementaires françaises AMF et Loi PACTE pour les titres tokenisés.
---

# France — AMF {#france-amf}

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page enregistre les mappages de contrôle prévus et les hypothèses configurées. Il ne s'agit pas d'un conseil légal français ni d'une preuve de classification d'un instrument, d'une autorisation réglementaire, de conformité ou d'un effet juridique
    . Obtenez l'examen actuel spécifique aux instruments, aux opérateurs, aux services et au déploiement.

La France a créé l'un des premiers cadres juridiques européens dédiés aux instruments financiers basés sur des jetons à travers la **Loi PACTE** (Plan d'Action pour la Croissance et la Transformation des Entreprises, 2019). L'**Autorité des Marchés Financiers (AMF)** supervise les émetteurs et les prestataires de services.

---

## Cadre réglementaire applicable {#applicable-regulatory-framework}

| Réglementation | Portée |
|---|---|
| Loi PACTE 2019-486 | Titres basés sur des jetons (minibons, titres financiers) |
| Code monétaire et financier (CMF) | Services d'investissement, AML |
| AMF Règlement général | Conduite du marché, prospectus, émission de jetons |
| AMF DOC-2022-15 | Conseils pour les DASP (fournisseurs de services d'actifs numériques) |
| Orientations ACPR PSAN | AML pour les entités enregistrées PSAN |
| MiCAR (UE) 2023/1114 | Applicabilité totale pour les CASP |
| DORA (UE) 2022/2554 | Résilience ICT |

---

## PSAN — Enregistrement des fournisseurs de services sur actifs numériques {#psan-digital-asset-service-provider-registration}

La loi française exige que les entités fournissant des services sur actifs numériques s'inscrivent auprès de l'**AMF** en tant que **Prestataire de Services sur Actifs Numériques (PSAN)**. Avec l'adoption de MiCAR en 2024, l'enregistrement PSAN passe à une autorisation MiCAR CASP, mais les enregistrements PSAN existants bénéficient de droits acquis pendant une période de transition.

Le profil de juridiction `FR_AMF` de Registerwerk porte le numéro d'enregistrement PSAN/CASP en configuration. Ce numéro apparaît dans les documents réglementaires.

---

## Principales différences par rapport à l'Allemagne {#key-differences-from-germany}

| Dimensions | DE (eWpG) | FR (AMF) |
|---|---|---|
| Loi sur les jetons primaires | eWpG (spécifique aux titres) | Loi PACTE / CMF (général DLT) |
| Type de registre pris en charge | Centralisé + décentralisé | Registre basé sur DLT (minibons, obligations) |
| Autorité compétente | BaFin | AMF (titres) + ACPR (bancaire/AML) |
| Période de conservation | 10 ans | 5 ans |
| Document KYC — registre du commerce | Handelsregisterauszug | Extrait Kbis (≤ 3 mois) |
| Registre des bénéficiaires effectifs | Transparenzregister | Registre des Bénéficiaires Effectifs (RBE) |
| Questionnaire AML | Spécifique à GwG | AMF/ACPR spécifique à PSAN |
| Rapports TRACFIN | BaFin | AMF/ACPR transmettre à TRACFIN |

---

## Exigences documentaires KYC pour `FR_AMF` {#kyc-document-requirements-for-framf}

Le profil de juridiction `FR_AMF` dans `JurisdictionRequirementConfig` requiert :

- **Extrait Kbis** (≤ 3 mois du Greffe du Tribunal de Commerce)
- **Déclaration de bénéficiaires effectifs** du national RBE
- Statuts
- Pièces d'identité de tous les administrateurs et bénéficiaires effectifs
- Rapport annuel (2 dernières années si disponible)
- Questionnaire AML AMF/ACPR
- Déclaration de la source des fonds (pour les investissements supérieurs au seuil AMF)

---

## Minibons et titres financiers {#minibons-and-titres-financiers}

La loi française autorise la tokenisation de deux catégories d'instruments :

**Minibons** (instruments de dette de financement participatif) : obligations à court terme émises via des plateformes de financement participatif, désormais éligibles à l'émission basée sur DLT en vertu de la loi PACTE.

**Titres financiers** (instruments financiers) : instruments de capitaux propres et de dette de toute nature, éligibles à l'émission basée sur DLT par l'intermédiaire d'un Prestataire de Compensation (contrepartie centrale équivalente dans le contexte DLT).

Les deux sont représentés dans Registerwerk en utilisant [ERC-3643](../token-standards/erc3643.md) (lié à l'identité, réglementé) ou [ERC-3525](../token-standards/erc3525.md) (obligations tranchées). Le déploiement sous `FR_AMF` déclenche des vérifications supplémentaires :

1. Notification AMF du programme de jetons (stocké sous `Asset.regulatoryNotificationRef`)
2. Vérification de l'affectation ISIN
3. Contrôle d'exemption de prospectus (en dessous du seuil de 8 millions d'euros pour les minibons)

---

## Reporting MiFIR pour la France {#mifir-reporting-for-france}

L'applicabilité, la capacité de reporting, l'autorité compétente et le canal du MiFIR nécessitent un examen externe spécifique à la transaction et à l'instrument. Le service actuel [MiFIR](../compliance/mifir.md) produit un prototype XML
`DRAFT_UNVALIDATED` ; il n'a pas de stratégie `FR_AMF` et ne dépose ni ne prouve la livraison à l'AMF ou à une autre autorité.

---

## TRACFIN — Déclaration de transactions suspectes {#tracfin-suspicious-transaction-reporting}

La portée et le processus de déclaration des renseignements financiers de la France nécessitent un examen externe. Le module de filtrage de Registerwerk enregistre les exécutions de filtrage et les décisions d'examen des opérateurs, mais il ne soumet pas de déclaration Tracfin ni ne vérifie indépendamment une référence de déclaration.

---

## Déclaration d'incidents DORA (France) {#dora-incident-reporting-france}

Le champ d'application de l'autorité et les délais actuels de déclaration des incidents nécessitent un examen externe. Le module `dora`
n'achemine ni ne transmet les incidents à l'ACPR, à l'AMF ou à une autre autorité. Les valeurs ci-dessous
sont des hypothèses de conception historiques, non configurées pour le dépôt de preuves :

- Notification initiale : 4 heures à compter de la classification comme majeure
- Rapport intermédiaire : 72 heures
- Rapport final : 30 jours

Voir [DORA](../compliance/dora.md).
