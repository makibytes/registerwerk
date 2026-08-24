---
title: Token-Administrator-Berechtigungen – Delegierbare Zwangsmaßnahmenbefugnis
description: ASSET_TOKEN_ADMIN – die delegierbare Berechtigung, die forcedTransfer/forcedApprove/forceBurn über REGISTRY_ADMIN hinaus absichert.
---

# Token-Administrator-Berechtigungen – Delegierbare Zwangsmaßnahmenbefugnis { #token-admin-grants-delegatable-forced-action-authority }

!!! warning "EXTERNAL_REVIEW_REQUIRED"
    Auf dieser Seite werden beabsichtigte Steuerungszuordnungen aufgezeichnet. Sie ist kein Beweis
    dafür, dass eine Delegation rechtlich gültig, im Rahmen der Autorisierung eines Betreibers
    gedeckt oder für eine Korrektur, Stornierung, Einfrierung, Vernichtung oder erzwungene
    Übertragung ausreichend ist. Kapazität, Weisungsnachweis, Aufgabentrennung sowie
    instrumenten-/jurisdiktionsspezifische Regeln erfordern eine externe Prüfung.

Registerwerks registrierungspflichtige Zwangsmaßnahmen bei Token — **forcedTransfer**,
**forcedApprove** und **forceBurn** — ermöglichen es dem Register, die Token eines Inhabers ohne
dessen Zustimmung zu verschieben, erneut zu genehmigen oder zu vernichten. Dies sind die
schärfsten Werkzeuge der Plattform: Ein unrechtmäßiger Aufruf verschiebt echten Wert an eine vom
Angreifer gewählte Adresse oder vernichtet ihn vollständig. Bisher waren sie nur für
`REGISTRY_ADMIN` erreichbar (zuzüglich, bei `forcedTransfer`/`forcedApprove`, für jeden
Emittenten, der allein aufgrund seines Eigentums auf sein eigenes Asset einwirkt).

**`ASSET_TOKEN_ADMIN`** ersetzt die Emittenten-Eigentums-Abkürzung durch eine explizite, vom
Betreiber gewährte Berechtigung. Standardmäßig hat sie niemand — nicht einmal der eigene Emittent
eines Assets. Ein Betreiber muss sie absichtlich an eine benannte Kundeneinheit (Emittent oder
Investor) delegieren, und zwar erst, nachdem bestätigt wurde, dass die Wallet dieser Entität ein
echter, auf der Whitelist stehender (und bei ERC-3643-Assets ONCHAINID-verifizierter) Teilnehmer
ist.

Beachten Sie, was sich **nicht** ändert: Die eigentliche On-Chain-Transaktion wird weiterhin,
genau wie zuvor, von der eigenen Operator-Wallet des Registers signiert. `ASSET_TOKEN_ADMIN` ist
ausschließlich ein **Autorisierungs-Gate auf API-Ebene** — es entscheidet, wer das Register um
eine erzwungene Aktion *bitten* darf, nicht, wer sie On-Chain *ausführt*.

---

## Was dies absichert { #what-it-gates }

| Aktion | Betreiberpfad | Kundenpfad |
|---|---|---|
| `forcedTransfer` / `forcedTransferSingle` | `TokenAdminController` | `IssuerTokenController` |
| `forcedApprove` | `TokenAdminController` | `IssuerTokenController` |
| `forceBurn` / `forceBurnSingle` | `TokenAdminController` | — (nur Betreiber) |
| ERC-3643-Äquivalente (inkl. Batch) | `Erc3643Controller` | `Erc3643Controller` |
| Canton `force-transfer-canton` / `burn-holding` | `TokenAdminController` | — (nur Betreiber) |

Jeder oben genannte Endpunkt erfordert jetzt `hasRole('REGISTRY_ADMIN')` **oder** eine aktive
`ASSET_TOKEN_ADMIN`-Berechtigung für die Entität des Aufrufers auf diesem konkreten Asset (siehe
`AssetAccessChecker.canForceAdmin`). Alles andere — Pause, Einfrieren, Whitelist, Minting,
Burning (die nicht erzwungene Art) — bleibt davon unberührt.

---

## eWpG §24 / §26 als Delegationsgrundlage (Deutschland) { #ewpg-24-26-as-the-delegation-basis-germany }

Die erzwungenen Aktionen bilden konkrete eWpG-Bestimmungen ab: `forcedTransfer` auf **§24
Berichtigung** (Registerberichtigung aufgrund einer BaFin-/gerichtlichen Anordnung), `forceBurn`
auf **§26 Einziehung** (Zwangslöschung). Beide Bestimmungen beschreiben die Befugnis der
*registerführenden Stelle*, einen Eintrag zu korrigieren oder zu löschen — sie sehen selbst nicht
vor, diese Befugnis an einen Kunden zu delegieren. Die Position, die dieses Feature einnimmt, ist,
dass die registerführende Stelle (der Betreiber) rechtlich für jede erzwungene Aktion
verantwortlich bleibt, unabhängig davon, wer den API-Aufruf initiiert hat; `ASSET_TOKEN_ADMIN`
ist eine **operative Delegation der Initiierung**, keine Delegation der rechtlichen Befugnis — die
eigene Dual-Control-Step-up-Prüfung des Betreibers (siehe unten) ist es, die die Ausführung bei
jedem einzelnen Aufruf tatsächlich autorisiert, unabhängig davon, ob der Initiator
`REGISTRY_ADMIN` oder ein berechtigter Kunde ist.

