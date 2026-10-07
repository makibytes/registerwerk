/** Operator wording for the machine-readable reason / gap codes the KYC queue and review return. */
const REASON_LABEL: Record<string, string> = {
  KYC_IN_PROGRESS: 'Awaiting a KYC decision',
  KYC_EXPIRED: 'KYC expired',
  KYC_EXPIRING: 'KYC expires within 30 days',
  EXPIRY_BEYOND_MAX_VALIDITY: 'Validity longer than the allowed maximum',
  CHECKLIST_INCOMPLETE: 'Document checklist incomplete',
  NO_BENEFICIAL_OWNER: 'No beneficial owner registered',
  OWNERSHIP_UNEXPLAINED: 'Ownership not sufficiently identified',
  PEP_WITHOUT_EDD_APPROVAL: 'Confirmed PEP without EDD approval',
  ENTITY_SCREENING_UNRESOLVED: 'Entity screening hit unresolved',
  BENEFICIAL_OWNER_SCREENING_UNRESOLVED: 'Beneficial-owner screening hit unresolved',
  BO_UNVERIFIED: 'Beneficial owner not verified against a document',
};

/** `CHECKLIST_INCOMPLETE (missing=2, ...)` -> "Document checklist incomplete (missing=2, ...)". Unknown codes pass through. */
export function reasonLabel(code: string): string {
  const match = /^([A-Z_]+)(\s.*)?$/.exec(code);
  const label = match ? REASON_LABEL[match[1]] : undefined;
  return label ? `${label}${match?.[2] ?? ''}` : code;
}
