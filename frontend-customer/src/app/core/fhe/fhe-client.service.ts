import { Injectable, inject } from '@angular/core';
import { initSDK, createInstance, SepoliaConfig, type FhevmInstance } from '@zama-fhe/relayer-sdk/web';
import type { Address } from 'viem';
import { WalletService } from '../wallet/wallet.service';

/**
 * Browser-side Zama Relayer SDK integration — the piece that makes confidential-token
 * encrypt/decrypt genuinely Registerwerk's own responsibility, not "someone else's problem": an
 * investor revealing their own balance, or encrypting a confidential-transfer amount, happens
 * ENTIRELY here in the browser, talking to Zama's real relayer directly. The backend/`zama-relayer`
 * sidecar is never in this path — it exists only for the operator/issuer-initiated flows that have
 * no browser/wallet at all (confidential mint, confidential force-burn, headless reconciliation).
 *
 * <p>Uses the connected wallet's own EIP-1193 provider as the SDK's `network` parameter (not a
 * backend-supplied RPC URL) — the injected wallet already has its own RPC connection, and this
 * avoids ever needing to hand the registry's own (possibly paid/keyed) RPC endpoint to the browser.
 *
 * <p>Scoped to Ethereum Sepolia today (`chainId=11155111`) — the only network with real, published
 * Zama fhEVM addresses at the time this was written (see
 * `docs/blockchains/confidential-evm.md`). Ethereum/Base mainnet or T-REX Chain support needs
 * their real addresses added here once published, not a guess.
 */
@Injectable({ providedIn: 'root' })
export class FheClientService {
  private readonly wallet = inject(WalletService);

  private instance: FhevmInstance | null = null;
  private initPromise: Promise<FhevmInstance> | null = null;

  private async doInit(chainId: number): Promise<FhevmInstance> {
    if (chainId !== 11155111) {
      throw new Error(
        `Confidential-token support is only configured for Ethereum Sepolia (chainId=11155111) in ` +
        `this build — got chainId=${chainId}. See docs/blockchains/confidential-evm.md.`
      );
    }
    const provider = this.wallet.injectedProvider;
    if (!provider) {
      throw new Error('Connect a wallet before using confidential-token features.');
    }
    await initSDK();
    return createInstance({ ...SepoliaConfig, network: provider as never });
  }

  private async instanceFor(chainId: number): Promise<FhevmInstance> {
    if (this.instance) {
      return this.instance;
    }
    if (!this.initPromise) {
      this.initPromise = this.doInit(chainId);
    }
    this.instance = await this.initPromise;
    return this.instance;
  }

  /**
   * Encrypts `value` client-side for a confidential contract call (e.g. the amount field of an
   * investor-initiated `confidentialTransfer`) — the plaintext value never leaves this browser
   * except as ciphertext + a ZK input proof.
   */
  async encrypt64(
    contractAddress: Address,
    userAddress: Address,
    value: bigint,
    chainId: number
  ): Promise<{ handle: `0x${string}`; inputProof: `0x${string}` }> {
    const instance = await this.instanceFor(chainId);
    const input = instance.createEncryptedInput(contractAddress, userAddress);
    input.add64(value);
    const encrypted = await input.encrypt();
    return { handle: toHex(encrypted.handles[0]), inputProof: toHex(encrypted.inputProof) };
  }

  /**
   * Reveals ONE ciphertext handle by having the connected wallet sign the Zama KMS's
   * `UserDecryptRequestVerification` EIP-712 authorization — succeeds only if the connected
   * address has actually been granted decrypt rights on this handle (the holder on their own
   * balance, or a registered viewer — see `ConfidentialERC20`'s viewer-ACL note). A wallet with no
   * such grant gets a real KMS rejection, not a fabricated value.
   */
  async userDecrypt(handle: `0x${string}`, contractAddress: Address, chainId: number): Promise<bigint> {
    const instance = await this.instanceFor(chainId);
    const userAddress = this.wallet.address();
    if (!userAddress) {
      throw new Error('Wallet not connected.');
    }

    const keypair = instance.generateKeypair();
    const startTimestamp = Math.floor(Date.now() / 1000);
    const durationDays = 365;
    const eip712 = instance.createEIP712(keypair.publicKey, [contractAddress], startTimestamp, durationDays);

    const signature = await this.wallet.signTypedData({
      domain: {
        name: eip712.domain.name,
        version: eip712.domain.version,
        chainId: Number(eip712.domain.chainId),
        verifyingContract: eip712.domain.verifyingContract as Address,
      },
      // Only the non-domain type definitions are passed — viem derives `EIP712Domain` itself
      // from `domain` above, the same convention `zama-relayer/src/operatorSigner.ts` uses.
      types: { UserDecryptRequestVerification: eip712.types.UserDecryptRequestVerification },
      primaryType: 'UserDecryptRequestVerification',
      message: {
        publicKey: eip712.message.publicKey,
        contractAddresses: eip712.message.contractAddresses,
        // The SDK's message carries these as decimal strings (matching the on-chain uint256 ABI
        // type), but viem's typed-data encoder requires bigint for uint256 fields.
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
      durationDays
    );
    const values = Object.values(results);
    if (values.length !== 1 || typeof values[0] !== 'bigint') {
      throw new Error('Unexpected decrypt result shape from the Zama Relayer.');
    }
    return values[0] as bigint;
  }
}

function toHex(bytes: Uint8Array): `0x${string}` {
  let hex = '0x';
  for (const b of bytes) {
    hex += b.toString(16).padStart(2, '0');
  }
  return hex as `0x${string}`;
}
