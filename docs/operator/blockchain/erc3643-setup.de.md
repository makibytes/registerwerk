---
title: ERC-3643 Einrichtung
---

# ERC-3643 (T-REX) Setup

Dieser Leitfaden führt Sie durch die komplette Einrichtung der ERC-3643-T-REX-Infrastruktur – von der Vertragsbereitstellung bis zur Ausstellung von KYC-Claims an Investoren.

## Was bereitgestellt wird

Für jede ERC-3643-Emission stellt die Factory sechs Verträge bereit:

| Vertrag | Rolle |
|----------|------|
| `Token` | Der ERC-3643-Token (Hauptvertrag, ERC-20-kompatible Schnittstelle) |
| `IdentityRegistry` | Ordnet Investoren-Wallets ihrer ONCHAINID zu |
| `IdentityRegistryStorage` | Erweiterbarer Speicher für das Identity Registry |
| `ClaimTopicsRegistry` | Definiert erforderliche Claim-Topic-IDs (z. B. KYC=1, AML=2) |
| `TrustedIssuersRegistry` | Definiert, welche Identitätsaussteller Claims signieren dürfen |
| `ModularCompliance` | Container für steckbare Compliance-Regelmodule |

Alle sechs werden atomar von `EwpgTREXFactory` über `AssetTokenFactory` bereitgestellt.

## Schritt 1 – Factory-Suite bereitstellen

Stellen Sie sicher, dass `AssetTokenFactory` und `EwpgTREXFactory` gemäß [Verträge bereitstellen](./deploying-contracts.md) bereitgestellt sind. Bestätigen Sie, dass die Factory-Adresse in `.env` gesetzt ist und das Backend sie geladen hat:

```bash
curl http://localhost:48080/api/v1/admin/chains/11155111 \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  | jq '.factoryAddress'
```

## Schritt 2 – ClaimIssuer des Registers bereitstellen und als vertrauenswürdig eintragen

Das Backend stellt KYC-/AML-Claims über einen ONCHAINID-`ClaimIssuer`-**Vertrag** aus, einen pro Chain, dessen MANAGEMENT-Schlüssel der Register-Signer des Backends ist. Das Signer-Wallet selbst kann nicht Aussteller sein: `addClaim` von ONCHAINID ruft `isClaimValid` auf dem Aussteller auf, was bei einem einfachen Wallet revertiert – solche Claims erreichen die Chain daher nie.

```bash
cd contracts
REGISTRY_WALLET_PRIVATE_KEY=$REGISTRY_SIGNER_KEY \
  forge script script/DeployClaimIssuer.s.sol --rpc-url $RPC_URL --broadcast
# Logs "ClaimIssuer : 0x…". The MANAGEMENT key is the broadcasting wallet, i.e. the
# backend's registry signer (default); CLAIM_ISSUER_MANAGEMENT_KEY is an optional override.
```

!!! warning "Management-Schlüssel = Hot-Signing-Key (Standard)"
    Im Standard signiert der Register-Signer Claims und kontrolliert zugleich den Schlüsselsatz des ClaimIssuers (`addKey`/`removeKey`) sowie dessen Upgrades; das Backend benötigt MANAGEMENT-Rechte, um `revokeClaimBySignature` aufzurufen. Behandeln Sie den Register-Signer als hochwertigen Schlüssel (in Produktion KMS-/HSM-gestützt). `CLAIM_ISSUER_MANAGEMENT_KEY` kann stattdessen einen separaten Cold- oder Multisig-Schlüssel benennen; dieser muss dann `addKey(keccak256(abi.encode(registrySigner)), 3, 1)` ausführen, damit der Signer Claims signieren kann, und ein Widerruf durch das Backend revertiert, solange der Signer keinen MANAGEMENT-Schlüssel (Purpose 1) hält – Claims müssen dann über den Management-Schlüssel widerrufen werden.

