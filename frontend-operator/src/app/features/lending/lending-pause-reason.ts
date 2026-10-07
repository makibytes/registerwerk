/**
 * Operator wording for a lending market's `pauseReason`. 9A-06: besides the fixed codes the registry now pauses a
 * market by itself when its collateral can no longer back new borrowing: `COLLATERAL_ASSET_<ASSET STATUS>` (suspended,
 * in redemption, transferring, ...) and `COLLATERAL_BOND_<BOND STATUS>` (overdue, defaulted, ...). The on-chain pause is
 * submitted automatically; lifting it stays the 4-eyes borrow-pause action, never automatic.
 */
const FIXED: Record<string, string> = {
  COLLATERAL_SHORTFALL: 'Collateral shortfall (reconcile)',
  BINDING_UNVERIFIED: 'Binding unverified (re-verify)',
  BORROW_PAUSED_ONCHAIN: 'Borrowing paused on-chain',
  CHAIN_READ_FAILED: 'Chain read failed',
};

export function pauseReasonText(reason: string): string {
  const fixed = FIXED[reason];
  if (fixed) {
    return fixed;
  }
  const words = (prefix: string): string => reason.slice(prefix.length).toLowerCase().replace(/_/g, ' ');
  if (reason.startsWith('COLLATERAL_ASSET_')) {
    return `Collateral asset ${words('COLLATERAL_ASSET_')} (auto-paused; unpause is a 4-eyes action)`;
  }
  if (reason.startsWith('COLLATERAL_BOND_')) {
    return `Collateral bond ${words('COLLATERAL_BOND_')} (auto-paused; unpause is a 4-eyes action)`;
  }
  return reason;
}
