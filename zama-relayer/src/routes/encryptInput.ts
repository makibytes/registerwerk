import { Router, type Request, type Response } from 'express';
import type { RelayerConfig } from '../config.js';
import { getFheInstance } from '../fheInstance.js';
import { toHex } from '../hex.js';

const UINT64_MAX = (1n << 64n) - 1n;

/**
 * `POST /v1/encrypt-input` — server-side equivalent of the browser SDK's
 * `createEncryptedInput(contract, user).add64(value).encrypt()`. Used by the backend for
 * operator/issuer-initiated confidential mint and confidential force-burn (there is no
 * browser/wallet in either flow — see `ConfidentialTokenAdminService`/`TokenAdminService`
 * on the Java side), never for an investor's own transfer (that stays fully client-side via
 * `FheClientService` in the frontends, talking to Zama's relayer directly).
 */
export function encryptInputRouter(config: RelayerConfig): Router {
  const router = Router();

  router.post('/', async (req: Request, res: Response) => {
    const { contractAddress, userAddress, value } = req.body ?? {};
    if (typeof contractAddress !== 'string' || typeof userAddress !== 'string' || value === undefined) {
      res.status(400).json({ error: 'contractAddress, userAddress, and value are required' });
      return;
    }

    let amount: bigint;
    try {
      amount = BigInt(value);
    } catch {
      res.status(400).json({ error: 'value must be an integer (decimal string or number)' });
      return;
    }
    if (amount < 0n || amount > UINT64_MAX) {
      res.status(400).json({ error: 'value must fit in an unsigned 64-bit integer (euint64)' });
      return;
    }

    try {
      const instance = await getFheInstance(config);
      const input = instance.createEncryptedInput(contractAddress, userAddress);
      input.add64(amount);
      const encrypted = await input.encrypt();
      res.json({
        ciphertextHandle: toHex(encrypted.handles[0]),
        inputProof: toHex(encrypted.inputProof),
      });
    } catch (err) {
      res.status(502).json({ error: `Relayer encrypt-input failed: ${(err as Error).message}` });
    }
  });

  return router;
}
