/** Answer of `GET /lending/lender-eligibility` (T2-20): whether the lender gate is enforced and whether this entity passes. */
export interface LenderEligibility {
  /** False in demo mode: the gate is not enforced and the demo wording stays. */
  productionMode: boolean;
  eligible: boolean;
  reasons: string[];
}

export const LENDER_DEMO_NOTE = 'Lender-side eligibility is under legal review.';

export const LENDER_PRODUCTION_NOTE =
  'Lender-side access is limited to approved, screened professional clients and eligible counterparties.';

/** True only when production mode enforces the gate and this entity fails it. */
export function lenderBlocked(gate: LenderEligibility | null | undefined): boolean {
  return !!gate && gate.productionMode && !gate.eligible;
}

/** The sentence shown on the Supply & Earn pages: a factual statement in production, the demo wording otherwise. */
export function lenderNote(gate: LenderEligibility | null | undefined): string {
  return gate?.productionMode ? LENDER_PRODUCTION_NOTE : LENDER_DEMO_NOTE;
}
