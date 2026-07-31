---
title: Kontoabstraktion und gesponserte Transaktionen
description: ERC-4337 / EIP-7702 Smart Accounts, gesponsertes Gas, Passkeys und gaslose Genehmigungen (Permits).
---

# Kontoabstraktion und gesponserte Transaktionen { #account-abstraction-sponsored-transactions }

Eine von [DeFi-Interoperabilität](./defi-interoperability.md) unabhängige Gelegenheit: Fortschritte
im Ethereum-Ökosystem seit ERC-4337 (Kontoabstraktion) machen es möglich, Gas für Privat- und
institutionelle Kunden zu sponsern und deren Onboarding zu vereinfachen – unabhängig von jeder
DeFi-Brücke. Das war eine grüne Wiese, als diese Arbeit begann – im gesamten Repository existierte
bislang kein AA-/Paymaster-/Passkey-Code.

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
7702-delegierten EOA `isValidSignature` implementiert wie jede andere Smart-Contract-Wallet –
einschließlich `EwpgPasskeyAccount` weiter unten, das genau eine solche Delegate-Implementierung ist.

**Frontend-Implikation (noch nicht gebaut):** `frontend-customer` hat heute keinerlei
Wallet-Abstraktion – eine einzige Aufrufstelle für `window.ethereum.request(...)`
(`frontend-customer/src/app/features/company-admin/org-identity/org-identity.component.ts`). Die
Einführung von 7702-/Smart-Account-Unterstützung bedeutet, eine dünne Wallet-Schicht von Grund auf neu
zu bauen; `viem` (mit seinen `viem/account-abstraction`- und EIP-7702-Helfern) ist das empfohlene SDK,
da keine bestehende ethers-/wagmi-Abhängigkeit besteht, deren Kompatibilität es zu wahren gälte. Das
bleibt der einzige Teil dieses Vorhabens, der UI-Arbeit statt einer Vertrags-/Backend-Änderung ist.

## `EwpgPaymaster` — gesponserte Transaktionen { #ewpgpaymaster-sponsored-transactions }

`contracts/src/ecosystem/EwpgPaymaster.sol` ist ein ERC-4337-`IPaymaster` (gegen EntryPoint v0.8, für
native EIP-7702-Unterstützung), der Gas für verifizierte Registerwerk-Kunden sponsert:

- **Compliance-gegated nach Wer, nicht nach Was aufgerufen wird**: `validatePaymasterUserOp` prüft
  `PermissionOracle.isActiveMember(userOp.sender)` und `hasClaimTopic(userOp.sender, KYC)` – sponsert
  niemals Gas für eine nicht verifizierte Wallet. Da ein 7702-delegiertes EOA seine ursprüngliche
  Adresse behält, *ist* `userOp.sender` die bestehende Mitglieds-Wallet-Adresse des Kunden, sodass
  hier direkt aus demselben Oracle gelesen wird, das jeder andere Ökosystemvertrag verwendet. Das
  `callData` eines beliebigen Smart Accounts zu parsen, um einzuschränken, *welcher* Vertrag
  aufgerufen werden darf, ist implementierungsspezifisch je Kontotyp und bewusst außerhalb des
  Anwendungsbereichs – siehe die NatSpec des Vertrags.
- **Das Sponsoring ist über eine opake `policyId`** (kodiert in `paymasterAndData`) abgegrenzt,
  finanziert über `fundSponsorship(policyId)` durch jeden, der bereit ist zu sponsern (den Betreiber
  oder das Treasury eines Emittenten) – das reserviert sowohl ein internes Budget als auch zahlt in
  den EntryPoint ein.
- **Ein Ausgabenlimit je Wallet** (`setWalletBudgetCap`) begrenzt, wie viel eine einzelne Wallet von
  einem *gemeinsam genutzten* Policy-Budget verbrauchen kann, zusätzlich zum aggregierten
  Policy-Budget selbst.
- Unterlegt durch die Backend-Entität `deployment/api/GasSponsorshipPolicy` – spiegelt das bestehende
  `MintControlRule`-Muster: eine Überschreibung je Deployment oder ein Standardwert auf
  Emittentenebene, den künftige Deployments dieses Emittenten erben, bis sie eine eigene
  Überschreibung erhalten (`GasSponsorshipService.resolveEffectivePolicy`,
  `asset/web/GasSponsorshipController`). Die On-Chain-`policyId` einer bestimmten Zeile ist
  `keccak256(id.toString())`. Diese Backend-Schicht ist reine Konfiguration – sie treibt noch keinen
  On-Chain-Sync-Job an, der Budgets automatisch in den Paymaster überträgt; ein Betreiber/Emittent
  finanziert `EwpgPaymaster.fundSponsorship` heute direkt.
