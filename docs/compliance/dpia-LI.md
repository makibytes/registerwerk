# Datenschutz-Folgenabschätzung (DSGVO Art. 35) — Liechtenstein / TVTG

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    This is a repository draft, not an approved DPIA. The deployment controller and DPO must
    establish scope, necessity, proportionality, risks, mitigations, consultation requirements,
    ownership, approval, and review evidence before relying on it.
# Data Protection Impact Assessment — LI_TVTG Jurisdiction

**System:** Registerwerk  
**Jurisdiction:** LI — FMA / TVTG (Token- und VT-Dienstleister-Gesetz) / SPG (Sorgfaltspflichtgesetz)  
**DPO:** [To fill in]  
**Date:** 2026-05-21  
**Status:** DRAFT — requires DPO + legal counsel sign-off before go-live

---

## 1. Rechtsgrundlage / Legal Basis

| Verarbeitung | Rechtsgrundlage | DSGVO-Artikel |
|---|---|---|
| KYC / Sorgfaltspflichten | Gesetzliche Pflicht — SPG Art. 3-5; TVTG §29-31 | Art. 6(1)(c) |
| VT-Wertpapierregister | Gesetzliche Pflicht — TVTG §3 (Token-Container-Modell) | Art. 6(1)(c) |
| Sanktionsprüfung / PEP | Gesetzliche Pflicht — SPG Art. 6; EU 2023/1113 (TVTG-Anpassung) | Art. 6(1)(c) |
| Token-Informationsdokument | Gesetzliche Pflicht — TVTG §9 | Art. 6(1)(b) |

---

## 2. Risikobeurteilung / Risk Assessment

| Risiko | Eintrittswahrscheinlichkeit | Schwere | Restrisiko | Maßnahme |
|---|---|---|---|---|
| Unbefugter Zugriff auf KYC-Daten | Gering | Hoch | NIEDRIG | RBAC; AES-256; TLS 1.3; Audit-Log |
| Smart-Contract-Sicherheitslücke | Gering | Hoch | NIEDRIG | Trail of Bits / OpenZeppelin Audit (TVTG-Pflicht) |
| Registerfälschung | Sehr gering | Kritisch | NIEDRIG | SHA-256-Hashkette; WORM-Trigger; täglicher Anker |
| Grenzüberschreitende Datenübermittlung | Gering | Mittel | NIEDRIG | AWS eu-central-1 (EWR); SCK |

---

## 3. Liechtenstein-spezifische Anforderungen

- **TVTG §9 Token-Informationsdokument:** Pflichtfeld im KYC-Dokumententyp `TOKEN_WHITEPAPER`; digital signiert via PAdES.
- **Smart-Contract-Audit:** TVTG verlangt unabhängiges Sicherheitsaudit. Dokumenttyp `SMART_CONTRACT_AUDIT` ist als Pflichtfeld in `JurisdictionRequirementConfig.buildLiTvtg()` konfiguriert.
- **FMA-Meldepflicht:** TT-Dienstleister nach TVTG §12 müssen der FMA gemeldet werden. Eintrag im `third_party_provider`-Register (V18).
- **SPG Sorgfaltspflichten:** WB-Erklärung (wirtschaftlich Berechtigte ≥ 25%) per `BeneficialOwner`-Entität (V12); SPG-konform.
- **Aufbewahrung:** 10 Jahre (TVTG §33); 5 Jahre AML-Dokumente (SPG Art. 7).
- **Betroffenenrechte:** DSGVO gilt direkt in Liechtenstein (EWR). Zugang: `GET /api/v1/me/dsar/export`; Löschung: `POST /api/v1/me/dsar/erasure`.

---

## 4. Datenschutz und Travel Rule (TVTG-Anpassung TFR)

Liechtenstein hat die EU-Transfer of Funds Regulation (TFR, Reg EU 2023/1113) als EWR-Mitglied übernommen. Schwellenwert: EUR 1.000 (TVTG-Anpassung). TravelRuleService ist konfiguriert für LI_TVTG.

---

## 5. Genehmigung

| Rolle | Name | Datum |
|---|---|---|
| Datenschutzbeauftragter | | |
| FMA-Compliance-Beauftragter | | |
| Geschäftsführer | | |
