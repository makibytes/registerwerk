import { Router, type Request, type Response } from 'express';
import type { RelayerConfig } from '../config';
import { getFheInstance } from '../fheInstance';
import { fromHex } from '../hex';

/**
 * `POST /v1/public-decrypt` — decrypts a handle the contract itself has made publicly
 * decryptable (e.g. via `ConfidentialERC20.requestSupplyDisclosure`'s on-chain oracle callback
 * pattern, or any other `TFHE.allow(handle, address(this))`-style public grant). No signature or
 * viewer authorization is needed here — that's the defining difference from
 * `/v1/operator-decrypt`/a browser's own `userDecrypt`: publicDecrypt only succeeds for handles
 * the contract has already opted to disclose to everyone.
 */
export function publicDecryptRouter(config: RelayerConfig): Router {
  const router = Router();

  router.post('/', async (req: Request, res: Response) => {
    const { ciphertextHandle } = req.body ?? {};
    if (typeof ciphertextHandle !== 'string') {
      res.status(400).json({ error: 'ciphertextHandle is required' });
      return;
    }

    try {
      const instance = await getFheInstance(config);
      const result = await instance.publicDecrypt([fromHex(ciphertextHandle)]);
      const values = Object.values(result.clearValues);
      if (values.length !== 1) {
        res.status(502).json({
          error: `Expected exactly one decrypted value from the relayer, got ${values.length}`,
        });
        return;
      }
      const [value] = values;
      if (typeof value !== 'bigint') {
        res.status(502).json({
          error: `Expected a euint64 (bigint) result, got ${typeof value} — this handle may not be a euint64 handle`,
        });
        return;
      }
      res.json({ cleartext: value.toString() });
    } catch (err) {
      res.status(502).json({ error: `Relayer public-decrypt failed: ${(err as Error).message}` });
    }
  });

  return router;
}
