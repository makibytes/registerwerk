import { Injectable, computed, signal } from '@angular/core';
import {
  type Address,
  type Hash,
  type PublicClient,
  type TypedDataDomain,
  type WalletClient,
  createPublicClient,
  createWalletClient,
  custom,
} from 'viem';

/**
 * Thin EIP-1193 wallet layer built on viem — the piece `docs/platform/account-abstraction.md`
 * flags as "not yet built": before this, `frontend-customer` had exactly one
 * `window.ethereum.request(...)` call site (`company-admin/org-identity`), duplicated ad hoc
 * wherever a component needed a signature. Every wallet interaction in the app — org-identity
 * binding, the repo/lending borrow stepper, supply/withdraw — now goes through this single
 * service instead.
 *
 * Scope: this covers the plain injected-EOA path (MetaMask, Rabby, any EIP-1193 provider),
 * which is what a trader uses today. The EIP-7702 smart-account upgrade + ERC-4337 sponsored
 * transactions live in {@link SponsoredTxService} as a separate, optional layer on top — see
 * that service for why it's scoped differently (it needs a configured bundler endpoint, a real
 * external dependency this environment doesn't ship by default). Passkey/`EwpgPasskeyAccount`
 * support is intentionally not implemented here: correctly encoding a WebAuthn assertion into
 * the signature format `SignerWebAuthn` expects is substantial, standalone work — see the
 * "Roadmap" section of the borrow stepper's UI copy rather than a faked implementation.
 */
@Injectable({ providedIn: 'root' })
export class WalletService {
  private readonly _address = signal<Address | null>(null);
  private readonly _chainId = signal<number | null>(null);
  private readonly _connecting = signal(false);
  private readonly _error = signal<string | null>(null);

  readonly address = this._address.asReadonly();
  readonly chainId = this._chainId.asReadonly();
  readonly connecting = this._connecting.asReadonly();
  readonly error = this._error.asReadonly();
  readonly isConnected = computed(() => this._address() !== null);

  private walletClient: WalletClient | null = null;
  private publicClient: PublicClient | null = null;

  /** True if an EIP-1193 provider (e.g. `window.ethereum`) is present in this browser. */
  get isAvailable(): boolean {
    return typeof window !== 'undefined' && !!(window as unknown as { ethereum?: unknown }).ethereum;
  }

  /** The raw injected provider — exposed only for {@link SponsoredTxService}'s 7702 signing path. */
  get injectedProvider(): unknown {
    return (window as unknown as { ethereum?: unknown }).ethereum;
  }

  async connect(): Promise<Address> {
    if (!this.isAvailable) {
      throw new Error('No browser wallet detected — install MetaMask or another EIP-1193 wallet.');
    }
    this._connecting.set(true);
    this._error.set(null);
    try {
      const injected = this.injectedProvider as Parameters<typeof custom>[0];
      const walletClient = createWalletClient({ transport: custom(injected) });
      const [address] = await walletClient.requestAddresses();
      if (!address) {
        throw new Error('Wallet returned no accounts.');
      }
      const chainId = await walletClient.getChainId();
      this.walletClient = walletClient;
      this.publicClient = createPublicClient({ transport: custom(injected) });
      this._address.set(address);
      this._chainId.set(chainId);
      return address;
    } catch (err: unknown) {
      const message = this.extractMessage(err, 'Wallet connection failed.');
      this._error.set(message);
      throw new Error(message, { cause: err });
    } finally {
      this._connecting.set(false);
    }
  }

  disconnect(): void {
    this.walletClient = null;
    this.publicClient = null;
    this._address.set(null);
    this._chainId.set(null);
  }

  /** Signs a plain message (`personal_sign`) — e.g. the org-identity wallet-binding challenge. */
  async signMessage(message: string): Promise<Hash> {
    const client = this.requireWalletClient();
    const account = this.requireAddress();
    try {
      return await client.signMessage({ account, message });
    } catch (err: unknown) {
      throw new Error(this.extractMessage(err, 'Signing failed or was rejected.'), { cause: err });
    }
  }

  /**
   * Signs an EIP-712 typed-data payload — used by {@code FheClientService} to authorize a Zama
   * Relayer `userDecrypt` request (the KMS's `UserDecryptRequestVerification` payload) with the
   * connected wallet's own signature, and available for any other typed-data signing need.
   * `types` must NOT include `EIP712Domain` itself — viem derives that automatically from
   * `domain`, the same convention `zama-relayer/src/operatorSigner.ts` uses server-side.
   */
  async signTypedData(params: {
    domain: TypedDataDomain;
    types: Record<string, readonly { name: string; type: string }[]>;
    primaryType: string;
    message: Record<string, unknown>;
  }): Promise<Hash> {
    const client = this.requireWalletClient();
    const account = this.requireAddress();
    try {
      return await client.signTypedData({
        account,
        domain: params.domain,
        types: params.types,
        primaryType: params.primaryType,
        message: params.message,
      } as Parameters<WalletClient['signTypedData']>[0]);
    } catch (err: unknown) {
      throw new Error(this.extractMessage(err, 'Typed-data signing failed or was rejected.'), { cause: err });
    }
  }

  async readContract<T>(params: {
    address: Address;
    abi: readonly unknown[];
    functionName: string;
    args?: readonly unknown[];
  }): Promise<T> {
    const client = this.requirePublicClient();
    return client.readContract(params as Parameters<PublicClient['readContract']>[0]) as Promise<T>;
  }

  /**
   * Simulates then submits a state-changing call from the connected wallet — the standard viem
   * "simulate, then write with the validated request" pattern, so a revert is caught before a
   * wallet signature prompt ever appears.
   */
  async writeContract(params: {
    address: Address;
    abi: readonly unknown[];
    functionName: string;
    args?: readonly unknown[];
  }): Promise<Hash> {
    const walletClient = this.requireWalletClient();
    const publicClient = this.requirePublicClient();
    const account = this.requireAddress();
    try {
      const { request } = await publicClient.simulateContract({ ...params, account } as Parameters<
        PublicClient['simulateContract']
      >[0]);
      return await walletClient.writeContract(request as Parameters<WalletClient['writeContract']>[0]);
    } catch (err: unknown) {
      throw new Error(this.extractMessage(err, 'Transaction failed or was rejected.'), { cause: err });
    }
  }

  async waitForTransaction(hash: Hash) {
    const client = this.requirePublicClient();
    const receipt = await client.waitForTransactionReceipt({ hash });
    // waitForTransactionReceipt only throws on RPC-level failures — an on-chain revert still
    // resolves normally with status: 'reverted', which every call site's try/catch would
    // otherwise miss entirely and report as a success.
    if (receipt.status !== 'success') {
      throw new Error(`Transaction reverted on-chain (${hash}).`);
    }
    return receipt;
  }

  private requireWalletClient(): WalletClient {
    if (!this.walletClient) throw new Error('Wallet not connected.');
    return this.walletClient;
  }

  private requirePublicClient(): PublicClient {
    if (!this.publicClient) throw new Error('Wallet not connected.');
    return this.publicClient;
  }

  private requireAddress(): Address {
    const address = this._address();
    if (!address) throw new Error('Wallet not connected.');
    return address;
  }

  private extractMessage(err: unknown, fallback: string): string {
    if (err && typeof err === 'object') {
      const withShort = err as { shortMessage?: string; message?: string };
      return withShort.shortMessage ?? withShort.message ?? fallback;
    }
    return fallback;
  }
}
