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

## Schritt 2 – Das Register als Trusted Issuer einrichten

Das Operator-Wallet des Registrierungs-Backends muss im `TrustedIssuersRegistry` registriert sein, damit es KYC-/AML-Claims ausstellen kann. Dies erfolgt einmal pro Factory-Bereitstellung.

```bash
cast send $TRUSTED_ISSUERS_REGISTRY \
  "addTrustedIssuer(address,uint256[])" \
  $REGISTRY_OPERATOR_ADDRESS "[1,2]" \
  --rpc-url $RPC_URL \
  --private-key $DEPLOYER_PRIVATE_KEY
```

Parameter:
- Erstes Argument: Adresse des Registrierungsbetreibers (Deployer-Wallet)
- Zweites Argument: Array der Claim-Topic-IDs, die dieser Aussteller signieren darf (1=KYC, 2=AML)

Überprüfen:

```bash
cast call $TRUSTED_ISSUERS_REGISTRY \
  "isTrustedIssuer(address)(bool)" \
  $REGISTRY_OPERATOR_ADDRESS \
  --rpc-url $RPC_URL
# Expected: true
```

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

1. Erstellt einen Claim mit Topic-ID, Ausstelleradresse und einem Hash des KYC-Verifizierungsdatensatzes
2. Signiert den Claim mit dem privaten Schlüssel des Betreibers
3. Ruft `addClaim` auf dem ONCHAINID-Vertrag des Investors auf

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
