---
title: Confidential EVM (Zama fhEVM)
description: Which chains actually run Registerwerk's confidential contracts, and what infrastructure they need.
---

# Confidential EVM (Zama fhEVM)

Registerwerk's confidential contracts (`ConfidentialERC20`, `ConfidentialERC3643`) are built
against **Zama's** FHEVM — specifically the `FHE` library of fhevm-solidity 0.14, vendored as the
`contracts/lib/fhevm` submodule (the `zama-ai/fhevm` monorepo; its `library-solidity` directory,
plus the `encrypted-types` submodule it imports) on the contract side, and the real
`@zama-fhe/relayer-sdk` package on both the backend (`zama-relayer` sidecar) and browser
(`frontend-customer`/`frontend-operator`) sides.

---

## Configured chain scope

| Chain | Registerwerk behavior |
|---|---|
| Ethereum | Confidential deployment is accepted when the network-specific factory is configured. |
| Base | Confidential deployment is accepted when the network-specific factory is configured. |
| Other `Chain` values | Confidential deployment is rejected. |

`AssetDeploymentService.FHEVM_CHAINS` is the authoritative allowlist. Fhenix and Inco remain
ordinary EVM entries in the `Chain` enum but are not valid confidential-deployment targets.

---

## Configuring the infrastructure

Every FHEVM host-contract address is injected, never hardcoded per chain:

```solidity
// ConfidentialERC20.FhevmInfra — passed to the constructor via EwpgConfidentialFactory
struct FhevmInfra {
    address aclAddress;
    address coprocessorAddress;   // the FHEVMExecutor
    address kmsVerifierAddress;
}
```

The Gateway and the payment contract of the earlier FHEVM (0.6) are gone from this struct: since
FHEVM 0.9 the Gateway lives on its own chain and a host-chain contract no longer talks to it, and
FHE operations are no longer paid for through a host-chain payment contract.

1. Deploy `EwpgConfidentialFactory` (or reuse one) on the target chain, calling `setFhevmInfra`
   with that chain's real Zama addresses.
2. Set `registerwerk.contracts.confidential-factory.<chain-identifier>` to the factory address.
3. For `CONF_ERC3643`, set `registerwerk.contracts.confidential-identity-registry.<chain-identifier>`
   to a real, provisioned T-REX `IdentityRegistry` — required; the factory reverts deployment if
   it's unset rather than silently deploying with a zero-address identity registry.
4. Set `registerwerk.contracts.confidential-operator-viewer.<chain-identifier>` and
   `.confidential-auditor-viewer.<chain-identifier>` to the operator's and an auditor's dedicated
   decrypt-only viewer addresses — see the viewer ACL model below. These are passed as
   `initialViewers` at deploy, so every confidential token on that chain grants them from block
   one.

---

## Who can decrypt — the viewer ACL model

See [Confidential Tokens](../token-standards/confidential.md#who-can-decrypt-what-the-viewer-acl-model)
for the full explanation. In short: every holder can decrypt only their OWN balance handle; a
small operator/auditor/issuer "viewer" set can decrypt every handle. This lives entirely in
`ConfidentialERC20`'s `isViewer`/`addViewer`/`removeViewer` — no separate per-investor contracts.

---

## Decryption — three paths, all real

- **User decryption** (a holder revealing their own balance, or a viewer revealing any balance):
  fully client-side. The connected wallet signs the KMS's `UserDecryptRequestVerification` EIP-712
  payload and the browser's own `@zama-fhe/relayer-sdk` instance completes `userDecrypt` directly
  against Zama's relayer — see `frontend-customer`/`frontend-operator`'s `FheClientService`. The
  backend never sees the plaintext value in this path.
- **Headless operator decryption** (reports/reconciliation, no browser in the loop): the backend's
  `zama-relayer` sidecar holds a dedicated decrypt-only key (`OPERATOR_DECRYPT_PRIVATE_KEY` —
  deliberately NOT an on-chain transaction-signing wallet) and self-signs the same EIP-712
  request, then completes `userDecrypt` in one round trip. See
  `ConfidentialBalanceReconciliationService` and `ZamaRelayerClient.requestOperatorDecrypt`.
- **Public decryption** (`ConfidentialERC20.requestSupplyDisclosure` /
  `fulfillSupplyDisclosure`): the owner marks the total-supply handle publicly decryptable; anyone
  fetches the cleartext and the KMS decryption proof from the relayer (`publicDecrypt`) and submits
  both, and the contract verifies the KMS signatures with `FHE.checkSignatures` before recording the
  value. The Foundry tests run against cleartext mocks of the host contracts
  (`contracts/test/mocks/MockFhevm.sol`, which sign and verify a simplified digest, not the real
  EIP-712 one); live-coprocessor integration and production readiness remain unverified.

`zama-relayer` (repo root `zama-relayer/`) is Registerwerk's own sidecar wrapping the real
`@zama-fhe/relayer-sdk`'s Node build — it exists only because Zama publishes no Java/JVM client;
every browser-initiated flow above talks to Zama directly and never touches this sidecar. Enable
it with `docker compose --profile confidential up`; see `zama-relayer`'s own source comments and
`.env.example`'s "Confidential tokens" section for configuration.

See [Confidential Tokens](../token-standards/confidential.md) for the full status matrix and
[SPL-2022 Confidential Transfer](../token-standards/spl-2022.md) for the unrelated, ElGamal-based
Solana equivalent — the two are easy to conflate but use different cryptography and have no code
in common.