Setzen Sie `CLAIM_ISSUER_<CHAIN>` (zum Beispiel `CLAIM_ISSUER_ETH_TESTNET`, gebunden an `registerwerk.contracts.claim-issuer.<chain>`) und starten Sie das Backend neu. Ohne diesen Wert weist das Backend die Claim-Ausstellung und die Bereitstellung von T-REX-Suiten auf dieser Chain ab (Fail-Closed), statt Transaktionen zu senden, die revertieren würden. Vor jedem `addClaim` prüft es außerdem, dass der Signer einen Schlüssel auf dem ClaimIssuer hält.

Neue, vom Backend bereitgestellte Suiten vertrauen diesem ClaimIssuer für die Topics 1 (KYC) und 2 (AML). Eine Suite, die **vor** dieser Änderung bereitgestellt wurde, registrieren Sie einmalig (Operator-API, nur für den Owner des `TrustedIssuersRegistry` der Suite):

```bash
curl -X POST http://localhost:48080/api/v1/assets/$ASSET_ID/erc3643/$DEPLOYMENT_ID/trusted-issuers \
  -H "Authorization: Bearer $OPERATOR_JWT" -H "Content-Type: application/json" \
  -d "{\"issuerAddress\": \"$CLAIM_ISSUER\", \"claimTopics\": [1,2]}"
```

Überprüfen:

```bash
cast call $TRUSTED_ISSUERS_REGISTRY \
  "isTrustedIssuer(address)(bool)" $CLAIM_ISSUER --rpc-url $RPC_URL
# Expected: true
```

Für Ökosystem-dApps, die über `PermissionOracle` abgesichert sind, registrieren Sie denselben ClaimIssuer über die Trusted-Issuer-Verwaltung des Registerbetreibers im `EcosystemTrustedIssuersRegistry`.

!!! note
    Beim Widerruf eines Claims wird dieser von der Identität entfernt **und** `revokeClaimBySignature` auf dem ClaimIssuer aufgerufen. Der zweite Schritt verhindert, dass jemand die veröffentlichte Signatur später erneut hinzufügt. Beide Schritte setzen voraus, dass der Register-Signer den MANAGEMENT-Schlüssel des ClaimIssuers hält.

## Schritt 3 – Claim Topics konfigurieren

`ClaimTopicsRegistry` listet alle Claim Topics auf, die für die Übertragungsberechtigung erforderlich sind:

```bash
cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 1 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $CLAIM_TOPICS_REGISTRY "addClaimTopic(uint256)" 2 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

| Topic-ID | Bedeutung |
|----------|---------|
| 1 | KYC – Identitätsüberprüfung |
| 2 | AML – Geldwäscheprävention/Screening |

Das Backend stellt diese Topics automatisch bereit, wenn eine neue T-REX-Emission erstellt wird.

## Schritt 4 – Investoren-ONCHAINID-Verträge registrieren

Wenn ein Investor onboardet wird, stellt das Backend für ihn einen ONCHAINID-Vertrag bereit und registriert ihn im Identity Registry. Das geschieht automatisch, wenn Sie einen Investor über das Operator-Frontend auf die Whitelist setzen.

Jede Registrierung benötigt ein numerisches Land nach ISO 3166-1. Der Operator-Dialog
übernimmt es vorab aus dem KYC-Registrierungsland der juristischen Person; die API weist ein
fehlendes Land oder das Land `0` ab. Sobald für einen Token ein Land gesperrt ist, weist
`EwpgComplianceModule` Übertragungen an eine Wallet mit registriertem Land `0` ab. Die
Identity-Registry-Tabelle kennzeichnet solche Wallets mit **Country missing** (Land fehlt).
Korrigieren Sie sie mit `updateCountry(address,uint16)` auf dem Identity Registry.

Um zu überprüfen, ob die ONCHAINID eines Investors registriert ist:

```bash
cast call $IDENTITY_REGISTRY \
  "contains(address)(bool)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

So suchen Sie die ONCHAINID-Adresse für eine Wallet:

```bash
cast call $IDENTITY_REGISTRY \
  "identity(address)(address)" \
  $INVESTOR_WALLET_ADDRESS \
  --rpc-url $RPC_URL
```