- Operator-UI: Die Asset-Detailseite von `frontend-operator` hat je Deployment einen Reiter
  **Gas Sponsorship** (deploymentspezifische Überschreibung setzen/entfernen), und die
  Kunden-Detailseite hat einen für Emittenten (den Standardwert auf Emittentenebene setzen, den neue
  Deployments erben) – beide gestützt auf `core/api/gas-sponsorship.service.ts`, zeigen die aktuell
  wirksame Policy und ob es sich um eine Überschreibung oder einen geerbten Standardwert handelt.
- Deploy-Skript: `contracts/script/DeployLiquidityDapps.s.sol` stellt `EwpgPaymaster` bereit
  (Standard-EntryPoint `ERC4337Utils.ENTRYPOINT_V08`) neben `EwpgRepoFacility` – getrennt von
  `DeployExampleDapps.s.sol` gehalten, da beide Pragma `^0.8.36` sind und keine Kompilationseinheit
  mit den erc3643-abhängigen Imports jenes Skripts teilen können (exakt gepinnt auf `0.8.30`).
- Demo-Daten: `EcosystemDemoDataSeeder` sät drei `GasSponsorshipPolicy`-Zeilen – Meridian Capitals
  eigenen Standardwert auf Emittentenebene (Sponsor `ISSUER`), Aurora Finances Emittenten-Standardwert,
  stattdessen vom Betreiber finanziert (Sponsor `OPERATOR`, zeigt den anderen Sponsor-Typ), sowie eine
  Deployment-Überschreibung auf Meridians Flaggschiff-Green-Bond-Deployment (`OPERATOR`, zeigt den
  Vorrang von Überschreibung vor Standardwert).
- Tests: `contracts/test/ecosystem/EwpgPaymaster.t.sol` (gegen einen minimalen `MockEntryPoint` –
  siehe dessen NatSpec dazu, warum eine vollständige `handleOps`-Simulation nicht nötig ist, um die
  eigene Buchungslogik des Paymasters zu testen), `backend/.../unit/GasSponsorshipServiceTest.java`.

## `EwpgPasskeyAccount` — Passkey-Signaturgeber für Retail { #ewpgpasskeyaccount-passkey-signers-for-retail }

`contracts/src/ecosystem/EwpgPasskeyAccount.sol` ist ein minimaler ERC-4337-Smart-Account, der durch
einen WebAuthn-/secp256r1-Passkey statt eines seed-phrase-verwalteten ECDSA-Schlüssels gesichert ist
und drei bereits über `contracts/lib/openzeppelin-contracts` eingebundene Bausteine kombiniert (keine
neue Abhängigkeit): OZs `Account` (ERC-4337 `validateUserOp`), `SignerWebAuthn`
(Passkey-Signaturprüfung) und `ERC7821` (minimale Batch-Ausführung). Er implementiert außerdem
ERC-1271, sodass er sich genau wie jede andere Smart-Contract-Wallet als Registerwerk-Mitglieds-Wallet
binden lässt.

Zusammen mit `EwpgPaymaster` kommt der Weg eines Retail-Anlegers vom Onboarding bis zur ersten
Zeichnung ohne Seed-Phrase und ohne Gas-Token aus – biometrische Passkey-Authentifizierung plus
gesponserte Ausführung. Hinweis: `contracts/foundry.toml` aktiviert inzwischen den
Solidity-Optimizer (`optimizer = true`, `optimizer_runs = 200`, passend zum eigenen Standard der
eingebundenen OZ-Bibliothek) – ohne ihn stößt das WebAuthn-Signatur-Parsing auf „stack too deep“.

Die Tests (`contracts/test/ecosystem/EwpgPasskeyAccount.t.sol`) bauen echte
WebAuthn-Authentifizierungs-Assertions mit Foundrys nativen P256-Cheatcodes
(`vm.publicKeyP256`/`vm.signP256`) auf, einschließlich eines durchgearbeiteten Beispiels für die eine
nicht offensichtliche Falle: `abi.encode(structValue)` fügt für eine Struktur mit dynamischen Feldern
ein zusätzliches Top-Level-Offset-Wort hinzu, das `WebAuthn.tryDecodeAuth` nicht erwartet – stattdessen
die Felder der Struktur als separate Argumente kodieren (siehe den `_sign`-Helfer des Tests und dessen
Inline-Kommentar).

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

## EIP-712 Typed Data — noch zurückgestellt { #eip-712-typed-data-still-deferred }

Wallets stellen EIP-712-strukturierte Daten weit lesbarer dar als opake Hex-/Klartext-Strings. Es
lohnt sich, das auf jede signierte Nachricht anzuwenden, sobald eine entsprechende Signatur-UI zur
Darstellung existiert – die bestehenden `personal_sign`-Abläufe für die Wallet-Bindungs-Challenge und
die Manifest-Signatur wurden in diesem Durchgang bewusst unverändert gelassen, da die Migration ihres
Wire-Formats eine Änderung der Frontend-Signaturmethode (`eth_signTypedData_v4`) erfordert, die hier
außerhalb des Anwendungsbereichs liegt; das Format ohne diesen Aufrufer einzuführen, würde nur
ungenutzte Oberfläche schaffen.
