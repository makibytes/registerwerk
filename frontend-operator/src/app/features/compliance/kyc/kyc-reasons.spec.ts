import { describe, expect, it } from 'vitest';
import { reasonLabel } from './kyc-reasons';

describe('reasonLabel', () => {
  it('translates known codes and keeps the detail suffix of parameterised ones', () => {
    expect(reasonLabel('KYC_IN_PROGRESS')).toBe('Awaiting a KYC decision');
    expect(reasonLabel('CHECKLIST_INCOMPLETE (missing=2, expired=0, tooOld=1)'))
      .toBe('Document checklist incomplete (missing=2, expired=0, tooOld=1)');
    expect(reasonLabel('OWNERSHIP_UNEXPLAINED (identified=40%)')).toBe('Ownership not sufficiently identified (identified=40%)');
  });

  it('passes unknown codes through unchanged', () => {
    expect(reasonLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});
