import { Injectable, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  type Address,
  type Hash,
  type Hex,
  type PrivateKeyAccount,
  concat,
  createPublicClient,
  createWalletClient,
  custom,
  http,
  numberToHex,
} from 'viem';
import { signAuthorization } from 'viem/actions';
import { createBundlerClient, toSimple7702SmartAccount } from 'viem/account-abstraction';
import { WalletService } from './wallet.service';
import { GasSponsorshipApiService } from '../api/gas-sponsorship.service';
import { environment } from '../../../environments/environment';

/** Paymaster gas limits signed into every voucher (postOp must stay >= EwpgPaymaster.MIN_POST_OP_GAS_LIMIT). */
const PAYMASTER_VERIFICATION_GAS = 150_000n;
const PAYMASTER_POST_OP_GAS = 80_000n;
/** Fallbacks when the bundler cannot estimate an unsponsored operation. */
const DEFAULT_CALL_GAS = 300_000n;
const DEFAULT_VERIFICATION_GAS = 150_000n;
const DEFAULT_PRE_VERIFICATION_GAS = 60_000n;

/** The backend refused a voucher, or no sponsorship is configured: pay gas yourself. */
export class SponsorshipUnavailableError extends Error {
  constructor(reason: string) {
    super(`Gas sponsorship is not available for this action: ${reason}`);
    this.name = 'SponsorshipUnavailableError';
  }
}

export interface SendResult {
  hash: Hash;
  sponsored: boolean;
  /** Why sponsorship was not used — show it to the user; never fall back silently. */
  fallbackReason?: string;
}

/**
 * Sponsored (gasless) transactions via ERC-4337 against `EwpgPaymaster`
 * (contracts/src/ecosystem/EwpgPaymaster.sol) — see `docs/platform/account-abstraction.md`.
 *
 * `EwpgPaymaster` is a *verifying* paymaster: every UserOperation needs a voucher signed by the
 * Registerwerk backend (`POST /gas-sponsorship/vouchers`) over that exact operation — sender,
 * nonce, callData, gas limits and fees — bound to chain, paymaster, policy and a short validity
 * window. The backend decides whether the deployment's policy is active, the wallet belongs to
 * the caller's organisation, the calls stay within the policy's scope and the monthly cap
 * allows it. Gas limits are fixed before the voucher is requested (the voucher signs them), so
 * the voucher returned from the stub hook is final and viem does not re-estimate afterwards.
 *
 * This optional layer requires:
 *
 * 1. An ERC-4337 bundler endpoint (`environment.bundlerUrl`).
 * 2. The connected EOA delegating its code to viem's `Simple7702Account` via EIP-7702
 *    (`signAuthorization`) — not `EwpgPasskeyAccount`, which is not a 7702 delegate.
 *
 * ### Implementation note: why {@link PrivateKeyAccount} is cast, not literal
 * viem's `toSimple7702SmartAccount` types its `owner` parameter as `PrivateKeyAccount`, but its
 * implementation calls `owner.address`, `owner.signMessage(...)`, and `owner.signTypedData(...)`
 * — exactly the three members a JSON-RPC account provides through the connected `WalletClient`.
 * The cast is a structural-typing workaround; this class does not hold a private key.
 */
@Injectable({ providedIn: 'root' })
export class SponsoredTxService {
  private readonly wallet = inject(WalletService);
  private readonly api = inject(GasSponsorshipApiService);

  get isSupported(): boolean {
    return !!environment.bundlerUrl && this.wallet.isAvailable;
  }

