import { privateKeyToAccount, type PrivateKeyAccount } from 'viem/accounts';
import type { KmsUserDecryptEIP712Type } from '@zama-fhe/relayer-sdk/node';
import type { RelayerConfig } from './config.js';

/**
 * Signs the Zama KMS's `UserDecryptRequestVerification` EIP-712 payload with the dedicated
 * "operator-decrypt" key (see {@link RelayerConfig.operatorDecryptPrivateKey}'s doc comment for
 * why this is deliberately NOT an on-chain transaction-signing wallet).
 *
 * This is the exact same signature an investor's own browser wallet produces via
 * `walletClient.signTypedData(...)` for their OWN userDecrypt request (see
 * `frontend-customer`'s `FheClientService`) — the only difference is WHO is authorized: here it's
 * whichever address `OPERATOR_DECRYPT_PRIVATE_KEY` corresponds to, which must already be a
 * registered viewer (`isViewer(address) == true`) on the confidential token being decrypted, or
 * the KMS will simply refuse to decrypt (Zama's ACL check happens server-side at the KMS, not
 * merely client-side) — a bad or unregistered key does not silently succeed.
 */
export function loadOperatorAccount(config: RelayerConfig): PrivateKeyAccount {
  if (!config.operatorDecryptPrivateKey) {
    throw new Error(
      'OPERATOR_DECRYPT_PRIVATE_KEY is not configured — the headless operator/auditor decrypt ' +
      'path is unavailable until it is set. Encrypt-input, user-decrypt (browser-signed), and ' +
      'public-decrypt do not need this key.'
    );
  }
  return privateKeyToAccount(config.operatorDecryptPrivateKey);
}

export async function signUserDecryptRequest(
  account: PrivateKeyAccount,
  eip712: KmsUserDecryptEIP712Type
): Promise<`0x${string}`> {
  return account.signTypedData({
    domain: {
      name: eip712.domain.name,
      version: eip712.domain.version,
      chainId: Number(eip712.domain.chainId),
      verifyingContract: eip712.domain.verifyingContract,
    },
    // Only the non-domain type definitions are passed — viem derives `EIP712Domain` itself from
    // the `domain` object above, the same convention ethers'/ viem's typed-data signing use
    // everywhere else in this codebase (see frontend-customer's WalletService.signTypedData).
    types: {
      UserDecryptRequestVerification: eip712.types.UserDecryptRequestVerification,
    },
    primaryType: 'UserDecryptRequestVerification',
    message: {
      publicKey: eip712.message.publicKey,
      contractAddresses: eip712.message.contractAddresses as readonly `0x${string}`[],
      // The SDK's message carries these as decimal strings (matches the on-chain `uint256` ABI
      // type in eip712.types), but viem's typed-data encoder requires `bigint` for uint256
      // fields — passing the raw string risks a silent mis-encoding, so convert explicitly.
      startTimestamp: BigInt(eip712.message.startTimestamp),
      durationDays: BigInt(eip712.message.durationDays),
      extraData: eip712.message.extraData,
    },
  });
}
