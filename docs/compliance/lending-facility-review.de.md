---
title: Compliance-Überprüfung der Repo-/Kreditfazilität
description: Priorisierte Compliance-Ergebnisse für EwpgRepoFacility und den EwpgRepoMarket/Vault/Oracle-Stack, mit Zuordnung pro Gerichtsbarkeit und Härtungsstatus.
---

# Compliance-Überprüfung der Repo-/Kreditfazilität { #repolending-facility-compliance-review }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Auf dieser Seite werden technische Erkenntnisse und beabsichtigte Steuerungszuordnungen erfasst. Sie
    ist kein Nachweis der rechtlichen Konformität, behördlichen Genehmigung, Produktzulassung oder
    Produktionsreife. Fragen zu Repo-Geschäften, Wertpapierleihe, Sicherheiten, Verwahrung, Verwertung,
    Insolvenz und Weiterverwendung erfordern eine betreiber-, produkt-, instrumenten-, transaktions- und
    jurisdiktionsspezifische Prüfung durch qualifizierte Rechtsberater und die verantwortlichen
    Risikoverantwortlichen.

Reviewdatum: 21.07.2026. Geltungsbereich: `contracts/src/examples/EwpgRepoFacility.sol`, die Weiterentwicklung
zum isolierten Markt unter `contracts/src/lending/` (`EwpgRepoMarket`, `EwpgRepoMarketFactory`,
`EwpgRepoVault`, `oracle/RegisterwerkNavOracle`) sowie das Backend-Lesemodellmodul `lending`.
Begleitdokument zu [DeFi-Interoperabilität](../platform/defi-interoperability.md), das die
Produkt-/Regulierungsgründe für das Design der Fazilität abdeckt; dieses Dokument ist die
Sicherheits-/Compliance-Lückenanalyse.

Befunde werden eingestuft als **P0** (muss vor produktivem Einsatz gegen reale Wertpapiere behoben
werden oder eine rechtliche Freigabe erhalten), **P1** (sollte behoben werden; sinnvolle Risikominderung,
für eine Referenzimplementierung nicht freigabeblockierend) und **P2** (zur Kenntnisnahme dokumentiert;
akzeptable MVP-Grenze oder erfordert eine größere Designänderung, als im Umfang dieses Durchgangs liegt).

---

## Zusammenfassung: umgesetzt vs. noch offen { #summary-shipped-vs-still-open }

| # | Befund | Schweregrad | Status |
|---|---|---|---|
| 1 | Leistungsschalter für Oracle-Preisabweichung fehlt | P0 | **Behoben** – siehe unten |
| 2 | Oracle-Veraltung war Opt-in, für bereitgestellte Märkte nicht erzwungen | P0 | **Behoben** – siehe unten |
| 3 | Jurisdiktionsspezifische rechtliche Prüfung von Margin-Lending nicht durchgeführt | P0 | **Offen — Beratungsarbeit** |
| 4 | `EwpgRepoVault` fehlt `ReentrancyGuard` | P1 | **Behoben** – siehe unten |
| 5 | Sicherheitenbuch kann nach einer erzwungenen Übertragung vom Token-Bestand abweichen | P1 | **Behoben** – siehe unten |
| 6 | Backend `LendingPositionController` hatte kein `@PreAuthorize` | P1 | **Behoben** – siehe unten |
| 7 | On-Chain-`borrowPaused` erreichte Backend/Frontend nie | P1 | **Behoben** – siehe unten |
| 8 | Verwertung nicht wirklich erlaubnislos, wenn der Bestand an verifizierten Liquidatoren gering ist | P1 | **Offen** |
| 9 | Nominee-Pool-Flag wird nur Off-Chain gesetzt, On-Chain nicht verifiziert | P2 | **Offen** |
| 10 | Einfrieren der Kreditnehmer-Wallet erreicht bereits verpfändete Sicherheiten nicht | P2 | **Offen** |
| 11 | Berechtigungsstring-/NatSpec-Abweichungen (`repo-oracle.*`, `repo-vault.*` vs. tatsächliche `repo-markets.*`-Konstanten) | P2 | **Behoben** – nur Kommentar |
| 12 | `EwpgRepoVault.totalAssets()` iteriert unbegrenzt über die vollständige Marktliste | P2 | **Offen** |