  /**
   * Sends `callData` against `to` (the deployment's token contract) sponsored by the
   * deployment's effective gas policy. Throws {@link SponsorshipUnavailableError} when the
   * backend refuses a voucher or no bundler is configured.
   */
  async sendSponsored(params: { to: Address; callData: Hex; deploymentId: string }): Promise<Hash> {
    if (!environment.bundlerUrl) {
      throw new SponsorshipUnavailableError('no bundler endpoint is configured for this environment');
    }
    const { walletClient, publicClient, eoaAddress } = await this.clients();

    // Adapt the connected JSON-RPC account to viem's narrower owner type.
    const owner = {
      address: eoaAddress,
      async signMessage({ message }: { message: unknown }) {
        return walletClient.signMessage({ account: eoaAddress, message } as Parameters<
          typeof walletClient.signMessage
        >[0]);
      },
      async signTypedData(typedData: unknown) {
        return walletClient.signTypedData({
          account: eoaAddress,
          ...(typedData as Record<string, unknown>),
        } as Parameters<typeof walletClient.signTypedData>[0]);
      },
      async signAuthorization(authorizationParams: unknown) {
        return signAuthorization(walletClient, {
          account: eoaAddress,
          ...(authorizationParams as Record<string, unknown>),
        } as Parameters<typeof signAuthorization>[1]);
      },
    } as unknown as PrivateKeyAccount;

    // EIP-7702 preserves the EOA address used by registry and compliance lookups.
    const account = await toSimple7702SmartAccount({ client: publicClient, owner });
    const bundlerClient = createBundlerClient({
      account,
      client: publicClient,
      transport: http(environment.bundlerUrl),
    });
    const calls = [{ to: params.to, data: params.callData }];

    // Fix every gas limit up front: the voucher signs them, so they must not change afterwards.
    let gas = {
      callGasLimit: DEFAULT_CALL_GAS,
      verificationGasLimit: DEFAULT_VERIFICATION_GAS,
      preVerificationGas: DEFAULT_PRE_VERIFICATION_GAS,
    };
    try {
      const estimate = await bundlerClient.estimateUserOperationGas({ account, calls });
      gas = {
        callGasLimit: (estimate.callGasLimit * 12n) / 10n,
        verificationGasLimit: (estimate.verificationGasLimit * 12n) / 10n,
        preVerificationGas: (estimate.preVerificationGas * 11n) / 10n,
      };
    } catch {
      // Unsponsored estimation can fail for an unfunded wallet — keep the conservative defaults.
    }

    const getVoucher = async (op: Record<string, unknown>) => {
      const factory = op['factory'] as Hex | undefined;
      const factoryData = op['factoryData'] as Hex | undefined;
      const hex = (v: unknown) => numberToHex((v as bigint | undefined) ?? 0n);
      try {
        const voucher = await firstValueFrom(
          this.api.requestVoucher({
            deploymentId: params.deploymentId,
            sender: op['sender'] as string,
            nonce: hex(op['nonce']),
            // Exactly the bytes the EntryPoint hands the paymaster: factory ‖ factoryData.
            initCode: factory ? concat([factory, factoryData ?? '0x']) : '0x',
            callData: op['callData'] as string,
            callGasLimit: hex(gas.callGasLimit),
            verificationGasLimit: hex(gas.verificationGasLimit),
            preVerificationGas: hex(gas.preVerificationGas),
            maxFeePerGas: hex(op['maxFeePerGas']),
            maxPriorityFeePerGas: hex(op['maxPriorityFeePerGas']),
            paymasterVerificationGasLimit: hex(PAYMASTER_VERIFICATION_GAS),
            paymasterPostOpGasLimit: hex(PAYMASTER_POST_OP_GAS),
          }),
        );
        return {
          paymaster: voucher.paymaster,
          paymasterData: voucher.paymasterData,
          paymasterVerificationGasLimit: PAYMASTER_VERIFICATION_GAS,
          paymasterPostOpGasLimit: PAYMASTER_POST_OP_GAS,
          isFinal: true,
        };
      } catch (err: unknown) {
        const reason =
          err instanceof HttpErrorResponse
            ? ((err.error as { message?: string } | null)?.message ?? `HTTP ${err.status}`)
            : 'the voucher service could not be reached';
        throw new SponsorshipUnavailableError(reason);
      }
    };

    try {
      const userOpHash = await bundlerClient.sendUserOperation({
        account,
        calls,
        ...gas,
        paymaster: {
          getPaymasterStubData: (op) => getVoucher(op as unknown as Record<string, unknown>),
          getPaymasterData: (op) => getVoucher(op as unknown as Record<string, unknown>),
        },
      });
      const receipt = await bundlerClient.waitForUserOperationReceipt({ hash: userOpHash });
      return receipt.receipt.transactionHash;
    } catch (err: unknown) {
      if (err instanceof SponsorshipUnavailableError) throw err;
      const cause = findCause(err);
      if (cause) throw cause;
      const message =
        err && typeof err === 'object'
          ? ((err as { shortMessage?: string; message?: string }).shortMessage ??
            (err as { message?: string }).message)
          : undefined;
      throw new Error(message ?? 'Sponsored transaction failed.', { cause: err });
    }
  }

  /**
   * Tries {@link sendSponsored}; when sponsorship is unavailable, sends the same call as an
   * ordinary self-paid transaction and reports why (`fallbackReason`) so the UI can say so.
   */
  async sendWithSponsorshipFallback(params: {
    to: Address;
    callData: Hex;
    deploymentId: string;
  }): Promise<SendResult> {
    if (this.isSupported) {
      try {
        return { hash: await this.sendSponsored(params), sponsored: true };
      } catch (err: unknown) {
        if (!(err instanceof SponsorshipUnavailableError)) throw err;
        const { walletClient, eoaAddress } = await this.clients();
        const hash = await walletClient.sendTransaction({
          account: eoaAddress,
          to: params.to,
          data: params.callData,
          chain: null,
        });
        return { hash, sponsored: false, fallbackReason: err.message };
      }
    }
    if (!this.wallet.isAvailable) throw new Error('No browser wallet detected.');
    const { walletClient, eoaAddress } = await this.clients();
    const hash = await walletClient.sendTransaction({
      account: eoaAddress,
      to: params.to,
      data: params.callData,
      chain: walletClient.chain ?? null,
    });
    return {
      hash,
      sponsored: false,
      fallbackReason: 'Gas sponsorship is not available in this environment.',
    };
  }

  private async clients() {
    if (!this.wallet.isAvailable) {
      throw new Error('No browser wallet detected.');
    }
    const injected = this.wallet.injectedProvider as Parameters<typeof custom>[0];
    // Same pinned chain as WalletService: viem then verifies the wallet's network at send time.
    const chain = this.wallet.pinnedViemChain;
    const walletClient = createWalletClient({ chain, transport: custom(injected) });
    const [eoaAddress] = await walletClient.requestAddresses();
    if (!eoaAddress) {
      throw new Error('Wallet returned no accounts.');
    }
    const publicClient = createPublicClient({ chain, transport: custom(injected) });
    return { walletClient, publicClient, eoaAddress };
  }
}

/** viem wraps hook errors; surface our own refusal instead of a generic bundler error. */
function findCause(err: unknown): SponsorshipUnavailableError | undefined {
  let current: unknown = err;
  for (let i = 0; i < 8 && current && typeof current === 'object'; i++) {
    if (current instanceof SponsorshipUnavailableError) return current;
    current = (current as { cause?: unknown }).cause;
  }
  return undefined;
}
