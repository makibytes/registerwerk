import request from 'supertest';
import { describe, expect, it, vi, type MockedFunction } from 'vitest';
import { loadConfig } from '../src/config.js';
import { createServer } from '../src/server.js';
import { getFheInstance } from '../src/fheInstance.js';

vi.mock('../src/fheInstance.js');

const mockedGetFheInstance = getFheInstance as MockedFunction<typeof getFheInstance>;
const API_KEY = 'test-relayer-api-key';
const AUTH_HEADER = `Bearer ${API_KEY}`;

describe('POST /v1/encrypt-input', () => {
  const config = loadConfig({ RELAYER_API_KEY: API_KEY });

  it('encrypts a valid euint64 value and returns hex-encoded handle + proof', async () => {
    const encrypt = vi.fn().mockResolvedValue({
      handles: [new Uint8Array([0xaa, 0xbb])],
      inputProof: new Uint8Array([0xcc, 0xdd, 0xee]),
    });
    const add64 = vi.fn().mockReturnThis();
    const createEncryptedInput = vi.fn().mockReturnValue({ add64, encrypt });
    mockedGetFheInstance.mockResolvedValue({ createEncryptedInput } as never);

    const app = createServer(config);
    const res = await request(app)
      .post('/v1/encrypt-input').set('Authorization', AUTH_HEADER)
      .send({ contractAddress: '0xContract', userAddress: '0xUser', value: '42' });

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ ciphertextHandle: '0xaabb', inputProof: '0xccddee' });
    expect(createEncryptedInput).toHaveBeenCalledWith('0xContract', '0xUser');
    expect(add64).toHaveBeenCalledWith(42n);
  });

  it('rejects a missing field', async () => {
    const app = createServer(config);
    const res = await request(app).post('/v1/encrypt-input').set('Authorization', AUTH_HEADER).send({ contractAddress: '0xC' });
    expect(res.status).toBe(400);
  });

  it('rejects a non-integer value', async () => {
    const app = createServer(config);
    const res = await request(app)
      .post('/v1/encrypt-input').set('Authorization', AUTH_HEADER)
      .send({ contractAddress: '0xC', userAddress: '0xU', value: 'not-a-number' });
    expect(res.status).toBe(400);
  });

  it('rejects a value that overflows uint64', async () => {
    const app = createServer(config);
    const res = await request(app)
      .post('/v1/encrypt-input').set('Authorization', AUTH_HEADER)
      .send({ contractAddress: '0xC', userAddress: '0xU', value: (2n ** 64n).toString() });
    expect(res.status).toBe(400);
  });

  it('rejects a negative value', async () => {
    const app = createServer(config);
    const res = await request(app)
      .post('/v1/encrypt-input').set('Authorization', AUTH_HEADER)
      .send({ contractAddress: '0xC', userAddress: '0xU', value: '-1' });
    expect(res.status).toBe(400);
  });

  it('surfaces an upstream relayer failure as 502, not a silent fallback', async () => {
    mockedGetFheInstance.mockRejectedValue(new Error('relayer unreachable'));
    const app = createServer(config);
    const res = await request(app)
      .post('/v1/encrypt-input').set('Authorization', AUTH_HEADER)
      .send({ contractAddress: '0xC', userAddress: '0xU', value: '1' });
    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/relayer unreachable/);
  });

  // ── Shared-secret auth ────────────────────────────────────────────────
  it('rejects a request with no Authorization header', async () => {
    const app = createServer(config);
    const res = await request(app)
      .post('/v1/encrypt-input')
      .send({ contractAddress: '0xC', userAddress: '0xU', value: '1' });
    expect(res.status).toBe(401);
  });

  it('rejects a request with the wrong API key', async () => {
    const app = createServer(config);
    const res = await request(app)
      .post('/v1/encrypt-input')
      .set('Authorization', 'Bearer wrong-key')
      .send({ contractAddress: '0xC', userAddress: '0xU', value: '1' });
    expect(res.status).toBe(401);
  });
});