---

## P0 — muss behoben werden oder vor der Produktion freigegeben werden { #p0-must-fix-or-get-sign-off-before-production }

### 1. Leistungsschalter für Oracle-Preisabweichung (behoben) { #1-oracle-price-deviation-circuit-breaker-fixed }

**Vorher:** `RegisterwerkNavOracle.pushPrice` (und `EwpgRepoFacility.updatePrice`) akzeptierte jeden
Preis ungleich null ohne Begrenzung gegenüber der vorherigen Marke. Ein einzelner kompromittierter oder
durch einen Tippfehler ausgelöster `PUSH_PRICE`-Schlüssel konnte Sicherheiten beliebig hoch markieren –
was übermäßige Kreditaufnahme ermöglicht, die den Pool leerlaufen lässt – oder beliebig niedrig, was
unnötige Massenverwertungen auslöst.

**Fix:** `RegisterwerkNavOracle.pushPrice` schlägt jetzt mit einem Revert (`ExcessiveDeviation`) fehl,
wenn der neue Preis um mehr als `maxDeviationBps` (Standardwert 2000 = 20 %, vom Betreiber über
`setMaxDeviationBps` einstellbar) von der vorherigen Marke abweicht. Der allererste Push für einen
Vermögenswert ist unbegrenzt (es gibt keine vorherige Marke zum Vergleich). Ein separat berechtigter
`pushPriceWithOverride` (gesteuert über `OVERRIDE_PRICE`, getrennt vom gewöhnlichen `PUSH_PRICE`) steht
für eine legitime große Neubepreisung zur Verfügung, sodass ein gewöhnlicher NAV-Feed-Automatisierungsschlüssel
den Leistungsschalter nicht allein umgehen kann.

**In diesem Durchgang nicht behoben:** `EwpgRepoFacility.updatePrice` (die ältere, gepoolte Fazilität) hat
weiterhin keine Abweichungsobergrenze – die Fazilität wird als eingefrorene Referenzimplementierung
behandelt, und die Korrektur wurde in den neueren `EwpgRepoMarket`/Oracle-Stack eingebracht, der sie
ablöst. Bleibt die Fazilität im Produktionseinsatz, muss derselbe Leistungsschalter dorthin portiert werden.

Tests: `contracts/test/lending/RegisterwerkNavOracle.t.sol` (7 Tests).

### 2. Oracle-Veraltung war Opt-in, für bereitgestellte Märkte nicht erzwungen (behoben) { #2-oracle-staleness-opt-in-not-enforced-for-deployed-markets-fixed }

**Vorher:** `EwpgRepoMarket._currentPrice()` lehnte bereits eine veraltete Marke ab, wenn
`maxPriceAgeSeconds != 0` galt, aber `0` (Veraltungsprüfung deaktiviert) war ein gültiges
Konstruktorargument, ohne dass eine Schutzvorkehrung einen Betreiber daran hinderte, einen echten Markt
auf diese Weise bereitzustellen – sei es versehentlich oder über einen kompromittierten Betreiberschlüssel,
der bewusst die einzige Schutzmaßnahme gegen einen eingefrorenen oder zurückgehaltenen Preis-Feed abschaltet.

**Fix:** `EwpgRepoMarket` selbst bleibt unverändert (die direkte Konstruktion mit `maxPriceAgeSeconds == 0`
funktioniert weiterhin, absichtlich, für Unit-Tests). `EwpgRepoMarketFactory.createMarket` – der einzige
Pfad, über den ein echter, vom Betreiber genehmigter Markt bereitgestellt wird – schlägt jetzt mit einem
Revert (`InvalidMaxPriceAge`) fehl, wenn `maxPriceAgeSeconds == 0` ist.

Tests: `contracts/test/lending/EwpgRepoMarketFactory.t.sol::test_createMarket_revertsWithZeroMaxPriceAge`.

