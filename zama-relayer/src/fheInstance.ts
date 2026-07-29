import { createInstance, SepoliaConfig, type FhevmInstance, type FhevmInstanceConfig } from '@zama-fhe/relayer-sdk/node';
import type { RelayerConfig } from './config';

/**
 * Lazily creates (and caches) the Zama `FhevmInstance` this whole sidecar wraps.
 *
 * `createInstance` does real network I/O at boot (fetching the FHE public key/CRS params from
 * the relayer) — a hard failure here means the sidecar cannot serve ANY request, so callers
 * should let it throw and crash-loop rather than silently degrade to a broken instance.
 */
let cachedInstance: FhevmInstance | null = null;

export async function getFheInstance(config: RelayerConfig): Promise<FhevmInstance> {
  if (cachedInstance) {
    return cachedInstance;
  }
  const instanceConfig = buildInstanceConfig(config);
  cachedInstance = await createInstance(instanceConfig);
  return cachedInstance;
}

/** Exposed for tests — allows resetting the module-level cache between test cases. */
export function _resetFheInstanceCacheForTests(): void {
  cachedInstance = null;
}

function buildInstanceConfig(config: RelayerConfig): FhevmInstanceConfig {
  if (config.preset === 'sepolia') {
    // SepoliaConfig omits `network` deliberately (see FhevmInstanceConfig's type) — the SDK
    // does not bundle a default RPC endpoint for the node/server entry point, so one must always
    // be supplied. (An earlier SDK generation's bundled default, BlastAPI's free Sepolia RPC, was
    // shut down — see https://community.zama.org/t/error-creating-relayer-instance-from-nodejs/3827
    // — which is the origin of "the SDK needs a network override" folklore; in the currently
    // vendored SDK version there simply is no default to override, `network` is mandatory.)
    if (!config.sepoliaNetworkUrlOverride) {
      throw new Error(
        'ZAMA_CONFIG_PRESET=sepolia requires SEPOLIA_NETWORK_RPC_URL (an Ethereum Sepolia RPC ' +
        'endpoint) — the SDK does not bundle a default one for Node/server use.'
      );
    }
    return {
      ...SepoliaConfig,
      network: config.sepoliaNetworkUrlOverride,
    };
  }

  const custom = config.custom;
  if (!custom) {
    throw new Error('ZAMA_CONFIG_PRESET=custom requires the full custom.* configuration block.');
  }
  // Custom/self-hosted networks (Ethereum/Base mainnet once Zama publishes final addresses,
  // or T-REX Chain once it publishes its own FHEVM infrastructure — see the confidential-token
  // docs) must supply every field SepoliaConfig otherwise provides as a preset. There is no
  // "MainnetConfig" preset usable here yet because Zama's own mainnet addresses were still being
  // finalised at the time this sidecar was written.
  return {
    aclContractAddress: custom.aclContractAddress,
    kmsContractAddress: custom.kmsContractAddress,
    inputVerifierContractAddress: custom.inputVerifierContractAddress,
    verifyingContractAddressDecryption: custom.verifyingContractAddressDecryption,
    verifyingContractAddressInputVerification: custom.verifyingContractAddressInputVerification,
    gatewayChainId: custom.gatewayChainId,
    relayerUrl: custom.relayerUrl,
    network: custom.networkUrl,
    chainId: custom.chainId,
  };
}
