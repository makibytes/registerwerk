import { describe, expect, it } from 'vitest';
import { hexToBytes, hashMessage, recoverAddress } from 'viem';
import { generatePrivateKey, privateKeyToAccount } from 'viem/accounts';
import { manifestSignRequest } from './manifest-signing';

const HASH = '0x' + '1b'.repeat(32);

/** What a wallet does with `personal_sign` params: a 0x-hex param is decoded to bytes, then EIP-191 prefixed. */
async function signedBy(request: ReturnType<typeof manifestSignRequest>, key: `0x${string}`): Promise<`0x${string}`> {
  const account = privateKeyToAccount(key);
  const [data] = request.params;
  return account.signMessage({ message: { raw: hexToBytes(data as `0x${string}`) } });
}

describe('manifestSignRequest', () => {
  it('asks the wallet to sign the 66 characters of the hash STRING, not the 32 hash bytes', () => {
    const request = manifestSignRequest(HASH, '0xabc');
    const bytes = hexToBytes(request.params[0] as `0x${string}`);
    expect(request.method).toBe('personal_sign');
    expect(request.params[1]).toBe('0xabc');
    expect(bytes.length).toBe(66);
    expect(new TextDecoder().decode(bytes)).toBe(HASH);
  });

  it('produces a signature the backend accepts: it recovers over the text of the hash (WalletSignatureVerifierImpl)', async () => {
    const key = generatePrivateKey();
    const signature = await signedBy(manifestSignRequest(HASH, privateKeyToAccount(key).address), key);

    // The backend: Sign.getEthereumMessageHash(message.getBytes(UTF_8)) with message = the hash string.
    const recovered = await recoverAddress({ hash: hashMessage(HASH), signature });
    expect(recovered).toBe(privateKeyToAccount(key).address);

    // Signing the raw bytes instead (the old behaviour) recovers a different address.
    const wrong = await recoverAddress({ hash: hashMessage({ raw: hexToBytes(HASH as `0x${string}`) }), signature });
    expect(wrong).not.toBe(privateKeyToAccount(key).address);
  });
});
