# Analyse d'Impact relative à la Protection des Données (RGPD Art. 35)

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This is a repository draft, not an approved DPIA. The deployment controller and DPO must
    establish scope, necessity, proportionality, risks, mitigations, consultation requirements,
    ownership, approval, and review evidence before relying on it.
# Data Protection Impact Assessment — FR_AMF Jurisdiction

**System:** Registerwerk  
**Jurisdiction:** FR — AMF / ACPR / Code monétaire et financier / Loi PACTE  
**DPO:** [To fill in]  
**Date:** 2026-05-21  
**Status:** DRAFT — requires DPO + legal counsel sign-off before go-live

---

## 1. Cadre légal / Legal Framework

| Traitement | Base légale | Article RGPD |
|---|---|---|
| KYC / LCB-FT | Obligation légale — CMF Art. L561-5, Loi PACTE | Art. 6(1)(c) |
| Registre de valeurs mobilières tokenisées | Obligation légale — AMF DOC-2022-15 | Art. 6(1)(c) |
| Criblage sanctions / PPE | Obligation légale — R. 2016/847, EU 2023/1113 | Art. 6(1)(c) |
| Reporting MiFIR | Obligation légale — UE 600/2014 Art. 26 | Art. 6(1)(c) |
| Déclaration bénéficiaires effectifs | Obligation légale — Loi PACTE Art. 52 | Art. 6(1)(c) |

---

## 2. Évaluation des risques / Risk Assessment

| Risque | Probabilité | Gravité | Risque résiduel | Mesure |
|---|---|---|---|---|
| Accès non autorisé aux données KYC | Faible | Élevée | FAIBLE | RBAC; AES-256; TLS 1.3; journal d'audit |
| Non-conformité TRACFIN (déclaration de soupçon) | Faible | Élevée | FAIBLE | Flux TRACFIN via AMF/ACPR; COMPLIANCE_OFFICER role |
| Violation du registre (falsification) | Très faible | Critique | FAIBLE | Chaîne de hachage SHA-256; déclencheur WORM |
| Transfert hors EEE | Faible | Moyenne | FAIBLE | AWS eu-central-1; CCT (Clauses Contractuelles Types) |

---

## 3. Exigences spécifiques France / France-Specific Requirements

- **Extrait Kbis ≤ 3 mois:** Collecté via type de document `COMMERCIAL_REGISTER_EXTRACT`; âge vérifié dans `DocumentRequirement.maxAge`.
- **Déclaration des bénéficiaires effectifs:** Modèle `BeneficialOwner` (V12) per Loi PACTE, seuil 25%.
- **TRACFIN:** Déclaration de soupçon (SAR) via `POST /api/v1/admin/ict-incidents` (DORA) avec category=AML_SAR. Le document est envoyé manuellement au portail TRACFIN (ACPR).
- **Conservation:** 5 ans (LCB-FT); 10 ans pour le registre (équivalence avec eWpG).
- **Droits des personnes:** CNIL — accès via `GET /api/v1/me/dsar/export`; effacement via `POST /api/v1/me/dsar/erasure`.

---

## 4. Consultation de la CNIL

La CNIL recommande la consultation de l'autorité compétente pour les traitements de données à grande échelle relatifs aux valeurs mobilières tokenisées. Cette AIPD devra être soumise à la CNIL avant la mise en production.

---

## 5. Validation

| Rôle | Nom | Date |
|---|---|---|
| DPO | | |
| Responsable conformité AMF | | |
| Directeur Général | | |
