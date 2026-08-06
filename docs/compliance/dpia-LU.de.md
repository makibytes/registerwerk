---
title: DPIA — Luxemburg
description: Entwurf einer Datenschutz-Folgenabschätzung für die Jurisdiktion LU_CSSF — erfordert die Freigabe durch DPO und Rechtsberatung vor dem Go-Live.
---

# Datenschutz-Folgenabschätzung – Jurisdiktion LU_CSSF { #data-protection-impact-assessment-lu_cssf-jurisdiction }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Dies ist ein Repository-Entwurf, kein genehmigter DPIA. Der Einsatzverantwortliche und der DPO
    müssen Umfang, Notwendigkeit, Verhältnismäßigkeit, Risiken, Abhilfemaßnahmen,
    Konsultationspflichten, Zuständigkeit, Genehmigung und Prüfnachweise festlegen, bevor sie sich
    darauf verlassen.

# Évaluation d'Impact sur la Protection des Données (RGPD Art. 35) { #évaluation-dimpact-sur-la-protection-des-données-rgpd-art-35 }

**System:** Registerwerk  
**Jurisdiktion:** LU — CSSF / Loi du 5 août 2005 / AML Law 2004  
**DPO:** [Ausfüllen]  
**Datum:** 21.05.2026  
**Status:** ENTWURF — erfordert Freigabe durch DPO und Rechtsberatung vor dem Go-Live

---

## 1. Rechtsgrundlage für die Verarbeitung mit hohem Risiko { #1-legal-basis-for-high-risk-processing }

| Verarbeitung | Rechtsgrundlage | DSGVO-Artikel |
|---|---|---|
| KYC / Sorgfaltspflichten gegenüber Kunden | Gesetzliche Verpflichtung — AML-Gesetz 2004 Art. 3, CSSF-Rundschreiben 19/732 | Art. 6(1)(c) |
| Führung des Wertpapierregisters | Gesetzliche Verpflichtung — CSSF-Rundschreiben 22/811 (DLT-basierte Instrumente) | Art. 6(1)(c) |
| Sanktions-/PEP-Screening | Gesetzliche Verpflichtung — AML-Gesetz 2004 Art. 3(4), EU-Verordnung 2580/2001 | Art. 6(1)(c) |
| MiFIR-Transaktionsmeldung | Gesetzliche Verpflichtung — EU-Verordnung 600/2014 (MiFIR) Art. 26 | Art. 6(1)(c) |
| MiCAR-CASP-Berichterstattung | Gesetzliche Verpflichtung — EU-Verordnung 2023/1114 Art. 60 | Art. 6(1)(c) |

---

## 2. Risikobewertung { #2-risk-assessment }

| Risiko | Wahrscheinlichkeit | Schweregrad | Restrisiko | Kontrolle |
|---|---|---|---|---|
| Weitergabe von KYC-PII an Unbefugte | Niedrig | Hoch | Niedrig | RBAC; AES-256; TLS 1.3; Audit-Trail |
| Verletzung der RBE-Daten (Register des Bénéficiaires Effectifs) | Niedrig | Hoch | Niedrig | Eingeschränkte `COMPLIANCE_OFFICER`-Rolle; Vier-Augen-Prinzip |
| Datenübertragung außerhalb des EWR | Niedrig | Mittel | Niedrig | AWS eu-central-1; Standardvertragsklauseln (SCCs) |
| Sanktionsliste verpasst | Niedrig | Hoch | Niedrig | Tägliche Aktualisierung von OpenSanctions; Vier-Augen-Annahme |
| Manipulation des Registers | Sehr niedrig | Kritisch | Niedrig | SHA-256-Hash-Kette; WORM-Trigger; täglicher Anker |
| Verzögerungen bei Betroffenenanfragen (30-Tage-SLA) | Niedrig | Niedrig | Niedrig | Implementierte DSAR-Endpunkte |

**Gesamtrisiko:** Mittel — gemindert durch technische und organisatorische Maßnahmen.

---

## 3. Luxemburg-spezifische Anforderungen { #3-specific-luxembourg-requirements }

- **Registre des Bénéficiaires Effectifs (RBE):** UBO-Extrakt wird gemäß AML-Gesetz 2004 Art. 3 gespeichert und aktualisiert.
- **CSSF-Rundschreiben 19/732:** AML-Fragebogen pro Emittent erhoben; gespeichert als KYC-Dokumenttyp `AML_QUESTIONNAIRE`.
- **CSSF-Rundschreiben 22/811:** Das Repository enthält DLT-orientierte Registerkomponenten, aber keine instrumentenspezifische Bestimmung der registerführenden Stelle oder CSSF-Meldenachweise. Beide sind Go-Live-Blocker.
- **Aufbewahrung:** 5 Jahre nach Ende der Geschäftsbeziehung gemäß AML-Gesetz 2004 Art. 4 (KYC); 10 Jahre für das Register (Äquivalenzregelung eWpG/CSSF).
- **Rechte der betroffenen Person:** Die DSGVO gilt in Luxemburg unmittelbar. DSAR-Endpunkt: `GET /api/v1/me/dsar/export`, Löschung: `POST /api/v1/me/dsar/erasure`.

---

## 4. Grenzüberschreitende Erwägungen { #4-cross-border-considerations }

Luxemburgische Rechtsträger können Wertpapiere halten, die unter den Jurisdiktionen DE_EWPG oder FR_AMF ausgegeben wurden. Für grenzüberschreitende Datenströme zwischen Betreiberjurisdiktionen wird Folgendes verwendet:

- TLS 1.3 bei der Übertragung
- AWS eu-central-1 (EWR) für die Speicherung
- Standardvertragsklauseln (SCCs) für alle Unterauftragsverarbeiter außerhalb des EWR

---

## 5. Freigabe { #5-sign-off }

| Rolle | Name | Datum |
|---|---|---|
| DPO | | |
| CSSF-Compliance-Beauftragter | | |
| Geschäftsführer | | |
