import request from 'supertest';
import { loadConfig } from '../src/config';
import { createServer } from '../src/server';
import { getFheInstance } from '../src/fheInstance';
import { loadOperatorAccount, signUserDecryptRequest } from '../src/operatorSigner';

jest.mock('../src/fheInstance');
jest.mock('../src/operatorSigner');

const mockedGetFheInstance = getFheInstance as jest.MockedFunction<typeof getFheInstance>;
const mockedLoadOperatorAccount = loadOperatorAccount as jest.MockedFunction<typeof loadOperatorAccount>;
const mockedSignUserDecryptRequest = signUserDecryptRequest as jest.MockedFunction<typeof signUserDecryptRequest>;

const API_KEY = 'test-relayer-api-key';
const AUTH_HEADER = `Bearer ${API_KEY}`;

describe('POST /v1/operator-decrypt', () => {
  const config = loadConfig({ RELAYER_API_KEY: API_KEY, OPERATOR_DECRYPT_PRIVATE_KEY: `0x${'1'.repeat(64)}` });

  beforeEach(() => {
    mockedLoadOperatorAccount.mockReturnValue({ address: '0xOperatorViewer' } as never);
    mockedSignUserDecryptRequest.mockResolvedValue('0xsignature');
  });

  it('generates a keypair, signs, and decrypts in one round trip', async () => {
    const generateKeypair = jest.fn().mockReturnValue({ publicKey: 'pub', privateKey: 'priv' });
    const createEIP712 = jest.fn().mockReturnValue({ domain: {}, types: {}, message: {} });
    const userDecrypt = jest.fn().mockResolvedValue({ '0xhandle': 777n });
    mockedGetFheInstance.mockResolvedValue({ generateKeypair, createEIP712, userDecrypt } as never);

    const app = createServer(config);
    const res = await request(app)
      .post('/v1/operator-decrypt').set('Authorization', AUTH_HEADER)
      .send({ ciphertextHandle: '0xaabb', contractAddress: '0xContract' });

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ cleartext: '777' });
    expect(createEIP712).toHaveBeenCalledWith('pub', ['0xContract'], expect.any(Number), 365);
    expect(userDecrypt).toHaveBeenCalledWith(
      [{ handle: expect.any(Uint8Array), contractAddress: '0xContract' }],
      'priv',
      'pub',
      '0xsignature',
      ['0xContract'],
      '0xOperatorViewer',
      expect.any(Number),
      365
    );
  });

  it('rejects a missing field', async () => {
    const app = createServer(config);
    const res = await request(app).post('/v1/operator-decrypt').set('Authorization', AUTH_HEADER).send({ ciphertextHandle: '0xaabb' });
    expect(res.status).toBe(400);
  });

  it('surfaces an unconfigured operator key as a clear error, not a silent no-op', async () => {
    mockedLoadOperatorAccount.mockImplementation(() => {
      throw new Error('OPERATOR_DECRYPT_PRIVATE_KEY is not configured');
    });
    const app = createServer(config);
    const res = await request(app)
      .post('/v1/operator-decrypt').set('Authorization', AUTH_HEADER)
      .send({ ciphertextHandle: '0xaabb', contractAddress: '0xContract' });
    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/not configured/);
  });

  it('surfaces a non-bigint result as an error', async () => {
    const generateKeypair = jest.fn().mockReturnValue({ publicKey: 'pub', privateKey: 'priv' });
    const createEIP712 = jest.fn().mockReturnValue({ domain: {}, types: {}, message: {} });
    const userDecrypt = jest.fn().mockResolvedValue({ '0xhandle': '0xnotabigint' });
    mockedGetFheInstance.mockResolvedValue({ generateKeypair, createEIP712, userDecrypt } as never);

    const app = createServer(config);
    const res = await request(app)
      .post('/v1/operator-decrypt').set('Authorization', AUTH_HEADER)
      .send({ ciphertextHandle: '0xaabb', contractAddress: '0xContract' });
    expect(res.status).toBe(502);
  });

  // ── finding #6, Phase 9: shared-secret auth ──────────────────────────────────────────────
  it('rejects a request with no Authorization header', async () => {
    const app = createServer(config);
    const res = await request(app)
      .post('/v1/operator-decrypt')
      .send({ ciphertextHandle: '0xaabb', contractAddress: '0xContract' });
    expect(res.status).toBe(401);
  });
});
