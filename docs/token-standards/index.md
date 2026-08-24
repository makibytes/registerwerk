---
title: Token Standards
description: Token-standard implementations represented in Registerwerk, with comparison, use cases, and implementation status.
---

# Token Standards

Registerwerk contains implementations, integrations, or placeholders for token standards across EVM, Solana, StarkNet, Stellar, and Canton chains. Presence in this table does not establish production readiness or suitability for a financial instrument.

---

## Quick-reference comparison

| Standard | Chain | Type | Use case | Confidential variant | Status |
|---|---|---|---|---|---|
| [ERC-20](erc20.md) | EVM | Fungible | Equity, utility | CONF_ERC20 | Implementation present; readiness unverified |
| [ERC-721](erc721.md) | EVM | Non-fungible | Unique certificates, NFT bonds | — | Implementation present; readiness unverified |
| [ERC-1155](erc1155.md) | EVM | Multi-token | Batch issuance | — | Implementation present; readiness unverified |
| [ERC-3525](erc3525.md) | EVM | Semi-fungible | Bonds with tranches, fund series | STARKNET_ERC3525 | Implementation present; readiness unverified |
| [ERC-3643](erc3643.md) | EVM | Fungible + identity | Regulated securities, permissioned | CONF_ERC3643 | Implementation present; readiness unverified |
| [ERC-4626](erc4626.md) | EVM | Vault (sync) | Money-market funds, daily NAV | — | Implementation present; economic model/readiness unverified |
| [ERC-7540](erc7540.md) | EVM | Vault (async) | Institutional funds, T+1/T+2 | — | Implementation present; economic model/readiness unverified |
| [DAML BOND FIXED](canton-daml.md) | Canton | Bond | Fixed-rate bond lifecycle | — | Custom Daml template; profile-verified, live conformance required |
| [DAML BOND FLOATING](canton-daml.md) | Canton | Bond | Floating-rate bond lifecycle | — | Custom Daml template; profile-verified, live conformance required |
| [DAML BOND ZERO](canton-daml.md) | Canton | Bond | Zero-coupon bond lifecycle | — | Custom Daml template; profile-verified, live conformance required |
| [CANTON_TOKEN](canton-daml.md) | Canton | Generic | CIP-0056 asset | — | Reserved; deliberately not deployable until a registry-specific adapter exists |
| [SPL](../blockchains/solana.md) | Solana | Fungible | Solana-native tokens | — | Integration present; readiness unverified |
| [SPL_2022](spl-2022.md) | Solana | Fungible + ext. | Extended Solana tokens | — | Integration present; readiness unverified |
| [SPL_2022_BOND](spl-2022.md) | Solana | Fungible + interest | Interest-bearing bonds on Solana | — | Integration present; readiness unverified |
| [SPL_2022_CONFIDENTIAL](spl-2022.md) | Solana | Confidential | Confidential Solana token | — | Integration present; readiness unverified |
| [STARKNET_ERC20](../blockchains/starknet-stellar.md) | StarkNet | Fungible | Cairo ERC-20 equivalent | — | ⚠️ Placeholder |
| [STARKNET_ERC3525](../blockchains/starknet-stellar.md) | StarkNet | Semi-fungible | Cairo ERC-3525 equivalent | — | ⚠️ Placeholder |
| [STELLAR_ASSET](../blockchains/starknet-stellar.md) | Stellar | Asset | Stellar-native issued asset | — | ⚠️ Placeholder |
| [CONF_ERC20](confidential.md) | Fhenix / Inco | Confidential fungible | Privacy-preserving equity | — | Implementation present; readiness unverified |
| [CONF_ERC3643](confidential.md) | Fhenix / Inco | Confidential regulated | Privacy-preserving regulated security | — | Implementation present; readiness unverified |

---

## How the `TokenStandard` enum drives deployment

The `TokenStandard` enum in the `deployment` module is the central switch that drives which deployment service is invoked:

```java
// BlockchainTokenDeploymentService — simplified routing
return switch (asset.tokenStandard()) {
    case ERC20         -> erc20DeploymentService.deploy(asset);
    case ERC721        -> erc721DeploymentService.deploy(asset);
    case ERC3525       -> erc3525DeploymentService.deploy(asset);
    case ERC3643       -> erc3643DeploymentService.deploy(asset);
    case ERC4626       -> erc4626DeploymentService.deploy(asset);
    case ERC7540       -> erc7540DeploymentService.deploy(asset);
    case DAML_BOND_FIXED, DAML_BOND_FLOATING, DAML_BOND_ZERO ->
                          cantonBondDeploymentService.deploy(asset);
    case SPL, SPL_2022, SPL_2022_BOND, SPL_2022_CONFIDENTIAL ->
                          solanaTokenService.deploy(asset);
    // ...
};
```

The selected standard also determines which admin operations are available in the operator portal and which indexer event types are subscribed to.

---

## Choosing the right standard

| Scenario | Recommended standard | Reason |
|---|---|---|
| Simple equity / utility token on EVM | ERC-20 | Widest wallet support |
| KYC-gated security on EVM | ERC-3643 | Built-in identity and compliance modules |
| Bond with multiple tranches / series | ERC-3525 | Native slot+value semi-fungible model |
| Daily-NAV fund share on EVM | ERC-4626 | Standard vault interface |
| Institutional fund T+1/T+2 redemption | ERC-7540 | Async request/claim model |
| Fixed-rate bond on private DAML ledger | DAML_BOND_FIXED | Native coupon payment support |
| Privacy-preserving security on EVM | CONF_ERC3643 | Zama fhEVM confidential + regulated |
| Interest-bearing bond on Solana | SPL_2022_BOND | Interest-bearing Token-2022 extension |
