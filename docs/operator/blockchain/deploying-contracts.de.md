---
title: Verträge bereitstellen
---

# Bereitstellen von Smart Contracts

## Übersicht

Alle Verträge liegen in `contracts/` und werden mit [Foundry](https://book.getfoundry.sh/) kompiliert.

### Vertragsarchitektur

```
AssetTokenFactory (CREATE2 factory)
├── EwpgERC20       — Fungible security token
├── EwpgERC721      — Non-fungible security token
├── EwpgERC1155     — Multi-token (e.g. bond tranches)
└── EwpgERC3643     — Regulated security token (T-REX / ERC-3643)
    └── EwpgTREXFactory — T-REX suite deployer
        ├── Token
        ├── IdentityRegistry
        ├── IdentityRegistryStorage
        ├── ModularCompliance
        ├── ClaimTopicsRegistry
        └── TrustedIssuersRegistry

ConfidentialERC3643 — Encrypted balances on Fhenix / Inco
```

## Build

```bash
cd contracts
forge build
```

Kompilierte Artefakte landen in `contracts/out/`. Der Maven-`web3j-maven-plugin` liest diese, um Java-Wrapper zu generieren.

## Test

```bash
forge test -vvv
forge coverage
```

Ziel: ≥80 % Zeilenabdeckung.

## Im Testnetz bereitstellen

```bash
export ETH_SEPOLIA_RPC=https://rpc.sepolia.org
export DEPLOYER_PRIVATE_KEY=0x<key>

forge script script/DeployTestnet.s.sol \
  --rpc-url $ETH_SEPOLIA_RPC \
  --broadcast \
  --verify
```

## Im Mainnet bereitstellen

```bash
forge script script/Deploy.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast \
  --verify \
  --slow   # 1 tx per block for safety
```

## CREATE2-Determinismus

`AssetTokenFactory` verwendet `CREATE2` mit dem Salt `keccak256(abi.encode(assetId, tokenStandard))`. Das bedeutet:
- Das Backend kann die Vertragsadresse **bevor** die Transaktion gemined wird vorab berechnen
- Die Adresse wird sofort als `PENDING` in `asset_deployment` gespeichert
- Die Bereitstellung ist idempotent — ein erneuter Lauf derselben Bereitstellung erzeugt dieselbe Adresse

Da Salt und Initcode aus öffentlichen Calldata abgeleitet werden, kann bei Factories, die vor der
Beschränkung von `deployToken`/`deployVault` auf das Register bereitgestellt wurden, jeder die
Calldata des Registers zuerst einspielen und so die Transaktion des Registers selbst scheitern
lassen (`CREATE2 failed`). Das Backend prüft deshalb `predictAddress` vor dem Senden und erneut nach
einem Revert: Liegt dort bereits ein Vertrag und stimmen dessen `assetId()` und `registry()`
überein, übernimmt es diesen Vertrag samt erzeugender Transaktion, statt fehlzuschlagen. Gehört der
Vertrag dort nicht zu uns (etwa weil die Registerberechtigung übergeben wurde), schlägt das
Deployment mit einer Fehlermeldung fehl, die die Adresse nennt.

!!! note "Einführung der auf das Register beschränkten Factory"
    `registryWallet` ist unveränderlich und `bindFactory` jedes Deployers nur einmal aufrufbar;
    die Beschränkung gilt daher erst für eine neue Factory-Generation. Je Chain: die sechs
    Deployer und eine neue `AssetTokenFactory` bereitstellen (`script/Deploy.s.sol` /
    `DeployL2.s.sol` / `DeployTestnet.s.sol`), dann
    `registerwerk.contracts.asset-token-factory.<chain>` aktualisieren und das Backend neu
    starten. Bestehende Token behalten ihre Adressen (sie sind in `asset_deployment` gespeichert);
    noch aktive alte Factories bleiben durch die oben beschriebene Übernahmeprüfung des Backends
    geschützt.

## Compliance-Module aktualisieren

```bash
forge script script/UpgradeCompliance.s.sol \
  --rpc-url $ETH_MAINNET_RPC \
  --broadcast
```

## Von einem einzelnen Deployment-Schlüssel zu Multisig/Timelock

!!! warning "Kein Skript in diesem Repository erledigt das für Sie"
    Jede Datei `script/Deploy*.s.sol` signiert mit dem einzelnen EOA hinter
    `REGISTRY_WALLET_PRIVATE_KEY` und gewährt derselben Adresse `DEFAULT_ADMIN_ROLE` +
    `OPERATOR_ROLE` auf `OrgRegistry`, `PermissionRegistry`, `EcosystemTrustedIssuersRegistry`,
    `PermissionOracle` und `DappRegistry` sowie die `Ownable`-Eigentümerschaft an jedem Token, das
    `AssetTokenFactory` erzeugt — dauerhaft, **ohne Schritt zur Weitergabe**. `UpgradeCompliance.s.sol`
    von `EwpgBondDesk` ist die einzige Ausnahme: eine optionale Umgebungsvariable `NEW_REGISTRY_WALLET`,
    die die Eigentümerschaft einer frisch bereitgestellten `WhitelistRegistry` verschiebt und sonst nichts.
    Wer mit einem rohen Schlüssel, der nie migriert wird, ins Mainnet geht, riskiert, dass ein einziger
    kompromittierter Laptop die gesamte Registry einfrieren, zwangsübertragen oder neu berechtigen kann.

Dies ist ein Betriebshandbuch, keine Vertragsänderung — das in den Verträgen vorhandene Modell aus `AccessControl`/`Ownable` ist genau das, was ein Multisig braucht; nichts hiervon erfordert eine Solidity-Änderung oder ein neues Deployment.

### 1. Das Multisig vor dem Deployment aufsetzen

Stellen Sie zuerst ein [Gnosis Safe](https://safe.global/) (oder Gleichwertiges) auf der Zielchain bereit, mit Signierenden, die namentlich benannte Personen auf getrennten Hardware-Wallets sind — niemals ein zweiter Schlüssel auf derselben Maschine, auf der `forge script` lief. Ein Schwellenwert von 3 aus 5 ist für einen Registerbetreiber ein sinnvoller Ausgangspunkt; passen Sie ihn an Ihre eigene Funktionstrennungs-Richtlinie an.

### 2. Mit dem EOA bereitstellen, dann die Admin-Rechte in derselben Sitzung übertragen

Führen Sie das Deploy-Skript genau wie oben dokumentiert aus — der EOA muss die Deployment-Transaktionen selbst signieren, mit diesen Skripten gibt es keinen Weg daran vorbei. Unmittelbar danach, im selben Betriebsfenster, für jeden Ökosystem-Vertrag:

```solidity
// One transaction pair per AccessControl contract (OrgRegistry, PermissionRegistry,
// EcosystemTrustedIssuersRegistry, PermissionOracle, DappRegistry):
grantRole(DEFAULT_ADMIN_ROLE, safeAddress);
grantRole(OPERATOR_ROLE, safeAddress);
// Only after confirming the Safe can exercise both roles (see step 4):
renounceRole(OPERATOR_ROLE, deployerEoa);
renounceRole(DEFAULT_ADMIN_ROLE, deployerEoa);

// For Ownable contracts (AssetTokenFactory-spawned tokens, EwpgBondDesk-style deployments):
transferOwnership(safeAddress);
```

`AssetTokenFactory.registryWallet` ist `immutable` — es kann nach dem Deployment nicht auf das Safe umgestellt werden. Braucht die Factory selbst Multisig-Kontrolle, muss das Safe der Deployer der Factory sein (also die Rolle von `REGISTRY_WALLET_PRIVATE_KEY` von Anfang an halten, über einen Safe-Transaktionsstapel statt eines EOA-Laufs von `forge script`), nicht etwas, das nachträglich migriert wird.

### 3. Vor das Safe einen Timelock schalten (für folgenreiche Aktionen)

Ein Multisig allein stoppt einen einzelnen kompromittierten Schlüssel; es gibt den Betroffenen (Emittenten, Anlegern, anderen Betreibern) keine Vorwarnung vor einer Änderung. Führen Sie Aktionen mit echter Tragweite — Widerruf von Vertrauen in `EcosystemTrustedIssuersRegistry`, plattformweite Änderung von `PermissionRegistry`-Berechtigungen, Umhängen von `PermissionOracle` — über einen [TimelockController](https://docs.openzeppelin.com/contracts/5.x/api/governance#TimelockController) (vorschlagen → verpflichtende Verzögerung → ausführen) statt direkt aus. Geben Sie dem Timelock `DEFAULT_ADMIN_ROLE` und dem Safe `PROPOSER_ROLE`/`EXECUTOR_ROLE` auf dem Timelock, nicht `DEFAULT_ADMIN_ROLE` direkt auf den Zielverträgen.

### 4. Prüfen, bevor etwas aufgegeben wird

Führen Sie vor den Aufrufen `renounceRole`/`renounceOwnership` aus Schritt 2 gegen jeden Vertrag eine echte, umkehrbare Safe-Transaktion aus (z. B. einen wirkungslosen Berechtigungs-Vergabe/Entzug-Durchlauf) und prüfen Sie, dass sie mit dem erwarteten Signierschwellenwert on-chain landet. `renounceRole` ist unumkehrbar — der gleichzeitige Verlust des Zugriffs auf den EOA und auf ein funktionierendes Safe-Quorum sperrt die Admin-Funktionen des Vertrags dauerhaft.

### 5. Den EOA-Schlüssel stilllegen

Sobald bestätigt ist, dass die Rollen/Eigentümerschaft jedes Vertrags übertragen wurden, hat der private Schlüssel des Deployer-EOA keinen legitimen Zweck mehr. Zerstören Sie ihn — archivieren Sie ihn nicht „für alle Fälle"; ein archivierter Deployment-Schlüssel ist genau das dauerhafte Risiko, das dieses ganze Verfahren beseitigen soll.
