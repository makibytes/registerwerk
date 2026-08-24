---
title: On-chain security review
---

# On-chain security review

The Anvil and wallet-management work received an internal, code-assisted security review before
integration. This is an engineering review, not an independent audit or a substitute for one.

## Review scope and resolved findings

| Area | Finding | Resolution |
| --- | --- | --- |
| Factory deployment | The previous monolithic factory runtime exceeded the EIP-170 size limit. | Split deployment into a 3 KB coordinator and per-standard deployer modules. CI now runs `forge build --sizes`, and the demo deploys on strict Anvil settings. |
| Upgradeability | A single upgradeable implementation would couple unrelated token standards and issued products. | Issued products stay immutable; a small UUPS deployment registry coordinates versioned addresses. ERC-3643 retains the T-REX proxy model. |
| Upgrade authorization | Registry upgrades and mutations must not be publicly reachable. | Both are owner-gated and tested for unauthorized callers, non-contract implementations, and storage preservation. |
| Smart accounts | A passkey/EntryPoint path could otherwise bypass recovery and administration policy. | Routine, administrative, and recovery selectors are separated. EntryPoint execution is limited by target-and-selector policy; guardian-only operations cannot pass through it. |
| Key custody | Passing Web3j credentials through services exposed private-key material broadly. | All EVM transaction paths use the `EvmSigner` boundary. PKCS#11 wallets persist only address and key label, and cannot be exported through wallet APIs. |
| Demo drift | Independently configured addresses could point frontends and backends at different deployments. | The deployment scripts write one persisted manifest. Backend reconciliation and the customer UI consume that artifact after bytecode validation. |

The regression suite covers factory access control and all standards, upgrade authorization and
storage preservation, passkey execution policy, manifest reconciliation, and wallet custody
boundaries. Slither emits SARIF for repository-wide review and fails CI on high-severity findings.

## Production acceptance criteria

Before deploying value-bearing contracts or attaching a production HSM:

1. commission an independent audit of the exact tagged source, compiler configuration and
   deployment scripts;
2. resolve or formally accept all static-analysis findings and publish the resulting report;
3. move registry ownership to a separately reviewed multisig and timelock policy;
4. perform vendor-specific Thales or Utimaco integration, key ceremony, backup and disaster
   recovery tests;
5. verify the deployed bytecode, proxy implementation slots, owner and manifest hashes against
   the approved release artifact;
6. repeat end-to-end tests against the production RPC, bundler and monitoring stack.

Confidential-token tests that need a dedicated fhEVM runtime are intentionally skipped in the
ordinary Foundry environment and must pass in that environment before confidential issuance is
enabled.
