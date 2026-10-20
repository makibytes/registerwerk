import request from 'supertest';
import { describe, expect, it } from 'vitest';
import { loadConfig } from '../src/config.js';
import { createServer } from '../src/server.js';

const API_KEY = 'test-relayer-api-key';
const AUTH_HEADER = `Bearer ${API_KEY}`;

describe('server hardening', () => {
  const app = createServer(loadConfig({ RELAYER_API_KEY: API_KEY }));

  it('does not advertise the framework', async () => {
    const res = await request(app).get('/health');
    expect(res.status).toBe(200);
    expect(res.headers['x-powered-by']).toBeUndefined();
  });

  it('answers a malformed JSON body with a JSON 400, not an HTML error page', async () => {
    const res = await request(app)
      .post('/v1/public-decrypt')
      .set('Authorization', AUTH_HEADER)
      .set('Content-Type', 'application/json')
      .send('{"ciphertextHandle": ');
    expect(res.status).toBe(400);
    expect(res.headers['content-type']).toMatch(/application\/json/);
    expect(res.body).toEqual({ error: 'Malformed request' });
  });

  it('refuses an oversized body with a JSON 413', async () => {
    const res = await request(app)
      .post('/v1/public-decrypt')
      .set('Authorization', AUTH_HEADER)
      .send({ ciphertextHandle: '0x' + 'ab'.repeat(40_000) });
    expect(res.status).toBe(413);
    expect(res.body).toEqual({ error: 'Request body too large' });
  });

  it('still rejects an unauthenticated request before parsing anything it needs', async () => {
    const res = await request(app).post('/v1/public-decrypt').send({ ciphertextHandle: '0xabc' });
    expect(res.status).toBe(401);
  });
});
