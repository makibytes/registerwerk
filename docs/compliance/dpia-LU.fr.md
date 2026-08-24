---
title: DPIA — Luxembourg
description: Analyse d'impact relative à la protection des données (DPIA) en projet pour la juridiction LU_CSSF — nécessite la validation du DPO et d'un conseiller juridique avant la mise en production.
---

# Analyse d'impact relative à la protection des données — Juridiction LU_CSSF

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Il s'agit d'une ébauche de dépôt, et non d'une DPIA approuvée. Le responsable du traitement pour ce déploiement
    et le DPO doivent établir la portée, la nécessité, la proportionnalité, les risques, les mesures d'atténuation,
    les exigences de consultation, la propriété, l'approbation et les éléments de preuve de la revue avant de s'y
    fier.

# Évaluation d'Impact sur la Protection des Données (RGPD Art. 35)

**Système :** Registerwerk  
**Juridiction :** LU — CSSF / Loi du 5 août 2005 / Loi AML de 2004  
**DPO :** [À remplir]  
**Date :** 2026-05-21  
**Statut :** DRAFT — nécessite la validation du DPO et d'un conseiller juridique avant la mise en production

---

## 1. Base juridique du traitement à haut risque

| Traitement | Base juridique | Article RGPD |
|---|---|---|
| KYC / diligence raisonnable client | Obligation légale — Loi AML de 2004 art. 3, circulaire CSSF 19/732 | Art. 6(1)(c) |
| Tenue du registre de valeurs mobilières | Obligation légale — circulaire CSSF 22/811 (instruments basés sur la DLT) | Art. 6(1)(c) |
| Filtrage sanctions / PPE | Obligation légale — Loi AML de 2004 art. 3(4), règlement UE 2580/2001 | Art. 6(1)(c) |
| Déclaration des transactions MiFIR | Obligation légale — règlement UE 600/2014 (MiFIR) art. 26 | Art. 6(1)(c) |
| Rapports MiCAR CASP | Obligation légale — règlement UE 2023/1114 art. 60 | Art. 6(1)(c) |

---

## 2. Évaluation des risques

| Risque | Probabilité | Gravité | Risque résiduel | Contrôle |
|---|---|---|---|---|
| Divulgation de données personnelles KYC à des tiers non autorisés | Faible | Élevé | LOW | RBAC ; AES-256 ; TLS 1.3 ; piste d'audit |
| Violation des données du RBE (Registre des Bénéficiaires Effectifs) | Faible | Élevé | LOW | Rôle COMPLIANCE_OFFICER restreint ; principe des quatre yeux |
| Transfert de données hors de l'EEE | Faible | Moyenne | LOW | AWS eu-central-1 ; CCT |
| Sanction non détectée lors du contrôle | Faible | Élevé | LOW | Actualisation quotidienne d'OpenSanctions ; acceptation à quatre yeux |
| Falsification du registre | Très faible | Critique | LOW | Chaîne de hachage SHA-256 ; déclencheur WORM ; ancrage quotidien |
| Retards de traitement des demandes d'accès des personnes concernées (SLA 30 jours) | Faible | Faible | LOW | Points de terminaison DSAR mis en œuvre |

**Risque global :** MEDIUM — atténué par des mesures techniques et organisationnelles.

---

## 3. Exigences spécifiques au Luxembourg

- **Registre des Bénéficiaires Effectifs (RBE) :** extrait des bénéficiaires effectifs stocké et actualisé conformément à la loi AML de 2004, art. 3.
- **Circulaire CSSF 19/732 :** questionnaire LCB-FT collecté par émetteur ; stocké sous le type de document KYC `AML_QUESTIONNAIRE`.
- **Circulaire CSSF 22/811 :** le dépôt contient des composants de registre orientés DLT, mais aucune désignation du teneur de registre spécifique à l'instrument ni aucune preuve de notification à la CSSF. Ces deux éléments bloquent la mise en production.
- **Conservation :** 5 ans après la fin de la relation d'affaires conformément à la loi AML de 2004, art. 4 (KYC) ; 10 ans pour le registre (politique d'équivalence avec l'eWpG).
- **Droits des personnes concernées :** le RGPD s'applique directement au Luxembourg. Point de terminaison DSAR : `GET /api/v1/me/dsar/export`, effacement : `POST /api/v1/me/dsar/erasure`.

---

## 4. Considérations transfrontalières

Des entités luxembourgeoises peuvent détenir des titres émis sous les juridictions DE_EWPG ou FR_AMF. Les flux de données transfrontaliers entre juridictions de l'opérateur utilisent :

- TLS 1.3 en transit
- AWS eu-central-1 (EEE) pour le stockage
- Clauses contractuelles types (CCT) pour tout sous-traitant hors EEE

---

## 5. Signature

| Rôle | Nom | Date |
|---|---|---|
| DPO | | |
| Responsable conformité CSSF | | |
| Directeur général | | |
