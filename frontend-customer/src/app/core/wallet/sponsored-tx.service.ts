import { Injectable, inject } from '@angular/core';
import {
  type Address,
  type Hash,
  type PrivateKeyAccount,
  createPublicClient,
  createWalletClient,
  custom,
  http,
} from 'viem';
import { signAuthorization } from 'viem/actions';
import { createBundlerClient, toSimple7702SmartAccount } from 'viem/account-abstraction';
import { WalletService } from './wallet.service';
import { environment } from '../../../environments/environment';

/**
 * Sponsored (gasless) transactions via ERC-4337 against `EwpgPaymaster`
 * (contracts/src/ecosystem/EwpgPaymaster.sol) — see `docs/platform/account-abstraction.md`.
 *
 * This optional layer requires:
 *
 * 1. An ERC-4337 bundler endpoint (`environment.bundlerUrl`).
 * 2. The connected EOA delegating its code to a smart-account implementation via EIP-7702
 *    (`signAuthorization`).
 *
 * {@link isSupported} reports whether the environment and wallet meet these prerequisites.
 *
 * ### Implementation note: why {@link PrivateKeyAccount} is cast, not literal
 * viem's `toSimple7702SmartAccount` types its `owner` parameter as `PrivateKeyAccount`, but its
 * implementation calls
 * `owner.address`, `owner.signMessage(...)`, and `owner.signTypedData(...)` — exactly the
 * three members a JSON-RPC account provides through the connected `WalletClient`. The cast is
 * a structural-typing workaround; this class does not hold a private key.
 */
@Injectable({ providedIn: 'root' })
export class SponsoredTxService {
  private readonly wallet = inject(WalletService);

  get isSupported(): boolean {
    return !!environment.bundlerUrl && this.wallet.isAvailable;
  }

  /**
   * Sends `callData` against `to` as a single sponsored call, paid for by the funded policy
   * `policyId` on `paymasterAddress`. `policyId` is `keccak256(GasSponsorshipPolicy.id)` — see
   * `backend/.../deployment/api/GasSponsorshipPolicy.java` and
   * `asset/internal/GasSponsorshipService.resolveEffectivePolicy`.
   */
  async sendSponsored(params: {
    to: Address;
    callData: `0x${string}`;
    paymasterAddress: Address;
    policyId: `0x${string}`;
  }): Promise<Hash> {
    if (!environment.bundlerUrl) {
      throw new Error(
        'Sponsored transactions are not configured for this environment (no bundler endpoint).',
      );
    }
    if (!this.wallet.isAvailable) {
      throw new Error('No browser wallet detected.');
    }

    const injected = this.wallet.injectedProvider as Parameters<typeof custom>[0];
    const walletClient = createWalletClient({ transport: custom(injected) });
    const [eoaAddress] = await walletClient.requestAddresses();
    if (!eoaAddress) {
      throw new Error('Wallet returned no accounts.');
    }

    const publicClient = createPublicClient({ transport: custom(injected) });

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

    try {
      // Last 32 bytes of paymasterData carry the policyId — EwpgPaymaster.validatePaymasterUserOp
      // reads it via `paymasterAndData[paymasterAndData.length - 32:]`.
      const userOpHash = await bundlerClient.sendUserOperation({
        account,
        calls: [{ to: params.to, data: params.callData }],
        paymaster: params.paymasterAddress,
        paymasterData: params.policyId,
      });

      const receipt = await bundlerClient.waitForUserOperationReceipt({ hash: userOpHash });
      return receipt.receipt.transactionHash;
    } catch (err: unknown) {
      const message =
        err && typeof err === 'object'
          ? ((err as { shortMessage?: string; message?: string }).shortMessage ??
            (err as { message?: string }).message)
          : undefined;
      throw new Error(message ?? 'Sponsored transaction failed.', { cause: err });
    }
  }
}
