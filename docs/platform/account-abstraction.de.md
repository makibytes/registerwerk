---
title: Kontoabstraktion und gesponserte Transaktionen
description: ERC-4337 / EIP-7702 Smart Accounts, gesponsertes Gas, Passkeys und gaslose Genehmigungen (Permits).
---

# Kontoabstraktion und gesponserte Transaktionen { #account-abstraction-sponsored-transactions }

Registerwerk unterstützt gesponserte ERC-4337-Transaktionen, delegierte EIP-7702-Konten,
ERC-1271-Wallet-Verifizierung und ein On-Chain-Passkey-Konto. Diese Funktionen sind unabhängig
von der [DeFi-Interoperabilität](./defi-interoperability.md).

## Grundlage: `WalletSignatureVerifier` { #foundation-walletsignatureverifier }

`WalletSignatureVerifier` (`orgidentity/api/WalletSignatureVerifier.java`, Basis für
`orgidentity/internal/MemberWalletService` und `marketplace/internal/ManifestSigningService`)
verifiziert Signaturen **entweder** über ECDSA-Recovery (einfache EOAs) **oder** über ERC-1271
`isValidSignature` (Smart-Contract-Wallets), abhängig vom On-Chain-Code der angegebenen Adresse. Das
ist die Voraussetzung für alles Folgende – ohne das könnte ein Smart Account sich nie als
Mitglieds-Wallet binden oder überhaupt ein Marketplace-Manifest signieren.

## EIP-7702: der Smart-Account-Einstieg { #eip-7702-the-smart-account-on-ramp }

EIP-7702 (live seit dem Pectra-Upgrade) erlaubt es einem bestehenden EOA, seinen Code an eine
Smart-Account-Implementierung zu delegieren – **unter Beibehaltung genau derselben Adresse**. Das ist
speziell für Registerwerk der natürliche Einstiegspunkt, da jeder Teil des bestehenden Modells an
einer festen Wallet-Adresse ansetzt:

- `OrgRegistry._orgOf[wallet]` (`contracts/src/ecosystem/OrgRegistry.sol`) – eine Wallet, eine Org,
  nach Adresse.
- T-REX `IdentityRegistry.registerIdentity(address, ...)` – Identität/Claims werden je Adresse
  registriert.
- `EwpgCompliance.isWhitelisted(address)` – Whitelist, indiziert nach Adresse.

Ein Kunde, der sein bestehendes EOA auf einen 7702-delegierten Smart Account upgradet, braucht dafür
**keinerlei Migration** der obigen Punkte – die Adresse ändert sich nicht, sodass Org-Mitgliedschaft,
Identitätsregistrierung und Whitelist-Einträge alle gültig bleiben. Die einzige neue Anforderung ist
der ERC-1271-Pfad von `WalletSignatureVerifier` (bereits vorhanden), da der Code eines
7702-delegierten EOA `isValidSignature` implementiert wie jede andere Smart-Contract-Wallet. Das
Kundenportal delegiert an viems `Simple7702Account`. `EwpgPasskeyAccount` (unten) ist **kein**
7702-Delegate: Passkey und Guardian sind Zustand der jeweiligen Deployment-Instanz. Ein EOA, das an
sie delegiert, hätte gar keinen Passkey (jede Signatur wird abgelehnt) und teilte sich einen
Guardian mit allen anderen delegierenden EOAs.

`frontend-customer` bündelt Browser-Wallet-Zugriffe in `WalletService` und implementiert optionale
EIP-7702-/ERC-4337-Ausführung in `SponsoredTxService`. Sponsoring braucht eine konfigurierte
`environment.bundlerUrl` und für jede UserOperation einen **Voucher** vom Backend (nächster
Abschnitt). Lehnt das Backend den Voucher ab, wirft der Service `SponsorshipUnavailableError` mit
dem Grund; `sendWithSponsorshipFallback` sendet denselben Aufruf dann als normale, selbst bezahlte
Transaktion und meldet das. Ein stiller Fallback findet nie statt. Die UI erstellt und bedient keine
`EwpgPasskeyAccount`-Instanzen.

