---
title: Verzeichnis von Verarbeitungstätigkeiten
description: Entwurf des Verzeichnisses von Verarbeitungstätigkeiten nach Art. 30 DSGVO.
---

# Verzeichnis von Verarbeitungstätigkeiten (DSGVO Art. 30) { #verzeichnis-von-verarbeitungstatigkeiten-dsgvo-art-30 }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Bei diesem Repository-Dokument handelt es sich um einen Inventarentwurf und nicht um eine genehmigte oder vollständige Aufzeichnung nach Artikel 30.
    Der Einsatzverantwortliche/Auftragsverarbeiter muss Umfang, Zwecke, Rechtsgrundlagen, Empfänger,
    Übertragungen, Aufbewahrung, Sicherheitsmaßnahmen, Eigentum, Genehmigung und Überprüfungsnachweise festlegen.

# Aufzeichnungen über Verarbeitungsaktivitäten (DSGVO Art. 30) { #records-of-processing-activities-gdpr-art-30 }

**Verantwortlicher:** [Betreibername zum Ausfüllen]  
**DPO:** [Kontakt zum Ausfüllen]  
**Letzte Aktualisierung:** 21.05.2026  
**Version:** 1.0

---

## 1. Kunden-Onboarding & KYC { #1-customer-onboarding-kyc }

| Feld | Wert |
|---|---|
| **Zweck** | Überprüfung der Kundenidentität und Onboarding für die elektronische Wertpapierausgabe (GwG §10, eWpG §3) |
| **Rechtsgrundlage** | Gesetzliche Verpflichtung (DSGVO Art. 6(1)(c)) — GwG §10, eWpG |
| **Datenkategorien** | Name der juristischen Person, LEI, Registrierungsnummer, Gründungsdatum, KYC-Dokumente (Registrierungsauszug, UBO-Erklärung, Ausweisdokumente, Vorstandsbeschlüsse), KYC-Status |
| **Natürliche Personen** | Direktoren, UBOs: Name, Geburtsdatum, Nationalität, Adresse, Art/Nummer des Ausweisdokuments, PEP/Sanktionsstatus |
| **Empfänger** | BaFin (DE), CSSF (LU), AMF (FR), FMA (LI) — nur auf behördliche Anfrage |
| **Drittlandübermittlungen** | Keine geplant; AWS S3 (eu-central-1) für die Dokumentenspeicherung — Standardvertragsklauseln |
| **Aufbewahrung** | 10 Jahre nach Beziehungsende (eWpG §15(3)); 5 Jahre für KYC-Datensätze (GwG §8) |
| **Sicherheitsmaßnahmen** | AES-256-GCM im Ruhezustand; TLS 1.3 im Transport; rollenbasierter Zugriff (COMPLIANCE_OFFICER, REGISTRY_ADMIN); Prüfprotokoll |

## 2. Elektronisches Wertpapierregister { #2-electronic-securities-registry }

| Feld | Wert |
|---|---|
| **Zweck** | Führung des elektronischen Wertpapierregisters gemäß eWpG (Registerführung) |
| **Rechtsgrundlage** | Gesetzliche Verpflichtung (Art. 6(1)(c)) — eWpG §7, §15, §16, §17 |
| **Datenkategorien** | Vermögensinhaber: Wallet-Adresse, Nennbetrag, Erwerbsdatum, Whitelist-Status; Transaktionsverlauf |
| **Natürliche Personen** | Inhaberidentität für natürliche Personen: Name, Geburtsdatum, Nationalität, Steuernummer (über HolderIdentity) |
| **Empfänger** | BaFin (gerichtlich angeordnete Offenlegungen); Emittent (gemäß eWpG §15) |
| **Aufbewahrung** | 10 Jahre nach Rückzahlung/Kündigung (eWpG §15(3)) |
| **Sicherheitsmaßnahmen** | Hash-verkettetes, unveränderliches Prüfprotokoll; WORM-Trigger; täglicher Anker; Kettendrift-Erkennung |

## 3. Sanktionen & PEP-Überprüfung { #3-sanctions-pep-screening }

| Feld | Wert |
|---|---|
| **Zweck** | Laufendes AML/CTF-Screening gemäß GwG §10 Abs. 1 Nr. 5 |
| **Rechtsgrundlage** | Gesetzliche Verpflichtung (Art. 6(1)(c)) — GwG §10, MiCAR Art. 60 |
| **Datenkategorien** | Name des Unternehmens, LEI, Registrierungsnummer — geprüft gegen OFAC SDN, EU CFSP, UN 1267, UK HMT, CH-SECO |
| **Auftragsverarbeiter** | OpenSanctions (offene Daten, DSGVO-neutral); Refinitiv World-Check (AVV erforderlich) |
| **Aufbewahrung** | 5 Jahre (GwG §8) |
| **Sicherheitsmaßnahmen** | Screening-Ergebnisse werden in verschlüsselter Datenbank gespeichert; Vier-Augen-Prinzip für die Annahme eines Treffers |

