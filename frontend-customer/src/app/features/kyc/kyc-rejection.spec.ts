import { describe, expect, it } from 'vitest';
import { kycRejectionText, toKycRejectionCategory } from '@registerwerk/ui';

describe('KYC rejection category display', () => {
  it('maps each fixed category to customer-safe text', () => {
    expect(kycRejectionText('DOCUMENTS_UNREADABLE')).toMatch(/could not be read/);
    expect(kycRejectionText('INFORMATION_INCOMPLETE')).toMatch(/incomplete/);
    expect(kycRejectionText('INFORMATION_INCONSISTENT')).toMatch(/inconsistent/);
    expect(kycRejectionText('CONTACT_SUPPORT')).toMatch(/contact support/i);
  });

  it('never renders legacy free text: unknown values read as CONTACT_SUPPORT', () => {
    expect(toKycRejectionCategory('Sanctions hit on UBO Mustermann')).toBe('CONTACT_SUPPORT');
    expect(kycRejectionText('Sanctions hit on UBO Mustermann')).not.toMatch(/Mustermann/);
  });

  it('has no text when there is no rejection', () => {
    expect(kycRejectionText(null)).toBeNull();
    expect(kycRejectionText('')).toBeNull();
  });
});
