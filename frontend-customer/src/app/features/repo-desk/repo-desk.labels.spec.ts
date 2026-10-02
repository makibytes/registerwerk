import { describe, expect, it } from 'vitest';
import { SETTLEMENT_SELF_CONFIRMED, eventLabel, openCashPrincipal, settlementLabel } from './repo-desk.labels';

describe('repo desk labels', () => {
  it('never labels the self-confirmed settlement as DvP (5C-05)', () => {
    expect(settlementLabel('DVP')).toBe(SETTLEMENT_SELF_CONFIRMED);
    expect(settlementLabel('DVP')).toBe('Bilateral, self-confirmed settlement');
    expect(`${settlementLabel('DVP')} ${settlementLabel('FOP')}`).not.toMatch(/dvp|delivery versus payment/i);
  });

  it('labels the new lifecycle events', () => {
    expect(eventLabel('DEFAULT_NOTICE')).toBe('Default notice served');
    expect(eventLabel('PARTY_FLAGGED')).toContain('no longer eligible');
    expect(eventLabel('SOMETHING_NEW')).toBe('Something New');
  });

  it('cash principal covers open trades only, per role and currency (never one mixed total)', () => {
    const t = (status: string, borrower: boolean, cashCurrency: string, cashAmount: number) =>
      ({ status, borrower, cashCurrency, cashAmount }) as never;
    const lines = openCashPrincipal([
      t('CLOSED', true, 'EUR', 10_000_000),
      t('ACTIVE', true, 'USD', 5_000_000),
      t('ACTIVE', false, 'EUR', 3_000_000),
      t('DEFAULTED', false, 'EUR', 1_000_000),
    ]);
    expect(lines).toEqual([
      { role: 'Borrowed', currency: 'USD', amount: 5_000_000 },
      { role: 'Lent', currency: 'EUR', amount: 3_000_000 },
    ]);
    expect(openCashPrincipal([t('CLOSED', true, 'EUR', 1)])).toEqual([]);
  });
});
