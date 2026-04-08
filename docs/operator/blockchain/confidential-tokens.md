---
id: confidential-tokens
title: Confidential Tokens (Zama fhEVM)
sidebar_label: Confidential Tokens
sidebar_position: 3
---

# Confidential Token Setup (Fhenix / Inco)

This guide covers deploying Confidential ERC-3643 tokens using Zama's fhEVM. Confidential tokens encrypt balances and transfer amounts on-chain while preserving full compliance enforcement.

## Choosing between Fhenix and Inco

Both Fhenix and Inco are FHE-enabled EVM chains powered by Zama's fhEVM technology. Key differences:

| Feature | Fhenix | Inco |
|---------|--------|------|
| Mainnet Chain ID | 21888 | 9090 |
| fhEVM version | Zama TFHE-rs | Zama TFHE-rs |
| KMS Gateway | Fhenix Gateway | Inco Gateway |
| Testnet | Fhenix Helium (8008135) | Inco Rivest (21097) |
| Token bridge | Available | Available |

For most new deployments, either chain is suitable. Both are pre-configured in the registry (Flyway migration V15).

## Step 1 — Enable the chain

Fhenix and Inco are pre-seeded. Verify they are enabled:

```bash
curl http://localhost:8080/api/v1/admin/chains \
  -H "Authorization: Bearer $OPERATOR_JWT" \
  | jq '.[] | select(.identifier | contains("FHENIX"))'
```

Set the RPC URLs in `.env`:

```bash
FHENIX_MAINNET_RPC=https://api.fhenix.zone
FHENIX_HELIUM_RPC=https://api.helium.fhenix.zone
INCO_MAINNET_RPC=https://mainnet.inco.org
INCO_RIVEST_RPC=https://testnet.inco.org
```

## Step 2 — Deploy the ConfidentialERC3643 contract

```bash
cd contracts
forge script script/Deploy.s.sol \
  --rpc-url $FHENIX_MAINNET_RPC \
  --broadcast \
  --private-key $DEPLOYER_PRIVATE_KEY
```

The confidential token stores balances as `euint64` (Zama TFHE encrypted uint64). The script deploys:
- `ConfidentialERC3643` token contract
- `FHEIdentityRegistry`
- `FHEModularCompliance`

:::note
Confidential contracts are a separate deployment from standard ERC-3643. They use FHE-aware versions of each T-REX component.
:::

## Step 3 — Zama KMS Gateway

The KMS Gateway handles decryption key derivation for authorized parties (investors, auditors, and the registry operator).

### Hosted gateways (recommended)

Both chains provide managed KMS Gateways:

```bash
FHENIX_KMS_GATEWAY_URL=https://gateway.fhenix.zone
INCO_KMS_GATEWAY_URL=https://gateway.inco.org
```

The backend uses these endpoints automatically for decryption operations.

### Self-hosted KMS (advanced)

For on-premises key management, refer to the [Zama KMS documentation](https://docs.zama.ai/). Only recommended for deployments with strict data sovereignty requirements.

## Step 4 — Investor decryption flow

When an investor views their confidential balance:

1. The portal sends a signed decryption request to the KMS Gateway
2. The KMS validates the investor owns the balance slot
3. The KMS returns a decryption key scoped to that slot
4. The portal decrypts and displays the balance — it is never sent to the registry servers

## Limitations

- Gas costs are 10–50x higher than standard ERC-3643
- Maximum encrypted balance: `uint64` (~18.4 quadrillion)
- Block explorer support is limited — encrypted amounts appear as opaque bytes
- Not all standard Foundry/Hardhat tooling supports FHE opcodes; use the fhEVM-flavored tooling for tests

## Testing on testnet

```bash
forge script script/Deploy.s.sol \
  --rpc-url https://api.helium.fhenix.zone \
  --broadcast \
  --private-key $DEPLOYER_PRIVATE_KEY
```

Faucets: [faucet.fhenix.zone](https://faucet.fhenix.zone) | [faucet.inco.org](https://faucet.inco.org)

# Confidential Tokens (Zama fhEVM)

Confidential tokens use Zama's Fully Homomorphic Encryption (FHE) to encrypt balances and transfer amounts on-chain. Even the node operators cannot see individual holdings.

## Supported chains

| Chain | Type | Notes |
|---|---|---|
| Fhenix Mainnet (21888) | Production FHE L2 | Full fhEVM |
| Fhenix Helium (8008135) | Testnet | Recommended for testing |
| Inco Mainnet (9090) | Confidentiality-as-a-Service | Uses Inco's FHE co-processor |
| Inco Rivest (21097) | Testnet | |

## How it works

Standard ERC-3643 stores balances as `uint256` — visible to everyone. The confidential variant:

1. Replaces `uint256` balances with `euint64` (encrypted 64-bit integer)
2. Transfer amounts are passed as `einput` (encrypted input from the client)
3. The FHE co-processor evaluates compliance checks on encrypted values (balance ≤ max, etc.)
4. Result: transfers are valid or rejected without revealing amounts

```solidity
// ConfidentialERC3643.sol (simplified)
mapping(address => euint64) private _encryptedBalances;

function confidentialTransfer(
    address to,
    einput encryptedAmount,
    bytes calldata inputProof
) external {
    euint64 amount = TFHE.asEuint64(encryptedAmount, inputProof);
    // compliance checks run on encrypted values
    _transferEncrypted(msg.sender, to, amount);
}
```

## Deploying a confidential suite

```bash
curl -X POST http://localhost:8000/api/v1/assets/{assetId}/deployments \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -d '{
    "chain": "FHENIX_HELIUM",
    "tokenStandard": "CONF_ERC3643",
    "tokenName": "Confidential Bond",
    "tokenSymbol": "cBOND",
    "decimals": 0
  }'
```

The backend uses `ConfidentialErc3643Service` which interacts with `ConfidentialERC3643.sol`.

## Client-side encryption

Investors use Zama's [fhevmjs](https://docs.zama.ai/fhevm) library to encrypt transfer amounts before submitting:

```typescript
import { createInstance } from 'fhevmjs';

const fhevm = await createInstance({ chainId: 8008135, publicKey });
const encrypted = fhevm.encrypt64(transferAmount);
await token.confidentialTransfer(recipient, encrypted.data, encrypted.inputProof);
```

## Limitations

- Confidential tokens are only available on Fhenix and Inco chains
- The indexer does not see transfer amounts (privacy by design)
- Token history shows `from`/`to` addresses but amounts are encrypted
- Gas costs are higher (~3–10×) due to FHE computation