## Schritt 5 – KYC-/AML-Claims ausstellen

Nach der KYC-Genehmigung im Operator-Frontend stellt das Backend automatisch Claims auf der ONCHAINID des Investors aus:

1. Erstellt die Claim-Daten `abi.encode(topic, scheme=1, claimIssuer, expiresAt, "")`
2. Signiert `keccak256(abi.encode(identity, topic, data))` (mit EIP-191-Präfix) mit dem Register-Signer
3. Ruft `addClaim(topic, 1, claimIssuer, signature, data, "")` auf dem ONCHAINID-Vertrag des Investors auf, mit dem ClaimIssuer-Vertrag der Chain als Aussteller

Claims haben ein Ablaufdatum (Standard: 365 Tage). Das Backend plant Erinnerungs-E-Mails zum Ablauf und kann Claims bei Verlängerung erneut ausstellen.

So überprüfen Sie Claims auf einer ONCHAINID manuell:

```bash
cast call $INVESTOR_ONCHAINID \
  "getClaimIdsByTopic(uint256)(bytes32[])" 1 \
  --rpc-url $RPC_URL
# Returns array of claim IDs for topic 1 (KYC)
```

## Schritt 6 – Compliance-Module

Konfigurieren Sie Compliance-Module je Emission im Operator-Frontend unter **Issuances → [Emission] → Compliance Modules**.

### MaxBalance-Modul

Begrenzt den maximalen Token-Bestand, den ein einzelner Investor halten darf.

Konfigurierbar über das Operator-Frontend, oder direkt:

```bash
cast send $MAX_BALANCE_MODULE \
  "setMaxBalance(address,uint256)" $TOKEN_ADDRESS 100000 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### MaxInvestors-Modul

Begrenzt die Gesamtzahl unterschiedlicher Token-Inhaber (nützlich für Ausnahmegrenzen nach Regulation D):

```bash
cast send $MAX_INVESTORS_MODULE \
  "setMaxInvestors(address,uint256)" $TOKEN_ADDRESS 499 \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### CountryRestrict-Modul

Blockiert Investoren aus bestimmten numerischen ISO-3166-1-Ländercodes:

```bash
# Block US (840) and CN (156)
cast send $COUNTRY_RESTRICT_MODULE \
  "batchRestrictCountries(address,uint16[])" \
  $TOKEN_ADDRESS "[840,156]" \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```

### EwpgComplianceModule (Registerwerks eigenes Modul)

`EwpgComplianceModule` vereint maximale Investorenzahl, maximalen Bestand je Investor, gesperrte
Länder, Übertragungs-Cooldown und die Ausnahme für Nominee-Pools. Alle Einstellungen werden je
`ModularCompliance`-Vertrag gespeichert. Die Setter erhalten diese Compliance-Adresse als erstes
Argument, zum Beispiel `setMaxInvestors(address compliance, uint256)`.

- **Wer es konfigurieren darf.** Nur der Eigentümer des Compliance-Vertrags darf einen Setter
  aufrufen, oder die Compliance selbst über `callModuleFunction`. Jeder andere Aufrufer scheitert
  mit `CallerNotComplianceAdmin`. T-REX überträgt die Eigentümerschaft in zwei Schritten; nach dem
  Deployment einer Suite ist das Register-Wallet daher nur *ausstehender* Eigentümer. Das Backend
  ruft beim Deployment `acceptOwnership()` auf der Compliance auf. Ist dieser Schritt
  fehlgeschlagen, holt es ihn vor der nächsten Änderung an einem Compliance-Modul nach.
- **Hinzufügen über das Backend.** Das Backend bindet das Modul, sendet die Setter und liest das
  Ergebnis mit `getConfig(address)` und `isCountryBlocked(address,uint16)` zurück. Die
  Datenbankzeile schreibt es erst, wenn jeder Wert on-chain steht. Schlägt die Konfiguration
  fehl, löst es das Modul wieder und meldet den Fehler.
