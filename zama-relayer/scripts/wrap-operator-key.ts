#!/usr/bin/env node
/**
 * Offline utility (finding #2, Phase 9): wraps a raw operator-decrypt private key under
 * `RELAYER_KEK_MASTER_KEY` so it can be stored as `OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED` instead
 * of plaintext. Never run this against a production master key from a shared/logged shell —
 * treat the printed wrapped value like any other secret in transit.
 *
 * Usage:
 *   RELAYER_KEK_MASTER_KEY=<master-key> npm run wrap-operator-key -- <0x-prefixed-32-byte-key>
 */
import { EnvVarKekProvider } from '../src/kekProvider.js';
import { toHex } from '../src/hex.js';

function main(): void {
  const rawKey = process.argv[2];
  if (!rawKey || !/^0x[0-9a-fA-F]{64}$/.test(rawKey)) {
    console.error('Usage: RELAYER_KEK_MASTER_KEY=<master-key> npm run wrap-operator-key -- <0x-prefixed-32-byte-key>');
    process.exit(1);
  }

  const masterKey = process.env.RELAYER_KEK_MASTER_KEY;
  if (!masterKey || masterKey.trim() === '') {
    console.error('RELAYER_KEK_MASTER_KEY must be set in the environment.');
    process.exit(1);
  }

  const provider = new EnvVarKekProvider(masterKey);
  const wrapped = provider.wrap(Buffer.from(rawKey.slice(2), 'hex'));

  console.log('OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED=' + toHex(wrapped));
}

main();