## `EwpgPaymaster` — gesponserte Transaktionen { #ewpgpaymaster-sponsored-transactions }

`contracts/src/ecosystem/EwpgPaymaster.sol` ist ein ERC-4337-**Verifying-Paymaster** (gegen
EntryPoint v0.8, für native EIP-7702-Unterstützung), der Gas für verifizierte Registerwerk-Kunden
sponsert.

**Voucher.** Eine UserOperation wird nur gesponsert, wenn sie einen Voucher trägt, den der für die
Policy registrierte Voucher-Signer signiert hat:

```
paymasterData = policyId (32) ‖ validUntil (6) ‖ validAfter (6) ‖ maxFeePerGasCap (16) ‖ signature (65)
```

Die Signatur ist eine EIP-191-Signatur (`personal_sign`) über `EwpgPaymaster.getHash(...)`. Dieser
Digest deckt jedes Feld der UserOperation ab (Sender, Nonce, `keccak(initCode)`,
`keccak(callData)`, Account-Gaslimits, die Paymaster-Gaslimits für Verifikation und postOp,
`preVerificationGas` und `gasFees`) sowie `block.chainid`, die Paymaster-Adresse, die Policy-ID,
das Gültigkeitsfenster und die Gaspreis-Obergrenze, unter dem Domain-Tag
`keccak256("EwpgPaymasterVoucher(v1)")`. Die Signatur-Bytes selbst sind bewusst ausgenommen: Sie
liegen in `paymasterAndData`, das Teil von `userOpHash` ist – ein Voucher kann also nicht
`userOpHash` signieren. Ein falscher Signer liefert `SIG_VALIDATION_FAILED` (der EntryPoint meldet
`AA34`), ein abgelaufener Voucher `AA32`. Die Validierung bricht außerdem ab, wenn die Policy
inaktiv oder nicht registriert ist, `maxFeePerGas` über der signierten Obergrenze liegt,
`paymasterPostOpGasLimit` unter 50.000 Gas liegt oder der Sender kein aktives, KYC-geprüftes
Mitglied ist (Defence in Depth; das Backend prüft das ebenfalls).

**Voucher-Aussteller (Backend).** `POST /api/v1/gas-sponsorship/vouchers` (Kunden-JWT,
`asset/web/GasSponsorshipVoucherController`, `asset/internal/GasSponsorshipVoucherService`) nimmt
die Deployment-ID und die vorbereitete UserOperation entgegen. Vor dem Signieren prüft er:

- die effektive Policy des Deployments ist in der Datenbank **aktiv**. Das Deaktivieren einer
  Policy stoppt Voucher sofort, schon bevor das On-Chain-Flag geändert ist.
- der Sender ist eine **aktive Mitglieds-Wallet der juristischen Person des Aufrufers** auf dieser
  Chain.
- der Sender **hält das Asset**: Er hat einen aktiven Registereintrag (keine Nominee-Pool-Zeile)
  der juristischen Person des Aufrufers für das Asset des Deployments. Erstzeichner ohne
  Registereintrag werden nicht gesponsert und zahlen ihr Gas selbst.
- **Scope** (Standard, bis das Produkt anders entscheidet): Jeder Aufruf im
  `execute`/`executeBatch`-Batch zielt ohne Wert auf den Token-Contract des Deployments, und
  `initCode` ist leer oder nur der EIP-7702-Marker. Factory-Deployments werden abgelehnt.
- **Gas**: `maxFeePerGas` ≤ `registerwerk.paymaster.max-fee-per-gas-cap-wei` (die Obergrenze wird
  in den Voucher signiert), die Summe der Gaslimits ≤ `max-total-gas` und postOp-Gas ≥ 50.000.
