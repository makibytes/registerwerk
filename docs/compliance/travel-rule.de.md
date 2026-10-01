---
title: Travel Rule (TFR)
description: IVMS-101-Implementierung der Travel Rule für Krypto-Asset-Transfers zwischen CASPs.
---

# Travel Rule (TFR / IVMS-101) { #travel-rule-tfr-ivms-101 }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Auf dieser Seite werden beabsichtigte Steuerungszuordnungen und das aktuelle Repository-Verhalten
    aufgezeichnet. Es ist kein Beweis dafür, dass der Betreiber oder die Transaktion im
    Geltungsbereich liegt, dass alle erforderlichen Daten erfasst oder ausgetauscht werden, oder
    dass eine Übertragung den aktuellen TFR-/Travel-Rule-Regeln entspricht. Geltungsbereich,
    Schwellenwerte, Gegenparteien, Ausnahmen, Datenschutz und Protokollnachweise erfordern eine
    aktuelle externe Überprüfung.

Die **Transfer of Funds Regulation (TFR)** — Verordnung (EU) 2023/1113 — auch als **Travel Rule**
bekannt, gilt seit dem 30. Dezember 2024 uneingeschränkt. Sie verlangt, dass Angaben zu Auftraggeber
und Begünstigtem (strukturiert nach dem **IVMS-101**-Standard) **jeden** Krypto-Asset-Transfer
zwischen Crypto-Asset Service Providern (CASPs) begleiten, **unabhängig vom Betrag**. Anders als bei
Fiat-Überweisungen enthält die TFR **keinen Geringfügigkeitsschwellenwert** für Überweisungen von
CASP zu CASP — dies wird durch die EBA-Travel-Rule-Leitlinien (EBA/GL/2024/11) bestätigt. Der Betrag
von 1.000 € in der TFR bezieht sich nur auf Überweisungen zu/von **selbstgehosteten Adressen**:
oberhalb dieses Betrags verlangt Art. 14(5), dass der auftraggebende CASP überprüft, ob die
selbstgehostete Adresse seinem eigenen Kunden gehört oder von diesem kontrolliert wird.

---

## Was die Travel Rule auslöst { #what-triggers-the-travel-rule }

Jeder ausgehende Krypto-Asset-Transfer wird geprüft. Die Verpflichtungen unterscheiden sich je nach Art der Gegenpartei:

1. **Ziel-Wallet gehört zu einem bekannten CASP/VASP** (über Verzeichnissuche) → Es müssen vollständige IVMS-101-Informationen zu Auftraggeber/Begünstigtem übermittelt werden, **in beliebiger Höhe**.
2. **Ziel ist eine selbstgehostete Adresse** → Auftraggeberinformationen werden lokal erfasst und gespeichert; oberhalb von 1.000 € muss der auftraggebende CASP zusätzlich Besitz/Kontrolle über die Adresse überprüfen (Art. 14(5) TFR).
3. Überweisungen zwischen zwei Wallets derselben juristischen Person beim selben CASP fallen nicht unter die CASP-zu-CASP-Übermittlungspflicht, werden aber dennoch aufgezeichnet.

Registerwerk prüft diese Bedingungen in `TravelRuleService.evaluate()`, bevor `forceTransfer` oder eine externe Mint-Operation ausgeführt wird.

---

## IVMS-101-Datenstruktur { #ivms-101-data-structure }

IVMS-101 (InterVASP Messaging Standard) definiert ein strukturiertes Format für Auftraggeber- und Begünstigteninformationen. Der `Ivms101`-Datensatz von Registerwerk in `travelrule/api/` ist den Feldern der FATF-Empfehlung 16 zugeordnet:

```java
public record Ivms101(
    Person originator,       // IVMS101 Person: name, geographicAddress, nationalIdentification
    Person beneficiary,      // IVMS101 Person: name, geographicAddress, nationalIdentification
    String originatorVasp,   // LEI or BIC of the originating VASP
    String beneficiaryVasp,  // LEI or BIC of the beneficiary VASP
    BigDecimal amount,
    String currency,
    String transferRef       // Unique transfer reference
) {}
```

Der Datensatz `Person` enthält den Namen, die Adresse und eine oder mehrere nationale Identifikationen einer natürlichen oder juristischen Person (Passnummer, LEI, Steuernummer).

---

## Übertragungsablauf { #transfer-flow }

