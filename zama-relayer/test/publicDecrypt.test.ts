import request from 'supertest';
import { describe, expect, it, vi, type MockedFunction } from 'vitest';
import { loadConfig } from '../src/config.js';
import { createServer } from '../src/server.js';
import { getFheInstance } from '../src/fheInstance.js';

vi.mock('../src/fheInstance.js');

const mockedGetFheInstance = getFheInstance as MockedFunction<typeof getFheInstance>;
const API_KEY = 'test-relayer-api-key';
const AUTH_HEADER = `Bearer ${API_KEY}`;

describe('POST /v1/public-decrypt', () => {
  const config = loadConfig({ RELAYER_API_KEY: API_KEY });

  it('decrypts a publicly-disclosed handle', async () => {
    const publicDecrypt = vi.fn().mockResolvedValue({
      clearValues: { '0xhandle': 123456n },
      abiEncodedClearValues: '0x',
      decryptionProof: '0x',
    });
    mockedGetFheInstance.mockResolvedValue({ publicDecrypt } as never);

    const app = createServer(config);
    const res = await request(app).post('/v1/public-decrypt').set('Authorization', AUTH_HEADER).send({ ciphertextHandle: '0xhandle' });

    expect(res.status).toBe(200);
    expect(res.body).toEqual({ cleartext: '123456' });
    expect(publicDecrypt).toHaveBeenCalled();
  });

  it('rejects a missing ciphertextHandle', async () => {
    const app = createServer(config);
    const res = await request(app).post('/v1/public-decrypt').set('Authorization', AUTH_HEADER).send({});
    expect(res.status).toBe(400);
  });

  it('surfaces a non-bigint result as an error rather than guessing', async () => {
    const publicDecrypt = vi.fn().mockResolvedValue({
      clearValues: { '0xhandle': true },
      abiEncodedClearValues: '0x',
      decryptionProof: '0x',
    });
    mockedGetFheInstance.mockResolvedValue({ publicDecrypt } as never);

    const app = createServer(config);
    const res = await request(app).post('/v1/public-decrypt').set('Authorization', AUTH_HEADER).send({ ciphertextHandle: '0xhandle' });
    expect(res.status).toBe(502);
  });

  it('surfaces an upstream failure as 502', async () => {
    mockedGetFheInstance.mockRejectedValue(new Error('KMS down'));
    const app = createServer(config);
    const res = await request(app).post('/v1/public-decrypt').set('Authorization', AUTH_HEADER).send({ ciphertextHandle: '0xhandle' });
    expect(res.status).toBe(502);
    expect(res.body.error).toMatch(/KMS down/);
  });

  // ── Shared-secret auth ────────────────────────────────────────────────
  it('rejects a request with no Authorization header', async () => {
    const app = createServer(config);
    const res = await request(app).post('/v1/public-decrypt').send({ ciphertextHandle: '0xhandle' });
    expect(res.status).toBe(401);
  });
});