- die **Monatsobergrenze** der Policy (`monthlyCapEth`). Jeder ausgestellte Voucher zählt mit seinen
  Worst-Case-Kosten (`Σ Gaslimits × maxFeePerGas`, der EntryPoint-Prefund) und wird in
  `gas_sponsorship_voucher` festgehalten, sodass die Obergrenze greift, bevor eine Operation
  abgerechnet ist. Ein Voucher zählt einmal je `(Policy, Sender, UserOperation-Nonce)`: Eine
  erneute Anfrage für dieselbe Nonce ersetzt den früheren Voucher. Eine juristische Person darf
  pro Monat höchstens `registerwerk.paymaster.entity-monthly-cap-share` (Standard 10 %) der
  Obergrenze nutzen, damit eine einzelne Organisation das Budget eines Emittenten nicht für alle
  anderen Inhaber aufbraucht.

Jeder Voucher ist `voucher-validity-seconds` lang gültig (Standard 300) und erzeugt das
Audit-Event `GAS_SPONSORSHIP_VOUCHER_ISSUED`. Der Entwicklungs-Signaturschlüssel ist
`registerwerk.paymaster.voucher-signer-key` (`REGISTERWERK_PAYMASTER_VOUCHER_SIGNER_KEY`). Er ist
in die `EvmSigner`-Abstraktion des Wallet-Moduls eingebettet; in Produktion wandert er in KMS/HSM.
Er darf **niemals** die Claim-Signing-Wallet (Trusted Issuer) sein: Ein Voucher-Schlüssel kann
Sponsoring-Budget ausgeben. Leer deaktiviert das Sponsoring. Paymaster-Adressen werden je Chain
unter `registerwerk.paymaster.addresses` konfiguriert (`PAYMASTER_<CHAIN>_<NETWORK>`). Die
On-Chain-`policyId` einer `GasSponsorshipPolicy`-Zeile ist `keccak256(id.toString())`.

**Budget-Buchhaltung.**

- `registerPolicy(policyId, signer, orgCap)` hält den Aufrufer als **Funder** der Policy fest,
  dazu den Voucher-Signer und eine Obergrenze je Org ungleich null. Die Policy kann im selben
  Aufruf finanziert werden.
- `fundSponsorship(policyId)` stockt auf. Nur der Funder darf das aufrufen, und nur der Funder darf
  den Signer wechseln (`setPolicySigner`), da ein Signer die Policy ausgeben kann.
- Die Validierung **reserviert** die `maxCost` der Operation aus dem Policy-Guthaben, sodass
  mehrere Operationen in einem Bundle nicht alle gegen dasselbe Guthaben bestehen. Sie prüft
  außerdem die Obergrenze der Sender-Org gegen verbraucht + reserviert + `maxCost`. Die Obergrenze
  ist an `orgOf(sender)` gebunden; neue Wallets derselben Org vervielfachen sie nicht.
- `postOp` bricht nie ab. Es bucht `min(maxCost, actualGasCost + (postOpGasLimit + 10.000) ×
  feePerGas)` und gibt den Rest der Reservierung frei. EntryPoint v0.7/v0.8 übergeben `postOp` die
  Kosten *bevor* das eigene postOp-Gas und die Strafe für ungenutztes Gas hinzukommen; würde nur
  `actualGasCost` gebucht, lägen die Bücher mit der Zeit über dem echten Deposit. Der gebuchte
  Betrag ist eine Obergrenze; die kleine Differenz bleibt als Überschuss im Deposit.
- `depositSurplus()` = EntryPoint-Deposit − (Σ Guthaben + Σ Reservierungen). Der Wert darf nie
  negativ werden. Als `paymaster_deposit_minus_booked_wei` überwachen und unter 0 alarmieren.

**Eigentum und Steuerung (on-chain).**

- `setPolicyActive(policyId, bool)` ist der On-Chain-Notschalter. Aufrufen dürfen der Funder oder
  ein Inhaber von `paymaster.configure`.
