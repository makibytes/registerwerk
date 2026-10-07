/**
 * A jurisdiction KYC rejection reaches the browser only as one of four fixed categories (the operator's free
 * text stays in the audit trail). Anything else - e.g. a legacy row - reads as CONTACT_SUPPORT, never as raw text.
 */
export type KycRejectionCategoryCode =
  | 'INFORMATION_INCOMPLETE' | 'DOCUMENTS_UNREADABLE' | 'INFORMATION_INCONSISTENT' | 'CONTACT_SUPPORT';

const TEXT: Record<KycRejectionCategoryCode, string> = {
  INFORMATION_INCOMPLETE: 'The information provided is incomplete.',
  DOCUMENTS_UNREADABLE: 'One or more documents could not be read.',
  INFORMATION_INCONSISTENT: 'The information provided is inconsistent.',
  CONTACT_SUPPORT: 'Please contact support for details.',
};

export function toKycRejectionCategory(value: string | null | undefined): KycRejectionCategoryCode | null {
  if (!value) return null;
  return value in TEXT ? (value as KycRejectionCategoryCode) : 'CONTACT_SUPPORT';
}

/** Display text of a stored rejection category; null when there is none. */
export function kycRejectionText(value: string | null | undefined): string | null {
  const category = toKycRejectionCategory(value);
  return category ? TEXT[category] : null;
}
