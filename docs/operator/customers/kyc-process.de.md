---
title: KYC-Prozess
---

Diese Seite beschreibt den Betreiber-Workflow für jurisdiktionsbezogene KYC-Datensätze und Checklistenentscheidungen. Diese Kontrollen werden noch nicht einheitlich über alle Emissions-, Bereitstellungs-, Empfangs- oder Übertragungspfade hinweg durchgesetzt.

## Umfang und Modell

Registerwerk speichert:

- KYC-Status auf Entitätsebene (`legal_entity.kyc_status`) für den allgemeinen Onboarding-Lebenszyklus.
- KYC-Genehmigungen auf Jurisdiktionsebene (`kyc_jurisdiction_approval`) für die Emissionsberechtigung unter bestimmten Regelwerken.
- Jurisdiktionsmarkierte Dokumente (`kyc_document.jurisdiction`), bei denen `null` universellen Nachweis bedeutet, der mehrere Jurisdiktionen erfüllen kann.

## Dokumentenhandhabung

- Dateien kleiner als 5 MB werden in PostgreSQL gespeichert.
- Dateien ab 5 MB werden in S3-kompatiblem Objektspeicher gespeichert.
- Zugriffe und zustandsändernde Aktionen werden in den Audit Trail geschrieben.

## KYC-Nachweise hochladen

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/documents \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -F "file=@certificate.pdf" \
   -F "documentType=INCORPORATION_CERTIFICATE" \
   -F "jurisdiction=DE_EWPG"
```

Wird `jurisdiction` weggelassen, wird das Dokument als universell behandelt.

## Compliance-Checkliste vor der Genehmigung

Vor der Jurisdiktionsgenehmigung berechnet das Backend die Konformität anhand des für diese Jurisdiktion konfigurierten Profils.

```bash
GET /api/v1/entities/{entityId}/kyc/compliance/{jurisdiction}
```

Die Antwort enthält:

- `missingCount`
- `expiredCount`
- `tooOldCount`
- `fullyCompliant` (ein Name auf Code-Ebene, der bedeutet, dass keine Lücke in der konfigurierten Checkliste besteht — nicht rechtliche Konformität)

## Jurisdiktions-KYC genehmigen

Autorisierungsmodell:

- `ROLE_COMPLIANCE_OFFICER`: darf nur genehmigen, wenn `fullyCompliant=true` für die konfigurierte Checkliste gilt.
- `ROLE_REGISTRY_ADMIN`: darf Fälle mit oder ohne konfigurierte Checklistenlücken genehmigen.
- Eine Genehmigung mit Checklistenlücken erfordert `overrideNote` und wird für Nutzer ohne Administratorrechte abgelehnt.

Genehmigung ohne konfigurierte Checklistenlücken:

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"expiresAt":"2027-01-31"}'
```

Bestehen Checklistenlücken, wird die Genehmigung blockiert, sofern kein expliziter Override-Hinweis angegeben wird:

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/approve \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"overrideNote":"Approved by compliance officer after manual source-of-funds review."}'
```

Wird ein Override verwendet, wird der Hinweis in `kyc_jurisdiction_approval.override_note` gespeichert, und die Compliance-Zähler werden in das Audit-Ereignis aufgenommen.

## Ablehnungs-Workflow

```bash
curl -X POST http://localhost:48000/api/v1/entities/{entityId}/kyc/jurisdictions/DE_EWPG/reject \
   -H "Authorization: Bearer $OPERATOR_TOKEN" \
   -H "Content-Type: application/json" \
   -d '{"reason":"Missing certified beneficial ownership register extract."}'
```

## On-Chain-Claims und Token-Kontrollen

Die Jurisdiktionsgenehmigung steht Backend-Workflows zur Verfügung, aber ein zentrales Fail-Closed-Operationsgate ist noch nicht einheitlich angewendet. ERC-3643-Verträge und ONCHAINID-Claims bieten, sofern konfiguriert, separate Einschränkungen auf Vertragsebene; sie belegen keine vollständige KYC-Durchsetzung oder rechtliche Berechtigung.

## Regulatorischer Hinweis

Dieser Funktionsumfang unterstützt den Nachweis von KYC-Kontrollen und deren Prüfbarkeit. Er entbindet für sich genommen nicht von
Lizenzierungspflichten, Meldepflichten für verdächtige Aktivitäten, der Travel Rule oder örtlichen Aufsichtspflichten.

## Override-Reporting

Prüfer und Admins können Override-Genehmigungen nach Jurisdiktion und Zeitraum auflisten:

```bash
GET /api/v1/audit/reports/kyc-overrides?jurisdiction=DE_EWPG&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z
```
