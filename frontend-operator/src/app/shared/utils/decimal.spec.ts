import { describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { RwDecimalPipe, formatDecimalString, isDecimalString, parseJsonPreservingBigNumbers } from '@registerwerk/ui';

describe('parseJsonPreservingBigNumbers', () => {
  it('keeps integers and decimals with 16+ significant digits as exact strings', () => {
    const parsed = parseJsonPreservingBigNumbers(
      '{"a":12345678901234567890123,"b":[0.1234567890123456789,-98765432109876543.21],"c":1.5,"d":42}',
    ) as { a: string; b: string[]; c: number; d: number };
    expect(parsed.a).toBe('12345678901234567890123');
    expect(parsed.b).toEqual(['0.1234567890123456789', '-98765432109876543.21']);
    expect(parsed.c).toBe(1.5);
    expect(parsed.d).toBe(42);
  });

  it('never touches string contents, even when they look like numbers', () => {
    const parsed = parseJsonPreservingBigNumbers('{"note":"a,12345678901234567890,b","tx":"0x1234567890123456789012"}') as Record<string, string>;
    expect(parsed['note']).toBe('a,12345678901234567890,b');
    expect(parsed['tx']).toBe('0x1234567890123456789012');
  });

  it('leaves 15-digit values and exponent literals as numbers and handles escaped quotes', () => {
    const parsed = parseJsonPreservingBigNumbers('{"x":123456789012345,"e":1.5e30,"q":"say \\"1234567890123456789\\""}') as Record<string, unknown>;
    expect(parsed['x']).toBe(123456789012345);
    expect(parsed['e']).toBe(1.5e30);
    expect(parsed['q']).toBe('say "1234567890123456789"');
  });

  it('is identical to JSON.parse for ordinary payloads', () => {
    const text = '{"n":[1,2.5,-3,0.001],"s":"x","b":true,"z":null}';
    expect(parseJsonPreservingBigNumbers(text)).toEqual(JSON.parse(text));
  });
});

describe('formatDecimalString', () => {
  it('formats base units above 2^53 without rounding the integer part', () => {
    expect(formatDecimalString('12345678901234567890123', 0, 0)).toBe('12,345,678,901,234,567,890,123');
    expect(formatDecimalString('9007199254740993', 0, 2)).toBe('9,007,199,254,740,993');
  });

  it('honours min/max fraction digits and rounds half away from zero', () => {
    expect(formatDecimalString('1234567890123456.125', 2, 2)).toBe('1,234,567,890,123,456.13');
    expect(formatDecimalString('1234567890123456.1', 2, 4)).toBe('1,234,567,890,123,456.10');
    expect(formatDecimalString('-1234567890123456.5', 0, 0)).toBe('-1,234,567,890,123,457');
    expect(formatDecimalString('999999999999999999.999', 0, 2)).toBe('1,000,000,000,000,000,000');
  });

  it('does not render negative zero', () => {
    expect(formatDecimalString('-0.0001', 0, 2)).toBe('0');
  });

  it('recognises plain decimal strings only', () => {
    expect(isDecimalString('123.45')).toBe(true);
    expect(isDecimalString('1e5')).toBe(false);
    expect(isDecimalString(12)).toBe(false);
  });
});

describe('RwDecimalPipe', () => {
  const pipe = TestBed.runInInjectionContext(() => new RwDecimalPipe());

  it('matches the number pipe for ordinary values and is exact above 2^53', () => {
    expect(pipe.transform(1234.5, '1.2-2')).toBe('1,234.50');
    expect(pipe.transform('1234.5', '1.2-2')).toBe('1,234.50');
    expect(pipe.transform('123456789012345678901', '1.0-0')).toBe('123,456,789,012,345,678,901');
    expect(pipe.transform('0.123456789012345678', '1.0-8')).toBe('0.12345679');
  });

  it('renders nothing for missing or non-numeric input', () => {
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform('n/a')).toBe('');
  });
});