**Andere Gerichtsbarkeiten:** In FR, LU und LI ist in dieser Codebasis noch kein direkt analoges
Konzept „Delegation der Einleitung einer zwangsweisen Registerberichtigung" dokumentiert.
Behandeln Sie eine Delegation an eine Kundeneinheit unter den lokalen Wertpapier-/DLT-Regelungen
dieser Gerichtsbarkeiten als **ungeprüft** — holen Sie die Bestätigung eines örtlichen
Rechtsberaters ein, bevor Sie `ASSET_TOKEN_ADMIN` in der Produktion an eine Nicht-DE-Entität
gewähren, im Einklang mit der an anderer Stelle in diesem Verzeichnis verwendeten
Haftungsausschluss-Konvention (z. B. [Sperrvermerk](sperrvermerk.md)) und der
[Jurisdiktionsübersicht](../legal/index.md).

---

## Berechtigungsmodell { #grant-model }

Zwei Varianten, beide ausschließlich erstellt/widerrufen von `REGISTRY_ADMIN` mit
`@RequiresStepUp(requireSecondApprover = true)` (derselbe TOTP-+-Vier-Augen-Ablauf, der auch für
die erzwungenen Aktionen selbst verwendet wird):

- **Asset-bezogen** (`POST /api/v1/assets/{assetId}/token-admin-grants`) — der übliche Fall: ein
  Asset, eine berechtigte Entität.
- **Entitätsweit** (`POST /api/v1/entities/{entityId}/token-admin-grants`) — gilt für jedes
  Asset, bei dem die Entität gegenwärtig oder künftig Emittent/Inhaber ist. Eine wesentlich
  größere Vertrauensdelegation; für einen vertrauenswürdigen Wiederholungs-Emittenten reservieren,
  nicht als Standard.

### Berechtigung, einmalig zum Zeitpunkt der Gewährung geprüft { #eligibility-validated-once-at-grant-time }

| Berechtigter | Wallet-Prüfung |
|---|---|
| Eigener Emittent des Assets (asset-bezogen) | Wallet an die Organisationsidentität der Entität gebunden (`orgidentity.PermissionGate.isWalletBoundToEntity`) |
| Ein Inhaber/Investor des Assets (asset-bezogen) | `AssetHolder.whitelisted = true` für diese Wallet auf diesem Asset, **plus** T-REX `IdentityRegistry.isVerified`, falls das Asset ERC-3643/CONF_ERC3643 ist |
| Entitätsweit | Wallet an die Organisationsidentität der Entität gebunden (kein einzelnes Asset, gegen das das Whitelisting geprüft werden könnte) |

Die bestandene Prüfung wird am Zuschuss (`eligibilityBasis`) zur Nachvollziehbarkeit erfasst — sie
wird **nicht** bei jedem nachfolgenden erzwungenen Aktionsaufruf live erneut geprüft; nur der
`ACTIVE`-/nicht abgelaufene Status des Zuschusses selbst wird geprüft. Wird eine Wallet später von
der Whitelist entfernt oder gesperrt, muss der Betreiber die Berechtigung separat widerrufen.

### Lifecycle { #lifecycle }

Entspricht dem `HolderBlock` von [Sperrvermerk](sperrvermerk.md): `ACTIVE → REVOKED` (manuell,
Step-up + Vier-Augen) oder `ACTIVE → EXPIRED` (nächtlicher `@Scheduled`-Job nach Ablauf von
`expiresAt`, falls eines gesetzt wurde).

---

## Betreiber-Oberfläche { #operator-ui }

- **Asset-bezogen** — Tab „Token Admin Grants" auf der Asset-Detailseite: Liste aktiver
  Berechtigungen, neue Berechtigung gewähren (Entität, Wallet, optionale Chain-Konfiguration,
  Rechtsgrundlage, optionaler Ablauf), widerrufen.
- **Entitätsweit** — `/compliance/token-admin-grants`: eine Entität nachschlagen, ihre
  entitätsweiten Berechtigungen verwalten. Bewusst eine von der nicht verwandten
  Berechtigungsseite des Orgidentity-Ökosystems (`/permissions`) getrennte Seite — jene regelt
  dApp-Marktplatz-Organisationsberechtigungen und hat überhaupt keine Asset-Dimension.

---

## Audit-Trail { #audit-trail }

Jede Gewährung und jeder Widerruf veröffentlicht `ASSET_TOKEN_ADMIN_GRANTED`-/
`ASSET_TOKEN_ADMIN_REVOKED`-Audit-Ereignisse (`asset.events.AssetTokenAdminGrantedEvent` /
`...RevokedEvent`), automatisch erfasst über die [Audit-Hash-Kette](../platform/audit-log.md) —
Akteur, Entität, Asset (oder „entitätsweit"), Wallet, Rechtsgrundlage und Berechtigungsgrundlage
werden alle erfasst.
