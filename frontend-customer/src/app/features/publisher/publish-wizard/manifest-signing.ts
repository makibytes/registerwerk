import { stringToHex } from 'viem';

/**
 * The `personal_sign` request that signs a dApp manifest hash.
 *
 * The registry's contract (docs/platform/dapp-development.md, `WalletSignatureVerifierImpl`) is EIP-191 over
 * the 66 characters of the 0x-hex hash STRING, not over the 32 hash bytes. A wallet decodes a `0x...` parameter
 * of `personal_sign` as hex bytes, so passing the hash string itself would sign the raw bytes and the signature
 * would never recover to the publisher wallet. The string therefore goes over the wire as the hex of its UTF-8
 * text, which the wallet decodes back to exactly those 66 characters (and shows as readable text).
 */
export function manifestSignRequest(manifestHash: string, account: string) {
  return { method: 'personal_sign', params: [stringToHex(manifestHash), account] } as const;
}
