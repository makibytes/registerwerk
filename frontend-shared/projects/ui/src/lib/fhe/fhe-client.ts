import type { Address, Hash, TypedDataDomain } from 'viem';
import type { FhevmInstance } from '@zama-fhe/relayer-sdk/web';

/*
 * Browser-side Zama Relayer SDK integration, shared by the operator and the customer portal: client-side
 * encryption of an amount for a confidential contract call, and reveal of ONE ciphertext handle by having the
 * connected wallet sign the KMS's `UserDecryptRequestVerification` EIP-712 authorisation. The backend and the
 * `zama-relayer` sidecar are never in this path; they serve only the headless operator/issuer flows.
 *
 * No Angular DI in here (each app wraps this in a one-line @Injectable over its own WalletService), and the SDK
 * (WASM and workers) is loaded on first use, so nothing that imports `@registerwerk/ui` pays for it.
 *
 * Scoped to Ethereum Sepolia (chainId 11155111), the only network with published Zama fhEVM addresses
 * (docs/blockchains/confidential-evm.md). Other networks need their real addresses added here, not a guess.
 */

/** What the client needs from an app's wallet service. */
export interface FheWallet {
  /** The raw EIP-1193 provider: the SDK's `network`, so the browser never needs the registry's own RPC endpoint. */
  readonly injectedProvider: unknown;
  address(): Address | null;
  signTypedData(params: {
    domain: TypedDataDomain;
    types: Record<string, readonly { name: string; type: string }[]>;
    primaryType: string;
    message: Record<string, unknown>;
  }): Promise<Hash>;
}

/** The slice of `@zama-fhe/relayer-sdk/web` this client calls (injectable for tests). */
export type FheSdk = Pick<typeof import('@zama-fhe/relayer-sdk/web'), 'initSDK' | 'createInstance' | 'SepoliaConfig'>;

export const FHE_SUPPORTED_CHAIN_ID = 11155111;

/**
 * Validity of the decrypt authorisation a user signs for one reveal. The keypair is generated for that call and
 * dropped afterwards, so the signature is useless to anyone later; the shortest window the KMS accepts (1 to 365
 * days) is all it needs.
 */
const DECRYPT_AUTHORISATION_DAYS = 1;

const loadRelayerSdk = (): Promise<FheSdk> => import('@zama-fhe/relayer-sdk/web');

export class FheClient {
  private instance: Promise<FhevmInstance> | null = null;

  constructor(
    private readonly wallet: FheWallet,
    private readonly loadSdk: () => Promise<FheSdk> = loadRelayerSdk,
  ) {}

  /**
   * The SDK instance, created once and shared by concurrent callers. A failed creation (no wallet connected yet,
   * SDK or network error) is forgotten, so the next call tries again instead of replaying the first error until
   * the page is reloaded. The chain is checked on every call, not only on the one that created the instance.
   */
  private async instanceFor(chainId: number): Promise<FhevmInstance> {
    if (chainId !== FHE_SUPPORTED_CHAIN_ID) {
      throw new Error(
        `Confidential-token support is only configured for Ethereum Sepolia (chainId=${FHE_SUPPORTED_CHAIN_ID}) in ` +
        `this build - got chainId=${chainId}. See docs/blockchains/confidential-evm.md.`,
      );
    }
    this.instance ??= this.createInstance().catch((err: unknown) => {
      this.instance = null;
      throw err;
    });
    return this.instance;
  }

  private async createInstance(): Promise<FhevmInstance> {
    const provider = this.wallet.injectedProvider;
    if (!provider) {
      throw new Error('Connect a wallet before using confidential-token features.');
    }
    const sdk = await this.loadSdk();
    await sdk.initSDK();
    return sdk.createInstance({ ...sdk.SepoliaConfig, network: provider as never });
  }

  /**
   * Encrypts `value` client-side for a confidential contract call (for example the amount of a
   * `confidentialTransfer`): the plaintext never leaves the browser except as ciphertext plus a ZK input proof.
   */
  async encrypt64(
    contractAddress: Address,
    userAddress: Address,
    value: bigint,
    chainId: number,
  ): Promise<{ handle: `0x${string}`; inputProof: `0x${string}` }> {
    const instance = await this.instanceFor(chainId);
    const input = instance.createEncryptedInput(contractAddress, userAddress);
    input.add64(value);
    const encrypted = await input.encrypt();
    return { handle: toHex(encrypted.handles[0]), inputProof: toHex(encrypted.inputProof) };
  }

  /**
   * Reveals ONE ciphertext handle. Succeeds only if the connected address has been granted decrypt rights on it
   * (the holder on their own balance, or a registered viewer - see `ConfidentialERC20`'s viewer-ACL note); any
   * other wallet gets a real KMS rejection, not a fabricated value.
   */
  async userDecrypt(handle: `0x${string}`, contractAddress: Address, chainId: number): Promise<bigint> {
    const instance = await this.instanceFor(chainId);
    const userAddress = this.wallet.address();
    if (!userAddress) {
      throw new Error('Wallet not connected.');
    }

    const keypair = instance.generateKeypair();
    const startTimestamp = Math.floor(Date.now() / 1000);
    const eip712 = instance.createEIP712(
      keypair.publicKey, [contractAddress], startTimestamp, DECRYPT_AUTHORISATION_DAYS);

    const signature = await this.wallet.signTypedData({
      domain: {
        name: eip712.domain.name,
        version: eip712.domain.version,
        chainId: Number(eip712.domain.chainId),
        verifyingContract: eip712.domain.verifyingContract as Address,
      },
      // Only the non-domain type definitions are passed: viem derives `EIP712Domain` itself from `domain`
      // above, the same convention `zama-relayer/src/operatorSigner.ts` uses.
      types: { UserDecryptRequestVerification: eip712.types.UserDecryptRequestVerification },
      primaryType: 'UserDecryptRequestVerification',
      message: {
        publicKey: eip712.message.publicKey,
        contractAddresses: eip712.message.contractAddresses,
        // The SDK's message carries these as decimal strings (the on-chain uint256 ABI type), but viem's
        // typed-data encoder requires bigint for uint256 fields.
        startTimestamp: BigInt(eip712.message.startTimestamp),
        durationDays: BigInt(eip712.message.durationDays),
        extraData: eip712.message.extraData,
      },
    });

    const results = await instance.userDecrypt(
      [{ handle, contractAddress }],
      keypair.privateKey,
      keypair.publicKey,
      signature,
      [contractAddress],
      userAddress,
      startTimestamp,
      DECRYPT_AUTHORISATION_DAYS,
    );
    const values = Object.values(results);
    if (values.length !== 1 || typeof values[0] !== 'bigint') {
      throw new Error('Unexpected decrypt result shape from the Zama Relayer.');
    }
    return values[0];
  }
}

function toHex(bytes: Uint8Array): `0x${string}` {
  let hex = '0x';
  for (const b of bytes) {
    hex += b.toString(16).padStart(2, '0');
  }
  return hex as `0x${string}`;
}
