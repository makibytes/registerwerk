/**
 * Environment-driven configuration for the relayer sidecar.
 *
 * All FHEVM/Gateway/Relayer addresses are read from env — never hardcoded — mirroring the same
 * "injectable, not hardcoded per network" principle the Solidity side uses for `FhevmInfra`
 * (see `contracts/src/confidential/ConfidentialERC20.sol`'s class-level note): Zama's own mainnet
 * addresses are still being finalised, and this sidecar must be retargetable to a new network
 * (Sepolia today, Ethereum/Base mainnet, T-REX Chain once it publishes its own addresses) purely
 * by changing env vars, not by editing code.
 */
export interface RelayerConfig {
  port: number;
  chainId: number;
  /** Preset name ("sepolia") or "custom" for a fully explicit configuration. */
  preset: 'sepolia' | 'custom';
  /**
   * Only used for preset === 'custom'. Mirrors `@zama-fhe/relayer-sdk`'s `FhevmInstanceConfig`
   * exactly — verified against the installed package's own `lib/node.d.ts`, not guessed: there
   * are five DISTINCT contract addresses (acl, kms, inputVerifier, plus the two
   * "verifyingContractAddress*" ones used to check the KMS/coprocessor signers' EIP-712
   * signatures — NOT aliases of kmsContractAddress/inputVerifierContractAddress), a
   * `gatewayChainId` (the Gateway's own chain id, not a URL — there is no `gatewayUrl` field in
   * this SDK version), and the relayer's HTTP `relayerUrl`.
   */
  custom?: {
    networkUrl: string;
    relayerUrl: string;
    chainId: number;
    gatewayChainId: number;
    aclContractAddress: string;
    kmsContractAddress: string;
    inputVerifierContractAddress: string;
    verifyingContractAddressDecryption: string;
    verifyingContractAddressInputVerification: string;
  };
  /**
   * Overrides SepoliaConfig's default RPC endpoint. Needed because the SDK's bundled default
   * (`https://eth-sepolia.public.blastapi.io`) was shut down by BlastAPI — see
   * https://community.zama.org/t/error-creating-relayer-instance-from-nodejs/3827. Leave unset to
   * use whatever the installed SDK version currently bundles.
   */
  sepoliaNetworkUrlOverride?: string;
  /**
   * The dedicated "operator-decrypt" keypair used ONLY to authorize userDecrypt requests on
   * behalf of the registry operator/auditor viewer role (see {@link ../src/routes/operatorDecrypt}).
   * Deliberately NOT the same key as any chain's on-chain transaction-signing wallet
   * (`WalletSigner`/`EvmContractService` on the backend side) — a compromised relayer sidecar can
   * then decrypt confidential balances it is a viewer on, but cannot move funds or submit
   * transactions. Its public address must be registered as a viewer via `addViewer`/the deploy
   * factory's `initialViewers` for whichever confidential tokens the operator needs to reconcile.
   */
  operatorDecryptPrivateKey?: `0x${string}`;
  /** How long the operator-decrypt EIP-712 authorization is valid for, in days. */
  operatorDecryptDurationDays: number;
  /**
   * Shared-secret bearer token every `/v1/*` request must present (finding #6, Phase 9). Required
   * exactly like `JWT_DEV_SECRET` is on the backend side — this sidecar's decrypt capability is
   * at least as sensitive as the backend's own JWT-gated endpoints, so it gets the same "must
   * never be empty" treatment rather than an optional, defaults-to-disabled toggle that would
   * leave the gap open wherever an operator forgets to opt in.
   */
  apiKey: string;
}

function requireEnv(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value || value.trim() === '') {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): RelayerConfig {
  const preset = (env.ZAMA_CONFIG_PRESET ?? 'sepolia').toLowerCase();
  if (preset !== 'sepolia' && preset !== 'custom') {
    throw new Error(`ZAMA_CONFIG_PRESET must be "sepolia" or "custom", got: ${preset}`);
  }

  const config: RelayerConfig = {
    port: Number(env.PORT ?? '3001'),
    chainId: Number(env.CHAIN_ID ?? '11155111'),
    preset: preset as 'sepolia' | 'custom',
    sepoliaNetworkUrlOverride: env.SEPOLIA_NETWORK_RPC_URL,
    operatorDecryptDurationDays: Number(env.OPERATOR_DECRYPT_DURATION_DAYS ?? '365'),
    apiKey: requireEnv(env, 'RELAYER_API_KEY'),
  };

  if (preset === 'custom') {
    config.custom = {
      networkUrl: requireEnv(env, 'NETWORK_RPC_URL'),
      relayerUrl: requireEnv(env, 'RELAYER_URL'),
      chainId: config.chainId,
      gatewayChainId: Number(requireEnv(env, 'GATEWAY_CHAIN_ID')),
      aclContractAddress: requireEnv(env, 'ACL_CONTRACT_ADDRESS'),
      kmsContractAddress: requireEnv(env, 'KMS_CONTRACT_ADDRESS'),
      inputVerifierContractAddress: requireEnv(env, 'INPUT_VERIFIER_CONTRACT_ADDRESS'),
      verifyingContractAddressDecryption: requireEnv(env, 'VERIFYING_CONTRACT_ADDRESS_DECRYPTION'),
      verifyingContractAddressInputVerification: requireEnv(
        env, 'VERIFYING_CONTRACT_ADDRESS_INPUT_VERIFICATION'
      ),
    };
  }

  const operatorKey = env.OPERATOR_DECRYPT_PRIVATE_KEY;
  if (operatorKey && operatorKey.trim() !== '') {
    if (!/^0x[0-9a-fA-F]{64}$/.test(operatorKey)) {
      throw new Error('OPERATOR_DECRYPT_PRIVATE_KEY must be a 0x-prefixed 32-byte hex private key');
    }
    config.operatorDecryptPrivateKey = operatorKey as `0x${string}`;
  }

  return config;
}
