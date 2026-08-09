import { Router, type Request, type Response } from 'express';
import type { RelayerConfig } from '../config.js';
import { getFheInstance } from '../fheInstance.js';
import { loadOperatorAccount, signUserDecryptRequest } from '../operatorSigner.js';
import { fromHex } from '../hex.js';

/**
 * `POST /v1/operator-decrypt` — headless decryption for the registry operator/auditor viewer
 * role, with no browser/wallet involved (unlike an investor revealing their OWN balance, which
 * is entirely client-side — see the frontends' `FheClientService`).
 *
 * Whole request happens in one round trip: generates a fresh ephemeral FHE keypair, builds the
 * KMS's `UserDecryptRequestVerification` EIP-712 payload for it, self-signs that payload with the
 * dedicated operator-decrypt key (see `operatorSigner.ts`), then immediately completes the
 * `userDecrypt` call. No cross-request state to manage — the ephemeral keypair only needs to
 * live for the duration of this one request.
 *
 * Requires the operator-decrypt address to already be a registered viewer
 * (`ConfidentialERC20.isViewer`) on the token being decrypted — the KMS enforces that ACL
 * server-side; an unregistered caller does not get a plausible-looking wrong answer, the request
 * fails.
 */
export function operatorDecryptRouter(config: RelayerConfig): Router {
  const router = Router();

  router.post('/', async (req: Request, res: Response) => {
    const { ciphertextHandle, contractAddress } = req.body ?? {};
    if (typeof ciphertextHandle !== 'string' || typeof contractAddress !== 'string') {
      res.status(400).json({ error: 'ciphertextHandle and contractAddress are required' });
      return;
    }

    try {
      const account = loadOperatorAccount(config);
      const instance = await getFheInstance(config);

      const keypair = instance.generateKeypair();
      const startTimestamp = Math.floor(Date.now() / 1000);
      const durationDays = config.operatorDecryptDurationDays;
      const eip712 = instance.createEIP712(keypair.publicKey, [contractAddress], startTimestamp, durationDays);
      const signature = await signUserDecryptRequest(account, eip712);

      const results = await instance.userDecrypt(
        [{ handle: fromHex(ciphertextHandle), contractAddress }],
        keypair.privateKey,
        keypair.publicKey,
        signature,
        [contractAddress],
        account.address,
        startTimestamp,
        durationDays
      );

      const values = Object.values(results);
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
      res.status(502).json({ error: `Relayer operator-decrypt failed: ${(err as Error).message}` });
    }
  });

  return router;
}
