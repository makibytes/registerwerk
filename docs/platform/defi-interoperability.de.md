# DeFi-Interoperabilität { #defi-interoperability }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Bei dieser Seite handelt es sich um eine technische Designdiskussion. Registerwerk und die
    beschriebenen DeFi-, Handels-, Verwahrungs-, Nominee-, Zahlungs- und Kreditvereinbarungen werden
    nicht als gesetzlich zulässig, konform oder autorisiert dargestellt und sind nicht
    produktionsbereit. Klassifizierung und Rechtswirksamkeit erfordern eine betreiber-,
    instrumenten-, dienstleistungs-, kontrahenten-, transaktions- und rechtsgebietsspezifische
    Prüfung.

Registerwerk ist für den Einsatz auf regulierten Kapitalmärkten bestimmt. Dieses Dokument erklärt,
wo und warum Brücken zum DeFi-/Ethereum-Ökosystem sinnvoll sind und – was genauso wichtig ist – wo
sie es nicht sind, angesichts der regulatorischen Realität tokenisierter Wertpapiere statt
Krypto-Assets.

## Der regulatorische Ausgangspunkt { #the-regulatory-starting-point }

Die MiCAR-/MiFID-II-Klassifizierung lässt sich nicht aus einem Token-Standard, einem
`eWpG`-Etikett oder einem Zahlungsweg-Flag ableiten. Das Modul `payment` speichert vom Betreiber
erfasste Offenlegungs- und Attestierungsfelder; sie stellen jedoch nicht eigenständig fest, dass es
sich bei einem Vermögenswert um ein Finanzinstrument handelt, dass ein Stablecoin ein EMT ist, oder
dass ein Emittent oder Dienstleister zugelassen ist.

Ob ein erlaubnisfreies AMM, ein Kreditpool, ein Verwahrer, ein Nominee oder eine Sammelstruktur ein
Instrument halten oder übertragen darf, ist eine rechtliche, regulatorische, verwahrungs-,
insolvenz- und produktdesignbezogene Frage – keine, die von den Smart Contracts oder der
Jurisdiktionskonfiguration beantwortet wird. Die folgenden Modelle sind technische Optionen zur
Prüfung durch Rechtsberatung und Control-Owner.

## Jurisdiktionsinteroperabilitätsmatrix { #jurisdiction-interoperability-matrix }

Modelliert in `backend/.../kyc/api/JurisdictionRequirementConfig.ComplianceMetadata` über die
neuen Felder `defiInteropModel` / `permissionlessAmmAllowed` (`DefiInteropModel`-Enum, dasselbe
Paket):

| Jurisdiktion | Regulator | `defiInteropModel` | `permissionlessAmmAllowed` | Basis |
|---|---|---|---|---|
| `DE_EWPG` | BaFin | `NOMINEE_POOL` | `false` | eWpG Sammelverwahrung |
| `LU_CSSF` | CSSF | `NOMINEE_POOL` | `false` | CSSF-beaufsichtigte Sammelverwahrung durch Verwahrer/Depotstelle |
| `FR_AMF` | AMF | `NOMINEE_POOL` | `false` | CMF-Regime teneur de compte-conservation (Kontoführer/Verwahrer) |
| `LI_TVTG` | FMA | `NOMINEE_POOL` | `false` | TVTG-Token-Container-Modell + lizenzierter VT-Dienstanbieter |

Alle vier Jurisdiktionen landen heute beim selben Modell, da alle vier bereits eine lizenzierte
Vermittler-Omnibusstruktur anerkennen – es gab bislang keine gebietsspezifische Abweichung zu
modellieren. `permissionlessAmmAllowed` ist überall `false` und bewusst explizit gesetzt (nicht
nur das Fehlen eines `true`), damit eine künftige Erweiterung um weitere Jurisdiktionen eine aktive
Entscheidung treffen muss, statt stillschweigend einen Standardwert zu erben. Der Geltungsbereich
dieses Durchgangs war bewusst auf die vier bereits angebundenen Jurisdiktionen beschränkt
(`DE_EWPG`, `LU_CSSF`, `FR_AMF`, `LI_TVTG`); die Aufnahme von Nicht-EU-Regelungen (z. B. das
Schweizer DLT-Gesetz mit seiner eigenen DLT-Handelseinrichtungslizenz, die Handel/Abwicklung/
Verwahrung kombiniert abdeckt) ist eine naheliegende nächste Erweiterung, sobald Registerwerk dort
tatsächlich tätig wird.

