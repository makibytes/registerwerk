# ERC-3643 / T-REX Web3j Wrapper Generation

This package should contain auto-generated Web3j Java wrappers for the T-REX contracts.
The wrappers must be generated from the compiled contract ABIs using the Web3j CLI.

## How to Generate

1. Compile the T-REX contracts (ABI export):
```bash
cd contracts
forge build --skip test script
# ABI files appear in out/EwpgTREXFactory.sol/EwpgTREXFactory.json etc.
```

2. Extract ABI from Foundry output:
```bash
jq '.abi' out/EwpgTREXFactory.sol/EwpgTREXFactory.json > abis/EwpgTREXFactory.abi
jq '.abi' out/EwpgERC3643.sol/EwpgERC3643.json > abis/EwpgERC3643.abi
# Repeat for: IdentityRegistry, ClaimTopicsRegistry, TrustedIssuersRegistry,
#              ModularCompliance, EwpgModularCompliance, IdentityRegistryStorage
```

3. Generate Java wrappers:
```bash
web3j generate solidity \
  -a abis/EwpgTREXFactory.abi \
  -o backend/src/main/java \
  -p de.makibytes.registerwerk.blockchain.internal.erc3643.wrappers
# Repeat for each contract
```

4. Update `Erc3643DeploymentService.java` to use the generated wrappers instead of the
   current placeholder implementation (search for `deployConfidential`).

## Contracts to wrap

- `EwpgTREXFactory` — deploys the full T-REX suite
- `EwpgERC3643` (Token) — the main identity-bound token
- `IdentityRegistry` — maps investor address to OnchainID
- `ClaimTopicsRegistry` — allowed claim topics
- `TrustedIssuersRegistry` — trusted claim issuers
- `ModularCompliance` / `EwpgModularCompliance` — compliance modules

## Starknet class hash

`StarknetTokenService.DEFAULT_ERC3525_CLASS_HASH` must be replaced with the actual
Sierra class hash after the Cairo contract is declared on the target network:

```bash
cd contracts
starkli declare cairo/target/dev/registerwerk_EwpgERC3525.contract_class.json \
  --account $ACCOUNT --rpc $RPC_URL
# Copy the class hash from the output into StarknetTokenService.java
```
