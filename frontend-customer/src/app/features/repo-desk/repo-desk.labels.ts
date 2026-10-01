import { RepoDefaultGround, RepoSettlementMethod } from '../../core/models';

/**
 * Registerwerk does not operate a settlement system for the repo desk: both legs are declared and confirmed by the
 * parties themselves (5C-05). The label must therefore never say "DvP" / "delivery versus payment".
 */
export const SETTLEMENT_SELF_CONFIRMED = 'Bilateral, self-confirmed settlement';

export function settlementLabel(method: RepoSettlementMethod): string {
  return method === 'DVP' ? SETTLEMENT_SELF_CONFIRMED : 'Free of payment, self-confirmed';
}

export function humanize(value: string): string {
  return value.toLowerCase().split('_').map(v => (v ? v[0].toUpperCase() + v.slice(1) : v)).join(' ');
}

const EVENT_LABELS: Record<string, string> = {
  TRADE_CONFIRMED: 'Trade confirmed',
  CASH_SENT: 'Cash declared sent',
  COLLATERAL_SENT: 'Collateral declared sent',
  OPEN_CASH_CONFIRMED: 'Opening cash receipt confirmed',
  OPEN_COLLATERAL_CONFIRMED: 'Opening collateral receipt confirmed',
  OPEN_SETTLED: 'Opening settlement complete',
  MARGIN_CALL: 'Margin call issued',
  MARGIN_DELIVERED: 'Margin declared sent',
  MARGIN_DISPUTED: 'Margin disputed',
  MARGIN_SATISFIED: 'Margin receipt confirmed, call cleared',
  SUBSTITUTION_REQUESTED: 'Substitution requested',
  SUBSTITUTION_APPROVED: 'Substitution approved',
  SUBSTITUTION_REJECTED: 'Substitution rejected',
  SUBSTITUTION_WITHDRAWN: 'Substitution withdrawn',
  SUBSTITUTION_EXPIRED: 'Substitution expired',
  SUBSTITUTION_REPLACEMENT_RECEIVED: 'Replacement collateral receipt confirmed',
  SUBSTITUTION_ORIGINAL_RETURNED: 'Original collateral return confirmed',
  SUBSTITUTION_COMPLETED: 'Substitution completed',
  CLOSE_INITIATED: 'Closing settlement started',
  CLOSE_CASH_CONFIRMED: 'Repurchase cash receipt confirmed',
  CLOSE_COLLATERAL_CONFIRMED: 'Collateral return confirmed',
  CLOSED: 'Trade closed',
  DEFAULT_NOTICE: 'Default notice served',
  DEFAULT_DECLARED: 'Default declared',
  DISPUTE_OPENED: 'Dispute opened',
  DISPUTE_RESOLVED: 'Dispute outcome recorded by operator',
  EVIDENCE_NOTE: 'Evidence note',
  PARTY_FLAGGED: 'Party no longer eligible (flagged)',
  CORPORATE_ACTION_DURING_TERM: 'Corporate action during the term',
};

export function eventLabel(type: string): string {
  return EVENT_LABELS[type] ?? humanize(type);
}

const GROUND_LABELS: Record<RepoDefaultGround, string> = {
  MARGIN_NOT_MET: 'Margin call not met',
  REPURCHASE_UNPAID: 'Repurchase amount unpaid',
  COLLATERAL_RETURN_FAILURE: 'Collateral not returned',
};

export function groundLabel(ground: RepoDefaultGround | string | null): string {
  return ground ? (GROUND_LABELS[ground as RepoDefaultGround] ?? humanize(ground)) : '';
}
