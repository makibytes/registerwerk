import { Router, type Request, type Response } from 'express';
import type { RelayerConfig } from '../config.js';
import { getFheInstance } from '../fheInstance.js';
import { fromHex } from '../hex.js';

/**
 * `POST /v1/public-decrypt` — decrypts a handle the contract itself has made publicly
 * decryptable (e.g. via `ConfidentialERC20.requestSupplyDisclosure`, which calls
 * `FHE.makePubliclyDecryptable`). No signature or
 * viewer authorization is needed here — that's the defining difference from
 * `/v1/operator-decrypt`/a browser's own `userDecrypt`: publicDecrypt only succeeds for handles
 * the contract has already opted to disclose to everyone.
 *
 * Besides the `cleartext`, the response carries `abiEncodedClearValues` and `decryptionProof`:
 * with fhEVM 0.14 the contract no longer receives a gateway callback — whoever holds the KMS
 * proof submits both to the contract, which verifies them with `FHE.checkSignatures`.
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
      res.json({
        cleartext: value.toString(),
        abiEncodedClearValues: result.abiEncodedClearValues,
        decryptionProof: result.decryptionProof,
      });
    } catch (err) {
      res.status(502).json({ error: `Relayer public-decrypt failed: ${(err as Error).message}` });
    }
  });

  return router;
}
