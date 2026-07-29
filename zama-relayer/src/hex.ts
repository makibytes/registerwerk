/** Converts a `Uint8Array` (as returned by the SDK's `encrypt()`) to a 0x-prefixed hex string. */
export function toHex(bytes: Uint8Array): `0x${string}` {
  return `0x${Buffer.from(bytes).toString('hex')}`;
}

/** Converts a 0x-prefixed hex string (or bare hex) to a `Uint8Array`, as the SDK's handle/proof inputs expect. */
export function fromHex(hex: string): Uint8Array {
  const clean = hex.startsWith('0x') ? hex.slice(2) : hex;
  return new Uint8Array(Buffer.from(clean, 'hex'));
}
