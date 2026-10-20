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
import { createKekProvider } from './kekProvider.js';
import { fromHex } from './hex.js';

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
   *
   * Resolved from ONE of two env inputs :
   *  - `OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED` + `RELAYER_KEK_MASTER_KEY` (preferred): the raw key
   *    is envelope-encrypted at rest (AES-256-GCM via {@link EnvVarKekProvider}, the exact scheme
   *    the backend's own `wallet.internal.EnvVarKekProvider` uses) and unwrapped only here, once,
   *    at process boot — mirroring this codebase's wallet-module KEK/DEK discipline instead of
   *    keeping the plaintext key sitting in the container's environment for its whole lifetime.
   *    Run `npm run wrap-operator-key -- <rawKeyHex>` (reads `RELAYER_KEK_MASTER_KEY` from env) to
   *    produce the wrapped value once, offline, when provisioning or rotating the operator key.
   *  - `OPERATOR_DECRYPT_PRIVATE_KEY` (legacy, plaintext): still accepted for backward
   *    compatibility, but logs a startup warning — this path should be migrated to the wrapped one.
   */
  operatorDecryptPrivateKey?: `0x${string}`;
  /** How long the operator-decrypt EIP-712 authorization is valid for, in days. */
  operatorDecryptDurationDays: number;
  /**
   * Shared-secret bearer token every `/v1/*` request must present . Required
   * exactly like `JWT_DEV_SECRET` is on the backend side — this sidecar's decrypt capability is
   * at least as sensitive as the backend's own JWT-gated endpoints, so it gets the same "must
   * never be empty" treatment rather than an optional, defaults-to-disabled toggle that would
   * leave the gap open wherever an operator forgets to opt in.
   */
  apiKey: string;
}

/**
 * `RELAYER_PRODUCTION_MODE=true` is the dedicated switch for the hardening below. It is NOT keyed
 * off NODE_ENV: the Dockerfile bakes NODE_ENV=production into every image, so the Compose demo
 * relayer would otherwise already run "in production".
 */
function isTrue(env: NodeJS.ProcessEnv, name: string): boolean {
  return (env[name] ?? '').trim().toLowerCase() === 'true';
}

/** The shortest `RELAYER_API_KEY` production mode accepts (bytes), as for the backend's `JWT_DEV_SECRET`. */
const MIN_PRODUCTION_API_KEY_BYTES = 32;

/**
 * An integer from the environment within [min, max]. `Number('abc')` is NaN and `Number('')` is 0; a NaN
 * port or an EIP-712 `durationDays` would only fail much later, far from the typo that caused it.
 */
