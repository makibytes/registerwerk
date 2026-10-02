const SIMPLE_ADDRESS = /^[A-Za-z0-9.!#$%'*+/=^_`{|}~-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/;

/**
 * `mailto:` link for a publisher contact. The address is validated (no `?`, `&`, `,`, spaces, so it
 * cannot smuggle extra headers such as bcc) and the subject is percent-encoded; an invalid address
 * yields no link at all (8B-13).
 */
export function publisherMailto(email: string | null | undefined, dappName: string): string | null {
  const address = (email ?? '').trim();
  if (!SIMPLE_ADDRESS.test(address)) return null;
  return `mailto:${address}?subject=${encodeURIComponent('Registerwerk dApp: ' + dappName)}`;
}
