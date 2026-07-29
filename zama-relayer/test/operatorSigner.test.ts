import { loadOperatorAccount, signUserDecryptRequest } from '../src/operatorSigner';
import { loadConfig } from '../src/config';

const TEST_KEY = `0x${'ab'.repeat(32)}` as const;

describe('operatorSigner', () => {
  it('throws a clear error when no operator decrypt key is configured', () => {
    const config = loadConfig({ RELAYER_API_KEY: 'test-relayer-api-key' });
    expect(() => loadOperatorAccount(config)).toThrow(/OPERATOR_DECRYPT_PRIVATE_KEY/);
  });

  it('derives a real viem account from the configured key', () => {
    const config = loadConfig({ RELAYER_API_KEY: 'test-relayer-api-key', OPERATOR_DECRYPT_PRIVATE_KEY: TEST_KEY });
    const account = loadOperatorAccount(config);
    expect(account.address).toMatch(/^0x[0-9a-fA-F]{40}$/);
  });

  it('signs UserDecryptRequestVerification with correct domain/message field conversions', async () => {
    const config = loadConfig({ RELAYER_API_KEY: 'test-relayer-api-key', OPERATOR_DECRYPT_PRIVATE_KEY: TEST_KEY });
    const account = loadOperatorAccount(config);
    const spy = jest.spyOn(account, 'signTypedData').mockResolvedValue('0xsig' as `0x${string}`);

    const eip712 = {
      types: {
        EIP712Domain: [],
        UserDecryptRequestVerification: [{ name: 'publicKey', type: 'bytes' }],
      },
      primaryType: 'UserDecryptRequestVerification' as const,
      domain: {
        name: 'Decryption' as const,
        version: '1' as const,
        chainId: 10901n,
        verifyingContract: '0xVerifying' as `0x${string}`,
      },
      message: {
        publicKey: '0xpub' as `0x${string}`,
        contractAddresses: ['0xContract'] as readonly `0x${string}`[],
        startTimestamp: '1700000000',
        durationDays: '365',
        extraData: '0x' as `0x${string}`,
      },
    };

    const signature = await signUserDecryptRequest(account, eip712 as never);

    expect(signature).toBe('0xsig');
    expect(spy).toHaveBeenCalledWith({
      domain: { name: 'Decryption', version: '1', chainId: 10901, verifyingContract: '0xVerifying' },
      types: { UserDecryptRequestVerification: eip712.types.UserDecryptRequestVerification },
      primaryType: 'UserDecryptRequestVerification',
      message: {
        publicKey: '0xpub',
        contractAddresses: ['0xContract'],
        // Converted from the SDK's decimal-string uint256 fields to bigint — see the code
        // comment in operatorSigner.ts on why the raw strings can't be passed to viem as-is.
        startTimestamp: 1700000000n,
        durationDays: 365n,
        extraData: '0x',
      },
    });
  });
});