```mermaid
sequenceDiagram
    participant Operator
    participant TravelRuleService
    participant VaspDirectory
    participant TravelRuleProtocolPort
    participant BeneficiaryVASP

    Operator->>TravelRuleService: forceTransfer(assetId, from, to, amount)
    TravelRuleService->>VaspDirectory: lookupVasp(toWalletAddress)
    VaspDirectory-->>TravelRuleService: VaspInfo (LEI, endpoint) or null
    alt Wallet belongs to known VASP
        TravelRuleService->>TravelRuleService: Build Ivms101 payload
        TravelRuleService->>TravelRuleProtocolPort: send(Ivms101)
        TravelRuleProtocolPort->>BeneficiaryVASP: IVMS-101 message
        BeneficiaryVASP-->>TravelRuleProtocolPort: ACK
        TravelRuleService->>TravelRuleService: Persist TravelRuleMessage (SENT)
    else Self-hosted address
        TravelRuleService->>TravelRuleService: Log exemption reason
    end
    TravelRuleService->>Blockchain: Execute on-chain transfer
```

---

## Steckbarer Protokolladapter { #pluggable-protocol-adapter }

Verschiedene VASPs verwenden unterschiedliche Travel-Rule-Protokolle (TRP, Sygna Bridge, Notabene, OpenVASP). Registerwerk verwendet einen Port (`TravelRuleProtocolPort`) mit einer standardmäßigen No-Op-Implementierung (`NoopTravelRuleAdapter`) und einem steckbaren Adaptersteckplatz:

```java
public interface TravelRuleProtocolPort {
    void send(Ivms101 payload, String beneficiaryVaspEndpoint);
    TravelRuleMessage.Status getStatus(String transferRef);
}
```

Um ein echtes Protokoll in der Produktion zu aktivieren, implementieren Sie `TravelRuleProtocolPort` und registrieren Sie es als Spring-Bean. Das `NoopTravelRuleAdapter` wird automatisch durch jede konkrete Bean im Anwendungskontext verdrängt.

---

## Eingehende Travel-Rule-Nachrichten { #inbound-travel-rule-messages }

Registerwerk empfängt auch Travel-Rule-Nachrichten von anderen VASPs, wenn diese Token an von Registerwerk verwaltete Wallets übertragen. Der Posteingangsendpunkt:

```
POST /api/v1/public/travel-rule/inbox
```

Jeder Peer-VASP wird von einem `REGISTRY_ADMIN` registriert (`POST /api/v1/compliance/travel-rule/peers`, Step-up und zweiter Freigeber) und erhält einen eigenen HMAC-Schlüssel, der nur einmal angezeigt wird. Eine Anfrage wird nur akzeptiert, wenn Folgendes gilt:

- `X-Vasp-Id`, `X-Registerwerk-Timestamp` (Epoch-Sekunden, innerhalb von 5 Minuten) und `X-Registerwerk-Peer-Signature` = Hex-HMAC-SHA256 über `timestamp|vaspId|sha256(body)` verifizieren gegen den Schlüssel dieses registrierten Peers, und die Signatur wurde noch nicht verwendet (Replay-Cache);
- `originatingVasp.vaspId` im Payload entspricht dem authentifizierten Peer;
- der Absender ist im CASP-Register nicht gesperrt oder widerrufen (eine abgelehnte Zustellung wird als `REJECTED_CASP` gespeichert und auditiert).

Beim Empfang:

1. Kontonummern und Übertragungsreferenz werden validiert; der Body ist auf 256 KiB begrenzt.
2. Die Nachricht wird unter dem **authentifizierten Peer** mit Payload-Hash und `transferDetails` gespeichert. Eine Wiederholung derselben Nutzlast ist idempotent; eine abweichende Nutzlast unter derselben Referenz wird ebenfalls gespeichert, beide Zeilen erhalten den Status `CONFLICT` und ein Audit-Ereignis – ein Peer kann eine fremde Nachricht nicht mehr unterdrücken, indem er die Referenz zuerst beansprucht.
3. Unvollständige Nachrichten (Name, Adresse oder Identifikation von Originator/Begünstigtem fehlen, TFR Art. 16 Abs. 1) erhalten den Status `INCOMPLETE`. Ein Hintergrundjob verknüpft jede Nachricht mit dem indexierten `token_transfer` (`matched_transfer_id`). `GET /api/v1/compliance/travel-rule/open` listet alles, was ein Operator bearbeiten muss.

!!! warning "Gemeinsamer Schlüssel"
    Der alte gemeinsame `X-Travel-Rule-Api-Key` ist veraltet: Er funktioniert nur mit `registerwerk.travel-rule.legacy-shared-key=true` außerhalb des Produktionsmodus, und `X-Vasp-Id` ist dann **nicht** authentifiziert. Registerwerk setzt keine Sperre auf die gutgeschriebenen Token; ob der Operator oder der Verwahrer des Inhabers der empfangende CASP ist, ist eine offene Rechtsfrage (geparkt T6-08).

---

## VASP-Verzeichnis { #vasp-directory }

Die `VaspDirectoryPort`-Schnittstelle unterstützt die steckbare VASP-Erkennung:

- **TRP-Verzeichnis** (Standard-Stub) — das globale VASP-Register, betrieben vom Travel Rule Protocol-Konsortium
- **Shyft Trust** — alternatives VASP-Verzeichnis
- Lokale Überschreibung: Betreiber können bekannte VASP-Zuordnungen im Admin-Portal registrieren

VASP-Abfragen werden über die bestehende Caffeine-Cache-Konfiguration 30 Sekunden lang zwischengespeichert.

---

## Pflichtenmatrix { #obligations-matrix }

| Szenario | Betrag | Aktion |
|---|---|---|
| CASP-zu-CASP-Übertragung | **Beliebiger Betrag** | Vollständige IVMS-101-Übertragung erforderlich — keine De-minimis-Grenze (TFR Art. 14–16) |
| CASP an selbstgehostete Wallet | ≤ 1.000 € | Auftraggeberinformationen erfassen und speichern (`UNHOSTED_RECORDED`) |
| CASP an selbstgehostete Wallet | > 1.000 € | Ausführung blockieren, bis Besitz/Kontrolle über die Adresse überprüft ist (Art. 14(5)) — `UNHOSTED_VERIFY_REQUIRED` |
| Selbstverwahrung durch dieselbe Einheit | Beliebiger Betrag | Außerhalb der CASP-zu-CASP-Übermittlungspflicht — wird aufgezeichnet |
| CASP-Gegenpartei, aber kein Protokolladapter konfiguriert | Beliebiger Betrag | **Übertragung wird abgelehnt (Fail-Closed / Abweisung im Fehlerfall)** — eine Ausführung ohne die erforderlichen Informationen würde gegen Art. 14 verstoßen |

Derzeit liefert kein Aufrufer eine EUR-Bewertung: Ein unbekannter Wert gilt als über 1.000 € (fail closed; Bewertungsquelle geparkt als T6-06), daher gibt ein Wallet-Kontrollnachweis (siehe unten) die Übertragung an eine registrierte selbstverwaltete Inhaber-Wallet frei. Die Bewertung dient **nur** als Auslöser für Art. 14 Abs. 5 – nie dazu, die CASP-zu-CASP-Nachricht zu überspringen.

---

## MiCA-Zulassungsprüfung der Gegenpartei { #mica-counterparty-authorization-check }

Die EU-weite MiCA-Übergangsfrist endet am **1. Juli 2026** (ESMA-Erklärung, 17. April 2026) — kein Mitgliedstaat darf den Bestandsschutz über dieses Datum hinaus verlängern. Ab dem Stichtag stellt die Erbringung von Krypto-Asset-Dienstleistungen in der EU ohne CASP-Zulassung einen Verstoß gegen EU-Recht dar, und Übertragungen an solche Gegenparteien dürfen nicht ausgeführt werden.

Registerwerk setzt dies über das **CASP-Zulassungsregister** durch (`/api/v1/compliance/casp-register`, Betreiber-UI unter *Compliance → CASP Register*). Compliance-Beauftragte spiegeln den ESMA-/NCA-Registerstatus jeder Travel-Rule-Gegenpartei wider:

| Gegenparteistatus | Vor dem 1. Juli 2026 | Ab dem 1. Juli 2026 |
|---|---|---|
| `AUTHORIZED` | Zulässig (blockiert, wenn `validUntil` überschritten ist) | Zulässig (blockiert, wenn `validUntil` überschritten ist) |
| `TRANSITIONAL` | Zulässig | **Blockiert** — kein Bestandsschutz |
| `NOT_AUTHORIZED` / `REVOKED` | **Blockiert** | **Blockiert** |
| Kein Registereintrag | Zulässig mit Warnung | **Gesperrt** (fail closed); Nicht-EU-VASPs benötigen einen geprüften Eintrag `THIRD_COUNTRY_REVIEWED` |

Blockierte Versuche werden in `travel_rule_message` mit dem Status `BLOCKED_MICA` aufgezeichnet, bevor die Übertragung abgelehnt wird, sodass im Audit-Trail die versuchte Übertragung und der regulatorische Grund erscheinen. Der Stichtag kann über `registerwerk.travel-rule.mica-enforcement-date` konfiguriert werden.

## IVMS-101-Identitätsanreicherung { #ivms-101-identity-enrichment }

Ausgehende Nutzlasten werden aus dem Asset-Inhaber-Register angereichert: Die Wallet des Auftraggebers wird auf den registrierten Inhaber (`asset_holder` → `legal_entity`) aufgelöst, und der IVMS-101-Datensatz trägt den offiziellen Namen (`LEGL`), die LEI als nationale `LEIX`-Identifikation, sofern vorhanden, die Entitätsnummer als Kundenidentifikation sowie das Wohnsitzland — denn gemäß TFR Art. 14 Abs. 1 genügt die Wallet-Adresse allein nicht den Informationsanforderungen. Die Begünstigtenseite wird nur bei registerinternen Übertragungen angereichert; bei externen Begünstigten hält der Gegenpart-CASP die Identität vor.