## 4. Handel und Transaktionsverarbeitung { #4-trading-transaction-processing }

| Feld | Wert |
|---|---|
| **Zweck** | Durchführung von Wertpapiergeschäften auf Handelsplätzen (Assetera, Archax, Talos, simuliert) |
| **Rechtsgrundlage** | Vertragliche Notwendigkeit (Art. 6(1)(b)); gesetzliche Verpflichtung für die MiFIR-Meldung (Art. 6(1)(c)) |
| **Datenkategorien** | Händler-ID, Entitäts-ID, Handelseinträge, Ausführungsaufzeichnungen, Wallet-Adressen |
| **Empfänger** | BaFin/AMF — MiFIR-RTS-22-Transaktionsmeldungen |
| **Aufbewahrung** | 7 Jahre (MiFIR Art. 25(1)); 5 Jahre (GwG) |
| **Sicherheitsmaßnahmen** | Rollenbasierter Zugriff (TRADER); Audit-Protokoll pro Trade |

## 5. Audit-Protokollierung { #5-audit-logging }

| Feld | Wert |
|---|---|
| **Zweck** | Sicherheits- und Compliance-Audit-Trail; eWpRV §6 Integritätsanforderung |
| **Rechtsgrundlage** | Gesetzliche Verpflichtung (Art. 6(1)(c)) — eWpG §15, eWpRV §6, DORA Art. 9 |
| **Datenkategorien** | Akteur-ID, Akteurrolle, Ereignistyp, Betreff-ID/-Typ, Nutzlast (kann Entitätsnamen enthalten) |
| **Aufbewahrung** | 10 Jahre (eWpG §15(3)); nur anhängend, kann nicht gelöscht werden |
| **Sicherheitsmaßnahmen** | SHA-256-Hash-Kette; WORM-DB-Trigger; täglicher Anker zur öffentlichen Blockchain; eingeschränkte DB-Rolle |

## 6. Operator-Benutzerverwaltung { #6-operator-user-management }

| Feld | Wert |
|---|---|
| **Zweck** | Authentifizierung und Autorisierung des Registrierungspersonals |
| **Rechtsgrundlage** | Berechtigtes Interesse (Art. 6(1)(f)) — IT-Sicherheit, Zugangskontrolle |
| **Datenkategorien** | E-Mail, gehashtes Passwort, Rollen, letzte Anmeldung, Aktionstoken |
| **Aufbewahrung** | Beschäftigungsdauer + 2 Jahre |
| **Sicherheitsmaßnahmen** | BCrypt-Passwort-Hashing; JWT (kurzlebig, 8 Stunden); MFA für sensible Vorgänge |

## 7. Regulatorische Berichterstattung (MiFIR, DAC8, Steuerbescheinigung) { #7-regulatory-reporting-mifir-dac8-steuerbescheinigung }

| Feld | Wert |
|---|---|
| **Zweck** | Obligatorische Transaktionsmeldung an die zuständigen Behörden |
| **Rechtsgrundlage** | Gesetzliche Verpflichtung (Art. 6(1)(c)) — MiFIR Art. 26, DAC8, EStG §43 |
| **Datenkategorien** | Name des Anlegers, Steuer-ID, Bestände, Transaktionen, IBAN (für Steuerbescheinigung) |
| **Empfänger** | BaFin (DE), AMF (FR), CSSF (LU), FMA (LI), BZSt (DAC8/CARF), DGFiP (FR), ACD (LU) |
| **Aufbewahrung** | 7 Jahre (MiFIR); 10 Jahre (eWpG) |
| **Sicherheitsmaßnahmen** | PAdES-B-LT-signierte PDFs; SFTP an Behördenportale; Einreichungsbelege |

---

## Rechte der betroffenen Person { #data-subject-rights }

| Recht | Implementierung |
|---|---|
| Art. 15 Auskunft | `GET /api/v1/me/dsar/export` |
| Art. 17 Löschung | `POST /api/v1/me/dsar/erasure` — PII mit Tombstone versehen; Audit-Hash-Kette bleibt erhalten (Art. 17(3)(b) gesetzliche Verpflichtung) |
| Art. 20 Übertragbarkeit | `GET /api/v1/me/dsar/export` gibt JSON zurück |
| Art. 21 Widerspruch | Nicht anwendbar (Rechtsgrundlage: gesetzliche Verpflichtung) |
| Art. 22 Automatisierte Entscheidung | Keine automatisierten Entscheidungen; alle KYC-Genehmigungen werden von Menschen geprüft |
