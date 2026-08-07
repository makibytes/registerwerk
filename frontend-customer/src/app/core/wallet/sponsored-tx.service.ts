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
 * Deliberately a separate, optional layer on top of {@link WalletService} rather than folded
 * into it, because it depends on two things the plain injected-wallet path does not:
 *
 * 1. An ERC-4337 bundler endpoint (`environment.bundlerUrl`) — not part of this project's
 *    docker-compose stack today; a real deployment points this at a bundler provider
 *    (Pimlico, Stackup, Alchemy) or a self-hosted one.
 * 2. The connected EOA delegating its code to a smart-account implementation via EIP-7702
 *    (`signAuthorization`) — see `docs/platform/account-abstraction.md`'s "EIP-7702: the
 *    smart-account on-ramp" section for why this is the natural on-ramp for Registerwerk
 *    specifically (org membership / KYC / whitelist all key off the unchanged EOA address).
 *    Wallet-level support for signing a 7702 authorization is still rolling out across
 *    browser wallets as of this writing.
 *
 * {@link isSupported} reflects both preconditions so the borrow stepper can offer sponsorship
 * as a toggle and cleanly fall back to `WalletService.writeContract` (the trader pays their
 * own gas) when either is unavailable — sponsorship is always an enhancement, never a
 * requirement, for every action in this module.
 *
 * `EwpgPasskeyAccount` (the passkey-secured alternative to a 7702-delegated EOA) is out of
 * scope here — see {@link WalletService}'s class doc for why.
 *
 * ### Implementation note: why {@link PrivateKeyAccount} is cast, not literal
 * viem's `toSimple7702SmartAccount` types its `owner` parameter as `PrivateKeyAccount`, but its
 * actual implementation (verified against the installed viem source) only ever calls
 * `owner.address`, `owner.signMessage(...)`, and `owner.signTypedData(...)` — exactly the
 * three members a JSON-RPC (browser-wallet) account can provide by delegating to the connected
 * `WalletClient`. The cast below is a structural-typing workaround for that overly-narrow
 * public type, not a correctness shortcut — there is no private key anywhere in this class.
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

    // See the class-doc note above for why this structural cast is safe: only address /
    // signMessage / signTypedData are ever invoked on `owner` by toSimple7702SmartAccount, and
    // signAuthorization is invoked separately by the bundler client's own delegation-check path
    // — all three delegate to the real, connected wallet, never to a raw private key.
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

    // toSimple7702SmartAccount keeps the EOA's own address as the smart-account address — the
    // whole point of 7702 here (see class doc): OrgRegistry, the T-REX IdentityRegistry, and
    // the compliance whitelist all key off this exact address, so no migration of org
    // membership / KYC / whitelist status is needed when upgrading to sponsored execution.
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
