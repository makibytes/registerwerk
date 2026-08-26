---
title: Grundbegriffe
description: Glossar der rechtlichen, finanziellen und technischen Begriffe, die in Registerwerk durchgehend verwendet werden.
---

# Grundbegriffe

Dieses Glossar definiert die Begriffe, die in Dokumentation, Code und Oberflächen von Registerwerk vorkommen. Die Begriffe sind nach Sachgebieten gruppiert; Querverweise führen, wo einschlägig, auf ausführliche Seiten.

---

## Wertpapiere und Emission

**Wertpapier-Token (Security Token)**
: Ein Blockchain-Token, der ein Finanzinstrument abbildet — eine Anleihe, eine Aktie, einen Fondsanteil oder einen anderen regulierten Vermögenswert. Registerwerk verwaltet Wertpapier-Token nach dem Wertpapierrecht der [unterstützten Jurisdiktionen](../legal/index.md).

**Elektronisches Wertpapier**
: Ein Wertpapier, das ausschließlich als Eintragung in einem zentralen oder dezentralen elektronischen Register besteht, ohne Urkunde. In Deutschland definiert durch [§2 eWpG](../legal/ewpg.md); Entsprechungen gibt es im luxemburgischen, französischen und liechtensteinischen Recht.

**Emittent**
: Der Rechtsträger, der einen Wertpapier-Token schafft und anbietet. In Registerwerk ist ein Emittent ein [Kunden](#kundenentitaten)-Rechtsträger mit der Rolle `ISSUER`, der die [KYC/AML](../compliance/kyc-aml.md)-Genehmigung durchlaufen hat.

**Anleger / Inhaber**
: Ein Rechtsträger oder eine natürliche Person mit einem Bestand an einem Wertpapier-Token. Im System als `AssetHolder`-Datensatz geführt, über eine `HolderIdentity` mit einer `LegalEntity` oder einer `NaturalPerson` verknüpft.

**ISIN** (International Securities Identification Number)
: Ein zwölfstelliger alphanumerischer Code, der ein Wertpapier weltweit eindeutig kennzeichnet. Registerwerk speichert die ISIN in der Entität `Asset` und bettet sie in die Token-Metadaten ein.

**Assetnummer**
: Registerwerks interne fortlaufende Kennung eines Wertpapiers, unabhängig von der ISIN. Wird in internen Abläufen und Prüfreferenzen verwendet.

**Emission / Ausbringung**
: Das Anlegen eines Token-Contracts auf einer Blockchain. In Registerwerk wird die Ausbringung als `AssetDeployment`-Datensatz geführt, der das Off-Chain-`Asset` mit seiner On-Chain-Contract-Adresse verbindet.

---

## Blockchain-Begriffe

**Blockchain / Chain**
: Ein verteiltes Ledger-Netz. Registerwerk unterstützt Ethereum, Polygon, Base, Arbitrum, Avalanche, Optimism (EVM), Solana, StarkNet, Stellar und Canton. Siehe [Unterstützte Blockchains](../blockchains/index.md).

**Token-Standard**
: Eine Spezifikation, die die Schnittstelle eines Tokens festlegt (wie er übertragen, abgefragt und verwaltet werden kann). Beispiele: ERC-20, ERC-3643, SPL-2022. Siehe [Token-Standards](../token-standards/index.md).

**Smart Contract**
: Ausführbarer Code, der auf einer Blockchain ausgebracht ist. Registerwerk bringt Contracts über [Web3j](https://web3j.io/) (EVM) und Solanaj (Solana) aus. Contract-Adressen werden in `AssetDeployment` gespeichert.

**Transaktion (on-chain)**
: Eine kryptografisch signierte Operation, die an eine Blockchain übermittelt wird. Jede Zustandsänderung wird als `BlockchainTransaction` festgehalten und mit dem zugehörigen Audit-Ereignis verknüpft.

**Chain-Drift**
: Eine Abweichung zwischen dem On-Chain-Tokenbestand und `AssetHolder.nominalAmount` in der Registerwerk-Datenbank. Der `ChainDriftDetectionJob` prüft je emittiertem Asset alle 15 Minuten auf Drift.

**Maßgebliches Register**
: Registerwerk führt eine operative Inhaberaufzeichnung in PostgreSQL und projiziert bzw. gleicht ausgewählten Zustand on-chain ab. Welche Aufzeichnung rechtlich maßgeblich ist, hängt von Instrument, Registermodell, Betreiber und Jurisdiktion ab und erfordert eine genehmigte Perimeterentscheidung. Weder die Datenbank noch die Blockchain ist allgemein maßgeblich.

**Wallet**
: Ein kryptografisches Schlüsselpaar zum Signieren von On-Chain-Transaktionen. Registerwerk verwaltet Betreiber-Wallets (Schlüsselmaterial im Ruhezustand verschlüsselt) über das Modul `wallet`.

---

## Regulierung und Compliance

**KYC** (Know Your Customer)
: Der Vorgang, die Identität eines Kunden zu prüfen — einschließlich Geschäft, Eigentümer und wirtschaftlich Berechtigten — bevor eine Geschäftsbeziehung begründet wird. Siehe [KYC & AML](../compliance/kyc-aml.md).

**KYB** (Know Your Business)
: Das unternehmensbezogene Gegenstück zu KYC, gerichtet auf die Prüfung von Legitimität und Eigentümerstruktur eines Rechtsträgers.

**AML** (Geldwäscheprävention)
: Das Regelwerk, das Unternehmen verpflichtet, Geldwäsche zu erkennen und zu verhindern. In Deutschland: GwG; EU-weit: AMLD6 und die kommende AMLR.

**PEP** (politisch exponierte Person)
: Eine Person, die ein herausgehobenes öffentliches Amt innehat oder innehatte. PEPs erfordern nach [§10(2) GwG](../compliance/kyc-aml.md) verstärkte Sorgfaltspflichten.

**UBO** (wirtschaftlich Berechtigter)
: Die natürliche(n) Person(en), die einen Rechtsträger letztlich besitzen oder kontrollieren, typischerweise ab einer Schwelle von 25 %. In Registerwerk als `BeneficialOwner` geführt, verknüpft mit einer `NaturalPerson`.

**Sanktionsprüfung**
: Der Abgleich einer Person oder eines Rechtsträgers mit internationalen Sanktionslisten (OFAC SDN, EU CFSP, UN 1267, UK HMT, Schweizer SECO). Siehe [Sanktionsprüfung](../compliance/sanctions-screening.md).

**Travel Rule (TFR)**
: Verordnung (EU) 2023/1113, die verlangt, dass Angaben zu Auftraggeber und Begünstigtem Kryptowerte-Transfers über 1.000 € zwischen VASPs begleiten. Umgesetzt über den [Datenstandard IVMS-101](../compliance/travel-rule.md).

**VASP** (Virtual Asset Service Provider)
: Ein reguliertes Unternehmen, das Dienstleistungen rund um virtuelle Werte erbringt (Börsen, Verwahrer). Registerwerk selbst tritt als VASP/CASP auf, wenn es Token für Dritte emittiert.

**CASP** (Kryptowerte-Dienstleister)
: Der MiCAR-Begriff für VASP im EU-Recht.

**Sperrvermerk**
: Deutscher Rechtsbegriff für einen Sperrvermerk an einer Eintragung im Wertpapierregister, der die Übertragung beschränkt oder den Vermögenswert belastet. Vorgeschrieben durch [§16 eWpG](../legal/ewpg.md). Siehe [Sperrvermerk](../compliance/sperrvermerk.md).

**DORA** (Digital Operational Resilience Act)
: EU-Verordnung 2022/2554, die Finanzunternehmen verpflichtet, IKT-Risiken zu steuern, schwerwiegende Vorfälle zu melden und ein Register über IKT-Drittdienstleister zu führen. Siehe [DORA](../compliance/dora.md).

**LEI** (Legal Entity Identifier)
: Ein 20-stelliger Code nach ISO 17442, der einen Rechtsträger weltweit eindeutig kennzeichnet. In Registerwerk an `LegalEntity` gespeichert; für alle Emittenten empfohlen.

---

## Kundenentitäten

**Betreiber**
: Die Organisation, die eine Registerwerk-Installation betreibt. Betreiber haben Zugang zum Betreiber-Frontend (:44200) und können alle Kunden, Assets und Compliance-Daten verwalten.

**Kunde**
: Ein von einem Betreiber aufgenommener Emittent oder Anleger. Kunden greifen über das Kong-API-Gateway auf das Kunden-Frontend (:44201) zu.

**Rechtsträger (`LegalEntity`)**
: Das zentrale Datenmodell für das Unternehmen eines Kunden. Enthält Jurisdiktion, Registernummer, LEI, KYC-Status sowie Verknüpfungen zu wirtschaftlich Berechtigten und KYC-Dokumenten.

**Natürliche Person (`NaturalPerson`)**
: Eine Einzelperson — Geschäftsführer, wirtschaftlich Berechtigter oder Anleger. Die derzeitige Entität bildet personenbezogene Daten wie Name, Geburtsdatum, Staatsangehörigkeit und Steuer-ID auf gewöhnliche Datenbankspalten ab; eine Feldverschlüsselung auf Anwendungsebene ist nicht umgesetzt.

**Wirtschaftlich Berechtigter (`BeneficialOwner`)**
: Verbindet eine `LegalEntity` mit einer `NaturalPerson` samt Beteiligungsquote und Art der Kontrolle.

---

## Plattformspezifische Begriffe

**Modul**
: Ein Spring-Modulith-Kontext mit klarer Grenze. Registerwerk hat 34 Module, jedes mit einem Paket `api/` (öffentliche Typen) und einem Paket `internal/` (private Umsetzung). Siehe [Modularchitektur](../platform/modules.md).

**Step-up-Authentifizierung**
: Eine zweite Authentifizierungsabfrage, die vor Operationen mit hohem Risiko verlangt wird (Zwangsübertragung, Zwangsvernichtung, KYC-Übersteuerung). Durchgesetzt über die Annotation `@RequiresStepUp`. Siehe [Step-up-MFA](../compliance/step-up-mfa.md).

**Vier-Augen-Prinzip**
: Eine Anforderung an doppelte Kontrolle: Eine zweite berechtigte Person muss eine Handlung bestätigen, bevor sie wirksam wird. Umgesetzt im Modul `stepup`.

**Audit-Kette**
: Die manipulationssicher nachweisbare Folge von Audit-Ereignissen, deren jedes einen Hash des vorherigen Eintrags enthält. Liefert kryptografischen Nachweis für Vollständigkeit und Unversehrtheit des Audit-Logs. Siehe [Audit-Log](../platform/audit-log.md).
