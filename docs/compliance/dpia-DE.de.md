---
title: DPIA — Deutschland
description: Entwurf einer Datenschutz-Folgenabschätzung für die Jurisdiktion DE_EWPG — erfordert die Freigabe durch DPO und Rechtsberatung vor dem Go-Live.
---

# Datenschutz-Folgenabschätzung (DSGVO Art. 35) { #datenschutz-folgenabschatzung-dsgvo-art-35 }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Dies ist ein Repository-Entwurf, keine genehmigte Datenschutz-Folgenabschätzung. Der Einsatzverantwortliche
    und der DPO müssen Umfang, Erforderlichkeit, Verhältnismäßigkeit, Risiken, Abhilfemaßnahmen,
    Konsultationspflichten, Zuständigkeit, Genehmigung und Prüfnachweise festlegen, bevor sie sich darauf
    verlassen.

# Datenschutz-Folgenabschätzung – Jurisdiktion DE_EWPG { #data-protection-impact-assessment-de_ewpg-jurisdiction }

**System:** Registerwerk  
**Jurisdiktion:** DE — eWpG / BaFin / GwG  
**DPO:** [Ausfüllen]  
**Datum:** 21.05.2026  
**Status:** ENTWURF — erfordert Freigabe durch DPO und Rechtsberatung vor dem Go-Live

---

## 1. Erforderlichkeit & Verhältnismäßigkeit { #1-necessity-proportionality }

**Verarbeitung:** Registerführung für tokenisierte elektronische Wertpapiere gemäß eWpG.

**Erforderlichkeit:** Gesetzlich vorgeschrieben. §7 eWpG schreibt das Zentralregister vor. §10 GwG schreibt KYC vor. §15 eWpG schreibt eine 10-jährige Aufbewahrung vor. Die Verarbeitung darf diese gesetzlichen Mindestanforderungen nicht unterschreiten.

**Verhältnismäßigkeit:** Die erhobenen Daten entsprechen dem nach eWpG und GwG erforderlichen Minimum. Personenbezogene Daten natürlicher Personen sind auf Geschäftsführer und wirtschaftlich Berechtigte beschränkt (Schwellenwert §3 GwG ≥25 %). Personenbezogene Daten von Anlegern werden nur erhoben, wenn der Anleger eine natürliche Person ist.

---

## 2. Risikobewertung { #2-risk-assessment }

| Risiko | Wahrscheinlichkeit | Schweregrad | Restrisiko | Kontrolle |
|---|---|---|---|---|
| Unbefugte Offenlegung von KYC-PII | Mittel | Hoch | Niedrig | Rollenbasierter Zugriff; AES-256 im Ruhezustand; TLS 1.3; Audit-Log |
| Manipulation des Audit-Logs | Niedrig | Kritisch | Niedrig | SHA-256-Hash-Kette + WORM-Trigger + täglicher öffentlicher Anker |
| Kompromittierung des Wallet-Schlüssels | Niedrig | Kritisch | Niedrig | KMS-Envelope-Verschlüsselung; kein exportRaw-Endpunkt; Zugriff protokolliert |
| Lücke bei der Sanktionsprüfung | Niedrig | Hoch | Niedrig | Tägliche Neuprüfung; Doppelliste (OpenSanctions + Refinitiv); Vier-Augen-Annahme |
| Datenschutzverletzung (Hacker) | Niedrig | Hoch | Mittel | Netzwerkisolation; WAF (Kong Bot-Erkennung + IP-Beschränkung); jährlicher Penetrationstest |
| Unrechtmäßige Löschung von Registereinträgen | Sehr niedrig | Kritisch | Niedrig | WORM-Trigger; unveränderliches Audit-Log; Trennung der DB-Rollen |
| Grenzüberschreitende Übermittlung ohne Garantien | Niedrig | Mittel | Niedrig | AWS eu-central-1; Standardvertragsklauseln |
| Verzögerungen bei Betroffenenanfragen | Niedrig | Niedrig | Niedrig | DSAR-Endpunkte unter /api/v1/me/dsar/ |

**Gesamtrisikostufe:** Mittel – gemildert durch die im ROPA beschriebenen Kontrollen.

---

## 3. Verarbeitungstätigkeiten mit hohem Risiko { #3-high-risk-processing-activities }

| Tätigkeit | Auslöser nach Art. 35 | Ergebnis der Folgenabschätzung |
|---|---|---|
| UBO-Daten (PEP, Sanktionsstatus) | Mögliche besondere Kategorie (Näherungswert für politische Meinung) | Gerechtfertigt durch Art. 6(1)(c) gesetzliche Verpflichtung; Art. 9(2)(g) erhebliches öffentliches Interesse |
| Audit-Log – kann nicht gelöscht werden | Ausnahme nach Art. 17(3)(b) angewendet | Gerechtfertigt: §15(3) eWpG zwingt zur 10-jährigen Aufbewahrung; in der Einwilligungserklärung dokumentiert |
| Anlegeridentität (natürliche Personen) | Umfangreiche Verarbeitung | Minimiert: nur Wallet-Adresse + Nennbetrag, außer bei natürlichen Personen als Anleger |

---

## 4. Maßnahmen zur Risikobewältigung { #4-measures-to-address-risks }

1. **Datenminimierung:** Es werden nur die nach eWpG/GwG erforderlichen Daten erhoben.
2. **Verschlüsselung:** AES-256-GCM für Dokumente + KMS-Envelope für Wallet-Schlüssel.
3. **Zugriffskontrolle:** Rolle `COMPLIANCE_OFFICER` für KYC; `REGISTRY_ADMIN` mit MFA für sensible Vorgänge.
4. **Durchsetzung der Aufbewahrung:** `KycMonitoringJob` erzwingt den Ablauf; automatisierte Löschung auf Antrag der betroffenen Person unter `POST /api/v1/me/dsar/erasure` (PII wird mit Tombstone versehen; Audit-Hash-Kette bleibt erhalten).
5. **Vorfallreaktion:** DORA-Vorfallklassifizierung in `ict_incident`; Meldung von Verstößen innerhalb von 72 Stunden gemäß DSGVO Art. 33.
6. **Rechte der betroffenen Person:** DSAR-Endpunkte implementiert; SLA für Antworten 30 Tage.
7. **DPO-Konsultation:** Diese Datenschutz-Folgenabschätzung erfordert eine Prüfung durch den DPO vor Beginn der Verarbeitung.

---

## 5. Konsultation des DPO { #5-consultation-with-dpo }

**Name des DPO:** [Ausfüllen]  
**Datum der DPO-Freigabe:** [Ausfüllen]  
**Stellungnahme des DPO:** [Ausfüllen]

---

## 6. Freigabe { #6-sign-off }

| Rolle | Name | Datum |
|---|---|---|
| DPO | | |
| Rechtsberatung | | |
| CTO | | |
| Geschäftsführung | | |

*Diese Datenschutz-Folgenabschätzung muss jährlich und bei jeder wesentlichen Änderung der Verarbeitungstätigkeiten überprüft werden.*
