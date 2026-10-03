import { describe, expect, it } from 'vitest';
import { canonicalJson, canonicalNumber, parseStrictJson } from './canonical-json';

/**
 * These vectors are the same as backend DualControlTargetTest: the approver's browser and the server must
 * produce byte-identical canonical JSON, or an honest approval would never match the request it covers.
 */
describe('canonicalJson', () => {
  it('sorts keys and drops whitespace', () => {
    expect(canonicalJson(parseStrictJson('{ "b": 1, "a": [true, null, "x"] }'))).toBe('{"a":[true,null,"x"],"b":1}');
  });

  it.each([
    ['1.50', '1.5'],
    ['100', '100'],
    ['1e3', '1000'],
    ['1E+3', '1000'],
    ['0.000', '0'],
    ['-0', '0'],
    ['-0.0', '0'],
    ['1E-7', '0.0000001'],
    ['12345678901234567890.123456789012345678', '12345678901234567890.123456789012345678'],
    ['123456789012345678901234567890', '123456789012345678901234567890'],
    ['-2.5e-3', '-0.0025'],
  ])('number %s is %s (exact plain decimal, no trailing zeros)', (token, expected) => {
    expect(canonicalNumber(token)).toBe(expected);
  });

  it('refuses a number that would expand to gigabytes of zeros', () => {
    expect(() => canonicalNumber('1e999999999')).toThrow();
  });

  it('a string that looks like a number is left alone', () => {
    expect(canonicalJson({ v: '1.50' })).toBe('{"v":"1.50"}');
  });

  it('escapes strings the way the backend does: short escapes, upper-case \\u00XX for other control characters, nothing else', () => {
    const text = 'a\u0001\u001f\b\t\n\f\r"\\/ä€\u007f ';
    expect(canonicalJson({ k: text })).toBe('{"k":"a\\u0001\\u001F\\b\\t\\n\\f\\r\\"\\\\/ä€\u007f "}');
    expect(canonicalJson({ '\u001f': 1 })).toBe('{"\\u001F":1}');
  });

  it('numbers of an in-memory value are canonicalised like parsed ones', () => {
    expect(canonicalJson({ a: 1500000, b: 0.1, c: 1e21, d: -0 })).toBe('{"a":1500000,"b":0.1,"c":1000000000000000000000,"d":0}');
  });
});

describe('parseStrictJson', () => {
  it('refuses a repeated object key at any depth', () => {
    expect(() => parseStrictJson('{"a":1,"a":2}')).toThrow(/repeated/i);
    expect(() => parseStrictJson('{"o":{"to":"0xA","to":"0xB"}}')).toThrow(/repeated/i);
    expect(() => parseStrictJson('[{"k":1,"k":1}]')).toThrow(/repeated/i);
  });

  it('refuses trailing content and malformed documents', () => {
    expect(() => parseStrictJson('{"a":1} {"b":2}')).toThrow();
    expect(() => parseStrictJson('{oops')).toThrow();
    expect(() => parseStrictJson('')).toThrow();
    expect(() => parseStrictJson('{"a":01}')).toThrow();
  });

  it('refuses a number a browser cannot hold exactly', () => {
    expect(() => parseStrictJson('{"a":12345678901234567890}')).toThrow(/exact/i);
    expect(() => parseStrictJson('{"a":0.1000000000000000055511151231257827}')).toThrow(/exact/i);
    expect(parseStrictJson('{"a":9007199254740991,"b":0.5,"c":100.50}')).toEqual({ a: 9007199254740991, b: 0.5, c: 100.5 });
  });

  it('keeps unicode escapes and nesting intact', () => {
    expect(parseStrictJson('{"s":"\\u00e4\\n","l":[[1],[]]}')).toEqual({ s: 'ä\n', l: [[1], []] });
  });
});
