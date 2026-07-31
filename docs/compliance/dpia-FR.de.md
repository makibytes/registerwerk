---
title: DPIA — Frankreich
description: Entwurf einer Datenschutz-Folgenabschätzung für die Jurisdiktion FR_AMF — erfordert die Freigabe durch DPO und Rechtsberatung vor dem Go-Live.
---

# Analyse d'Impact relative à la Protection des Données (RGPD Art. 35) { #analyse-dimpact-relative-à-la-protection-des-données-rgpd-art-35 }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Dies ist ein Repository-Entwurf, kein genehmigter DPIA. Der Einsatzverantwortliche und der DPO
    müssen Umfang, Notwendigkeit, Verhältnismäßigkeit, Risiken, Abhilfemaßnahmen,
    Konsultationspflichten, Zuständigkeit, Genehmigung und Prüfnachweise festlegen, bevor sie sich
    darauf verlassen.

# Datenschutz-Folgenabschätzung – Jurisdiktion FR_AMF { #data-protection-impact-assessment-fr_amf-jurisdiction }

**System:** Registerwerk  
**Jurisdiktion:** FR — AMF / ACPR / Code monétaire et financier / Loi PACTE  
**DPO:** [Ausfüllen]  
**Datum:** 21.05.2026  
**Status:** ENTWURF — erfordert Freigabe durch DPO und Rechtsberatung vor dem Go-Live

---

## 1. Cadre légal / Rechtlicher Rahmen { #1-cadre-legal-rechtlicher-rahmen }

| Verarbeitung | Rechtsgrundlage | Artikel DSGVO |
|---|---|---|
| KYC / LCB-FT | Gesetzliche Verpflichtung — CMF Art. L561-5, Loi PACTE | Art. 6(1)(c) |
| Register für tokenisierte Wertpapiere | Gesetzliche Verpflichtung — AMF DOC-2022-15 | Art. 6(1)(c) |
| Sanktions-/PPE-Screening | Gesetzliche Verpflichtung — R. 2016/847, EU 2023/1113 | Art. 6(1)(c) |
| MiFIR-Meldewesen | Gesetzliche Verpflichtung — UE 600/2014 Art. 26 | Art. 6(1)(c) |
| Meldung wirtschaftlich Berechtigter | Gesetzliche Verpflichtung — Loi PACTE Art. 52 | Art. 6(1)(c) |

---

## 2. Évaluation des risques / Risikobewertung { #2-evaluation-des-risques-risikobewertung }

| Risiko | Wahrscheinlichkeit | Schweregrad | Restrisiko | Maßnahme |
|---|---|---|---|---|
| Unbefugter Zugriff auf KYC-Daten | Niedrig | Hoch | Niedrig | RBAC; AES-256; TLS 1.3; Audit-Log |
| Nichteinhaltung von TRACFIN (Verdachtsmeldung) | Niedrig | Hoch | Niedrig | TRACFIN-Meldefluss über AMF/ACPR; Rolle `COMPLIANCE_OFFICER` |
| Verletzung des Registers (Fälschung) | Sehr niedrig | Kritisch | Niedrig | SHA-256-Hash-Kette; WORM-Trigger |
| Übertragung außerhalb des EWR | Niedrig | Mittel | Niedrig | AWS eu-central-1; Standardvertragsklauseln (CCT / SCC) |

---

## 3. Exigences spécifiques France / Frankreich-spezifische Anforderungen { #3-exigences-specifiques-france-frankreich-spezifische-anforderungen }

- **Extrait Kbis ≤ 3 Monate:** Erfasst über den Dokumenttyp `COMMERCIAL_REGISTER_EXTRACT`; Alter wird in `DocumentRequirement.maxAge` geprüft.
- **Meldung wirtschaftlich Berechtigter:** Modell `BeneficialOwner` (V12) gemäß Loi PACTE, Schwellenwert 25 %.
- **TRACFIN:** Verdachtsmeldung (SAR) über `POST /api/v1/admin/ict-incidents` (DORA) mit category=AML_SAR. Das Dokument wird manuell an das TRACFIN-Portal (ACPR) übermittelt.
- **Aufbewahrung:** 5 Jahre (LCB-FT); 10 Jahre für das Register (Äquivalenz zum eWpG).
- **Rechte der betroffenen Personen:** CNIL — Zugriff über `GET /api/v1/me/dsar/export`; Löschung über `POST /api/v1/me/dsar/erasure`.

---

## 4. Consultation de la CNIL { #4-consultation-de-la-cnil }

Die CNIL empfiehlt die Konsultation der zuständigen Behörde bei umfangreichen (großmaßstäblichen) Datenverarbeitungen im Zusammenhang mit tokenisierten Wertpapieren. Diese AIPD muss vor der Inbetriebnahme der CNIL vorgelegt werden.

---

## 5. Validation / Freigabe { #5-validation-freigabe }

| Rolle | Name | Datum |
|---|---|---|
| DPO | | |
| Responsable conformité AMF | | |
| Directeur Général | | |
