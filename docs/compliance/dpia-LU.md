# Data Protection Impact Assessment — LU_CSSF Jurisdiction

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This is a repository draft, not an approved DPIA. The deployment controller and DPO must
    establish scope, necessity, proportionality, risks, mitigations, consultation requirements,
    ownership, approval, and review evidence before relying on it.
# Évaluation d'Impact sur la Protection des Données (RGPD Art. 35)

**System:** Registerwerk  
**Jurisdiction:** LU — CSSF / Loi du 5 août 2005 / AML Law 2004  
**DPO:** [To fill in]  
**Date:** 2026-05-21  
**Status:** DRAFT — requires DPO + legal counsel sign-off before go-live

---

## 1. Legal Basis for High-Risk Processing

| Processing | Legal Basis | GDPR Article |
|---|---|---|
| KYC / customer due diligence | Legal obligation — AML Law 2004 Art. 3, CSSF Circular 19/732 | Art. 6(1)(c) |
| Securities registry maintenance | Legal obligation — CSSF Circular 22/811 (DLT-based instruments) | Art. 6(1)(c) |
| Sanctions / PEP screening | Legal obligation — AML Law 2004 Art. 3(4), EU Regulation 2580/2001 | Art. 6(1)(c) |
| MiFIR transaction reporting | Legal obligation — EU Regulation 600/2014 (MiFIR) Art. 26 | Art. 6(1)(c) |
| MiCAR CASP reporting | Legal obligation — EU Regulation 2023/1114 Art. 60 | Art. 6(1)(c) |

---

## 2. Risk Assessment

| Risk | Likelihood | Severity | Residual Risk | Control |
|---|---|---|---|---|
| Disclosure of KYC PII to unauthorized parties | Low | High | LOW | RBAC; AES-256; TLS 1.3; audit trail |
| Breach of RBE (Register des Bénéficiaires Effectifs) data | Low | High | LOW | Restricted COMPLIANCE_OFFICER role; 4-eyes |
| Data transfer outside EEA | Low | Medium | LOW | AWS eu-central-1; SCCs |
| Sanctions list miss | Low | High | LOW | OpenSanctions daily refresh; 4-eyes acceptance |
| Registry tampering | Very Low | Critical | LOW | SHA-256 hash chain; WORM trigger; daily anchor |
| Subject access request delays (30-day SLA) | Low | Low | LOW | Implemented DSAR endpoints |

**Overall risk:** MEDIUM — mitigated by technical and organizational measures.

---

## 3. Specific Luxembourg Requirements

- **Registre des Bénéficiaires Effectifs (RBE):** UBO extract stored and refreshed per AML Law 2004 Art. 3.
- **CSSF Circular 19/732:** AML questionnaire collected per issuer; stored as KYC document type `AML_QUESTIONNAIRE`.
- **CSSF Circular 22/811:** The repository contains DLT-oriented registry components, but no
  instrument-specific registrar determination or CSSF notification evidence. Both are go-live blockers.
- **Retention:** 5 years post-relationship end per AML Law 2004 Art. 4 (KYC); 10 years for registry (eWpG/CSSF equivalence policy).
- **Data subject rights:** GDPR applies directly in Luxembourg. DSAR endpoint: `GET /api/v1/me/dsar/export`, erasure: `POST /api/v1/me/dsar/erasure`.

---

## 4. Cross-Border Considerations

Luxembourg entities may hold securities issued under DE_EWPG or FR_AMF jurisdictions. Cross-border data flows between operator jurisdictions use:
- TLS 1.3 in transit
- AWS eu-central-1 (EEA) for storage
- Standard Contractual Clauses (SCCs) for any non-EEA sub-processors

---

## 5. Sign-Off

| Role | Name | Date |
|---|---|---|
| DPO | | |
| CSSF-Compliance Officer | | |
| Managing Director | | |