- **„Investor“ bedeutet ONCHAINID.** Bestände und Investorenzahl werden je Identität summiert.
  Mehrere Wallets derselben ONCHAINID teilen sich daher eine Bestandsobergrenze und zählen als ein
  Investor. Übertragungen zwischen zwei Wallets derselben Identität sind immer erlaubt.
- **Unbekanntes Land.** Solange mindestens ein Land gesperrt ist, wird ein Empfänger ohne
  hinterlegtes Land (`0`) abgewiesen.

#### Migration einer produktiven Suite auf das korrigierte Modul

Vor dieser Korrektur bereitgestellte Verträge verwenden das alte Modul. Dort kann **jeder** die
Einstellungen ändern, und die Grenzen gelten je Wallet statt je Identität. Das Modul ist nicht
upgradefähig; jede produktive Suite muss daher auf ein neu bereitgestelltes Modul umgestellt
werden.

1. Das neue `EwpgComplianceModule` bereitstellen.
2. Als Eigentümer der Compliance (zuerst `acceptOwnership()` aufrufen, falls Sie noch
   ausstehender Eigentümer sind) `addModule(newModule)` auf der `ModularCompliance` der Suite
   ausführen.
3. Das neue Modul mit den in der Datenbank erfassten Werten (`erc3643_compliance_module`)
   konfigurieren: `setMaxInvestors`, `setMaxBalance`, `setTransferCooldown`, `blockCountry` und
   `setNomineePool`. Übernehmen Sie keine Werte aus dem On-chain-Zustand des alten Moduls — jeder
   könnte sie verändert haben.
4. Bestehende Inhaber nachtragen:
   `syncHolders(compliance, wallets)` mit jeder Wallet aus `erc3643_identity_registry` für die
   Suite (sowie jeder weiteren Inhaber-Wallet, die der Indexer kennt). Der Aufruf ist idempotent,
   kann also in Teilmengen ausgeführt und gefahrlos wiederholt werden.
5. `getConfig(compliance)` mit der Datenbank abgleichen, einschließlich der Investorenzahl gegen
   die Anzahl unterschiedlicher ONCHAINIDs mit positivem Bestand.
6. `removeModule(oldModule)` auf der Compliance ausführen.

!!! warning "Ein Alt-Modul lässt sich nicht über das Backend binden"
    Das Backend liest die Konfiguration nach dem Binden eines Moduls mit `getConfig(address)`
    zurück. Ein vor dieser Korrektur bereitgestelltes Modul hat kein `getConfig`; das Hinzufügen
    über das Backend wird daher immer zurückgerollt. Nicht erneut versuchen:
    `EwpgComplianceModule` neu bereitstellen und die neue Instanz binden.

Bis eine Suite migriert ist, alarmieren Sie bei jeder Abweichung zwischen `isCountryBlocked` bzw.
den Grenzen des alten Moduls und der Datenbank. Melden Sie außerdem jeden Identity-Registry-Eintrag
mit Land `0` — geprüft gegen `investorCountry(wallet)` on-chain — den Operatoren als
`updateCountry`-Aufgabe.

## Schritt 7 – Agent-Rollen

Das Wallet des Registrierungs-Backends muss auf jedem bereitgestellten Token Agent-Rollen halten, um Verwaltungsvorgänge durchzuführen. Das Bereitstellungsskript gewährt diese automatisch.

| Rolle | Erlaubt |
|------|--------|
| Identity Registry Agent | `registerIdentity`, `updateIdentity`, `deleteIdentity` |
| Token Agent | `mint`, `burn`, `freezePartialTokens`, `forcedTransfer` |
| Compliance Agent | `addModule`, `removeModule`, `callModuleFunction` |

So erteilen Sie Agent-Rollen manuell (falls erforderlich):

```bash
cast send $IDENTITY_REGISTRY \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY

cast send $TOKEN \
  "addAgent(address)" $BACKEND_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL --private-key $DEPLOYER_PRIVATE_KEY
```
