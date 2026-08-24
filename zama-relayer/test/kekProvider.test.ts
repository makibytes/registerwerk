import { describe, expect, it } from 'vitest';
import { EnvVarKekProvider } from '../src/kekProvider.js';

describe('EnvVarKekProvider', () => {
  it('round-trips an arbitrary plaintext', () => {
    const provider = new EnvVarKekProvider('master-key-1');
    const plaintext = Buffer.from(`0x${'ab'.repeat(32)}`, 'utf8');

    const wrapped = provider.wrap(plaintext);
    expect(wrapped.equals(plaintext)).toBe(false);
    expect(provider.unwrap(wrapped).equals(plaintext)).toBe(true);
  });

  it('produces a different ciphertext each time (random IV per wrap)', () => {
    const provider = new EnvVarKekProvider('master-key-1');
    const plaintext = Buffer.from('same input', 'utf8');

    const a = provider.wrap(plaintext);
    const b = provider.wrap(plaintext);

    expect(a.equals(b)).toBe(false);
    expect(provider.unwrap(a).equals(plaintext)).toBe(true);
    expect(provider.unwrap(b).equals(plaintext)).toBe(true);
  });

  it('fails to unwrap with the wrong master key', () => {
    const wrapped = new EnvVarKekProvider('correct-key').wrap(Buffer.from('secret', 'utf8'));

    expect(() => new EnvVarKekProvider('wrong-key').unwrap(wrapped)).toThrow();
  });

  it('fails to unwrap a tampered ciphertext (GCM authentication)', () => {
    const provider = new EnvVarKekProvider('master-key-1');
    const wrapped = provider.wrap(Buffer.from('secret', 'utf8'));
    wrapped[wrapped.length - 1] ^= 0xff;

    expect(() => provider.unwrap(wrapped)).toThrow();
  });

  it('rejects a wrapped value too short to contain an IV and tag', () => {
    const provider = new EnvVarKekProvider('master-key-1');

    expect(() => provider.unwrap(Buffer.from([1, 2, 3]))).toThrow(/too short/);
  });
});