### 3. Jurisdiktionsspezifische rechtliche Prüfung von Margin-Lending (offen — Beratungsarbeit) { #3-jurisdiction-specific-margin-lending-legal-review-open-counsel-work }

**Befund, unverändert gegenüber dem bereits bestehenden Hinweis in `defi-interoperability.md`:** Die
Verpfändung eines Wertpapiers als Kreditsicherheit löst eine regulatorische Ebene aus, die von der
Korrektheit des Smart Contracts unabhängig ist – Regeln zur Verwahrungstrennung, Margin-Lending-Lizenzierung
und (je nach Jurisdiktion) Weiterverpfändungsbeschränkungen, die ein einfacher bargeldbesicherter Kredit nie
auslöst.

| Jurisdiktion | Regulator | Relevantes Regime | Status |
|---|---|---|---|
| `DE_EWPG` | BaFin | KWG-Margin-Lending-/Wertpapierleihe-Regeln, eWpG-Verwahrung | Ungeprüft |
| `LU_CSSF` | CSSF | CSSF-Depotbank-/Verwahrstellenregeln zur Weiterverpfändung | Ungeprüft |
| `FR_AMF` | AMF | CMF-Beschränkungen für den teneur de compte-conservation | Ungeprüft |
| `LI_TVTG` | FMA | TVTG-Verwahrungstrennung für Token-Container | Ungeprüft |

**Keine weitere Vertragshärtung ersetzt dies.** Dieser Befund wird unverändert fortgeführt – er liegt
ausdrücklich außerhalb des Anwendungsbereichs einer rein codebasierten Compliance-Prüfung und erfordert
jurisdiktionsspezifische externe Rechtsberatung, bevor `EwpgRepoFacility` oder `EwpgRepoMarket` gegen reale
Wertpapiere produktiv betrieben werden.

---

## P1 — sollte behoben werden { #p1-should-fix }

### 4. `EwpgRepoVault` fehlt `ReentrancyGuard` (behoben) { #4-ewpgrepovault-missing-reentrancyguard-fixed }

**Vorher:** `EwpgRepoVault` war der einzige Vertrag im Lending-Stack (sowohl `EwpgRepoFacility` als auch
`EwpgRepoMarket` schützen jede zustandsverändernde Funktion), der keinen `ReentrancyGuard` hatte – seine
geerbten ERC-4626-Funktionen `deposit`/`mint`/`withdraw`/`redeem` sowie die eigenen
`allocate`/`deallocate` führen alle externe Token-Aufrufe ohne Reentrancy-Schutz auf der Vault-Ebene aus.

**Fix:** `EwpgRepoVault` erbt jetzt von `ReentrancyGuard`. `deposit`/`mint`/`withdraw`/`redeem` werden
ausschließlich überschrieben, um `nonReentrant` um die OZ-Implementierung herum zu ergänzen (keine
Logikänderung); `allocate`/`deallocate` haben den Modifikator direkt erhalten.

Tests: Die bestehende Test-Suite `contracts/test/lending/EwpgRepoVault.t.sol` besteht unverändert weiterhin
(der Schutz ist additiv; keine Verhaltensänderung für legitime Aufrufer).

### 5. Sicherheitenbuch kann nach einer erzwungenen Übertragung vom Token-Bestand abweichen (behoben) { #5-collateral-ledger-can-desync-from-token-balance-after-a-forced-transfer-fixed }

**Vorher:** Ein `forcedTransfer` oder `forceBurn` durch Emittent/Agent auf dem Sicherheiten-Token (eine
eWpG-§24-Berichtigung oder eine gerichtlich angeordnete AWG/GwG-Einfrierungsaktion auf Token-Ebene) kann
Token aus dem Bestand von `EwpgRepoMarket` abziehen, ohne `repay`/`liquidate` zu durchlaufen – die interne
Buchführung des Marktes (`positions[borrower].collateralAmount`) hat keine Möglichkeit, dies zu erkennen.
Bleibt dies unabgeglichen, übersteigt die erfasste Sicherheit das, was der Markt tatsächlich liefern kann,
sodass ein nachfolgendes `repay`/`liquidate` entweder scheitert oder, schlimmer, aus den Mitteln anderer
Teilnehmer zu viel auszahlt.