function intEnv(env: NodeJS.ProcessEnv, name: string, fallback: number, min: number, max: number): number {
  const raw = (env[name] ?? '').trim();
  const value = raw === '' ? fallback : Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}, got: ${raw === '' ? fallback : raw}`);
  }
  return value;
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

  const productionMode = isTrue(env, 'RELAYER_PRODUCTION_MODE');
  if (productionMode && (env.OPERATOR_DECRYPT_DURATION_DAYS ?? '').trim() === '') {
    // The viewer decryption authorisation is signed once and valid this long; the default (365)
    // is too long to adopt silently, so production mode makes the choice explicit (T7-07).
    throw new Error(
      'RELAYER_PRODUCTION_MODE=true requires OPERATOR_DECRYPT_DURATION_DAYS to be set explicitly'
    );
  }

  const config: RelayerConfig = {
    port: intEnv(env, 'PORT', 3001, 1, 65535),
    chainId: intEnv(env, 'CHAIN_ID', 11155111, 1, Number.MAX_SAFE_INTEGER),
    preset: preset as 'sepolia' | 'custom',
    sepoliaNetworkUrlOverride: env.SEPOLIA_NETWORK_RPC_URL,
    // The KMS accepts a decryption authorisation of at most 365 days.
    operatorDecryptDurationDays: intEnv(env, 'OPERATOR_DECRYPT_DURATION_DAYS', 365, 1, 365),
    apiKey: requireEnv(env, 'RELAYER_API_KEY'),
  };

  if (preset === 'custom') {
    config.custom = {
      networkUrl: requireEnv(env, 'NETWORK_RPC_URL'),
      relayerUrl: requireEnv(env, 'RELAYER_URL'),
      chainId: config.chainId,
      gatewayChainId: intEnv(env, 'GATEWAY_CHAIN_ID', Number.NaN, 1, Number.MAX_SAFE_INTEGER),
      aclContractAddress: requireEnv(env, 'ACL_CONTRACT_ADDRESS'),
      kmsContractAddress: requireEnv(env, 'KMS_CONTRACT_ADDRESS'),
      inputVerifierContractAddress: requireEnv(env, 'INPUT_VERIFIER_CONTRACT_ADDRESS'),
      verifyingContractAddressDecryption: requireEnv(env, 'VERIFYING_CONTRACT_ADDRESS_DECRYPTION'),
      verifyingContractAddressInputVerification: requireEnv(
        env, 'VERIFYING_CONTRACT_ADDRESS_INPUT_VERIFICATION'
      ),
    };
  }

  if (productionMode && Buffer.byteLength(config.apiKey, 'utf8') < MIN_PRODUCTION_API_KEY_BYTES) {
    throw new Error(
      `RELAYER_PRODUCTION_MODE=true requires RELAYER_API_KEY to be at least ${MIN_PRODUCTION_API_KEY_BYTES} bytes ` +
      '(it is the only credential in front of the decryption capability)'
    );
  }

  config.operatorDecryptPrivateKey = resolveOperatorDecryptPrivateKey(env, productionMode);

  return config;
}

function resolveOperatorDecryptPrivateKey(
  env: NodeJS.ProcessEnv, productionMode: boolean
): `0x${string}` | undefined {
  const wrapped = env.OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED;
  const plaintext = env.OPERATOR_DECRYPT_PRIVATE_KEY;
  const providerName = (env.RELAYER_KEK_PROVIDER ?? 'ENV_VAR').trim() || 'ENV_VAR';
  const allowEnvKek = isTrue(env, 'RELAYER_ALLOW_ENV_KEK');

  // Production mode: the KEK master key (ENV_VAR provider) or the plaintext key would sit in the
  // same environment as the wrapped key / the key itself, so wrapping buys nothing. Refuse both
  // unless the operator explicitly acknowledges it; a KMS provider is the real fix (T7-05).
  const usesEnvSecret = (wrapped && wrapped.trim() !== '' && providerName === 'ENV_VAR')
    || (!(wrapped && wrapped.trim() !== '') && plaintext && plaintext.trim() !== '');
  if (productionMode && usesEnvSecret) {
    if (!allowEnvKek) {
      throw new Error(
        'RELAYER_PRODUCTION_MODE=true refuses the ENV_VAR KEK provider and the plaintext ' +
        'OPERATOR_DECRYPT_PRIVATE_KEY (the secret sits beside the key it protects). Use a KMS ' +
        'provider (RELAYER_KEK_PROVIDER=AWS_KMS|GCP_KMS|AZURE_KEY_VAULT) or acknowledge the ' +
        'exception with RELAYER_ALLOW_ENV_KEK=true.'
      );
    }
    // eslint-disable-next-line no-console
    console.warn(
      'RELAYER_ALLOW_ENV_KEK=true: running production mode with an environment-held KEK / plaintext ' +
      'operator decrypt key. This is an acknowledged exception, not a recommended setup.'
    );
  }

  if (wrapped && wrapped.trim() !== '') {
    const provider = createKekProvider(providerName, env);
    let unwrapped: Buffer;
    try {
      unwrapped = provider.unwrap(Buffer.from(fromHex(wrapped)));
    } catch (e) {
      throw new Error(
        'Failed to unwrap OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED — check RELAYER_KEK_MASTER_KEY ' +
        'matches the key it was wrapped with.', { cause: e }
      );
    }
    const rawKey = `0x${unwrapped.toString('hex')}`;
    if (!/^0x[0-9a-fA-F]{64}$/.test(rawKey)) {
      throw new Error('OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED did not unwrap to a 32-byte hex private key');
    }
    return rawKey as `0x${string}`;
  }

  if (plaintext && plaintext.trim() !== '') {
    if (!/^0x[0-9a-fA-F]{64}$/.test(plaintext)) {
      throw new Error('OPERATOR_DECRYPT_PRIVATE_KEY must be a 0x-prefixed 32-byte hex private key');
    }
    // eslint-disable-next-line no-console
    console.warn(
      'OPERATOR_DECRYPT_PRIVATE_KEY is set as plaintext — this key sits unencrypted in the ' +
      'container environment for the process lifetime. Migrate to OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED ' +
      '+ RELAYER_KEK_MASTER_KEY (see src/config.ts / npm run wrap-operator-key).'
    );
    return plaintext as `0x${string}`;
  }

  return undefined;
}
