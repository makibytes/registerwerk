import { toHex, fromHex } from '../src/hex';

describe('hex round-trip', () => {
  it('converts bytes to 0x hex and back', () => {
    const bytes = new Uint8Array([0xde, 0xad, 0xbe, 0xef]);
    const hex = toHex(bytes);
    expect(hex).toBe('0xdeadbeef');
    expect(fromHex(hex)).toEqual(bytes);
  });

  it('accepts hex without a 0x prefix', () => {
    expect(fromHex('deadbeef')).toEqual(new Uint8Array([0xde, 0xad, 0xbe, 0xef]));
  });

  it('round-trips a 32-byte handle', () => {
    const bytes = new Uint8Array(32).fill(7);
    expect(fromHex(toHex(bytes))).toEqual(bytes);
  });
});
