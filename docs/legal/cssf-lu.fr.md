---
title: Luxembourg — CSSF
description: Comment Registerwerk met en œuvre les exigences réglementaires luxembourgeoises CSSF pour les titres tokenisés.
---

# Luxembourg — CSSF {#luxembourg-cssf}

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Cette page enregistre les mappages de contrôle prévus et les hypothèses configurées. Il ne s'agit pas d'un avis juridique luxembourgeois
    ni d'une preuve de classification de l'instrument, d'autorisation réglementaire, de conformité,
    ou d'effet juridique. Obtenez un examen actuel spécifique aux instruments, aux opérateurs, aux services et au déploiement.

Le Luxembourg est le plus grand siège de fonds en Europe et une juridiction leader pour les instruments de fonds tokenisés. La **Commission de Surveillance du Secteur Financier (CSSF)** réglemente l'utilisation de la technologie des registres distribués (DLT) pour les instruments financiers en vertu de la Circulaire CSSF 19/732 et des directives ultérieures.

---

## Cadre réglementaire applicable {#applicable-regulatory-framework}

| Réglementation | Portée |
|---|---|
| CSSF Circulaire 19/732 | Calcul et administration de fonds basés sur DLT NAV |
| CSSF Circulaire 22/811 | Services de fonds DLT et instruments tokenisés |
| Loi AML 2004 (modifiée) | Obligations de diligence raisonnable envers la clientèle |
| Loi du 5 avril 1993 (secteur financier) | Agrément des entreprises d'investissement |
| MiCAR (UE) 2023/1114 | Fournisseurs de services sur crypto-actifs |
| DORA (UE) 2022/2554 | Résilience opérationnelle ICT |

---

## Différences clés par rapport à l'Allemagne {#key-differences-from-germany}

| Dimensions | DE (eWpG) | LU (CSSF) |
|---|---|---|
| Registre faisant autorité | La base de données est canonique (§16 eWpG) | La base de données est canonique (conseils CSSF) |
| Période de conservation | 10 ans | 5 ans |
| Applicabilité de MiCAR | Exonéré (jetons eWpG ≠ jetons de monnaie électronique) | S'applique aux services de crypto-actifs |
| Seuil UBO | 25% (GwG §3) | 25 % (loi AML, article 1(7)) |
| DD amélioré | PPE (GwG §10(2)) | PPE + pays tiers à haut risque |
| Registre des actionnaires | Non requis | Obligatoire pour les SICAV et SICAF |
| Déclaration sur l'origine des fonds | Facultatif | Obligatoire pour tous les clients |

---

## Exigences en matière de documents KYC pour `LU_CSSF` {#kyc-document-requirements-for-lucssf}

En plus des documents communs (certificat de constitution, extrait du registre du commerce), le profil de juridiction `LU_CSSF` nécessite :

- **Registre extrait des Bénéficiaires Effectifs (RBE)** — Registre des bénéficiaires effectifs luxembourgeois
- **Registre des actionnaires** — pour les sociétés d'investissement (SICAV/SICAF/SIF)
- **Déclaration d'origine des fonds** — signée par le représentant légal du client
- **Questionnaire AML spécifique à CSSF**
- Rapports annuels (2 dernières années)

Voir [KYC & AML](../compliance/kyc-aml.md) pour le cycle de vie complet du document.

---

## Caractéristiques des jetons de fonds {#fund-token-specifics}

Le Luxembourg est le siège principal des instruments de fonds tokenisés. Registerwerk prend en charge les normes de jetons préférées par CSSF pour ce cas d'utilisation :

| Type d'instrument | Norme de jeton | Prise en charge du travail d'enregistrement |
|---|---|---|
| Fonds synchrone (NAV quotidien) | [ERC-4626](../token-standards/erc4626.md) | Complet — `AssetVaultState`, `VaultNavStrike` |
| Fonds asynchrone (T+1 / T+2) | [ERC-7540](../token-standards/erc7540.md) | Complet — `VaultRequest`, flux de demande/réclamation |
| Obligation à tranches | [ERC-3525](../token-standards/erc3525.md) | Complet — `AssetSlot` (tranche) |
| Actions/obligations réglementées | [ERC-3643](../token-standards/erc3643.md) | Complet – T-REX lié à l'identité |

L'entité `AssetVaultState` suit la NAV par action. `VaultNavStrike` enregistre chaque point de calcul de la NAV, donnant aux régulateurs une piste d'audit horodatée de toutes les décisions de tarification.

---

## Calendrier de règlement {#settlement-timing}

Les obligations de règlement actuelles nécessitent un examen externe. Le module `trading` peut enregistrer un horodatage
`settledAt`, mais le prototype [MiFIR](../compliance/mifir.md) ne valide pas le statut de règlement
ni une fenêtre de règlement réglementaire avant de sélectionner les lignes.

---

## Rapport d'incident CSSF {#cssf-incident-reporting}

Sous DORA Art. 19 (transposé au Luxembourg via la loi d'exécution DORA), les incidents majeurs ICT doivent être signalés au CSSF :

- **Notification initiale** : dans les 4 heures ouvrables suivant la classification comme majeur
- **Rapport intermédiaire** : dans les 72 heures
- **Rapport final** : dans un délai d'un mois

Le `DoraService` stocke les incidents classés manuellement et les horodatages de rappel d'application. Il
ne détermine pas la classification/les délais légalement corrects, ni n'achemine de notifications vers la CSSF.
Voir [DORA](../compliance/dora.md).

---

## Obligations MiCAR (LU_CSSF) {#micar-obligations-lucssf}

La transposition luxembourgeoise de MiCAR le rend applicable aux prestataires de services sur crypto-actifs opérant depuis le Luxembourg. Pour les déploiements Registerwerk avec `LU_CSSF` comme juridiction principale :

- L'opérateur doit détenir une licence CASP de CSSF (ou une licence passeportable d'un autre État membre de l'UE)
- La [Travel Rule](../compliance/travel-rule.md) s'applique à tous les transferts de crypto-actifs ≥ 1 000 €
- Le composant [DAC8/CARF](../compliance/dac8.md) produit une sortie prototype `DRAFT_UNVALIDATED` ; il
  ne dépose pas auprès de l'ACD et ne prouve pas la livraison ou l'acceptation par l'autorité
