---
title: DAML Finance Bonds (Canton)
description: Canton-/DAML-Finance-Anleihestandards für Private-Ledger-Bereitstellungen.
---

# DAML Finance Bonds (Canton) { #daml-finance-bonds-canton }

Canton ist ein datenschutzorientierter Distributed Ledger, der auf der **DAML**-Smart-Contract-
Sprache aufbaut. DAML Finance bietet eine Bibliothek zusammensetzbarer Finanzprimitive für
Canton — einschließlich Anleihen, Aktien und Derivaten. Registerwerk unterstützt drei
DAML-Finance-Anleihetypen auf Canton für Private-Ledger-Bereitstellungen.

---

## Unterstützte DAML-Finance-Anleihetypen { #supported-daml-finance-bond-types }

| Standard | Token-Enum | Beschreibung |
|---|---|---|
| `DAML_BOND_FIXED` | Festverzinsliche Anleihe | Bekannter Kuponsatz, fester Zeitplan |
| `DAML_BOND_FLOATING` | Anleihe mit variabler Verzinsung | Zinssatz gebunden an EURIBOR/SOFR/andere Referenz |
| `DAML_BOND_ZERO` | Nullkuponanleihe | Kein periodischer Kupon; wird mit einem Abschlag gehandelt |
| `CANTON_TOKEN` | Generisches Canton-Asset | Jedes DAML-basierte digitale Asset |

---

## Wie sich Canton von EVM unterscheidet { #how-canton-differs-from-evm }

| Dimension | EVM (ERC-Standards) | Canton (DAML Finance) |
|---|---|---|
| Vertraulichkeit | Öffentlicher Ledger (alle Teilnehmer sehen den Status) | Privat — jeder Teilnehmer sieht nur seine eigenen Verträge |
| Smart-Contract-Sprache | Solidity / Vyper | DAML (Haskell-ähnlich) |
| Finalität | Probabilistisch (n Bestätigungen) | Deterministisch (Bestätigung durch die Ledger API) |
| Identität | Wallet-Adresse | Canton Party (eindeutige Kennung je Teilnehmer) |
| Off-Ledger-Abwicklung | Optional | Nativ: Der DAML-Workflow umfasst die Abwicklung |
| Vertrauliche Positionen | Erfordert Zama fhEVM | Nativ — private Verträge |

---

## Canton-Party-Zuweisung { #canton-party-allocation }

Jeder `LegalEntity` in Registerwerk hat eine **Canton Party** — eine eindeutige Kennung auf dem
Canton-Ledger. Dies verwaltet der Dienst `CantonPartyAllocator` im Modul `blockchain`:

1. Wenn ein Kunde mit einem Canton-fähigen Instrument onboardet wird, registriert
   `CantonPartyAllocator.allocate(entityId)` die Entität auf dem Canton-Ledger
2. Die Party-Kennung wird in `LegalEntity.cantonPartyId` gespeichert
3. Alle DAML-Finance-Verträge referenzieren die Canton Party, nicht eine Wallet-Adresse

---

## Zuordnung der Anleihebedingungen { #bond-terms-mapping }

`AssetBondTerms` speichert die finanziellen Parameter für alle Anleihetypen:

| Feld | DAML_BOND_FIXED | DAML_BOND_FLOATING | DAML_BOND_ZERO |
|---|---|---|---|
| `couponRate` | Fest (z. B. 5,0 %) | Referenzzinsspanne | N/A |
| `referenceRate` | N/A | z. B. EURIBOR_3M | N/A |
| `maturityDate` | ✅ | ✅ | ✅ |
| `paymentFrequency` | ANNUAL / SEMIANNUAL / QUARTERLY / MONTHLY | Gleich | N/A |
| `dayCountConvention` | ACT_365 / ACT_ACT / 30_360 | Gleich | ACT_365 |
| `issuePrice` | 100 (Par) oder Abschlag/Aufschlag | Par | Abschlag (< 100) |

---

## Kuponzahlung auf Canton { #coupon-payment-on-canton }

Für `DAML_BOND_FIXED` und `DAML_BOND_FLOATING` führt die Methode
`CantonBondOperations.payCoupon()` den DAML-Finance-Kuponzahlungs-Workflow aus:

1. Der Canton-Participant-Node von Registerwerk schlägt der Party des Emittenten einen
   Kuponzahlungsvertrag vor
2. Der Node des Emittenten übt die Kupon-Lebenszyklus-Choice aus
3. Alle Bond-Holder-Parties erhalten ihre Kuponbeträge über den DAML-Abwicklungsbatch
4. Der Datensatz `CorporateAction(type=COUPON, status=SETTLED)` wird in der Datenbank von
   Registerwerk aktualisiert

---

## Das Maven-Profil `-Pcanton` { #the-pcanton-maven-profile }

Canton-Unterstützung erfordert das DAML SDK und zugehörige Java-Bibliotheken. Diese werden über
das Maven-Profil `-Pcanton` aktiviert:

```bash
cd backend && ./mvnw verify -Pcanton
```

Ohne dieses Profil wird `CantonBondDisabledStub` anstelle des echten Canton-Clients eingefügt, und
alle Canton-bezogenen API-Aufrufe liefern `503 Service Unavailable` mit einer beschreibenden
Meldung zurück. Das erlaubt der Anwendung einen sauberen Start ohne einen Canton-Participant-Node.
