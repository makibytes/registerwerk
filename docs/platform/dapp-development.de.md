# Entwicklung von dApps für das Registerwerk-Ökosystem { #building-dapps-for-the-registerwerk-ecosystem }

Registerwerk bietet ein **On-Chain-Identitäts- und Berechtigungs-Framework**, auf dem Finanzinstitute
Tokenisierungs-dApps aufbauen, sowie einen **Marktplatz**, auf dem diese dApps überprüft, on-chain
verankert und anderen Teilnehmern angeboten werden. Dieses Handbuch behandelt den gesamten
Entwicklerworkflow von Anfang bis Ende.

## Die Bausteine { #the-building-blocks }

| Vertrag | Zweck |
|---|---|
| `OrgRegistry` | Bindet Mitglieds-Wallets an Organisationen (eine Organisation = ihre ONCHAINID-Adresse). Jede Wallet gehört je Chain höchstens einer Organisation. |
| `PermissionRegistry` | Der Betreiber gewährt Organisationen Berechtigungen; Organisationsadministratoren delegieren sie an Mitgliedsrollen und können sie als rollenbeschränkt markieren. |
| `EcosystemTrustedIssuersRegistry` | Anspruchsaussteller, denen je ONCHAINID-Anspruchsthema vertraut wird (1 = KYC, 2 = AML, 3 = Akkreditierung). |
| `DappRegistry` | Verankert genehmigte Marktplatzmanifeste (keccak256) und optionale Instanzbescheinigungen. |
| `PermissionOracle` | **Die eine Adresse, die Ihre dApp speichert.** Bündelt alle oben genannten Register hinter einer stabilen Abfragefassade. |

Ihre dApp kommuniziert nie direkt mit den Registern – nur mit dem `PermissionOracle`
(`IPermissionOracle`), das der Betreiber auf aktualisierte Register umstellen kann, ohne
bereitgestellte dApps zu beschädigen.

## Einen Gated Contract schreiben { #writing-a-gated-contract }

Erben Sie von `RegisterwerkGated` (in `contracts/src/ecosystem/RegisterwerkGated.sol`) und übergeben
Sie die Oracle-Adresse in Ihrem Konstruktor:

```solidity
import "@registerwerk/ecosystem/RegisterwerkGated.sol";

contract LoanDesk is RegisterwerkGated {
    bytes32 public constant OPEN_LOAN = keccak256("loandesk.open");

    constructor(IPermissionOracle oracle_) RegisterwerkGated(oracle_) {}

    function openLoan() external requiresPermission(OPEN_LOAN) requiresClaim(1) {
        // caller's wallet belongs to an active org holding "loandesk.open",
        // and the org's ONCHAINID carries a valid KYC claim
    }
}
```

Verfügbare Modifier:

- `requiresPermission(bytes32 permission)` — Gewährung auf Organisationsebene (plus
  Rollendelegierung, wenn die Organisation die Berechtigung als rollenbeschränkt markiert hat).
- `requiresClaim(uint256 topic)` — ein gültiger Anspruch des Themas auf der ONCHAINID der
  aufrufenden Organisation, signiert von einem im Ökosystem vertrauenswürdigen Aussteller.
- `requiresActiveMember` — die Wallet des Aufrufers ist an eine nicht suspendierte Organisation
  gebunden.

Permission-IDs sind `keccak256("<ihr-slug>.<aktion>")`. Ihr Marktplatz-Slug ist Ihr Namensraum —
Manifeste, die Berechtigungen außerhalb von `<slug>.*` deklarieren, werden abgelehnt, es sei denn,
der Code existiert bereits als Plattformberechtigung.

