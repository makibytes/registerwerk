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
 * Thin EIP-1193 wallet layer built on viem — previously `frontend-operator` had NO wallet
 * connection capability at all (every on-chain interaction was backend-signed via the registry's
 * own operator wallet keystore). This exists specifically for the operator/auditor confidential
 * balance reveal-and-reconcile panel (`ConfidentialViewerPanelComponent`): a viewer address
 * registered via `TokenAdminService.confidentialAddViewer` needs its OWN wallet connected in the
 * browser to sign the Zama Relayer's `userDecrypt` EIP-712 authorization — the backend's operator
 * key is a SEPARATE, decrypt-only key (see `zama-relayer`'s `OPERATOR_DECRYPT_PRIVATE_KEY`), not
 * something the operator portal's browser session has access to. Mirrors
 * `frontend-customer/src/app/core/wallet/wallet.service.ts` — see that file for the fuller
 * rationale; this port intentionally keeps the same shape so `FheClientService` is identical in
 * both apps.
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

  /** The raw injected provider — exposed for {@link FheClientService}'s `createInstance` call. */
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

  /**
   * Signs an EIP-712 typed-data payload — used by {@link FheClientService} to authorize a Zama
   * Relayer `userDecrypt` request with the connected viewer wallet's own signature. `types` must
   * NOT include `EIP712Domain` itself — viem derives that automatically from `domain`.
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
