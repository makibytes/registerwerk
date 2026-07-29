import express, { type Express, type Request, type Response } from 'express';
import type { RelayerConfig } from './config';
import { requireApiKey } from './auth';
import { encryptInputRouter } from './routes/encryptInput';
import { operatorDecryptRouter } from './routes/operatorDecrypt';
import { publicDecryptRouter } from './routes/publicDecrypt';

/**
 * Builds the Express app without starting it — kept separate from `index.ts`'s `listen()` call
 * so tests can exercise routes via `supertest` without binding a real port.
 */
export function createServer(config: RelayerConfig): Express {
  const app = express();
  app.use(express.json());

  // Deliberately unauthenticated: a container liveness/readiness probe shouldn't need a secret,
  // and it reveals nothing sensitive (no ciphertext handles, no key material).
  app.get('/health', (_req: Request, res: Response) => {
    res.json({ status: 'ok', chainId: config.chainId, preset: config.preset });
  });

  // Every /v1/* route requires the shared-secret bearer token (finding #6, Phase 9) — see
  // requireApiKey's doc comment for why this sits here rather than relying on the backend's own
  // RBAC as the only gate.
  app.use('/v1', requireApiKey(config.apiKey));
  app.use('/v1/encrypt-input', encryptInputRouter(config));
  app.use('/v1/operator-decrypt', operatorDecryptRouter(config));
  app.use('/v1/public-decrypt', publicDecryptRouter(config));

  return app;
}