Ein minimal lauffähiges Beispiel liegt in `contracts/test/ecosystem/SampleGatedDapp.t.sol`. Zwei
vollständig verpackte, marktreife Referenz-dApps — einschließlich einer echten ERC-3643-(T-REX)-
Integration — finden Sie unten unter [Referenz-Beispiel-dApps](#reference-example-dapps).

## Das Manifest { #the-manifest }

Der Marktplatz speichert **nur Metadaten**: Ihre Container bleiben in Ihrer eigenen OCI-Registry,
gepinnt per Digest. Das Manifest (JSON, Schema:
`backend/src/main/resources/schemas/dapp-manifest.schema.json`) beschreibt:

```json
{
  "slug": "loandesk",
  "name": "Loan Desk",
  "version": "1.0.0",
  "description": "Institutional loan origination on Registerwerk rails.",
  "category": "lending",
  "contracts": [
    { "name": "LoanDesk", "abiSha256": "<sha256 of the ABI json>" }
  ],
  "requiredPermissions": [
    { "code": "loandesk.open", "rationale": "Open loan requests on behalf of the org" }
  ],
  "requiredClaimTopics": [1],
  "images": [
    { "name": "backend",  "role": "backend",
      "ref": "registry.bank.example/loandesk/backend@sha256:…" },
    { "name": "frontend", "role": "frontend",
      "ref": "registry.bank.example/loandesk/frontend@sha256:…" }
  ],
  "deployment": { "composeUrl": "https://…/docker-compose.yml", "composeSha256": "…" },
  "docsUrl": "https://docs.bank.example/loandesk",
  "contact": "dapps@bank.example",
  "license": "commercial",
  "pricingNote": "Contact publisher"
}
```

Bei der Validierung erzwungene Regeln:

- **Digest-Pinning ist Pflicht** — `images[].ref` muss mit `…@sha256:<64 hex>` übereinstimmen;
  reine Tag-Referenzen werden abgelehnt.
- Das `slug` im Manifest muss dem Listing-Slug entsprechen.
- `requiredPermissions[].code` muss in Ihrem Namensraum liegen oder eine bestehende
  Plattformberechtigung sein.

## Zahlungsmethoden deklarieren { #declaring-payment-methods }

Die Ausgabe eines Asset-Tokens ist nur die halbe Miete — die meisten dApps benötigen auch eine
Zahlungsseite (Zeichnungszahlungen, Kupon-/Dividendenauszahlungen, Rückzahlungen). Damit nicht
jeder Herausgeber seine eigenen Zahlungswege selbst baut und prüfen muss, kuratiert der
Registerbetreiber einen Katalog fertiger Zahlungswege — Stablecoins mit vom Betreiber erfassten
MiCAR-bezogenen Offenlegungs- und Attestierungsfeldern, die Pontes-Instant-Payment-API, eine
ERC-7573-artige Lieferung-gegen-Zahlung-Abwicklung (LgZ) und klassisches Off-Chain-SEPA — und Ihr
Manifest kann sie einfach über den Code referenzieren:

```json
"paymentMethods": [
  { "rail": "aueur", "note": "Primary-market subscription plus coupon and redemption payouts" },
  { "rail": "usdc" },
  { "rail": "erc7573-dvp", "note": "Same-transaction DvP; exact-leg, finality, and legal-register checks remain external" }
]
```

Rufen Sie den aktuellen Katalog der aktivierten Zahlungswege unter
`GET /api/v1/payment-rails/catalog` ab (wird auch im Schritt „Zahlungsmethoden" des
Veröffentlichungsassistenten angezeigt) und kopieren Sie einen `code`. Jeder Zahlungsweg-Eintrag
wird bei der Einreichung **und erneut bei der Genehmigung** validiert — ein Zahlungsweg, den der
Betreiber zwischenzeitlich deaktiviert hat, blockiert die Genehmigung der Version, bis das
Manifest aktualisiert wird.

Das ist eine Empfehlung, keine Whitelist: Ihre dApp kann jederzeit ihre eigene Zahlungslogik
implementieren. Deklarieren Sie sie dann als `custom`-Eintrag statt als `rail`-Referenz:

```json
"paymentMethods": [
  { "custom": { "name": "Own SEPA collection account", "description": "Publisher-run SEPA rail, settled off-chain", "currency": "EUR" } }
]
```

Benutzerdefinierte Einträge bestehen die Validierung bedingungslos, werden dem Betreiber während
der Prüfung aber deutlich hervorgehoben (ebenso den Anlegern auf der Katalogdetailseite) — der
Markt sieht so genau, was den komfortablen Pfad der „vom Register bereitgestellten Zahlungswege"
verlassen hat.

Für dApps, die selbst eine atomare Lieferung gegen Zahlung anbieten möchten (z. B. ein
Sekundärmarktschalter), implementiert der `DvpSettlement`-Vertrag des Betreibers
(`contracts/src/settlement/DvpSettlement.sol`) eine ERC-7573-artige Same-Chain-DvP-Abwicklung:
Eine Partei hinterlegt den Vermögenswert oder die Zahlung treuhänderisch, die Gegenpartei begleicht
beide Seiten atomar, oder der Handel läuft ab und die sperrende Partei erhält ihre Hinterlegung
zurück. Siehe die NatSpec für den ERC-3643-Escrow-Vorbehalt (T-REX-Token erfordern, dass die
ONCHAINID des Abwicklungsvertrags im Identitätsregister verifiziert ist, bevor sie treuhänderisch
verwahrt werden können — die Sperrung der Zahlungsseite umgeht dies bei Wertpapier-Token).

## Veröffentlichungsworkflow { #publication-workflow }

1. **Voraussetzung:** Ihr Unternehmen ist als On-Chain-Organisation (betreiberseitig) registriert,
   und Ihre Publishing-Wallet ist daran gebunden (Customer Portal → Company Admin → Organization).
2. Customer Portal → **My dApps** → *New dApp* (Slug + Ankerkette).
3. Fügen Sie das Manifest in den Veröffentlichungsassistenten ein → die serverseitige Validierung
   liefert Fehler zurück sowie `manifestHash = keccak256(manifest_raw_bytes)` als
   0x-präfixierte Hex-Zeichenkette.
4. **Signieren** Sie mit einer gebundenen Org-Wallet: `personal_sign` (EIP-191) wird mit dem
   **0x-Hex-Hash-*String*** als Nachricht aufgerufen — nicht mit den rohen 32 Hash-Bytes. Das ist
   eine bewusste Entscheidung, damit jede Wallet-Oberfläche die für Menschen lesbare Hex-Zeichenkette
   anzeigt, die signiert wird; Prüfer müssen die Signatur gegen denselben String zurückrechnen
   (siehe [Integritätsprüfung](#integrity-verification-consumers) unten).
5. **Einreichen** — der Registerbetreiber prüft mit Step-up + Vier-Augen-Prinzip.
6. Bei Genehmigung ruft das Backend `DappRegistry.registerDapp(keccak256(slug), publisherOrg,
   manifestHash, …)` auf; sobald die Transaktion bestätigt ist, ist das Listing im Katalog live.

Versionsaktualisierungen wiederholen die Schritte 3–6; der neue Hash wird per
`DappRegistry.updateManifest` verankert, und die vorherige Version wird als abgelöst markiert.

## Integritätsprüfung (für Konsumenten) { #integrity-verification-consumers }

Alles, was zur unabhängigen Prüfung eines Listings nötig ist, steht in der Katalogdetailansicht:

```bash
# 1. The manifest hash must match the onchain anchor
MANIFEST_HASH=$(cast keccak "$(cat manifest.json)")
cast call $DAPP_REGISTRY "getDapp(bytes32)" $(cast keccak "loandesk") --rpc-url $RPC

# 2. The signature must recover to the declared publisher wallet, which must be a bound
#    member wallet of the publisher org. Recovery is over the hex *string* $MANIFEST_HASH
#    (EIP-191 personal_sign), not the raw 32 hash bytes:
cast wallet verify --address $PUBLISHER_WALLET "$MANIFEST_HASH" $SIGNATURE

# 3. Pull images only by the digests listed in the manifest
```

## Instanzbescheinigung (optional) { #instance-attestation-optional }

Bereitgestellte Vertragsinstanzen Ihrer dApp können von Ihrem Organisationsadministrator im
`DappRegistry` (`attestInstance`) bescheinigt werden. Andere Verträge können dann
`oracle.isApprovedInstance(caller)` verlangen — eine Opt-in-Kompositionsschicht; sie ist bewusst
nicht in `hasPermission` zusammengefasst, da selbst gehostete Bereitstellungen ihre eigenen
Aufrufer selbst kontrollieren.

## Externe DeFi-Komponierbarkeit { #external-defi-composability }

`PermissionOracle` und `DvpSettlement` sind beide frei und ohne Erlaubnis von **jedem** externen
Vertrag aufrufbar — nicht nur von Registerwerk-Marktplatz-dApps. Bei keinem der beiden gibt es ein
`onlyRole` oder eine Zulassungsliste:

- **`PermissionOracle`** — ein externes DeFi-Protokoll (sein eigener Pool, Tresor oder
  Kreditmarkt) kann `hasPermission`/`hasClaimTopic`/`isActiveMember` für jede Wallet-Adresse
  aufrufen, um seine *eigene* Logik auf Registerwerk-verifizierte Anleger zu beschränken, ohne
  je einen Registerwerk-Wertpapier-Token zu berühren oder Gelder zu halten, von denen das Orakel
  überhaupt Kenntnis hat. Das ist das `ORACLE_ONLY`-Interoperabilitätsmodell (siehe
  `DefiInteropModel` im Backend-Modul `kyc`) — kein Verwahrungsrisiko, da das Orakel niemals
  etwas verwahrt; es beantwortet nur die Frage „Ist diese Wallet für Thema X KYC-geprüft?". Ein
  Vorbehalt, den man sich merken sollte: Das Orakel prüft die Organisationsmitgliedschaft der
  abgefragten Wallet selbst, nicht die des Aufrufers. Wenn Ihr eigener Vertrag selbst die
  geprüfte Identität *sein* muss (z. B. um eine geschützte Registerwerk-dApp-Funktion als
  `msg.sender` aufzurufen), muss die Adresse Ihres Vertrags selbst wie jede andere
  Mitglieds-Wallet über `OrgRegistry` angebunden werden — es gibt keine generische Abkürzung
  nach dem Motto „jeder Smart Contract kommt durch".
- **`DvpSettlement`** — ein generisches Treuhandkonto im ERC-7573-Stil ohne Zugriffsbeschränkung
  (ungated), das von jedem externen Protokoll für atomare Asset↔Stablecoin-Swaps verwendet werden
  kann, völlig unabhängig vom Ökosystem-Berechtigungs-Framework. Lesen Sie den NatSpec-Vorbehalt
  vor der Integration sorgfältig: Für die Hinterlegung eines ERC-3643-Assets über `lockAsset` muss
  `DvpSettlement` selbst `isVerified()` im Identitätsregister des Tokens bestehen (ein einmaliger
  Onboarding-Schritt durch den Register-Agenten des Tokens); ruft man stattdessen `lockPayment`
  auf, wird das vollständig vermieden, da sich die Wertpapier-Token-Seite dann als direkte
  Vertragsübertragung unmittelbar von Verkäufer zu Käufer bewegt, statt im Treuhandkonto zu
  verbleiben. Das Bestehen der technischen Prüfungen des Tokens begründet keine rechtliche oder
  regulatorische Compliance und keine Abwicklung.

Für die schwierigere Frage — kann ein externes Protokoll einen Registerwerk-Wertpapier-Token als
gepooltes Guthaben *halten* (ein AMM-Pool, ein Kreditmarkt) — siehe
[`docs/platform/defi-interoperability.md`](./defi-interoperability.md); dort wird erläutert, warum
das eine lizenzierte Nominee-/Verwahrer-Struktur (das `NOMINEE_POOL`-Modell) statt eines anonymen,
erlaubnisfreien Pools erfordert, und wie die Nominee-Ausnahme des `EwpgComplianceModule`
funktioniert.

## Referenz-Beispiel-dApps { #reference-example-dapps }

Drei technische Referenzbeispiele werden in diesem Repository mit Manifesten, Solidity-Quellcode,
Tests und einem `README` ausgeliefert. Es handelt sich um Beispiele, nicht um genehmigte
Produktvorlagen; sie werden von `EcosystemDemoDataSeeder` als `PUBLISHED`-Demo-Marktplatzeinträge
geseedet, wenn `registerwerk.seed-demo-data=true` gesetzt ist:

| dApp | Slug | Demonstriert |
|---|---|---|
| **Boardroom Governance** | `boardroom` | Das Berechtigungsverwaltungs-Framework in voller Ausprägung: Vorschlagen/Abstimmen/Auszählen, gesteuert durch Berechtigungen + ONCHAINID-Ansprüche (KYC, Akkreditierung), sowie den Ablauf zur **Rollenbeschränkung / Admin-Delegation der Organisation** bei `boardroom.tally`. |
| **eWpG Bond Desk** | `bond-desk` | Ein technisches ERC-3643/T-REX-Beispiel mit einer konfigurierten Token-Zahlungsseite. `subscribe` führt Zahlungsübertragung und Prägung in einer Transaktion durch; `payCoupon`/`redeem` wenden Zeit- und Idempotenzkontrollen an. Dies ist keine rechtlich klassifizierte Anleihe, keine verifizierte Zahlungsvereinbarung und kein Nachweis einer rechtswirksamen Abwicklung. |
| **eWpG Repo & Lending Facility** | `repo-facility` | Ein technisches Beispiel für besicherte Kreditvergabe mit einer offenen Stablecoin-Kreditgeberseite und einer vertragsgebundenen Kreditnehmerseite. Die Produktionsnutzung ist gesperrt, bis rechtliche Einordnung, Verwahrung/Kontrolle, Verwertung, Orakel, Insolvenz, Anspruchsberechtigung und Sicherheitsfreigabe geklärt sind. Allein die Prüfung der Token-Identität macht die Verwertung nicht compliant. Siehe [DeFi-Interoperabilität](./defi-interoperability.md#ewpgrepofacility-the-primary-exit-liquidity-mechanism). |

| | Pfad |
|---|---|
| Verträge | `contracts/src/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility,MockStablecoin}.sol`, `contracts/src/settlement/DvpSettlement.sol` |
| Tests | `contracts/test/examples/{BoardroomGovernance,EwpgBondDesk,EwpgRepoFacility}.t.sol`, `contracts/test/settlement/DvpSettlementTest.t.sol` |
| T-REX-Bootstrap-Helfer | `contracts/test/helpers/TrexSuiteDeployer.sol` — der vollständige T-REX-+-ONCHAINID-Aufbau (Implementation Authority, Identity Factory, Compliance-Modul), der vom Test- und Deploy-Skript des Bond Desks wiederverwendet wird |
| Deploy-Skripte | `contracts/script/DeployEwpgTrexBond.s.sol`, `contracts/script/DeployExampleDapps.s.sol` (boardroom, bond-desk), `contracts/script/DeployLiquidityDapps.s.sol` (repo-facility, plus `EwpgPaymaster` — in einem separaten Skript gehalten, da beide Pragma `^0.8.36` verwenden und keine Kompilationseinheit mit den oben genannten erc3643-abhängigen Verträgen teilen können; siehe die eigene NatSpec dieses Skripts) |
| Manifeste | `backend/src/main/resources/demo/dapps/{boardroom,bond-desk,repo-facility}.manifest.json` — wird auch direkt vom Demo-Data-Seeder gelesen (`registerwerk.seed-demo-data=true`), der alle drei als Live-Marktplatzeinträge mit echten, unabhängig überprüfbaren Signaturen veröffentlicht |
| Anleitungen | `examples/dapps/{boardroom,bond-desk,repo-facility}/README.md` |

Führen Sie `forge test --match-path 'test/examples/*'` aus, um alle drei End-to-End im Einsatz zu
sehen — einschließlich, für den Bond Desk, echter ONCHAINID-Identitäten und ECDSA-signierter
KYC/AML-Ansprüche über einen On-Chain-`ClaimIssuer`.

Zwei weitere Verträge demonstrieren das `NOMINEE_POOL`-Brückenmuster und das
AMM-für-Stablecoins-Muster aus [DeFi-Interoperabilität](./defi-interoperability.md) — anders als
die drei dApps oben werden sie nur als getesteter Solidity-Code ausgeliefert (kein Manifest, nicht
als Live-Marktplatzeinträge gesät):

- `contracts/src/examples/CompliantSecondaryMarket.sol` — ein Nominee-/Sammel-Sekundärmarktschalter,
  gesteuert durch `secondary-market.trade` + das `NOMINEE`-Anspruchsthema (4); wickelt jeden Handel
  über den oben genannten unveränderten, ungated `DvpSettlement` ab, und seine Abschlüsse dienen
  zugleich als Preisfeed für `EwpgRepoFacility.updatePrice`. Tests:
  `contracts/test/examples/CompliantSecondaryMarket.t.sol`.
- `contracts/src/examples/StablecoinAmm.sol` — ein minimales Constant-Product-AMM, beschränkt auf
  reine Stablecoin-Paare, bewusst **nicht** `RegisterwerkGated` (siehe die NatSpec für den Grund).
  Tests: `contracts/test/examples/StablecoinAmm.t.sol`.