- `withdrawPolicy(policyId, amount)` zahlt unreserviertes Budget aus dem EntryPoint-Deposit
  zurück. Der Funder oder ein Inhaber von `paymaster.configure` darf sie auslösen, sie zahlt aber
  **immer an den festgehaltenen Funder**. Niemand kann sie umleiten.
- `addStake(unstakeDelaySec)` (erfordert `paymaster.configure`), `unlockStake()` und
  `withdrawStake()` verwalten den EntryPoint-Stake. Der erste Staker wird als `stakeFunder`
  festgehalten, und der Stake geht immer an diese Adresse zurück. Nur `stakeFunder` darf
  `unlockStake()` aufrufen: Ohne Stake verwerfen öffentliche Bundler den Paymaster, deshalb kann
  ein anderer Inhaber von `paymaster.configure` das Unstaking nicht starten.

!!! note "Betreiber-Org und Sponsor-Berechtigungen"
    Der Paymaster wird mit `(oracle, entryPoint, operatorOrg)` konstruiert. `paymaster.configure`
    (Kill-Switch, Obergrenze je Org, Auslösen von `withdrawPolicy` und die oben genannte
    Stake-Verwaltung) wirkt nur für Wallets dieser `operatorOrg`; eine fremde Org mit derselben
    Berechtigung kann weder eine Policy noch den Stake verwalten. `registerPolicy` und `addStake`
    sind genauso gebunden: `registerPolicy` erfordert die Berechtigung `paymaster.register-policy`
    über die Org des Aufrufers, die der Betreiber jedem Sponsor erteilt (der eigenen Org und denen
    der Emittenten); eine vom Betreiber nicht freigegebene Wallet kann daher keine veröffentlichte
    Policy-ID registrieren oder besetzen.

**Operator-UI.** Die Asset-Detailseite von `frontend-operator` hat pro Deployment einen Tab
**Gas Sponsorship** (deploymentspezifische Überschreibung setzen/entfernen). Die
Kunden-Detailseite hat einen für Emittenten (Emittenten-Standard setzen, den neue Deployments
erben). Beide nutzen `core/api/gas-sponsorship.service.ts`. Der Asset-Tab zeigt außerdem den
On-Chain-Zustand der Policy: Aktiv-Flag, verfügbares und reserviertes Guthaben, Obergrenze je
Org, Funder und Voucher-Signer (`GET /assets/{id}/deployments/{depId}/gas-sponsorship/onchain`).
Er warnt, wenn eine Policy in der Datenbank deaktiviert, on-chain aber noch aktiv ist.
`GET /gas-sponsorship/voucher-signer` liefert die Adresse, die jede Policy als Signer
registrieren muss.

- Deploy-Skript: `contracts/script/DeployLiquidityDapps.s.sol` deployt `EwpgPaymaster` mit
  EntryPoint `ERC4337Utils.ENTRYPOINT_V08` zusammen mit `EwpgRepoFacility`.
- Demo-Daten: `EcosystemDemoDataSeeder` legt drei `GasSponsorshipPolicy`-Zeilen an – den
  Emittenten-Standard von Meridian Capital (Sponsor `ISSUER`), den Emittenten-Standard von Aurora
  Finance, stattdessen vom Operator finanziert (Sponsor `OPERATOR`, als Beispiel für den anderen
  Sponsortyp), und eine Überschreibung auf Deployment-Ebene für Meridians Green-Bond-Flaggschiff
  (`OPERATOR`, zeigt den Vorrang der Überschreibung vor dem Standard).
- Tests: `contracts/test/ecosystem/EwpgPaymaster.t.sol` führt jeden gesponserten Pfad durch
  `handleOps` des **echten EntryPoint v0.8.0** (nur für Tests vendort unter
  `contracts/test/aa-v08/`), einschließlich Regressionstests für die unten beim Rollout genannten
  Drain-Szenarien. `backend/.../asset/internal/GasSponsorshipVoucherServiceTest.java` und
  `unit/GasSponsorshipVoucherDigestTest.java` binden den Java-Digest über einen gemeinsamen
  Testvektor an den Solidity-Digest.

