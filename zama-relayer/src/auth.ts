import crypto from 'node:crypto';
import type { NextFunction, Request, Response } from 'express';

/**
 * Shared-secret bearer-token check for every `/v1/*` route. The sidecar enforces authentication
 * at the decryption capability in addition to the backend's role checks.
 *
 * Uses `timingSafeEqual` rather than `===` so a byte-by-byte early-exit comparison can't leak how
 * many leading characters of a guessed key were correct via response-time differences.
 */
export function requireApiKey(apiKey: string) {
  const expected = Buffer.from(apiKey, 'utf8');

  return (req: Request, res: Response, next: NextFunction) => {
    const header = req.header('authorization') ?? '';
    const match = /^Bearer (.+)$/.exec(header);
    const provided = Buffer.from(match?.[1] ?? '', 'utf8');

    if (provided.length !== expected.length || !crypto.timingSafeEqual(provided, expected)) {
      res.status(401).json({ error: 'Unauthorized' });
      return;
    }
    next();
  };
}