**Fix:** Ein neues, CONFIGURE-gegatetes `EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)`
erlaubt es dem Betreiber, die erfasste Sicherheit einer Position **nach unten** auf den Betrag zu
korrigieren, den eine bestimmte Zwangsübertragungstransaktion tatsächlich entfernt hat. Die Funktion nimmt
den korrigierten Betrag als expliziten Parameter entgegen – statt zu versuchen, ihn aus dem aggregierten
`balanceOf(this)` des Tokens abzuleiten –, weil dieser Saldo alle Kreditnehmer im Markt zusammenfasst, sodass
nur ein Off-Chain-Abgleich der konkreten Zwangsübertragungstransaktion (durch denselben Betreiberakt, der sie
angeordnet hat) die Reduzierung korrekt einem Kreditnehmer zuordnen kann. Die On-Chain-Invariante, die
durchgesetzt wird, ist einseitig gerichtet: Der Aufruf schlägt mit einem Revert
(`ReconciliationWouldIncreaseCollateral`) fehl, wenn der neue Betrag nicht strikt niedriger ist als der
aktuell erfasste Betrag – es können also nie Sicherheiten erzeugt werden, die nie verpfändet wurden.

Tests: `contracts/test/lending/EwpgRepoMarket.t.sol` (`test_reconcileCollateral_*`, 4 Tests).

### 6. Backend `LendingPositionController` hatte kein `@PreAuthorize` (behoben) { #6-backend-lendingpositioncontroller-had-no-preauthorize-fixed }

**Vorher:** `GET /api/v1/lending/my-positions` und `/supply-positions` trugen kein methoden- oder
klassenweites `@PreAuthorize`, anders als jeder andere kundenseitige Controller in diesem Modul. Die
Eingrenzung war rein implizit – ein nicht authentifizierter Aufruf löste eine Null-`appUserId` auf und gab
stillschweigend eine leere Liste zurück, statt rundweg abgelehnt zu werden.

**Fix:** `@PreAuthorize("isAuthenticated()")` wurde auf Klassenebene ergänzt, passend zu dem Muster, das
`PositionStatementController`/`SteuerbescheinigungController` für andere kundenseitige Lese-Endpunkte
verwenden.

### 7. On-Chain-`borrowPaused` erreichte Backend/Frontend nie (behoben) { #7-on-chain-borrowpaused-never-reached-the-backendfrontend-fixed }

**Vorher:** `EwpgRepoMarket.borrowPaused` (und `LendingMarketStatus.PAUSED` im Backend-Lesemodell)
existierten, aber nichts setzte den in der Datenbank persistierten Status jemals auf `PAUSED` – das
On-Chain-Flag und das Lesemodell waren voneinander getrennt, sodass ein pausierter Markt überall weiterhin
als `ACTIVE` erschien und ein Kreditversuch eines Traders dagegen einfach On-Chain ohne Vorwarnung scheiterte
(Revert).

**Fix:** `LendingMarketService.resolveEffectiveStatus` liest das On-Chain-Flag live für jeden Markt, dessen
persistierter Status `ACTIVE` ist, und spiegelt `PAUSED` in jeder Listen-/Detailantwort, ohne die DB-Zeile zu
verändern (ein Live-Read, keine Zustandsänderung) – ein fehlgeschlagener Chain-Read fällt auf den
persistierten Status zurück, statt die gesamte Auflistung fehlschlagen zu lassen, entsprechend demselben
Best-Effort-Muster, das bereits für Health-Factor-Reads von Positionen verwendet wird. Der kundenseitige
Borrow-Stepper zeigt jetzt einen expliziten Status „Markt vorübergehend pausiert" an, statt die Transaktion
scheitern zu lassen.