### Stake, Rollout und Stilllegung des bisherigen Paymasters { #paymaster-operations }

Die Validierung schreibt Storage (Reservierungen) und liest andere Contracts
(`PermissionOracle`). Nach ERC-7562 akzeptieren öffentliche Bundler den Paymaster daher nur, wenn
er **gestakt** ist. Je Chain:

| Chain | Empfohlener Stake | Unstake-Delay |
|---|---|---|
| Ethereum Mainnet | ≥ 1 ETH | ≥ 1 Tag (86.400 s) |
| L2s (Base, Arbitrum, Optimism, Polygon) | Minimum des Bundlers (meist 0,1–1 des nativen Tokens) | ≥ 1 Tag |
| Testnets | Minimum des Bundlers | ≥ 1 Tag |

Vor dem Staken das veröffentlichte Minimum des Bundler-Anbieters prüfen. Ein Stake mit kürzerem
Delay als vom Bundler verlangt gilt als nicht gestakt.

Rollout:

1. Der vor dieser Änderung deployte Paymaster (HEAD `b810acb` und früher) ist unveränderlich und
   unsicher: Jedes Mitglied konnte jede Policy ausgeben, der Gaspreis war unbegrenzt, und ein
   Bundle konnte mehr ausgeben als vorhanden. **Nicht mehr finanzieren.** Er hat keine
   Auszahlungsfunktion, deshalb verweist keine Finanzierungsmöglichkeit mehr auf ihn.
2. Den neuen `EwpgPaymaster` mit seiner `operatorOrg` deployen (`PAYMASTER_OPERATOR_ORG` in `DeployLiquidityDapps.s.sol`, Standard: die Org des Deployers). `addStake` aus der Operator-Wallet aufrufen und
   `registerwerk.paymaster.addresses.<chain>` sowie den Voucher-Signer-Schlüssel setzen.
3. Der Betreiber erteilt jeder Sponsor-Org (der eigenen und denen der Emittenten) `paymaster.register-policy` und nur der Betreiber-Org `paymaster.configure`. Jeder Funder ruft dann `registerPolicy(keccak256(policyRowId), voucherSigner, orgCap)` mit dem
   Budget auf.
4. Restliches ETH im alten Paymaster (`EntryPoint.balanceOf(old)`) je Chain als **gestrandetes
   Guthaben** erfassen. Es lässt sich nur durch gesponserte Operationen verbrauchen, was wegen der
   oben genannten Mängel unterbleiben muss.

Bekannte Einschränkung: `registerPolicy` vergibt eine Policy-ID unter den freigegebenen Sponsoren
an den Ersten. Ein Sponsor, der die Registrierung front-runnt, kann diese ID blockieren (aber
keine Mittel entnehmen); der Funder registriert die Policy dann unter einer neuen Zeilen-ID.
Wallets ohne `paymaster.register-policy` können das nicht mehr.

## `EwpgPasskeyAccount` — Passkey-Signaturgeber für Retail { #ewpgpasskeyaccount-passkey-signers-for-retail }

`contracts/src/ecosystem/EwpgPasskeyAccount.sol` ist ein minimaler ERC-4337-Smart-Account, der durch
einen WebAuthn/secp256r1-Passkey statt eines per Seed-Phrase verwalteten ECDSA-Schlüssels gesichert
ist. Er kombiniert drei Bausteine, die bereits über `contracts/lib/openzeppelin-contracts` vendort
sind (keine neue Abhängigkeit): OZs `Account` (ERC-4337 `validateUserOp`), `SignerWebAuthn`
(Passkey-Signaturprüfung) und `ERC7821` (minimale Batch-Ausführung). Außerdem implementiert er
ERC-1271, sodass er sich wie jede andere Smart-Contract-Wallet als Registerwerk-Mitglieds-Wallet
binden lässt. Er wird je Kunde als eigener Account deployt und ist **kein EIP-7702-Delegate**: Ohne
Passkey im eigenen Storage des Accounts schlägt jede Signaturprüfung fehl.

