import express, { type Express, type NextFunction, type Request, type Response } from 'express';
import type { RelayerConfig } from './config.js';
import { requireApiKey } from './auth.js';
import { encryptInputRouter } from './routes/encryptInput.js';
import { operatorDecryptRouter } from './routes/operatorDecrypt.js';
import { publicDecryptRouter } from './routes/publicDecrypt.js';

/**
 * Builds the Express app without starting it — kept separate from `index.ts`'s `listen()` call
 * so tests can exercise routes via `supertest` without binding a real port.
 */
export function createServer(config: RelayerConfig): Express {
  const app = express();
  // Do not advertise the framework, and keep bodies small: the largest legitimate request is a ciphertext handle.
  app.disable('x-powered-by');
  app.use(express.json({ limit: '64kb' }));

  // Deliberately unauthenticated: a container liveness/readiness probe shouldn't need a secret,
  // and it reveals nothing sensitive (no ciphertext handles, no key material).
  app.get('/health', (_req: Request, res: Response) => {
    res.json({ status: 'ok', chainId: config.chainId, preset: config.preset });
  });

  // Every /v1/* route requires the shared-secret bearer token  — see
  // requireApiKey's doc comment for why this sits here rather than relying on the backend's own
  // RBAC as the only gate.
  app.use('/v1', requireApiKey(config.apiKey));
  app.use('/v1/encrypt-input', encryptInputRouter(config));
  app.use('/v1/operator-decrypt', operatorDecryptRouter(config));
  app.use('/v1/public-decrypt', publicDecryptRouter(config));

  // Express' default error page is HTML and, outside production, carries the stack. Every caller is a program:
  // a malformed or oversized body is a JSON 4xx, anything else a bare 500 (the cause stays in the server log).
  app.use((err: Error & { status?: number; statusCode?: number }, _req: Request, res: Response, _next: NextFunction) => {
    const status = err.status ?? err.statusCode;
    if (status !== undefined && status >= 400 && status < 500) {
      res.status(status).json({ error: status === 413 ? 'Request body too large' : 'Malformed request' });
      return;
    }
    console.error('relayer: unhandled error', err);
    res.status(500).json({ error: 'Internal error' });
  });

  return app;
}