Ein drittes Modell, `ORACLE_ONLY`, existiert für das bereits einsetzbare, verwahrungsrisikofreie
Muster, bei dem ein externes Protokoll nur `PermissionOracle`-Ansprüche *liest* – siehe
[dapp-development.md § Externe DeFi-Komponierbarkeit](./dapp-development.md#external-defi-composability).
Keine Jurisdiktion ist heute auf `ORACLE_ONLY` beschränkt, da `NOMINEE_POOL` eine strikte Obermenge
dessen ist, was es erlaubt.

## Die Nominee-/Omnibus-Brücke (`NOMINEE_POOL`) { #the-nomineeomnibus-bridge-nominee_pool }

Ein neues ONCHAINID-Anspruchsthema, `NOMINEE` (Thema 4, neben den bestehenden 1=KYC/2=AML/
3=Akkreditierung), wird von einem vertrauenswürdigen Aussteller an das eigene ONCHAINID eines
lizenzierten Verwahrers/CASP ausgestellt — unter Verwendung genau derselben
`ClaimIssuanceService`/`EcosystemTrustedIssuersRegistry`-Infrastruktur, die bereits für
KYC-/AML-Ansprüche vorhanden ist. Die Pool-Vertragsadresse dieses Verwahrers wird dann auf dem
`EwpgComplianceModule` des jeweiligen Tokens (`contracts/src/compliance/EwpgModularCompliance.sol`,
`setNomineePool`) als Nominee-Pool gekennzeichnet, was:

- **Nimmt** die Pool-Adresse von `maxBalancePerInvestor` und `maxInvestors` **aus** – das ist der
  ganze Sinn der Sache, da ein Pool das wirtschaftliche Engagement vieler LPs hinter einer Adresse
  bündelt und eine Obergrenze pro Investor für diese eine Adresse den regulatorischen Zweck der
  Obergrenze zunichtemachen würde (sie würde entweder jegliches Pooling komplett blockieren, oder
  stillschweigend zulassen, dass die Obergrenze umgangen wird, indem viele Investoren hinter einem
  „Investor" gebündelt werden).
- **Behält** die Länderblockierungs- und Transfer-Cooldown-Prüfungen bedingungslos bei – der
  Poolbetreiber selbst darf weiterhin nicht in einer gesperrten Jurisdiktion ansässig sein.
- **Gilt nur für Vertragsadressen** (`to.code.length > 0`) – die Kennzeichnung eines EOA als
  Nominee-Pool ist ein No-Op, da die gesamte Ausnahme nur für einen echten Pooled-Custody-Vertrag
  Sinn ergibt, nicht für die Wallet einer Einzelperson.

Die Verantwortung für die Look-through-KYC/AML-Prüfung der zugrundeliegenden LPs des Pools liegt
vollständig beim Nominee-/Verwahrer-Betreiber, off-chain – genau wie heute bei einem traditionellen
Sammelkonto einer Verwahrbank. Wird das Manifest einer dApp mit `requiredClaimTopics: [4]`
markiert, wird dies dem prüfenden Betreiber während der Marktplatzgenehmigung
(`ManifestValidationService`) als Hinweis angezeigt, der eine menschliche Prüfung der
Verwahrer-Lizenz des Herausgebers erfordert – keine automatisch genehmigte Deklaration.

## Was Liquiditätsanbieter und Market Maker eigentlich anzieht { #what-actually-attracts-liquidity-providers-and-market-makers }

Bevor man entscheidet, was gebaut werden soll, lohnt sich ein ehrlicher Blick darauf, wofür ein
Händler oder Market Maker optimiert und wie sich das auf ein konformes Wertpapierregister abbildet
– oder eben nicht:

- **TradFi-Anleihemärkte sind nicht in erster Linie wegen des Sekundärhandels liquide.** Sie sind
  liquide wegen **Repo-Geschäften** (ein Pensionsgeschäft: Die Anleihe wird jetzt verkauft, mit der
  Vereinbarung, sie später zu einem festen Preis zurückzukaufen) und **Wertpapierleihe**. Ein
  Händler, der eine illiquide Position hält, verkauft sie nicht, um Barmittel zu beschaffen – er
  gibt sie per Repo über Nacht oder auf Zeit weiter, behält das wirtschaftliche Engagement und legt
  das Bargeld neu an. Repo-Märkte bewegen täglich Billionen und stellen das Handelsvolumen
  sekundärer Cash-Bonds in den Schatten, gerade weil sie einem Inhaber den Zugriff auf Liquidität
  *ohne* einen direkten Verkauf ermöglichen (kein realisierter Preis, kein verlorenes Kurspotenzial,
  kein Zwangsverkäuferabschlag).
- **DeFi-Geldmärkte (Aave, Compound) sind derselbe Mechanismus, gepoolt und algorithmisch**:
  Sicherheiten werden hinterlegt und dagegen Kredite aufgenommen, zu einem Zinssatz, der durch
  Echtzeit-Auslastung statt durch bilaterale Verhandlung festgelegt wird. Was LPs tatsächlich zu
  einem Geldmarkt lockt, ist ein transparenter, auslastungsgesteuerter Zinssatz, erlaubnisfreier
  Zugang auf der Angebotsseite und ein glaubwürdiger Verwertungsmechanismus, der die Einleger
  schadlos hält.
- **Uniswap-artige AMMs ziehen LPs durch Gebühreneinnahmen und erlaubnisfreie Pool-Erstellung an**
  — doch dieses Modell setzt voraus, dass der gehandelte Vermögenswert fungibel, kontinuierlich
  bepreist und sicher genug ist, um von einem anonymen Vertrag treuhänderisch für viele Parteien
  gehalten zu werden. Nichts davon gilt für ein NAV-bepreistes, zulassungsbeschränktes Wertpapier –
  genau deshalb schließt dieses Repository ein AMM/Orderbuch für die Wertpapier-Token-Seite aus
  (siehe den Abschnitt zum Handelsmechanismus unten).
- **Real-World-Asset-Plattformen haben diese Lektion bereits gelernt.** Ondo, Centrifuge, Maple und
  BlackRocks BUIDL ziehen alle den größten Teil ihres DeFi-Nutzens daraus, dass sie als
  **Sicherheit** in einem Kreditmarkt hinterlegt werden, nicht aus Spot-Handelsliquidität – der
  tokenisierte RWA sitzt in einer einzigen verwahrten/gepoolten Position, und die
  Stablecoin-Liquidität bewegt sich um ihn herum.
- **Was sich ein Market Maker konkret wünscht**: eine Möglichkeit, sowohl Long- als auch
  Short-Positionen einzugehen, sich abzusichern, dasselbe Kapital über mehrere Positionen hinweg
  wiederzuverwenden (Kapitaleffizienz), und Ausführungssicherheit. Besicherte Kreditaufnahme gibt
  einem Inhaber genau das – Hebelwirkung und Kapitaleffizienz – ohne dass Registerwerk je eine
  Matching-Engine betreiben müsste.

Die Schlussfolgerung: **Eine besicherte Kreditfazilität als Referenzimplementierung ist ein
potenzielles Liquiditätsmerkmal für Registerwerk, kein rechtlich zugelassenes Produkt.** Sie passt
auch perfekt zur Vorgabe „keine DEX bauen", da besicherte Kreditvergabe von Anfang an kein
Orderbuch war.

## `EwpgRepoFacility` — der primäre Exit-Liquiditätsmechanismus { #ewpgrepofacility-the-primary-exit-liquidity-mechanism }

`contracts/src/examples/EwpgRepoFacility.sol` ist eine Referenz-Repo-/besicherte-Kreditfazilität
mit bewusst asymmetrischem Gating. Die Produktionsnutzung ist gesperrt, bis rechtliche Einordnung,
Verwahrung/Kontrolle, Verwertung, Orakel, Insolvenz und Smart-Contract-Freigabe geklärt sind:

- **Die Kreditgeberseite (`deposit`/`withdraw`) steht jedem Stablecoin-Inhaber offen** –
  überhaupt keine `RegisterwerkGated`-Prüfung. Einleger halten stets nur einen Anspruch auf
  gepoolte Stablecoins; sie berühren nie den eingeschränkten Wertpapier-Token, daher gibt es
  keinen wertpapierrechtlichen Grund, sie zu gaten. Das ist der größte einzelne Hebel für
  „Registerwerks Attraktivität, um Liquidität am Markt zu gewinnen": Je weniger Hürden für die
  *Bereitstellung* von Kapital bestehen, desto tiefer der Pool, da das zu bepreisende Risiko
  vollständig von der (gegateten) Kreditnehmerseite getragen wird.
- **Die Kreditnehmerseite (`pledgeAndBorrow`) ist gegated** – die Berechtigung
  `repo-facility.borrow` plus ein gültiger KYC-Anspruch, da nur ein verifizierter Anleger die
  eingeschränkte Sicherheit verpfänden darf. Ein Kreditnehmer verpfändet z. B. eine
  `EwpgERC3643`-Anleiheposition und zieht Stablecoin bis zu einer konfigurierten Beleihungsquote
  (LTV), während die Anleiheposition und ihre Kupon-/Rückzahlungsrechte intakt bleiben. Das ist
  das „Repo"-Geschäft: Liquidität ohne Verkauf.
- **`repay` und `liquidate` bleiben bewusst ungated.** Die Rückübertragung der Sicherheit an den
  Aufrufer unterliegt selbst der eigenen T-REX-Identitätsregister-Prüfung des Tokens – die
  Transaktion eines nicht verifizierten Aufrufers wird schlicht auf Token-Ebene zurückgerollt
  (Revert). Das bedeutet, die Verwertung kann für berechtigte Empfänger technisch erlaubnisfrei
  erfolgen, begründet damit aber keine rechtliche oder regulatorische Compliance. Die vorhandene
  `isVerified()`-Mauer liefert nur ein Gate auf Vertragsebene. Die Rückzahlung bleibt aus Prinzip
  offen – die Reduzierung des eigenen Risikos und die Rückforderung der eigenen, zuvor
  verpfändeten Sicherheit sollte nie durch eine administrative Berechtigungsänderung blockiert
  werden können.
- **Die Verzinsung ist auslastungsbasiert** (im Aave-Stil `liquidityIndex`/`borrowIndex`,
  WAD-skaliert), sodass beide Seiten unabhängig von der Teilnehmerzahl in O(1) abrechnen und
  Einleger eine transparente, markträumende Rendite statt eines Festzinssatzes sehen.
- Wie bei `CompliantSecondaryMarket` bündelt auch die eigene Adresse der Fazilität die
  Sicherheiten vieler Kreditnehmer hinter einer Adresse; daher benötigt jeder als Sicherheit
  hinterlegte `EwpgERC3643`-Vermögenswert dasselbe
  `EwpgComplianceModule.setNomineePool(token, address(facility), true)`-Flag, bevor Verpfändungen
  über die individuelle Obergrenze des ersten Anlegers hinaus erfolgreich sein können.

### Handelsmechanismus, aufgeteilt nach Paartyp { #trading-mechanism-split-by-pair-type }

- **Wertpapier-Token-Seite: RFQ/bilateraler Abgleich über `DvpSettlement`**
  (`contracts/src/examples/CompliantSecondaryMarket.sol`). Keine gemeinsame Bonding Curve – Kurse
  werden off-chain abgeglichen (oder über eine einfache On-Chain-Kursstellungsfunktion) und in
  einer einzigen erfolgreichen Transaktion über die vorhandenen Grundfunktionen
  `lockAsset`/`lockPayment`/`settle` abgewickelt. Das Exact-Leg-Verhalten setzt Token ohne
  Transfergebühren/Rebases voraus; Finalität und die Eintragung ins gesetzliche Register sind
  gesondert zu betrachten. Das vermeidet Impermanent Loss und Orakel-Manipulationsrisiken bei
  einer potenziell illiquiden, NAV-bepreisten Anleihe – aus demselben Grund verwenden real
  regulierte Handelsplätze (SDX, MTFs im EU-DLT-Pilotregime) Orderbuch-/RFQ-Preisbildung statt
  Konstantprodukt-Kurven für Wertpapiere. Seine Rolle versteht man inzwischen besser als
  Preisfindung, die `EwpgRepoFacility.updatePrice` speist (der zuletzt ausgeführte Fill liefert
  eine legitime Bewertungsmarke für die Sicherheit) statt als primärer Liquiditätsplatz – genau
  wie der Sekundärhandel mit Anleihen in TradFi überwiegend der Preisfindung dient, während Repo
  die eigentliche Liquiditätsarbeit übernimmt. Mehrere konkurrierende Nominee-Betreiber können
  jeweils ihre eigene Instanz bereitstellen und auf demselben Token gekennzeichnet werden – es
  handelt sich also um Dealer-to-Client-Wettbewerb zwischen Market Makern, nicht um einen
  einzelnen Monopolschalter.
- **Nur-Stablecoin-Seite: ein einfaches Konstantprodukt-AMM**
  (`contracts/src/examples/StablecoinAmm.sol`). Reserviert für Paare, bei denen keine der beiden
  Seiten ein Wertpapier ist (z. B. AUEUR/USDC, beide über den Zahlungswege-Katalog
  `PaymentRailType.STABLECOIN` des Moduls `payment` deklariert) – der einzige Fall, in dem ein
  vertrautes, DeFi-natives AMM tatsächlich die risikoärmere Wahl ist, da es überhaupt keine
  Bedenken hinsichtlich der Preisintegrität von Wertpapieren gibt.

Alle drei Referenz-dApps erben `RegisterwerkGated` auf dieselbe Weise wie `BoardroomGovernance`/
`EwpgBondDesk`. `EwpgRepoFacility` liefert zusätzlich ein vollständiges Manifest, ein README und
Demo-Seeding wie die anderen beiden Flaggschiff-Beispiele – siehe
[dapp-development.md § Referenz-Beispiel-dApps](./dapp-development.md#reference-example-dapps).
`CompliantSecondaryMarket` und `StablecoinAmm` bleiben nur als getesteter Solidity-Code vorhanden
(kein Manifest, nicht als Marktplatz-Listing gesät).

## `EwpgRepoMarket` / `EwpgRepoVault` — die Evolution zum isolierten Markt { #ewpgrepomarket-ewpgrepovault-the-isolated-market-evolution }

`contracts/src/lending/` ist die Morpho-Blue-artige Weiterentwicklung von `EwpgRepoFacility`,
additiv zu ihr (beide können gegen dasselbe Ökosystem laufen – siehe
`script/DeployRepoMarkets.s.sol`). Während die Fazilität jeden Sicherheitentyp hinter einem
gemeinsamen Cash-Pool und einem gemeinsamen Indexpaar bündelt, isoliert jeder `EwpgRepoMarket` das
Risiko auf genau ein `{loanToken, collateralToken}`-Paar, bereitgestellt über die
`EwpgRepoMarketFactory` (CREATE2, betreibergesteuert). `EwpgRepoVault` ist die MetaMorpho-artige
Curator-Schicht darüber, die Kreditgebereinlagen mit Obergrenzen pro Markt über mehrere Märkte
hinweg leitet. `RegisterwerkNavOracle`/`IRepoOracle` formalisieren das NAV-Push-Muster der
Fazilität als eigenständige, austauschbare Schnittstelle. Dasselbe asymmetrische Gating wie bei
der Fazilität (Kreditgeberseite offen, Kreditnehmerseite KYC- und berechtigungsgegated,
`repay`/`liquidate` auf dieser Ebene ungated – die eigene T-REX-Mauer des Tokens ist das
eigentliche Gate); die Mechanik im Einzelnen steht in der jeweiligen NatSpec der Verträge.

Diese Weiterentwicklung löst zwei der drei unten für die Fazilität genannten Vereinfachungen: Ein
Reservefaktor (gedeckelt bei 25 %, vom Betreiber einstellbar) und eine partielle Verwertung (50 %
Close-Factor, im Aave-Stil) existieren nun beide in `EwpgRepoMarket` – die Fazilität selbst bleibt
unverändert und eine einfachere Referenzimplementierung. Der dritte Punkt – die
jurisdiktionsspezifische rechtliche Prüfung von Margin-Lending – gilt für beide identisch und ist
**weiterhin offen**; siehe die Prüfung unten.

## Compliance-Überprüfung (2026-07-21) — Ergebnisse und Härtungsmaßnahmen { #compliance-review-2026-07-21-findings-and-hardening }

Eine vollständige Compliance-Prüfung von `EwpgRepoFacility`, dem `EwpgRepoMarket`/`Vault`/
Oracle-Stack und dem Backend-Read-Model `lending` ergab die unten aufgeführten Punkte. Vollständige
Details, Zuordnung je Jurisdiktion und Einstufung nach Schweregrad:
`docs/compliance/lending-facility-review.md`. Zusammenfassung dessen, was in diesem Durchgang
umgesetzt wurde, gegenüber dem, was noch offen ist:

**Gehärtet (dieser Durchgang):**

- **Schutzschalter gegen Orakel-Preisabweichungen** – `RegisterwerkNavOracle.pushPrice` lehnt
  jetzt einen Push ab, der mehr als einen vom Betreiber konfigurierbaren Wert `maxDeviationBps`
  (Standard 20 %) von der vorherigen Marke abweicht; für legitime große Neubewertungen existiert
  ein separat berechtigtes `pushPriceWithOverride`. Begrenzt den Schadensradius eines einzelnen
  kompromittierten oder fehlerhaft bedienten NAV-Feed-Schlüssels.
- **Verpflichtende Oracle-Frische für bereitgestellte Märkte** – `EwpgRepoMarket` selbst erlaubt
  für direkt konstruierte Unit-Tests weiterhin `maxPriceAgeSeconds == 0` (Frische-Prüfung
  deaktiviert), aber `EwpgRepoMarketFactory.createMarket` lehnt `0` jetzt ab – jeder vom Betreiber
  bereitgestellte Markt verfügt über eine echte Frischegrenze.
- **`EwpgRepoVault`-Reentrancy-Schutz** – der Vault war der einzige Vertrag im Lending-Stack ohne
  `ReentrancyGuard` an seinen wertbewegenden Einstiegspunkten
  (`deposit`/`mint`/`withdraw`/`redeem`/`allocate`/`deallocate`); jetzt geschützt wie jeder andere
  Ewpg*-Lending-Vertrag.
- **Abgleich des Sicherheiten-Ledgers** (eWpG §24 Berichtigung) – eine neue, CONFIGURE-gegatete
  Funktion `EwpgRepoMarket.reconcileCollateral(borrower, attributableCollateral)` erlaubt es dem
  Betreiber, die erfasste Sicherheit einer Position **nach unten** zu korrigieren (nie nach oben),
  nachdem ein Agent per `forcedTransfer`/`forceBurn` Sicherheiten unabhängig von `repay`/`liquidate`
  aus dem Pool bewegt hat – das schließt eine Lücke, in der das interne Ledger sonst vom
  tatsächlichen Bestand des Tokens abweichen könnte.
- **Autorisierungslücke im Backend-`LendingPositionController`** – `/api/v1/lending/my-positions`
  und `/supply-positions` trugen kein `@PreAuthorize`; jetzt ist eine Authentifizierung
  erforderlich.
- **On-Chain-`borrowPaused` erreicht jetzt Backend/Frontend** – `LendingMarketService` liest das
  Flag live aus (best effort; schlägt ein Chain-Read fehl, greift der Dienst auf den gespeicherten
  Status zurück, statt das Listing scheitern zu lassen) und spiegelt es als `PAUSED` – das schließt
  die Lücke, in der der Status zwar im Modell existierte, aber nirgends sichtbar wurde. Der
  Borrow-Stepper zeigt jetzt einen expliziten „Markt pausiert"-Status an, statt einen
  Kreditversuch on-chain scheitern zu lassen (Revert).

**Noch offen (weitere Einzelheiten finden Sie im Überprüfungsdokument):**

- **Jurisdiktionsspezifische rechtliche Prüfung von Margin-Lending** – unverändert gegenüber der
  Fazilität (siehe unten): Verwahrungstrennung, Margin-Lending-Lizenzierung und
  Weiterverpfändungsbeschränkungen sind eine vom Vertrag unabhängige regulatorische Frage und
  bleiben für `DE_EWPG`/`LU_CSSF`/`FR_AMF`/`LI_TVTG` ungeprüft.
- **Die Verwertung ist nicht wirklich erlaubnisfrei, wenn der Kreis verifizierter Verwerter dünn
  ist** – beschlagnahmte Sicherheiten werden an den Verwerter übergeben, daher gatet die
  T-REX-Mauer auch den Verwerter; gibt es keinen zugelassenen Verwerter, kann eine notleidende
  Position nicht geschlossen werden. Bislang existiert kein Fallback-Verwertungspfad (z. B. ein
  „Agent of Last Resort").
- **Der On-Chain-Status als Nominee-Pool wird nur off-chain behauptet** – nichts on-chain
  verifiziert, dass ein Markt tatsächlich als Nominee-Pool gekennzeichnet wurde, bevor
  Verpfändungen über die Obergrenze pro Investor hinaus akzeptiert werden; die erste über die
  Obergrenze hinausgehende Verpfändung wird heute schlicht auf Token-Ebene zurückgerollt (Revert).
- **Ein Wallet-Freeze des Kreditnehmers erreicht bereits verpfändete Sicherheiten nicht** –
  befindet sich die Sicherheit erst im Pool, gatet ein nachträgliches Freeze der eigenen Wallet
  des Kreditnehmers sie nicht mehr, da ab diesem Zeitpunkt der Pool-Vertrag der registrierte
  Inhaber des Tokens ist.

## Kreditvergabe gegen Wertpapiere als Sicherheit: Was ist tatsächlich implementiert, und was bedarf noch der rechtlichen Freigabe { #lending-against-securities-as-collateral-whats-actually-implemented-vs-what-still-needs-legal-sign-off }

`EwpgRepoFacility` ist implementiert und getestet
(`contracts/test/examples/EwpgRepoFacility.t.sol`) als **Referenzimplementierung** – die Mechanik
der besicherten Kreditvergabe, das Gating und die Verwertungslogik sind echt und korrekt, aber die
folgenden Punkte bleiben bewusste Vereinfachungen oder offene Fragen vor einem Produktionseinsatz:

- **Kein Protokoll-Reservefaktor** – 100 % der Kreditnehmerzinsen fließen heute an die Einleger;
  das ist bewusst so gehalten, um in einer Referenzimplementierung eine exakt nachprüfbare
  Buchhaltung zu ermöglichen. Ein Reserveabzug wäre eine isolierte, additive Änderung.
  (`EwpgRepoMarket` hat bereits einen – siehe oben.)
- **Nur Verwertung mit vollständigem Close-Factor** – eine notleidende Position wird in einem
  einzigen Aufruf für die gesamten ausstehenden Schulden verwertet, nicht teilweise. Echte
  Geldmärkte unterstützen oft eine Teilverwertung, um den Kapitalbedarf des Verwerters zu senken.
  (`EwpgRepoMarket` unterstützt bereits eine teilweise Verwertung mit Close-Factor – siehe oben.)
- **Wertpapiere als Sicherheit lösen unabhängig vom Smart-Contract-Design eine eigene
  Regulierungsebene aus**: Regeln zur Verwahrungstrennung, Margin-Lending-Lizenzierung und (je
  nach Jurisdiktion) Weiterverpfändungsbeschränkungen, die für ein einfaches bargeldbesichertes
  Darlehen nicht gelten. Holen Sie eine jurisdiktionsspezifische rechtliche Prüfung der
  Margin-Lending-Regeln nach `DE_EWPG`/`LU_CSSF`/`FR_AMF`/`LI_TVTG` ein, bevor diese Fazilität in
  der Produktion gegen echte Wertpapiere betrieben wird – das ist einer der stärker regulierten
  Bereiche von MiFID II / nationalem Wertpapierrecht, und die Korrektheit des Vertrags ersetzt
  diese Prüfung nicht. **Zum Stand der Compliance-Prüfung vom 2026-07-21 oben weiterhin
  ungeprüft** – das ist Aufgabe der Rechtsberatung und lässt sich nicht durch weitere
  Vertragsänderungen ersetzen.
