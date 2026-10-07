import { Injectable, computed, signal } from '@angular/core';
import {
  type Address,
  type Chain,
  type Hash,
  type PublicClient,
  type TypedDataDomain,
  type WalletClient,
  createPublicClient,
  createWalletClient,
  custom,
  defineChain,
} from 'viem';
import { erc20Abi } from './abi/repo-market.abi';

/** Shared viem layer for browser-wallet connection, signing, and direct transactions. */
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

  private pinnedChain: Chain | null = null;
  private walletClient: WalletClient | null = null;
  private publicClient: PublicClient | null = null;
  private detachListeners: (() => void) | null = null;

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
      const probe = createWalletClient({ transport: custom(injected) });
      const [address] = await probe.requestAddresses();
      if (!address) {
        throw new Error('Wallet returned no accounts.');
      }
      const chainId = await probe.getChainId();
      this.pinChain(injected, chainId);
      this._address.set(address);
      this._chainId.set(chainId);
      this.attachListeners(injected);
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
    this.detachListeners?.();
    this.detachListeners = null;
    this.walletClient = null;
    this.publicClient = null;
    this.pinnedChain = null;
    this._address.set(null);
    this._chainId.set(null);
  }

  /**
   * Keeps the signals honest when the user switches account or network inside the wallet. Without this the
   * app would keep acting as the account / chain it saw at connect time.
   */
  private attachListeners(provider: unknown): void {
    this.detachListeners?.();
    const p = provider as {
      on?: (event: string, handler: (arg: unknown) => void) => void;
      removeListener?: (event: string, handler: (arg: unknown) => void) => void;
    };
    if (typeof p.on !== 'function') return;
    const onAccounts = (accounts: unknown) => {
      const next = Array.isArray(accounts) && typeof accounts[0] === 'string' ? (accounts[0] as Address) : null;
      this._address.set(next);
      if (next === null) {
        this.walletClient = null;
        this.publicClient = null;
        this._chainId.set(null);
      }
    };
    const onChain = (chainId: unknown) => {
      const parsed = typeof chainId === 'string' ? Number.parseInt(chainId, chainId.startsWith('0x') ? 16 : 10) : Number(chainId);
      this._chainId.set(Number.isFinite(parsed) ? parsed : null);
    };
    p.on('accountsChanged', onAccounts);
    p.on('chainChanged', onChain);
    this.detachListeners = () => {
      p.removeListener?.('accountsChanged', onAccounts);
      p.removeListener?.('chainChanged', onChain);
    };
  }

  /**
   * Fails closed unless the wallet is on `expectedChainId`: asks the wallet to switch, re-reads the chain id
   * and throws a clear message when it is still elsewhere. An unknown expected chain (older cached market
   * response) is refused rather than guessed. Call at the top of every on-chain action (8A-02).
   */
  async ensureChain(expectedChainId: number | null | undefined, chainName?: string | null): Promise<void> {
    if (expectedChainId == null || !Number.isFinite(expectedChainId)) {
      throw new Error('The network of this market is unknown. Reload the page and try again.');
    }
    const client = this.requireWalletClient();
    const label = `${chainName ?? 'the required network'} (id ${expectedChainId})`;
    let current = await client.getChainId();
    if (current !== expectedChainId) {
      try {
        await client.switchChain({ id: expectedChainId });
      } catch {
        /* the wallet refused or does not know the chain; re-check below */
      }
      current = await client.getChainId();
    }
    this._chainId.set(current);
    if (current !== expectedChainId) {
      throw new Error(`Switch your wallet to ${label} to continue.`);
    }
    // From here on viem re-checks the wallet's chain against this one at send time, so a switch between
    // this check and the signature prompt fails closed instead of sending on the wrong network.
    this.pinChain(this.injectedProvider as Parameters<typeof custom>[0], expectedChainId);
  }

  /** The chain the clients are pinned to (the connected chain until {@link ensureChain} pins a market's). */
  get pinnedChainId(): number | null {
    return this.pinnedChain?.id ?? null;
  }

  /** The viem chain object the clients are pinned to; other viem clients created for a send must use it too. */
  get pinnedViemChain(): Chain | undefined {
    return this.pinnedChain ?? undefined;
  }

  /**
   * (Re)creates the viem clients WITH a `chain`: without one viem never compares the wallet's chain with the
   * intended one when sending. Deliberately NOT done from `chainChanged`: a wallet that moves away from the
   * pinned chain must make sends fail until `ensureChain` runs again, not silently retarget the clients.
   */
  private pinChain(injected: Parameters<typeof custom>[0], chainId: number): void {
    const chain = defineChain({
      id: chainId,
      name: `Chain ${chainId}`,
      nativeCurrency: { name: 'Native token', symbol: 'ETH', decimals: 18 },
      rpcUrls: { default: { http: [] } },
    });
    this.pinnedChain = chain;
    this.walletClient = createWalletClient({ chain, transport: custom(injected) });
    this.publicClient = createPublicClient({ chain, transport: custom(injected) });
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

  /**
   * Makes sure `spender` may pull exactly `amount` of `token` from the connected wallet, without ever
   * granting an unlimited allowance. A non-zero allowance below `amount` is first reset to 0 (tokens such
   * as USDT refuse to move a non-zero allowance to another non-zero value, and a stale allowance must not
   * silently linger), then set to `amount`. Returns the approval transaction hashes it sent (empty when the
   * allowance already covers `amount`). Each approval is awaited, and a revert throws.
   */
  async ensureAllowance(token: Address, spender: Address, amount: bigint): Promise<Hash[]> {
    const owner = this.requireAddress();
    if (amount <= 0n) return [];
    const current = await this.readContract<bigint>({
      address: token, abi: erc20Abi, functionName: 'allowance', args: [owner, spender],
    });
    if (current >= amount) return [];
    const sent: Hash[] = [];
    if (current > 0n) {
      const reset = await this.writeContract({ address: token, abi: erc20Abi, functionName: 'approve', args: [spender, 0n] });
      await this.waitForTransaction(reset);
      sent.push(reset);
    }
    const approval = await this.writeContract({ address: token, abi: erc20Abi, functionName: 'approve', args: [spender, amount] });
    await this.waitForTransaction(approval);
    sent.push(approval);
    return sent;
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

  /**
   * Plain-language wording for custom contract errors the app can act on. viem decodes them into
   * `ContractFunctionRevertedError.data` (somewhere in the `cause` chain) only when the error is in
   * the ABI passed to the call; the default `shortMessage` is a raw signature otherwise.
   */
  private isChainMismatch(err: unknown): boolean {
    for (let e: unknown = err, depth = 0; e && typeof e === 'object' && depth < 10; depth++) {
      if ((e as { name?: string }).name === 'ChainMismatchError') return true;
      e = (e as { cause?: unknown }).cause;
    }
    return false;
  }

  private knownRevertMessage(err: unknown): string | null {
    for (let e: unknown = err, depth = 0; e && typeof e === 'object' && depth < 10; depth++) {
      const data = (e as { data?: { errorName?: string; args?: readonly unknown[] } }).data;
      if (data?.errorName === 'LiquidationExceedsMaxRepay' && data.args?.length === 2) {
        const [required, max] = data.args as [bigint, bigint];
        return (
          `Liquidating this position costs at least ${required} (loan-token base units), above the ` +
          `maximum repay amount of ${max} that was allowed. Raise the maximum to at least ${required} and try again.`
        );
      }
      e = (e as { cause?: unknown }).cause;
    }
    return null;
  }

  private extractMessage(err: unknown, fallback: string): string {
    const known = this.knownRevertMessage(err);
    if (known) return known;
    if (this.isChainMismatch(err)) {
      return 'Your wallet is on a different network than this action needs. Switch the network and try again.';
    }
    if (err && typeof err === 'object') {
      const withShort = err as { shortMessage?: string; message?: string };
      return withShort.shortMessage ?? withShort.message ?? fallback;
    }
    return fallback;
  }
}
