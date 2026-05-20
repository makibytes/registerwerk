# Datenschutz-Folgenabschätzung (DSGVO Art. 35)
# Data Protection Impact Assessment — DE_EWPG Jurisdiction

**System:** Registerwerk  
**Jurisdiction:** DE — eWpG / BaFin / GwG  
**DPO:** [To fill in]  
**Date:** 2026-05-21  
**Status:** DRAFT — requires DPO + legal counsel sign-off before go-live

---

## 1. Necessity & Proportionality

**Processing:** Registerführung (registry maintenance) for tokenized electronic securities per eWpG.

**Necessity:** Legally required. eWpG §7 mandates the central register. GwG §10 mandates KYC. eWpG §15 mandates 10-year retention. Processing cannot be reduced below these statutory minima.

**Proportionality:** Data collected is the minimum required by eWpG and GwG. Natural-person PII is limited to directors and UBOs (GwG §3 threshold ≥25%). Investor PII is collected only where the investor is a natural person.

---

## 2. Risk Assessment

| Risk | Likelihood | Severity | Residual Risk | Control |
|---|---|---|---|---|
| Unauthorized disclosure of KYC PII | Medium | High | LOW | Role-based access; AES-256 at rest; TLS 1.3; audit log |
| Audit log tampering | Low | Critical | LOW | SHA-256 hash chain + WORM trigger + daily public anchor |
| Wallet key compromise | Low | Critical | LOW | KMS envelope encryption; no exportRaw endpoint; access logged |
| Sanctions screening miss | Low | High | LOW | Daily re-screen; dual-list (OpenSanctions + Refinitiv); 4-eyes acceptance |
| Data breach (hacker) | Low | High | MEDIUM | Network isolation; WAF (Kong bot-detection + ip-restriction); pen test annually |
| Unlawful deletion of registry entries | Very Low | Critical | LOW | WORM trigger; immutable audit log; DB role separation |
| Cross-border transfer without safeguards | Low | Medium | LOW | AWS eu-central-1; Standard Contractual Clauses |
| Subject access request delays | Low | Low | LOW | DSAR endpoints at /api/v1/me/dsar/ |

**Overall risk level:** MEDIUM — mitigated by controls described in ROPA.

---

## 3. High-Risk Processing Activities

| Activity | Art. 35 Trigger | DPIA Outcome |
|---|---|---|
| UBO data (PEP, sanctions status) | Special categories potential (political opinion proxy) | Justified by Art. 6(1)(c) legal obligation; Art. 9(2)(g) substantial public interest |
| Audit log — cannot be deleted | Art. 17(3)(b) exception applied | Justified: eWpG §15(3) 10-year retention is mandatory; documented in consent notice |
| Investor identity (natural persons) | Large-scale processing | Minimized: only wallet address + nominal amount unless natural-person investor |

---

## 4. Measures to Address Risks

1. **Data minimization:** Only data required by eWpG/GwG collected.
2. **Encryption:** AES-256-GCM for documents + KMS envelope for wallet keys.
3. **Access control:** `COMPLIANCE_OFFICER` role for KYC; `REGISTRY_ADMIN` with MFA for sensitive operations.
4. **Retention enforcement:** `KycMonitoringJob` enforces expiry; automated data-subject erasure at `POST /api/v1/me/dsar/erasure` (PII tombstone; audit hash preserved).
5. **Incident response:** DORA incident classification in `ict_incident`; breach notification within 72h per DSGVO Art. 33.
6. **Data subject rights:** DSAR endpoints implemented; response SLA 30 days.
7. **DPO consultation:** This DPIA requires DPO review before processing commences.

---

## 5. Consultation with DPO

**DPO name:** [To fill in]  
**DPO sign-off date:** [To fill in]  
**DPO opinion:** [To fill in]

---

## 6. Sign-Off

| Role | Name | Date |
|---|---|---|
| DPO | | |
| Legal Counsel | | |
| CTO | | |
| Managing Director | | |

*This DPIA must be reviewed annually and upon any significant change to processing activities.*
