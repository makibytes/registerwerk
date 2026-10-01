import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';

/**
 * Port for Key-Encryption-Key (KEK) providers — mirrors the backend's own
 * `wallet.api.KekProvider` (Java) so the two envelope-encryption schemes stay conceptually
 * identical even though they're implemented in different languages for different secrets
 * (wallet private keys there, the operator-decrypt key here).
 */
export interface KekProvider {
  /** Human-readable name, useful in startup logs. */
  readonly name: string;

  /** Wraps (encrypts) plaintext. The returned buffer is opaque and only unwrappable by this provider. */
  wrap(plaintext: Buffer): Buffer;

  /** Unwraps (decrypts) a previously wrapped value. */
  unwrap(wrapped: Buffer): Buffer;
}

const GCM_IV_LEN = 12;
const GCM_TAG_LEN = 16;

/**
 * Dev/test-only KEK provider backed by an environment-variable master key. Byte-for-byte mirrors
 * the backend's `wallet.internal.EnvVarKekProvider`: the AES-256 key is SHA-256(masterKey), a
 * fresh random 12-byte IV is generated per wrap, and the wire format is `iv || ciphertext || tag`
 * (16-byte GCM tag) — matching how `javax.crypto.Cipher` in GCM mode appends its tag to the
 * ciphertext, since Node's `crypto` module returns the tag separately via `getAuthTag()`.
 *
 * Not suitable for production: the master key sits in plaintext in the process environment,
 * exactly the property this provider exists to move the operator-decrypt key OUT of. Swap for a
 * real cloud-KMS-backed `KekProvider` (AWS KMS / Azure Key Vault / GCP KMS, matching the backend's
 * equivalents) before going live with real confidential-token viewer keys.
 */
export class EnvVarKekProvider implements KekProvider {
  readonly name = 'ENV_VAR_KEK';
  private readonly rawKey: Buffer;

  constructor(masterKey: string) {
    this.rawKey = createHash('sha256').update(masterKey, 'utf8').digest();
  }

  wrap(plaintext: Buffer): Buffer {
    const iv = randomBytes(GCM_IV_LEN);
    const cipher = createCipheriv('aes-256-gcm', this.rawKey, iv);
    const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    const tag = cipher.getAuthTag();
    return Buffer.concat([iv, ciphertext, tag]);
  }

  unwrap(wrapped: Buffer): Buffer {
    if (wrapped.length < GCM_IV_LEN + GCM_TAG_LEN) {
      throw new Error('Wrapped value is too short to contain an IV and a GCM tag');
    }
    const iv = wrapped.subarray(0, GCM_IV_LEN);
    const tag = wrapped.subarray(wrapped.length - GCM_TAG_LEN);
    const ciphertext = wrapped.subarray(GCM_IV_LEN, wrapped.length - GCM_TAG_LEN);
    const decipher = createDecipheriv('aes-256-gcm', this.rawKey, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(ciphertext), decipher.final()]);
  }
}

/**
 * Placeholder for a cloud-KMS-backed provider. Mirrors the backend's AWS_KMS / GCP_KMS /
 * AZURE_KEY_VAULT provider names so the selection mechanism ({@link createKekProvider}) is in place,
 * but no vendor client is bundled: every method throws until a real implementation is wired for a
 * chosen target (parked decision T7-05). Failing loudly beats silently falling back to the
 * environment-variable provider.
 */
class UnconfiguredKekProvider implements KekProvider {
  constructor(readonly name: string) {}

  wrap(): Buffer {
    throw new Error(`KEK provider ${this.name} is not configured in this build`);
  }

  unwrap(): Buffer {
    throw new Error(`KEK provider ${this.name} is not configured in this build`);
  }
}

export const KEK_PROVIDER_NAMES = ['ENV_VAR', 'AWS_KMS', 'GCP_KMS', 'AZURE_KEY_VAULT'] as const;
export type KekProviderName = (typeof KEK_PROVIDER_NAMES)[number];

/**
 * Registry of KEK providers keyed by `RELAYER_KEK_PROVIDER`. Only `ENV_VAR` is functional; the KMS
 * entries are stubs that throw "not configured" when used.
 */
export function createKekProvider(name: string, env: NodeJS.ProcessEnv = process.env): KekProvider {
  switch (name) {
    case 'ENV_VAR': {
      const masterKey = env.RELAYER_KEK_MASTER_KEY;
      if (!masterKey || masterKey.trim() === '') {
        throw new Error('Missing required environment variable: RELAYER_KEK_MASTER_KEY');
      }
      return new EnvVarKekProvider(masterKey);
    }
    case 'AWS_KMS':
    case 'GCP_KMS':
    case 'AZURE_KEY_VAULT':
      return new UnconfiguredKekProvider(name);
    default:
      throw new Error(`RELAYER_KEK_PROVIDER must be one of ${KEK_PROVIDER_NAMES.join(', ')}, got: ${name}`);
  }
}