Der Guardian ist ein explizites Konstruktorargument und nie versehentlich der Deployer. Aufrufe
lassen sich nach Ziel und Selector als Routine, Admin oder Recovery einstufen. EntryPoint-/ERC-7821-
Batches lehnen Admin- und Recovery-Operationen ab, sodass ein kompromittierter Session-Passkey oder
eine gesponserte UserOperation sie nicht ausführen kann. `guardianExecute` ist ein **vollständiger
Verwahr-Override**, kein rein schützender Pfad: Der Guardian kann ohne Timelock und ohne
Mitsignatur des Passkeys jeden Aufruf aus dem Account ausführen und setzt die Rollentabelle selbst.
Ob ein vom Register gehaltener Guardian mit einseitiger Kontrolle über Retail-Accounts gewollt
ist, ist eine offene Verwahrungsentscheidung (Erlaubnis und Offenlegung). Bis dahin ist der
Guardian-Schlüssel als Verwahrung der Vermögenswerte des Accounts zu behandeln.

Zusammen mit `EwpgPaymaster` braucht der Weg eines Retail-Anlegers vom Onboarding bis zur ersten
Zeichnung weder Seed-Phrase noch Gas-Token – biometrische Passkey-Authentifizierung plus gesponserte
Ausführung. Hinweis: `contracts/foundry.toml` aktiviert jetzt den Solidity-Optimizer
(`optimizer = true`, `optimizer_runs = 200`, passend zum Standard der vendorten OZ-Bibliothek) –
das Parsen von WebAuthn-Signaturen läuft ohne ihn in „stack too deep“.

Die Tests (`contracts/test/ecosystem/EwpgPasskeyAccount.t.sol`) bauen echte
WebAuthn-Authentifizierungs-Assertions mit Foundrys nativen P256-Cheatcodes
(`vm.publicKeyP256`/`vm.signP256`), einschließlich eines durchgearbeiteten Beispiels für den einen
nicht offensichtlichen Stolperstein: `abi.encode(structValue)` fügt bei einem Struct mit
dynamischen Feldern ein zusätzliches Offset-Wort auf oberster Ebene hinzu, das
`WebAuthn.tryDecodeAuth` nicht erwartet – stattdessen die Felder des Structs als einzelne Argumente
kodieren (siehe den `_sign`-Helper des Tests und seinen Inline-Kommentar).
`test_eip7702DelegateHasNoSignerAndFailsClosed` zeigt, dass ein an eine Instanz delegierendes EOA
keinen Signer hat und nicht vom Deployer der Instanz kontrolliert werden kann.

## Gaslose Genehmigungen (Permits) { #gasless-permits }

`EwpgBondDesk.subscribeWithPermit` verwendet ein signiertes EIP-2612-`permit` statt eine separate,
vorgelagerte `approve`-Transaktion zu verlangen – halbiert die Anzahl der Transaktionen und passt
natürlich zum Sponsoring durch `EwpgPaymaster` (Permit + gesponserte Ausführung = UX ganz ohne
Gas-Token). `MockStablecoin` implementiert nun `ERC20Permit`, sodass das Beispiel/die Tests dies
End-to-End durchspielen können
(`test_subscribeWithPermit_succeedsWithoutPriorApproval` in
`contracts/test/examples/EwpgBondDesk.t.sol`). Nicht jeder reale Zahlungsweg unterstützt das: USDC
implementiert EIP-2612 nativ; prüfen Sie die Unterstützung von AllUnity Euro, bevor Sie
`subscribeWithPermit` in Produktion dagegen verdrahten – der einfache `subscribe`-Pfad bleibt so oder
so verfügbar.

## Signaturformate { #signature-formats }

Wallet-Bindung und Manifest-Signatur verwenden `personal_sign`. `WalletSignatureVerifier`
akzeptiert dieses Format für EOAs und ERC-1271-Wallets, jedoch keine EIP-712-Typed-Data-Signaturen.
