import { AssetBondTerms, CorporateActionView, CouponScheduleEntry } from '../models';

type BondStatus = AssetBondTerms['bondStatus'];

export type Perspective = 'issuer' | 'investor';

/** Settlement progress text for a corporate action, from the issuer's or an investor's point of view. */
export function corporateActionProgress(a: Pick<CorporateActionView, 'status' | 'settlementTxHash' | 'issuerAttestedAt' | 'dualControlApprovedAt' | 'heldOutstanding'>,
                                         perspective: Perspective): string {
  switch (a.status) {
    case 'PROPOSED': return 'Awaiting operator review';
    case 'REJECTED': return perspective === 'issuer' ? 'Rejected — submit a fresh proposal' : 'Rejected';
    case 'CANCELLED': return 'Cancelled';
    case 'SNAPSHOT_BLOCKED': return 'Waiting for register reconciliation - no action needed from you';
    case 'SETTLED':
    case 'CLOSED': {
      const base = a.settlementTxHash ? `${a.settlementTxHash.slice(0, 10)}…` : 'Settled off-chain';
      return a.heldOutstanding ? `${base} - Settled - some entitlements still held` : base;
    }
    default:
      if (!a.issuerAttestedAt) return perspective === 'issuer' ? 'Awaiting your attestation' : 'Awaiting issuer attestation';
      if (!a.dualControlApprovedAt) return perspective === 'issuer' ? 'Attested — awaiting operator confirmation' : 'Issuer attested — awaiting operator confirmation';
      return 'Confirmed — awaiting settlement dispatch';
  }
}

export function hasHeldEntitlements(a: Pick<CorporateActionView, 'status' | 'heldOutstanding'>): boolean {
  return !!a.heldOutstanding && (a.status === 'SETTLED' || a.status === 'CLOSED');
}

/** Neutral, factual label for the bond status (no default wording beyond the registry's own status). */
export function bondStatusLabel(status: BondStatus): string {
  switch (status) {
    case 'OVERDUE': return 'Payment overdue';
    case 'DEFAULTED': return 'Defaulted (registry status)';
    default: return status;
  }
}

/**
 * Coupon rows to show: every OVERDUE/MISSED row (never silently dropped), the next `upcoming` SCHEDULED
 * rows and the latest `paid` PAID rows, newest payment date first.
 */
export function couponWindow(rows: readonly CouponScheduleEntry[], upcoming = 4, paid = 3): CouponScheduleEntry[] {
  const byDate = (a: CouponScheduleEntry, b: CouponScheduleEntry) => a.paymentDate.localeCompare(b.paymentDate);
  const unresolved = rows.filter(r => r.couponStatus === 'OVERDUE' || r.couponStatus === 'MISSED');
  const next = rows.filter(r => r.couponStatus === 'SCHEDULED').sort(byDate).slice(0, upcoming);
  const past = rows.filter(r => r.couponStatus === 'PAID').sort(byDate).slice(-paid);
  return [...unresolved, ...next, ...past].sort((a, b) => byDate(b, a));
}

/** Factual per-row note: badge text and how long ago the payment date passed. */
export function couponNote(c: Pick<CouponScheduleEntry, 'couponStatus' | 'paymentDate' | 'paidDate'>, now: number = Date.now()): string | null {
  const days = Math.floor((now - Date.parse(c.paymentDate)) / 86_400_000);
  const ago = Number.isFinite(days) && days >= 0 ? `payment date passed ${days} day${days === 1 ? '' : 's'} ago` : '';
  switch (c.couponStatus) {
    case 'OVERDUE': return `Payment overdue${ago ? ' - ' + ago : ''}`;
    case 'MISSED': return `Payment missed${ago ? ' - ' + ago : ''}`;
    case 'PAID': return c.paidDate ? `Paid ${c.paidDate}` : 'Paid';
    default: return null;
  }
}