Tests: `LendingMarketServiceTest` (`activeMarketReflectsOnchainBorrowPaused`,
`activeMarketStaysActiveWhenNotPaused`, `retiredMarketSkipsOnchainCheck`,
`onchainReadFailureFallsBackToPersistedStatus`).

### 8. Verwertung nicht wirklich erlaubnislos, wenn der Bestand an verifizierten Liquidatoren gering ist (offen) { #8-liquidation-not-truly-permissionless-when-the-verified-liquidator-set-is-thin-open }

**Befund:** `liquidate` ist auf der `RegisterwerkGated`-Ebene nominell ungegatet (dokumentiert als
„erlaubnislos, wie bei Aave, weil die T-REX-Wall des Tokens selbst die Compliance-Arbeit kostenlos
übernimmt"). Das stimmt nur zur Hälfte: Die Wall sperrt den **Empfänger** der beschlagnahmten Sicherheiten
(den Liquidator), nicht nur den Kreditnehmer. Ist der Bestand an T-REX-verifizierten, nicht eingefrorenen,
nicht länder-gesperrten Adressen, die zur Verwertung bereit sind, gering, kann eine ungesunde Position
möglicherweise überhaupt keinen zulässigen Liquidator finden – die Position bleibt unterbesichert offen und
geht zulasten der Einleger, ohne dass ein Ausweichpfad zu ihrer Schließung besteht. Ein verifizierter
Liquidator, dessen Bestand am Sicherheiten-Token nahe an `maxBalancePerInvestor` liegt, ist ebenfalls daran
gehindert, beschlagnahmte Sicherheiten entgegenzunehmen, es sei denn, der Liquidator selbst ist als Nominee
gekennzeichnet.

**Empfehlung:** Für Märkte, bei denen nicht davon ausgegangen werden kann, dass der Bestand an verifizierten
Liquidatoren groß ist, sollte ein Verwertungspfad als letzte Instanz entworfen werden (z. B. eine vom
Betreiber kontrollierte, vorab als Nominee-Pool gekennzeichnete Adresse, die befugt ist, zu verwerten und
beschlagnahmte Sicherheiten sofort weiterzuverteilen oder einzulagern). In diesem Durchgang nicht
implementiert – es handelt sich um ein neues Zugriffskontrolldesign, keine begrenzte Korrektur.

---

## P2 — dokumentiert, derzeit akzeptabel oder erfordert größere Designarbeit { #p2-documented-acceptable-for-now-or-requires-larger-design-work }

### 9. Nominee-Pool-Status wird nur Off-Chain gesetzt (offen) { #9-nominee-pool-status-is-asserted-off-chain-only-open }

Das gesamte Pooling-Modell hängt von einer Off-Chain-Betreiberaktion (`EwpgModularCompliance.setNomineePool`)
ab, die den Markt als Nominee-Pool auf dem Sicherheiten-Token markiert, sowie von einem Off-Chain-Look-Through-KYC/AML
der eigenen Einleger des Pools (siehe
[DeFi-Interoperabilität § Nominee-/Omnibus-Bridge](../platform/defi-interoperability.md#the-nomineeomnibus-bridge-nominee_pool)).
Nichts in den Kreditverträgen verifiziert On-Chain, dass dieses Flag vor der Annahme von Verpfändungen
tatsächlich gesetzt wurde – die erste Verpfändung, die die Obergrenze pro Investor überschreiten würde,
scheitert einfach auf Token-Ebene (Revert), wenn das Flag fehlt; das ist ein funktionierendes
Sicherheitsnetz, liefert aber kein proaktives Signal.
**Empfehlung:** Ein On-Chain-Ereignis, das die Bereitstellung eines Marktes mit seinem Nominee-Pool-Flag
verknüpft (z. B. indem die Factory das Flag bei `createMarket` ausliest und protokolliert), würde die
Prüfbarkeit verbessern, ohne das Sicherheitsmodell zu ändern. Als „nice-to-have"-Verbesserung der
Beobachtbarkeit zurückgestellt, keine Lücke im Compliance-Modell selbst.

### 10. Einfrieren der Kreditnehmer-Wallet erreicht bereits verpfändete Sicherheiten nicht (offen) { #10-borrower-wallet-freeze-doesnt-reach-already-pledged-collateral-open }

Sobald Sicherheiten in einen Markt verpfändet sind, ist der Pool-Vertrag – nicht der Kreditnehmer – der
registrierte Inhaber des Tokens. Ein nachträglicher Sperrvermerk nach §16 eWpG oder eine AWG/GwG-Einfrierung
der eigenen Wallet des Kreditnehmers erfasst diese bereits verpfändeten Sicherheiten nicht mehr, da die
Freeze-Prüfung gegen die `from`-Adresse einer Übertragung läuft und der Pool bei jeder weiteren Bewegung die
`from`-Adresse ist. Ob dies der regulatorischen Absicht hinter einem Wallet-Einfrieren entspricht, ist selbst
eine Rechtsfrage, die mit Befund #3 oben zusammenhängt, und keine Smart-Contract-Lücke mit einer
offensichtlichen Code-Korrektur (das Einfrieren der *Position* statt der Wallet würde einen neuen Zustand
und einen neuen Durchsetzungspunkt in jedem Kreditvertrag erfordern). Für die Rechtsprüfung in Befund #3 zur
ausdrücklichen Berücksichtigung dokumentiert.

### 11. Berechtigungsstring-/NatSpec-Abweichungen (behoben — nur Kommentar) { #11-permission-string-natspec-mismatches-fixed-comment-only }

Zwei Doc-Comment-Abweichungen zwischen der tatsächlich durchgesetzten Konstante und dem in NatSpec
dokumentierten String (ein Governance-/Audit-Hygiene-Thema – der falsche String könnte irreführen, wer
Berechtigungen anhand der Dokumentation statt des Codes vergibt):
- Der Doc-Comment von `RegisterwerkNavOracle.pushPrice` nannte `repo-oracle.push-price`; die tatsächliche
  Konstante ist `PUSH_PRICE = keccak256("repo-markets.push-price")`. Behoben.
- Der Doc-Comment auf Vertragsebene von `EwpgRepoVault` nannte `repo-vault.curate`; die tatsächliche Konstante
  ist `CURATE = keccak256("repo-markets.curate-vault")`. Behoben.

Keine Verhaltensänderung – beide Konstanten waren bereits korrekt und gemäß der Namensraum-Regel von
`ManifestValidationService` unter dem Marktplatzeintrag `repo-markets` benannt; nur die Prosa war falsch.

### 12. `EwpgRepoVault.totalAssets()` iteriert über die vollständige Marktliste (offen) { #12-ewpgrepovaulttotalassets-iterates-the-full-market-list-open }

`totalAssets()` durchläuft bei jeder Berechnung des Anteilspreises jeden jemals hinzugefügten Markt
(einschließlich deaktivierter) – jede `deposit`/`withdraw`/`mint`/`redeem`-Umrechnung trägt diese Kosten.
Für die Handvoll Märkte, die ein Kurator-Vault realistischerweise verwaltet, ist das unerheblich, aber
unbegrenztes Marktwachstum würde irgendwann zu einem Gaskosten-Problem werden. Akzeptable MVP-Grenze; ein
begrenztes/paginiertes `totalAssets` (oder der Ausschluss deaktivierter Märkte aus der Schleife) ist eine
naheliegende v2-Verfeinerung, falls ein Vault jemals Dutzende von Märkten erreicht.

---

## Verifizierung { #verification }

- Verträge: `cd contracts && forge test --match-path "test/lending/*" -vv` – 45 Tests, alle bestanden
  (0 Regressionen gegenüber der bereits bestehenden Lending-Suite); vollständige Suite `forge test` –
  388 bestanden, 0 fehlgeschlagen, 18 übersprungen.
- Backend: `cd backend && ./mvnw verify` – 436 Unit- + 30 Integrationstests bestanden, alle
  JaCoCo-Coverage-Gates (einschließlich der compliance-kritischen Grenzwerte für
  `registerstatement`/Lending-angrenzende Bereiche) erfüllt.
