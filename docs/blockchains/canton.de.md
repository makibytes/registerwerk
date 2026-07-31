---
title: Canton / DAML Ledger
description: Canton Private Ledger und DAML-Finance-Integration für regulierte Anleiheinstrumente.
---

# Canton / DAML Ledger

Canton ist ein **Privacy-first Distributed Ledger**, entwickelt von Digital Asset. Anders als öffentliche Blockchains implementiert Canton **Vertraulichkeit je Teiltransaktion**: Jeder Teilnehmer sieht nur die Verträge, bei denen er Vertragspartei ist. Das macht Canton attraktiv für institutionelle Instrumente, bei denen Positionen für andere Marktteilnehmer nicht sichtbar sein sollen.

---

## Architekturkonzepte von Canton

| Konzept | Canton | Abbildung in Registerwerk |
|---|---|---|
| **Ledger** | Der verteilte Canton-Ledger | Ein Canton-Network-Participant-Node je Registerbetreiber |
| **Party** | Eine eindeutige kryptografische Identität auf dem Ledger | `LegalEntity.cantonPartyId` |
| **Contract** | Eine Instanz eines DAML-Vertrags | Einer je Anleihe oder Vermögensposition |
| **Choice** | Eine Aktion, die auf einem Vertrag ausgeübt werden kann | Kapitalmaßnahme (Kupon, Rückzahlung) |
| **Synchroniser** | Die Konsenskomponente | Globaler Synchroniser des Canton Network |
| **Ledger API** | gRPC-API zur Interaktion mit Canton | `CantonLedgerEndpoint` |

---

## DAML-Finance-Anleihetypen

Siehe [DAML Finance Bonds](../token-standards/canton-daml.md) für die vollständige Darstellung der Konfiguration von Anleihebedingungen und Kuponzahlungen.

---

## Verbindungskonfiguration

Der `CantonLedgerEndpoint` verbindet sich über die **Ledger API** (gRPC) mit einem Canton-Participant-Node:

```yaml
registerwerk:
  canton:
    mainnet:
      ledgerApiUrl: "participant.example.com:5001"
      synchronizerId: "global-synchronizer"
      applicationId: "registerwerk"
      authToken: "${CANTON_MAINNET_TOKEN}"  # JWT for participant auth
    devnet:
      ledgerApiUrl: "localhost:5001"
      synchronizerId: "dev-synchronizer"
```

Für das Canton Network (öffentliches Canton): Beziehen Sie einen Participant-Node vom Betreiber des Canton Network, registrieren Sie Ihre Anwendung und hinterlegen Sie die Ledger-API-URL.

Für die Entwicklung: Eine lokale Canton-Sandbox steht über `docker compose -f indexer/canton/docker-compose.yml up` zur Verfügung.

---

## Party-Allokation

Bevor ein Kunde an Canton-basierten Instrumenten teilnehmen kann, muss ihm eine **Canton Party** zugewiesen werden. Das übernimmt `CantonPartyAllocator.allocate(entityId)`:

1. Ruft die Ledger-API `PartyManagementService.allocateParty()` auf
2. Speichert den zurückgegebenen Party-Identifier in `LegalEntity.cantonPartyId`
3. Der Party-Identifier wird in allen DAML-Vertragsreferenzen für diesen Rechtsträger verwendet

Parties sind nach der Zuweisung unveränderlich; eine Party kann niemals für einen anderen Rechtsträger wiederverwendet werden.

---

## Vertraulichkeitsmodell

Canton setzt Vertraulichkeit auf Ledger-Ebene durch:

- **Emittent** sieht: alle Verträge zu seinen Instrumenten
- **Anleger** sieht: nur die eigenen Positionsverträge
- **Registerbetreiber** sieht: alle Verträge (in der DAML-Observer-Rolle)
- **Andere Anleger**: können die Positionen anderer Anleger nicht sehen

Das ist native Vertraulichkeit ohne Verschlüsselung — die Ledger-Infrastruktur stellt sicher, dass Vertragsdaten nur an Parteien übertragen werden, die Stakeholder des jeweiligen Vertrags sind.

---

## Das Maven-Profil `-Pcanton`

Da das DAML SDK und die zugehörigen JARs groß und nicht auf Maven Central verfügbar sind, ist die Canton-Unterstützung hinter dem Profil `-Pcanton` gekapselt:

```bash
./mvnw verify -Pcanton          # includes Canton
./mvnw verify                   # Canton disabled, stub injected
```

Ohne `-Pcanton` wird `CantonBondDisabledStub` verwendet. API-Aufrufe zu Canton-basierten Instrumenten liefern `503 Service Unavailable` mit einer Meldung, die erklärt, dass die Canton-Unterstützung das Profil `-Pcanton` und einen laufenden Participant-Node voraussetzt.

---

## Indexer

Der Canton-Indexer nutzt den **Transaction Service** der Ledger API, um alle committeten Transaktionen zu streamen. Verarbeitet werden:
- Anleiheemissionsverträge → erzeugt `AssetHolder`-Datensätze
- Kuponzahlungsereignisse → erzeugt `token_transfer`-Datensätze vom Typ `COUPON`
- Transferereignisse → aktualisiert `AssetHolder.nominalAmount`

Die Liveness des Canton-Indexers wird von `IndexerMonitorService` überwacht, genauso wie bei den EVM- und Solana-Indexern.
