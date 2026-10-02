import { describe, expect, it } from 'vitest';
import { indicativeMark, nominalTotalsByCurrency } from './portfolio.util';

const NOW = Date.parse('2026-10-01T12:00:00Z');
const fresh = { pricePerUnit: '1000000000000000000', priceUpdatedAt: '2026-10-01T11:59:00Z', oracleReliable: true };

describe('portfolio value (interim, 8X-01)', () => {
  it('never adds EUR and USD into one number', () => {
    const totals = nominalTotalsByCurrency([
      { currency: 'EUR', nominalAmount: 1000 }, { currency: 'USD', nominalAmount: 500 }, { currency: 'EUR', nominalAmount: 250 },
    ]);
    expect(totals).toEqual([{ currency: 'EUR', total: 1250 }, { currency: 'USD', total: 500 }]);
  });

  it('marks an 18-decimal market correctly (hard-coded 1e6 would be off by 1e12)', () => {
    const m = indicativeMark(10, fresh, { loanTokenDecimals: 18, loanRailCode: 'USDC', maxPriceAgeSeconds: '300' }, NOW);
    expect(m?.display).toBe('10');
    expect(m?.currency).toBe('USDC');
  });

  it('6-decimal market with fractional price', () => {
    const m = indicativeMark(3, { ...fresh, pricePerUnit: '1250000' }, { loanTokenDecimals: 6, loanRailCode: 'USDC', maxPriceAgeSeconds: '300' }, NOW);
    expect(m?.display).toBe('3.75');
  });

  it('no mark when the oracle is unreliable, the price is stale or the amount is not a whole unit', () => {
    const market = { loanTokenDecimals: 6, loanRailCode: 'USDC', maxPriceAgeSeconds: '300' };
    expect(indicativeMark(10, { ...fresh, oracleReliable: false }, market, NOW)).toBeNull();
    expect(indicativeMark(10, { ...fresh, priceUpdatedAt: '2026-10-01T11:00:00Z' }, market, NOW)).toBeNull();
    expect(indicativeMark(10.5, fresh, market, NOW)).toBeNull();
    expect(indicativeMark(10, fresh, { ...market, maxPriceAgeSeconds: null }, NOW)).toBeNull();
  });
});
