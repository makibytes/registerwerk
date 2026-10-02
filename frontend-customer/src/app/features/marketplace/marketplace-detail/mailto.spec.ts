import { describe, expect, it } from 'vitest';
import { publisherMailto } from './mailto';

describe('publisherMailto', () => {
  it('encodes the subject so a hostile dApp name cannot add headers', () => {
    const href = publisherMailto('dev@example.com', 'x&bcc=attacker@evil.example');
    expect(href).toBe('mailto:dev@example.com?subject=Registerwerk%20dApp%3A%20x%26bcc%3Dattacker%40evil.example');
    expect(href).not.toContain('&bcc');
  });

  it('refuses addresses that could inject headers or are not addresses', () => {
    expect(publisherMailto('a@b.com?bcc=evil@x.com', 'n')).toBeNull();
    expect(publisherMailto('a@b.com,evil@x.com', 'n')).toBeNull();
    expect(publisherMailto('not-an-address', 'n')).toBeNull();
    expect(publisherMailto(null, 'n')).toBeNull();
  });
});
