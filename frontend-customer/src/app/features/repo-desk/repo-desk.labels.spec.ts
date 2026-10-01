import { describe, expect, it } from 'vitest';
import { SETTLEMENT_SELF_CONFIRMED, eventLabel, settlementLabel } from './repo-desk.labels';

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
});
