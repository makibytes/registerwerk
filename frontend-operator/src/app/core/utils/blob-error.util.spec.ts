import { describe, expect, it } from 'vitest';
import { blobErrorMessage } from './blob-error.util';

describe('blobErrorMessage (9A-05)', () => {
  const blob = (body: string) => new Blob([body], { type: 'application/json' });

  it('reads the server message out of a blob error body', async () => {
    const err = { status: 409, error: blob(JSON.stringify({ status: 409, message: 'Register statement refused: the register is being reconciled' })) };
    expect(await blobErrorMessage(err, 'fallback')).toBe('Register statement refused: the register is being reconciled');
  });

  it('falls back for a non-JSON body, a missing message or a non-http error', async () => {
    expect(await blobErrorMessage({ error: blob('<html>bad gateway</html>') }, 'fallback')).toBe('fallback');
    expect(await blobErrorMessage({ error: blob('{}') }, 'fallback')).toBe('fallback');
    expect(await blobErrorMessage(new Error('boom'), 'fallback')).toBe('fallback');
    expect(await blobErrorMessage(null, 'fallback')).toBe('fallback');
  });

  it('also understands an already parsed error body', async () => {
    expect(await blobErrorMessage({ error: { message: 'plain' } }, 'fallback')).toBe('plain');
  });
});
