---
title: Frankreich – AMF
description: Wie Registerwerk die französischen regulatorischen Anforderungen von AMF und Loi PACTE für tokenisierte Wertpapiere umsetzt.
---

# Frankreich – AMF { #france-amf }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Auf dieser Seite werden beabsichtigte Kontrollzuordnungen und konfigurierte Annahmen festgehalten. Sie
    stellt keine französische Rechtsberatung und keinen Nachweis der Instrumentenklassifizierung, einer
    behördlichen Zulassung, Konformität oder Rechtswirkung dar. Holen Sie eine aktuelle instrumenten-,
    betreiber-, dienst- und einsatzspezifische Prüfung ein.

Frankreich hat mit dem **Loi PACTE** (Plan d'Action pour la Croissance et la Transformation des Entreprises, 2019) einen der ersten eigenständigen Rechtsrahmen Europas für tokenbasierte Finanzinstrumente geschaffen. Die **Autorité des Marchés Financiers (AMF)** beaufsichtigt Emittenten und Dienstleister.

---

## Anwendbarer Regulierungsrahmen { #applicable-regulatory-framework }

| Regelwerk | Geltungsbereich |
|---|---|
| Loi PACTE 2019-486 | Tokenbasierte Wertpapiere (Minibons, titres financiers) |
| Code monétaire et financier (CMF) | Wertpapierdienstleistungen, AML |
| AMF Règlement général | Marktverhalten, Prospekt, Token-Emission |
| AMF DOC-2022-15 | Leitlinien für DASPs (Digital Asset Service Providers) |
| ACPR PSAN-Leitlinien | AML für PSAN-registrierte Unternehmen |
| MiCAR (EU) 2023/1114 | Volle Anwendbarkeit für CASPs |
| DORA (EU) 2022/2554 | IKT-Resilienz |

---

## PSAN – Registrierung als Digital Asset Service Provider { #psan-digital-asset-service-provider-registration }

Das französische Recht verpflichtet Unternehmen, die Dienstleistungen für digitale Vermögenswerte erbringen, sich bei der **AMF** als **Prestataire de Services sur Actifs Numériques (PSAN)** registrieren zu lassen. Mit der Anwendung von MiCAR ab 2024 geht die PSAN-Registrierung in eine MiCAR-CASP-Zulassung über; bestehende PSAN-Registrierungen genießen jedoch während einer Übergangsfrist Bestandsschutz.

Das Zuständigkeitsprofil `FR_AMF` von Registerwerk führt die PSAN-/CASP-Registrierungsnummer des Betreibers in der Konfiguration. Diese Nummer erscheint in behördlichen Meldungen.

---

## Wesentliche Unterschiede zu Deutschland { #key-differences-from-germany }

| Dimension | DE (eWpG) | FR (AMF) |
|---|---|---|
| Primäres Token-Gesetz | eWpG (wertpapierspezifisch) | Loi PACTE / CMF (allgemeines DLT-Recht) |
| Unterstützter Registertyp | Zentral + dezentral | DLT-basiertes Register (Minibons, obligations) |
| Zuständige Behörde | BaFin | AMF (Wertpapiere) + ACPR (Bankwesen/AML) |
| Aufbewahrungsfrist | 10 Jahre | 5 Jahre |
| KYC-Dokument – Handelsregister | Handelsregisterauszug | Extrait Kbis (≤ 3 Monate alt) |
| Register der wirtschaftlich Berechtigten | Transparenzregister | Registre des Bénéficiaires Effectifs (RBE) |
| AML-Fragebogen | GwG-spezifisch | AMF-/ACPR-PSAN-spezifisch |
| Meldung verdächtiger Transaktionen | BaFin | AMF/ACPR leiten an TRACFIN weiter |

---

## KYC-Dokumentanforderungen für `FR_AMF` { #kyc-document-requirements-for-fr_amf }

Das Zuständigkeitsprofil `FR_AMF` in `JurisdictionRequirementConfig` verlangt:

- **Extrait Kbis** (≤ 3 Monate alt, vom Greffe du Tribunal de Commerce)
- **Déclaration de bénéficiaires effectifs** aus dem nationalen RBE
- Statuts (Satzung)
- Identitätsdokumente aller Direktoren und wirtschaftlich Berechtigten (UBOs)
- Jahresbericht (letzte 2 Jahre, sofern verfügbar)
- AMF-/ACPR-AML-Fragebogen
- Erklärung zur Herkunft der Mittel (bei Investitionen oberhalb der AMF-Schwelle)

---

## Minibons und titres financiers { #minibons-and-titres-financiers }

Das französische Recht erlaubt die Tokenisierung zweier Instrumentenkategorien:

**Minibons** (Crowdfunding-Schuldinstrumente): kurzfristige Anleihen, die über Crowdfunding-Plattformen begeben werden und nun nach dem Loi PACTE für eine DLT-basierte Emission zugelassen sind.

**Titres financiers** (Finanzinstrumente): Eigenkapital- und Fremdkapitalinstrumente jeder Art, zugelassen für eine DLT-basierte Emission über einen Prestataire de Compensation (das DLT-Äquivalent einer zentralen Gegenpartei).

Beide werden in Registerwerk über [ERC-3643](../token-standards/erc3643.md) (identitätsgebunden, reguliert) oder [ERC-3525](../token-standards/erc3525.md) (tranchierte Anleihen) abgebildet. Ein Deployment unter `FR_AMF` löst zusätzliche Prüfungen aus:

1. AMF-Meldung des Token-Programms (gespeichert als `Asset.regulatoryNotificationRef`)
2. Prüfung der ISIN-Zuweisung
3. Prüfung der Prospektbefreiung (unterhalb der 8-Mio.-€-Schwelle für Minibons)

---

## MiFIR-Meldung für Frankreich { #mifir-reporting-for-france }

Anwendbarkeit von MiFIR, Meldefähigkeit, zuständige Behörde und Meldekanal erfordern eine transaktions- und instrumentenspezifische externe Prüfung. Der aktuelle [MiFIR](../compliance/mifir.md)-Dienst erzeugt ein `DRAFT_UNVALIDATED`-Prototyp-XML; er verfügt über keine `FR_AMF`-Strategie und reicht nichts bei der AMF oder einer anderen Behörde ein und weist auch keine Zustellung nach.

---

## TRACFIN – Meldung verdächtiger Transaktionen { #tracfin-suspicious-transaction-reporting }

Geltungsbereich und Verfahren der französischen Financial-Intelligence-Meldung erfordern eine externe Prüfung. Das Screening-Modul von Registerwerk zeichnet Screening-Läufe und Bewertungsentscheidungen der Betreiber auf, übermittelt jedoch keine TRACFIN-Meldung und überprüft auch keine Meldungsreferenz eigenständig.

---

## DORA-Vorfallmeldung (Frankreich) { #dora-incident-reporting-france }

Zuständigkeitsbereich der Behörde und die aktuell geltenden Meldefristen erfordern eine externe Prüfung. Das Modul `dora` leitet Vorfälle nicht an die ACPR, die AMF oder eine andere Behörde weiter und übermittelt sie auch nicht. Die folgenden Werte sind historische Entwurfsannahmen, kein Nachweis einer konfigurierten Meldung:

- Erstmeldung: 4 Stunden ab Einstufung als schwerwiegend
- Zwischenbericht: 72 Stunden
- Abschlussbericht: 30 Tage

Siehe [DORA](../compliance/dora.md).