## Massenimport des CASP-Registers { #bulk-import-of-the-casp-register }

`POST /api/v1/compliance/casp-register/import` (Betreiber-UI: *Compliance → CASP Register → Import CSV*) akzeptiert eine CSV-Datei mit den kanonischen Spalten `legal_name`, `vasp_did` (oder `lei`, aus dem `lei:<LEI>` synthetisiert wird), `status` sowie optional `home_member_state`, `authorization_id`, `valid_from`, `valid_until`, `notes`. Die Statuszuordnung ist tolerant gegenüber der britischen Schreibweise von ESMA („Authorised") und ordnet „Withdrawn" dem Wert `REVOKED` zu. Der Import erfolgt zeilenweise nach bestem Aufwand: gültige Zeilen werden anhand des Schlüssels `vaspDid` eingefügt oder aktualisiert, Fehler werden pro Zeile gemeldet.


## Zustellung, Nachweise und Registerkontrollen { #delivery-proofs-register-controls }

!!! note "Ausgehende Zustellung wird abgewartet"
    Bei einem CASP-Begünstigten wird zuerst die Zeile `PENDING_SEND` committet, die Nachricht gesendet und abgewartet (`registerwerk.travel-rule.send-timeout-seconds`, Standard 15); nur ein bestätigtes `SENT` lässt die erzwungene Übertragung on-chain einreichen. Bei Fehler oder Timeout wird `FAILED` gespeichert, die Operation abgelehnt und kann einfach wiederholt werden; es gibt keine Übersteuerung (geparkt T6-06). Zeilen, die länger als 5 Minuten in `PENDING_SEND` hängen, setzt ein Sweeper mit Alarm auf `FAILED`. Dasselbe Gate läuft für ERC-20/721/1155 und für erzwungene ERC-3643-Übertragungen.

Die Nachricht enthält die eigene VASP-Identität des Operators (`registerwerk.travel-rule.own-vasp.did`/`lei`/`legal-name`, in Produktion Pflicht), den aus dem Verzeichnis aufgelösten Begünstigten-VASP (dessen eigener Endpunkt wird zur Zustellung verwendet: nur https, keine privaten Adressen, optional `trp.allowed-hosts`) und `transferDetails` (Tokenmenge und Symbol, Vertrag, Ausführungsdatum). Fehlen Pflichtangaben zu Originator oder Begünstigtem, stoppt die Übertragung mit Status `INCOMPLETE_IVMS`, und es wird nichts gesendet.

**Art.-14(5)-Wallet-Kontrollnachweise.** Eine Übertragung an eine selbstverwaltete Inhaber-Wallet wird freigegeben, wenn für genau dieses Paar (Rechtsträger, Wallet) ein gültiger Nachweis vorliegt: eine Signaturnachricht-Challenge (`POST /api/v1/compliance/travel-rule/wallet-proofs/challenges`, dann `/{id}/signature`) oder eine Operator-Bestätigung mit verpflichtender Evidenznotiz (Step-up und zweiter Freigeber). Die Nachricht wird als `UNHOSTED_VERIFIED` mit der Nachweis-ID erfasst. Ohne Nachweis bleibt die Übertragung gesperrt, und die Fehlermeldung nennt den Endpunkt. Die registerinterne Ausnahme (`registerwerk.travel-rule.register-internal-exempt`) ist standardmäßig aus, da sie eine Rechtsposition ist (geparkt T6-06).

**CASP-Register.** Die Suche erfolgt über DID, dann LEI, dann einen eindeutigen Firmennamen. Änderungen, Löschungen und Importe erfordern Step-up und einen zweiten Freigeber; das Aufheben eines Status `NOT_AUTHORIZED`/`REVOKED` oder das Löschen einer solchen Zeile erfordert einen `REGISTRY_ADMIN` als Freigeber. Ein CSV-Import erfolgt zweistufig: `POST /casp-register/import/preview` liefert die Differenz und einen `diffDigest`, `POST /casp-register/import?diffDigest=...` übernimmt sie. Ein Eintrag `THIRD_COUNTRY_REVIEWED` benötigt Prüfer, zweiten Freigeber und ein Ablaufdatum.

!!! warning "Rechtliche Annahmen"
    Ob die TFR auf eWpG-Kryptowertpapiere anwendbar ist, ob gerichtlich angeordnete oder registerinterne Übertragungen ausgenommen sind, die EUR-Bewertungsquelle und die Behandlung von Nicht-EU-VASPs sind geparkte Entscheidungen (T6-06, T6-07). Das beschriebene Verhalten ist die vorsichtige Zwischenlösung, keine Rechtsbewertung.
