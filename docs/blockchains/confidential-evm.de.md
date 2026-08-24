---
title: Vertrauliches EVM (Zama fhEVM)
description: Welche Chains die vertraulichen Verträge von Registerwerk tatsächlich betreiben und welche Infrastruktur sie benötigen.
---

# Vertrauliches EVM (Zama fhEVM) { #confidential-evm-zama-fhevm }

Registerwerks vertrauliche Verträge (`ConfidentialERC20`, `ConfidentialERC3643`) sind gegen **Zamas**
fhEVM gebaut – konkret gegen die `TFHE.sol`/Gateway-API, die unter `contracts/lib/fhevm` (dem
Submodul `zama-ai/fhevm-solidity`) vertragsseitig eingebunden ist, sowie das reale
`@zama-fhe/relayer-sdk`-Paket sowohl im Backend (`zama-relayer`-Sidecar) als auch im Browser
(`frontend-customer`/`frontend-operator`).

---

## Konfigurierter Chain-Umfang { #configured-chain-scope }

| Chain | Verhalten von Registerwerk |
|---|---|
| Ethereum | Vertrauliche Deployments werden akzeptiert, wenn die netzwerkspezifische Factory konfiguriert ist. |
| Base | Vertrauliche Deployments werden akzeptiert, wenn die netzwerkspezifische Factory konfiguriert ist. |
| Andere `Chain`-Werte | Vertrauliche Deployments werden abgewiesen. |

`AssetDeploymentService.FHEVM_CHAINS` ist die maßgebliche Positivliste. Fhenix und Inco bleiben
gewöhnliche EVM-Einträge im `Chain`-Enum, sind aber keine gültigen Ziele für vertrauliche Deployments.

---

## Konfigurieren der Infrastruktur { #configuring-the-infrastructure }

Jede FHEVM-Host-Vertragsadresse wird injiziert, niemals pro Chain fest codiert:

```java
// ConfidentialERC20.FhevmInfra — passed to the constructor via EwpgConfidentialFactory
struct FhevmInfra {
    address aclAddress;
    address tfheExecutorAddress;
    address fhePaymentAddress;
    address kmsVerifierAddress;
    address gatewayAddress;
}
```

1. Stellen Sie `EwpgConfidentialFactory` auf der Ziel-Chain bereit (oder verwenden Sie eine bestehende)
   und rufen Sie `setFhevmInfra` mit den echten Zama-Adressen dieser Chain auf.
2. Setzen Sie `registerwerk.contracts.confidential-factory.<chain-identifier>` auf die Factory-Adresse.
3. Setzen Sie für `CONF_ERC3643` `registerwerk.contracts.confidential-identity-registry.<chain-identifier>`
   auf eine echte, bereitgestellte T-REX-`IdentityRegistry` – erforderlich; die Factory bricht die
   Bereitstellung mit einem Revert ab, wenn dieser Wert nicht gesetzt ist, statt stillschweigend mit
   einer Identity Registry der Nulladresse bereitzustellen.
4. Setzen Sie `registerwerk.contracts.confidential-operator-viewer.<chain-identifier>` und
   `.confidential-auditor-viewer.<chain-identifier>` auf die dedizierten, nur entschlüsselnden
   Viewer-Adressen des Betreibers bzw. eines Prüfers – siehe das Viewer-ACL-Modell unten. Diese werden
   bei der Bereitstellung als `initialViewers` übergeben, sodass jeder vertrauliche Token auf dieser
   Chain ihnen von Block eins an Zugriff gewährt.

---

## Wer entschlüsseln kann – das Viewer-ACL-Modell { #who-can-decrypt-the-viewer-acl-model }

Siehe [Vertrauliche Token](../token-standards/confidential.md#who-can-decrypt-what-the-viewer-acl-model)
für die vollständige Erklärung. Kurz gefasst: Jeder Inhaber kann nur sein EIGENES Guthaben-Handle
entschlüsseln; eine kleine Betreiber-/Prüfer-/Emittenten-„Viewer"-Menge kann jedes Handle entschlüsseln.
Das ist vollständig in `ConfidentialERC20`s `isViewer`/`addViewer`/`removeViewer` verankert – keine
separaten Verträge pro Anleger.

---

## Entschlüsselung – drei Wege, alle real { #decryption-three-paths-all-real }

- **Benutzerentschlüsselung** (ein Inhaber legt sein eigenes Guthaben offen, oder ein Viewer legt ein
  beliebiges Guthaben offen): vollständig clientseitig. Das verbundene Wallet signiert die
  `UserDecryptRequestVerification`-EIP-712-Nutzlast des KMS, und die browsereigene
  `@zama-fhe/relayer-sdk`-Instanz führt `userDecrypt` direkt gegen Zamas Relayer aus – siehe den
  `FheClientService` von `frontend-customer`/`frontend-operator`. Das Backend sieht den Klartextwert
  auf diesem Weg nie.
- **Headless-Betreiberentschlüsselung** (Berichte/Abstimmung, ohne Browser im Ablauf): Der
  `zama-relayer`-Sidecar des Backends hält einen dedizierten, nur entschlüsselnden Schlüssel
  (`OPERATOR_DECRYPT_PRIVATE_KEY` – bewusst KEIN On-Chain-Transaktionssignatur-Wallet) und signiert
  dieselbe EIP-712-Anfrage selbst, um `userDecrypt` dann in einem Roundtrip abzuschließen. Siehe
  `ConfidentialBalanceReconciliationService` und `ZamaRelayerClient.requestOperatorDecrypt`.
- **Öffentliche/Oracle-Entschlüsselung** (`ConfidentialERC20.requestSupplyDisclosure`): Der Vertrag
  selbst fordert vom Gateway die Entschlüsselung eines Werts an (z. B. den Gesamtbestand) und erhält
  den Klartext über einen signierten Callback zurück. Repository-Implementierung und Foundry-Tests
  liegen vor, die Integration mit einem echten Coprozessor und die Produktionsreife sind aber noch
  nicht verifiziert.

`zama-relayer` (im Repo-Root unter `zama-relayer/`) ist Registerwerks eigener Sidecar, der den
Node-Build des echten `@zama-fhe/relayer-sdk` umschließt – er existiert nur, weil Zama keinen
Java/JVM-Client veröffentlicht; jeder oben beschriebene, vom Browser initiierte Ablauf spricht direkt
mit Zama und berührt diesen Sidecar nie. Aktivieren Sie ihn mit
`docker compose --profile confidential up`; Konfigurationshinweise finden Sie in den Quellcode-
Kommentaren von `zama-relayer` selbst und im Abschnitt „Confidential tokens" von `.env.example`.

Siehe [Vertrauliche Token](../token-standards/confidential.md) für die vollständige Statusmatrix und
[SPL-2022 Confidential Transfer](../token-standards/spl-2022.md) für das nicht verwandte,
ElGamal-basierte Solana-Äquivalent – die beiden werden leicht verwechselt, verwenden aber
unterschiedliche Kryptografie und haben keinen gemeinsamen Code.
