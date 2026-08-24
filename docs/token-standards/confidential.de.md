---
title: Vertrauliche Token (Zama fhEVM)
description: Datenschutzwahrende ERC-20- und ERC-3643-Token mit vollständig homomorpher Verschlüsselung von Zama – der komplette Verschlüsselungs-/Entschlüsselungslebenszyklus, End-to-End.
---

# Vertrauliche Token (Zama fhEVM) { #confidential-tokens-zama-fhevm }

Vertrauliche Token verwenden **vollständig homomorphe Verschlüsselung (FHE)**, um Token-Guthaben
und Transferbeträge vor öffentlicher Einsicht zu schützen, während die von Regulierungsbehörden
geforderten Compliance- und Audit-Fähigkeiten erhalten bleiben.

!!! warning "Registerwerk IST der Client"
    Frühere Fassungen dieser Seite beschrieben den Verschlüsselungs-/Entschlüsselungslebenszyklus
    als „das Problem von jemand anderem" – Aufgabe des Browsers, ein Begleitdienst, den man selbst
    bereitstellen muss. Das war die falsche Sichtweise: Die einzigen Parteien, die vertrauliche
    Guthaben entschlüsseln dürfen – Emittenten, Anleger, der Registerbetreiber und ein Auditor –
    handeln alle *über* Registerwerk. Der Aufbau der vollständigen `@zama-fhe/relayer-sdk`-
    Integration liegt daher in Registerwerks eigener Verantwortung, und sie ist inzwischen gebaut:
    Verträge, ein Relayer-Sidecar im Repository, Backend-Dienste und Browser-Integration in beiden
    Frontends. Siehe die [Statusmatrix](#status) unten für genau das, was real ist gegenüber dem,
    was noch ein Live-Netzwerk zum Testen benötigt.


---

## Unterstützte vertrauliche Standards { #supported-confidential-standards }

| Standard | Basiert auf | Verschlüsselter Zustand |
|---|---|---|
| `CONF_ERC20` | ERC-7984 vertraulicher fungibler Token | `euint64`-Guthaben/-Freibeträge |
| `CONF_ERC3643` | ERC-3643 (T-REX) + ERC-7984 | `euint64`-Guthaben + Klartext-Identität/Compliance |

Verträge: `contracts/src/confidential/ConfidentialERC20.sol` / `ConfidentialERC3643.sol`,
bereitgestellt über `contracts/src/factory/EwpgConfidentialFactory.sol`.

---

## Welche Chains das tatsächlich betreiben { #which-chains-actually-run-this }

Zamas fhEVM-Coprozessor läuft auf **Ethereum und Base** (gemäß Zamas eigener Produktankündigung
zum „fhEVM Coprocessor") – plus schon heute **Sepolia** als vollständig konfiguriertes Testnetz
(echte ACL-/Executor-/Payment-/KMSVerifier-/Gateway-Adressen sind unter
`contracts/lib/fhevm/config/` eingebunden, und dieselben echten Sepolia-Adressen sind in
`@zama-fhe/relayer-sdk` als `SepoliaConfig` gebündelt). Zamas eigene Ethereum-**Mainnet**-Adressen
waren zum Zeitpunkt der Erstellung noch nicht final festgelegt (Zielquartal Q3 2026) und bleiben
auch nach dem Livegang per Governance aktualisierbar.

**Fhenix und Inco sind KEINE Zama-fhEVM-Chains.** Sie betreiben ihre eigenen, separaten,
inkompatiblen FHE-Stacks. `ConfidentialERC20`/`ConfidentialERC3643` sind speziell gegen Zamas
`TFHE.sol`/Gateway-API gebaut und funktionieren auf keinem von beiden.

**T-REX Chain**: Das T-REX Network kündigte im März 2026 an, dass Zama zur Vertraulichkeitsschicht
für die T-REX Ledger wird – direkt relevant für `CONF_ERC3643`, das bereits T-REX-Identität/
Compliance mit Zamas FHE-Guthaben kombiniert. Die T-REX Chain ist in diesem Backend noch nicht als
eigener `Chain`-Enum-Wert abgebildet und hat ihre eigenen FHEVM-Infrastrukturadressen noch nicht
(öffentlich) geteilt. Bestätigen Sie diese, bevor Sie sich in der Produktion auf diese Kombination
verlassen.

FHEVM-Infrastrukturadressen werden in den Verträgen nie pro Netzwerk fest codiert – sie werden zum
Zeitpunkt der Konstruktion/Factory-Konfiguration injiziert (`ConfidentialERC20.FhevmInfra`,
`EwpgConfidentialFactory.setFhevmInfra`), genau damit ein neues Netzwerk (Mainnet, T-REX Chain)
durch das Konfigurieren echter Adressen angesteuert werden kann, statt durch ein erneutes
Deployment des Vertrags.

---

## Wer kann was entschlüsseln – das Viewer-ACL-Modell { #who-can-decrypt-what-the-viewer-acl-model }

Zamas ACL-Berechtigungen sind additiv und gelten je Chiffretext-Handle: Sobald eine Adresse für
ein Handle `allow`ed wurde, ist diese Berechtigung für dieses spezifische Handle dauerhaft (es
gibt keinen Widerruf – siehe den Doc-Kommentar von `ConfidentialERC20.removeViewer`). Registerwerk
nutzt dieses Grundelement, um innerhalb eines **einzigen vertraulichen Vertrags pro Asset** (nicht
eines Vertrags pro Anleger – siehe unten) genau die Isolation zu erreichen, die die Plattform
benötigt:

- **Jeder Inhaber erhält Entschlüsselungsrechte nur für sein EIGENES Guthaben-Handle**, und zwar
  jedes Mal, wenn es sich ändert (Mint/Transfer/Burn). Ein Anleger kann das Guthaben eines anderen
  Anlegers nie entschlüsseln, da er für dessen Handle nie freigegeben (`allow`ed) wird.
- **Eine kleine „Viewer"-Menge** — der Registerbetreiber, ein Auditor und (nachträglich per
  `addViewer` hinzufügbar) optional die eigene Wallet des Emittenten — erhält Entschlüsselungsrechte
  für **jedes** Handle (Guthaben und Gesamtbestand) und erfüllt damit die Anforderung: „Der
  Betreiber muss alle Beträge aller Anleger entschlüsseln können, und die Auditor-Rolle muss
  Beträge entschlüsseln können."
- Viewer werden bei der Bereitstellung als `initialViewers` provisioniert
  (`EwpgConfidentialFactory.deployConfidentialErc20`/`deployConfidentialErc3643`, bezogen aus
  `registerwerk.contracts.confidential-operator-viewer.*` / `.confidential-auditor-viewer.*`) oder
  später über `TokenAdminService.confidentialAddViewer`/`confidentialRemoveViewer`
  (`POST .../admin/confidential-add-viewer` / `-remove-viewer`) hinzugefügt/entfernt.

Warum ein Vertrag mit einer ACL, statt eines Vertrags pro Anleger: identische
Isolationsgarantie, zu normalen Deployment-/Gaskosten, ohne die Komplexität eines
Bestandsabgleichs pro Anleger.

---

## Was die Verträge tatsächlich tun { #what-the-contracts-actually-do }

- `confidentialTransfer` / `confidentialTransferFrom` / `confidentialApprove` — ERC-7984-
  verschlüsselter Transfer/Allowance, mit `TFHE.select`-basierter Silent-Failure-Semantik bei
  unzureichendem Guthaben (entspricht der ERC-7984-Konvention, kein Bug).
- `confidentialMint` / `confidentialBurn` — owner-/agent-gegated, gewähren dem oben beschriebenen
  Viewer-Set Zugriff auf jedes mutierte Handle. Bei `ConfidentialERC3643` ist `confidentialBurn`
  zugleich das Grundelement für die Zwangseinziehung (eWpG §26 Einziehung) verschlüsselter
  Beträge.
- `ConfidentialERC3643` erzwingt zusätzlich T-REX-Identitätsprüfung, Freeze, Pause und ein
  steckbares `IConfidentialCompliance`-Modul vor jedem Transfer.
- `requestSupplyDisclosure` / `callbackSupplyDisclosure` — der Pfad für **öffentliche/Orakel-
  Entschlüsselung**: Der Vertrag selbst bittet Zamas Gateway, den Gesamtbestand zu entschlüsseln,
  und erhält den Klartext über einen signierten Callback zurück, für eine von einer
  Regulierungsbehörde ausgelöste Offenlegung – zu unterscheiden davon, dass ein Inhaber/Viewer
  sein eigenes oder ein fremdes Guthaben über den Relayer entschlüsselt (siehe unten).

---

## Der Verschlüsselungs-/Entschlüsselungslebenszyklus – wer macht was { #status }

| Akteur | Aktion | Wie | Status |
|---|---|---|---|
| Investor | Eigenes Guthaben offenlegen | Browser: `FheClientService.userDecrypt` (die verbundene Wallet signiert die KMS-EIP-712-Anfrage, entschlüsselt direkt gegen Zamas Relayer) | ✅ Real — `frontend-customer` |
| Investor | Vertraulicher Transfer | Browser: `FheClientService.encrypt64` clientseitig, dann übermittelt die Wallet `confidentialTransfer` | ✅ Real — `frontend-customer` |
| Emittent | Vertrauliches Minting | Backend verschlüsselt serverseitig (kein Browser in diesem Ablauf) über den `zama-relayer`-Sidecar und übermittelt dann | ✅ Real — `TokenAdminService.confidentialMint`, `POST .../issuer/mint-confidential` |
| Emittent | Guthaben eines beliebigen Inhabers offenlegen | Browser, als registrierter Viewer (derselbe `FheClientService.userDecrypt`-Pfad) | ✅ Real — Panel für vertrauliche Guthaben des Emittenten in `frontend-customer` |
| Betreiber | Headless-Entschlüsselung für Berichte/Abgleich | Dedizierter Betreiber-Entschlüsselungsschlüssel des Backends über `zama-relayer`, keine Wallet | ✅ Real — `ConfidentialBalanceReconciliationService`, `GET .../confidential-reconciliation` |
| Betreiber / Auditor | Offenlegen + Abgleichen über eigene Wallet | Browser: Reiter „Vertrauliche Guthaben" von `frontend-operator` (`ConfidentialViewerPanelComponent`) | ✅ Real |
| Betreiber | Vertrauliche Zwangsvernichtung (§26 Einziehung) | Backend verschlüsselt serverseitig über `zama-relayer` und übermittelt dann | ✅ Real — `TokenAdminService.confidentialForceBurn`, `POST .../force-burn-confidential` |
| Regulierungsbehörde | Öffentliche/Orakel-Offenlegung des Gesamtbestands | On-Chain: `requestSupplyDisclosure`/`callbackSupplyDisclosure` | ✅ Real, Foundry-getestet |
| Vertrauliches ERC-3643 Freeze/Pause/Zwangsübertragung über die Operator-API | — | `Erc3643Controller` zielt auf die Klartext-ABI von `EwpgERC3643`; ein Aufruf gegen `ConfidentialERC3643` sendet nicht passende Calldata | ❌ Nicht angebunden — nur Force-Burn hat heute einen vertraulichkeitsspezifischen Pfad |
| Vertraulicher Zahlungsweg (verschlüsselte Stablecoin-Beträge im DvP-Zahlungsleg) | — | — | ❌ Nicht gebaut |

**Was hier tatsächlich unverifiziert ist**: Diese Sandbox verfügt über kein Live-Docker/Kong und
kein finanziertes Sepolia-Konto, um echte Transaktionen einzureichen, daher wurde der On-Chain-
Ablauf Submit → Mine → Decrypt in dieser Umgebung nicht end-to-end ausgeführt. Was während der
Entwicklung gegen Zamas echte, live laufende Sepolia-Infrastruktur **verifiziert wurde**: Der
Endpunkt `/v1/encrypt-input` von `zama-relayer` erzeugte über eine Live-`createInstance`-
Verbindung zu Zamas echtem Relayer (`https://relayer.testnet.zama.org`) und einem öffentlichen
Sepolia-RPC ein echtes Chiffretext-Handle und einen ZK-Eingabenachweis – keine Attrappe. Jede
Komponente hier ist gebaut, unit-/Foundry-getestet und (soweit geprüft) auf Ebene des
Einzelaufrufs live-netzwerk-verifiziert; nur der vollständige, mehrstufige Transaktions-Roundtrip
benötigt noch ein finanziertes Konto und ein bereitgestelltes Asset, um abgeschlossen zu werden.

---

## Bereitstellung eines vertraulichen Assets { #deploying-a-confidential-asset }

1. Stellen Sie `EwpgConfidentialFactory` auf einer Chain mit konfigurierten echten Zama-FHEVM-
   Adressen bereit (heute Sepolia), oder konfigurieren Sie eine vorhandene Factory über
   `setFhevmInfra`.
2. Stellen Sie für `CONF_ERC3643` ein gemeinsames T-REX-`IdentityRegistry` für vertrauliche Assets
   auf dieser Chain bereit und setzen Sie
   `registerwerk.contracts.confidential-identity-registry.<chain>` — eine Bereitstellung mit
   unkonfiguriertem/Null-Identity-Registry schlägt laut fehl (`EwpgConfidentialFactory` macht
   einen Revert).
3. Setzen Sie `registerwerk.contracts.confidential-factory.<chain>` auf die bereitgestellte
   Factory-Adresse sowie `registerwerk.contracts.confidential-operator-viewer.<chain>` /
   `.confidential-auditor-viewer.<chain>` auf die dedizierten, nur entschlüsselnden Viewer-
   Adressen des Betreibers/Auditors (siehe [Confidential EVM](../blockchains/confidential-evm.md)).
4. Stellen Sie `zama-relayer` bereit (`docker compose --profile confidential up`), wobei
   `OPERATOR_DECRYPT_PRIVATE_KEY` auf den privaten Schlüssel gesetzt wird, der zur obigen
   Operator-Viewer-Adresse passt, und richten Sie das Backend über
   `registerwerk.zama.relayer-url` darauf aus.
5. Geben Sie das Asset als `CONF_ERC20`/`CONF_ERC3643` aus – die Bereitstellung ist auf echte
   Zama-Coprozessor-Chains beschränkt (`Chain.ETHEREUM`, `Chain.BASE`), nicht auf Fhenix/Inco.

Siehe [Confidential EVM](../blockchains/confidential-evm.md) für Details zur Chain-Konfiguration
und [Operator: Vertrauliche Token](../operator/blockchain/confidential-tokens.md) für den
betrieblichen Alltagsworkflow.
