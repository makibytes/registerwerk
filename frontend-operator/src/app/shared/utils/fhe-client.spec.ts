import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FHE_SUPPORTED_CHAIN_ID, FheClient, type FheSdk, type FheWallet } from '@registerwerk/ui';

const CONTRACT = '0x00000000000000000000000000000000000000c1' as const;
const USER = '0x00000000000000000000000000000000000000a1' as const;
const HANDLE = `0x${'ab'.repeat(32)}` as const;

function fakeInstance(decrypted: Record<string, unknown> = { [HANDLE]: 42n }) {
  return {
    generateKeypair: vi.fn(() => ({ publicKey: 'pub', privateKey: 'priv' })),
    createEIP712: vi.fn((_pub: string, contracts: string[], start: number, days: number) => ({
      domain: { name: 'Decryption', version: '1', chainId: '10901', verifyingContract: '0x0000000000000000000000000000000000000d1' },
      types: { UserDecryptRequestVerification: [{ name: 'publicKey', type: 'bytes' }] },
      message: { publicKey: 'pub', contractAddresses: contracts, startTimestamp: String(start), durationDays: String(days), extraData: '0x00' },
    })),
    userDecrypt: vi.fn(async () => decrypted),
    createEncryptedInput: vi.fn(() => ({
      add64: vi.fn(),
      encrypt: vi.fn(async () => ({ handles: [Uint8Array.of(1, 255)], inputProof: Uint8Array.of(0, 16) })),
    })),
  };
}

function setup(over: { provider?: unknown; instance?: ReturnType<typeof fakeInstance> } = {}) {
  const instance = over.instance ?? fakeInstance();
  const sdk = {
    initSDK: vi.fn(async () => true),
    createInstance: vi.fn(async () => instance),
    SepoliaConfig: { chainId: FHE_SUPPORTED_CHAIN_ID },
  };
  const wallet = {
    injectedProvider: 'provider' in over ? over.provider : { request: vi.fn() },
    address: vi.fn((): `0x${string}` | null => USER),
    signTypedData: vi.fn<FheWallet['signTypedData']>(async () => '0xsig'),
  } satisfies FheWallet;
  const loadSdk = vi.fn(async () => sdk as unknown as FheSdk);
  return { client: new FheClient(wallet, loadSdk), sdk, wallet, loadSdk, instance };
}

describe('FheClient', () => {
  beforeEach(() => vi.clearAllMocks());

  it('creates the SDK instance once on the wallet provider and shares it between calls and concurrent callers', async () => {
    const { client, sdk, wallet, loadSdk } = setup();
    await Promise.all([
      client.userDecrypt(HANDLE, CONTRACT, FHE_SUPPORTED_CHAIN_ID),
      client.userDecrypt(HANDLE, CONTRACT, FHE_SUPPORTED_CHAIN_ID),
    ]);
    await client.userDecrypt(HANDLE, CONTRACT, FHE_SUPPORTED_CHAIN_ID);
    expect(loadSdk).toHaveBeenCalledTimes(1);
    expect(sdk.initSDK).toHaveBeenCalledTimes(1);
    expect(sdk.createInstance).toHaveBeenCalledTimes(1);
    expect(sdk.createInstance).toHaveBeenCalledWith({ chainId: FHE_SUPPORTED_CHAIN_ID, network: wallet.injectedProvider });
  });

  it('forgets a failed creation: connecting a wallet after "Connect a wallet" makes the next call work', async () => {
    const { client, wallet, sdk } = setup({ provider: undefined });
    await expect(client.userDecrypt(HANDLE, CONTRACT, FHE_SUPPORTED_CHAIN_ID)).rejects.toThrow(/Connect a wallet/);

    (wallet as { injectedProvider: unknown }).injectedProvider = { request: vi.fn() };
    await expect(client.userDecrypt(HANDLE, CONTRACT, FHE_SUPPORTED_CHAIN_ID)).resolves.toBe(42n);
    expect(sdk.createInstance).toHaveBeenCalledTimes(1);
  });

  it('refuses any chain other than Sepolia on every call, also once an instance exists', async () => {
    const { client } = setup();
    await client.userDecrypt(HANDLE, CONTRACT, FHE_SUPPORTED_CHAIN_ID);
    await expect(client.userDecrypt(HANDLE, CONTRACT, 1)).rejects.toThrow(/only configured for Ethereum Sepolia.*got chainId=1/);
    await expect(client.encrypt64(CONTRACT, USER, 5n, 8453)).rejects.toThrow(/got chainId=8453/);
  });

  it('asks the wallet to sign the KMS authorisation with a 1-day window, uint256 fields as bigint, and passes the same window to the relayer', async () => {
    const { client, wallet, instance } = setup();
    await client.userDecrypt(HANDLE, CONTRACT, FHE_SUPPORTED_CHAIN_ID);

    const [, , startTimestamp, days] = instance.createEIP712.mock.calls[0];
    expect(days).toBe(1);
    const typed = wallet.signTypedData.mock.calls[0][0];
    expect(typed.primaryType).toBe('UserDecryptRequestVerification');
    expect(typed.domain).toMatchObject({ name: 'Decryption', version: '1', chainId: 10901 });
    expect(Object.keys(typed.types)).toEqual(['UserDecryptRequestVerification']);
    expect(typed.message).toMatchObject({ startTimestamp: BigInt(startTimestamp), durationDays: 1n, contractAddresses: [CONTRACT] });
    expect(instance.userDecrypt).toHaveBeenCalledWith(
      [{ handle: HANDLE, contractAddress: CONTRACT }], 'priv', 'pub', '0xsig', [CONTRACT], USER, startTimestamp, 1);
  });

  it('needs a connected wallet address to reveal, and rejects an unexpected result shape', async () => {
    const noAddress = setup();
    noAddress.wallet.address.mockReturnValue(null);
    await expect(noAddress.client.userDecrypt(HANDLE, CONTRACT, FHE_SUPPORTED_CHAIN_ID)).rejects.toThrow('Wallet not connected.');

    const wrongShape = setup({ instance: fakeInstance({ [HANDLE]: 'not a bigint' }) });
    await expect(wrongShape.client.userDecrypt(HANDLE, CONTRACT, FHE_SUPPORTED_CHAIN_ID)).rejects.toThrow(/Unexpected decrypt result/);
  });

  it('encrypt64 returns the handle and the input proof as 0x-hex', async () => {
    const { client } = setup();
    await expect(client.encrypt64(CONTRACT, USER, 7n, FHE_SUPPORTED_CHAIN_ID))
      .resolves.toEqual({ handle: '0x01ff', inputProof: '0x0010' });
  });
});
