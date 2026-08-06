---
title: DPIA — Liechtenstein
description: Analyse d'impact relative à la protection des données (DPIA) en projet pour la juridiction LI_TVTG — nécessite la validation du DPO et d'un conseiller juridique avant la mise en production.
---

# Datenschutz-Folgenabschätzung (DSGVO Art. 35) — Liechtenstein / TVTG

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Il s'agit d'une ébauche de dépôt, et non d'une DPIA approuvée. Le responsable du traitement pour ce déploiement
    et le DPO doivent établir la portée, la nécessité, la proportionnalité, les risques, les mesures d'atténuation,
    les exigences de consultation, la propriété, l'approbation et les éléments de preuve de la revue avant de s'y
    fier.

# Analyse d'impact relative à la protection des données — Juridiction LI_TVTG

**Système :** Registerwerk  
**Juridiction :** LI — FMA / TVTG (Token- und VT-Dienstleister-Gesetz) / SPG (Sorgfaltspflichtgesetz)  
**DPO :** [À remplir]  
**Date :** 2026-05-21  
**Statut :** DRAFT — nécessite la validation du DPO et d'un conseiller juridique avant la mise en production

---

## 1. Rechtsgrundlage / Base juridique

| Traitement | Base juridique | Article DSGVO |
|---|---|---|
| KYC / obligations de diligence (Sorgfaltspflichten) | Obligation légale — SPG art. 3-5 ; TVTG §29-31 | Art. 6(1)(c) |
| Registre des titres VT (VT-Wertpapierregister) | Obligation légale — TVTG §3 (modèle du conteneur de jetons) | Art. 6(1)(c) |
| Filtrage des sanctions / PPE | Obligation légale — SPG art. 6 ; UE 2023/1113 (adaptation TVTG) | Art. 6(1)(c) |
| Document d'information sur le jeton (Token-Informationsdokument) | Obligation légale — TVTG §9 | Art. 6(1)(b) |

---

## 2. Évaluation des risques

| Risque | Probabilité | Gravité | Risque résiduel | Mesure |
|---|---|---|---|---|
| Accès non autorisé aux données KYC | Faible | Élevée | FAIBLE | RBAC ; AES-256 ; TLS 1.3 ; journal d'audit |
| Faille de sécurité du smart contract | Faible | Élevée | FAIBLE | Audit Trail of Bits / OpenZeppelin (obligation TVTG) |
| Falsification du registre | Très faible | Critique | FAIBLE | Chaîne de hachage SHA-256 ; déclencheur WORM ; ancrage quotidien |
| Transfert transfrontalier de données | Faible | Moyenne | FAIBLE | AWS eu-central-1 (EEE) ; SCK |

---

## 3. Exigences spécifiques au Liechtenstein

- **TVTG §9 document d'information sur le jeton :** champ obligatoire dans le type de document KYC `TOKEN_WHITEPAPER` ; signé numériquement via PAdES.
- **Audit du smart contract :** le TVTG exige un audit de sécurité indépendant. Le type de document `SMART_CONTRACT_AUDIT` est configuré comme champ obligatoire dans `JurisdictionRequirementConfig.buildLiTvtg()`.
- **Obligation de déclaration à la FMA :** les prestataires de services TT (TT-Dienstleister) au sens du TVTG §12 doivent être déclarés à la FMA. Inscription dans le registre `third_party_provider` (V18).
- **Obligations de diligence SPG :** déclaration des ayants droit économiques (bénéficiaires effectifs ≥ 25 %) via l'entité `BeneficialOwner` (V12) ; conforme au SPG.
- **Conservation :** 10 ans (TVTG §33) ; 5 ans pour les documents LCB-FT (SPG art. 7).
- **Droits des personnes concernées :** le DSGVO s'applique directement au Liechtenstein (EEE). Accès : `GET /api/v1/me/dsar/export` ; effacement : `POST /api/v1/me/dsar/erasure`.

---

## 4. Protection des données et Travel Rule (adaptation TVTG TFR)

Le Liechtenstein a transposé le règlement européen sur les transferts de fonds (TFR, règlement UE 2023/1113) en tant que membre de l'EEE. Seuil : 1 000 EUR (adaptation TVTG). Le `TravelRuleService` est configuré pour LI_TVTG.

---

## 5. Approbation

| Rôle | Nom | Date |
|---|---|---|
| Délégué à la protection des données | | |
| Responsable conformité FMA | | |
| Directeur général | | |
