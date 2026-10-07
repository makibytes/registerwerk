import { describe, expect, it } from 'vitest';
import { LenderEligibility, lenderBlocked, lenderNote } from './lender-copy.util';

const gate = (over: Partial<LenderEligibility>): LenderEligibility => ({
  productionMode: true, eligible: true, reasons: [], ...over,
});

describe('lender-side copy (T2-20)', () => {
  it('keeps the legal-review wording in demo mode and while the gate is unknown', () => {
    expect(lenderNote(null)).toContain('Lender-side eligibility is under legal review');
    expect(lenderNote(gate({ productionMode: false }))).toContain('Lender-side eligibility is under legal review');
  });

  it('states the production requirement as a fact, not as a pending review', () => {
    const text = lenderNote(gate({}));
    expect(text).not.toContain('under legal review');
    expect(text).toContain('approved');
    expect(text).toContain('screened');
    expect(text).toContain('professional');
  });

  it('blocks only when production mode is enforced and the entity fails the gate', () => {
    expect(lenderBlocked(null)).toBe(false);
    expect(lenderBlocked(gate({ productionMode: false, eligible: false }))).toBe(false);
    expect(lenderBlocked(gate({ eligible: true }))).toBe(false);
    expect(lenderBlocked(gate({ eligible: false, reasons: ['KYC is not APPROVED'] }))).toBe(true);
  });
});
