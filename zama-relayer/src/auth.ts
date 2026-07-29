import crypto from 'node:crypto';
import type { NextFunction, Request, Response } from 'express';

/**
 * Shared-secret bearer-token check for every `/v1/*` route (finding #6, Phase 9). Without this,
 * this sidecar's `/v1/operator-decrypt` — which can decrypt ANY confidential balance the
 * configured operator-viewer key is a viewer on, i.e. every confidential asset by design (see
 * `EwpgConfidentialFactory`/`ContractAddressConfig.confidentialInitialViewers`) — was reachable by
 * any process that could route to it, with the Spring Boot backend's own RBAC (`@PreAuthorize`)
 * being the only gate in front of it. That RBAC lives one hop away from the actual decrypt
 * capability; this closes the gap at the capability itself. Mirrors the venue-adapter API-key
 * pattern already used elsewhere in this codebase (`TradingProperties`/`AsseTeraVenueAdapter`).
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
