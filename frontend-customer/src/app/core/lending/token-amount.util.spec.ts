import { describe, expect, it } from 'vitest';
import { formatTokenAmount, formatTokenAmountGrouped, fullRepayRequest, parseTokenAmount } from './token-amount.util';

describe('token amount helpers (bigint-safe)', () => {
  it('keeps an 18-decimal debt exact where Number() truncates', () => {
    const debt = 123456789012345678901n;
    expect(formatTokenAmount(debt, 18)).toBe('123.456789012345678901');
    expect(Number(formatTokenAmount(debt, 18)).toString()).not.toBe('123.456789012345678901');
    expect(formatTokenAmountGrouped(1234567890000000000000n, 18)).toBe('1,234.56789');
  });

  it('parses plain decimals only, never exponent notation (String(1e-7) breaks parseUnits)', () => {
    expect(parseTokenAmount('0.000000000000000001', 18)).toBe(1n);
    expect(parseTokenAmount('1e-7', 18)).toBeNull();
    expect(parseTokenAmount('-1', 6)).toBeNull();
    expect(parseTokenAmount('1.0000001', 6)).toBeNull();
    expect(parseTokenAmount('12.5', 6)).toBe(12_500_000n);
  });

  it('full repay requests strictly more than the snapshot debt, never unlimited', () => {
    const debt = 5_000_000n;
    const req = fullRepayRequest(debt, { baseRateWad: '20000000000000000', slopeWad: '100000000000000000' });
    expect(req > debt).toBe(true);
    expect(req).toBe(debt + 50_000n); // 1 % buffer beats one day of 12 % p.a.
    expect(req < 2n ** 255n).toBe(true);
  });

  it('uses one day of interest when that exceeds the percentage buffer', () => {
    const debt = 10n ** 24n;
    const req = fullRepayRequest(debt, { baseRateWad: '0', slopeWad: '10000000000000000000' }, 1n); // 1000 % p.a.
    expect(req - debt).toBe((debt * 10n * 10n ** 18n + 10n ** 18n * 365n - 1n) / (10n ** 18n * 365n));
  });

  it('zero debt requests nothing; tiny debt still gets a positive margin', () => {
    expect(fullRepayRequest(0n, {})).toBe(0n);
    expect(fullRepayRequest(1n, {})).toBe(2n);
  });
});
