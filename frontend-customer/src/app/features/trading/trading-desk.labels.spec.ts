import { describe, expect, it } from 'vitest';
import { PAYMENT_DISPUTED_TOAST, failedExplanation } from './trading-desk.labels';

describe('trading desk wording', () => {
  it('dispute toast says the units stay reserved, never that they were returned', () => {
    expect(PAYMENT_DISPUTED_TOAST).toContain('stay reserved');
    expect(PAYMENT_DISPUTED_TOAST).not.toMatch(/returned|failed/i);
  });

  it('FAILED after a declared payment does not claim the units were released', () => {
    expect(failedExplanation({ paymentReference: 'REF-1', paymentDeclaredAt: '2026-01-01T00:00:00Z' }))
      .toContain('not automatically released');
    expect(failedExplanation({ paymentReference: null, paymentDeclaredAt: null })).toContain('The units were released');
  });
});
