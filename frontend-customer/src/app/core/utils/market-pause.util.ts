/**
 * 9A-06: customer wording for the pause reasons the registry sets when a lending market's collateral can no longer
 * back new borrowing. The reason is `COLLATERAL_ASSET_<ASSET STATUS>` (suspended, in redemption, transferring, ...) or
 * `COLLATERAL_BOND_<BOND STATUS>` (overdue, defaulted, ...). Returns null for every other reason. Repay, withdraw and
 * liquidation stay possible, so the text says so.
 */
export function collateralPauseMessage(reason: string | null | undefined): string | null {
  if (!reason) {
    return null;
  }
  const tail = (prefix: string): string => reason.slice(prefix.length).toLowerCase().replace(/_/g, ' ');
  if (reason.startsWith('COLLATERAL_ASSET_')) {
    return `New borrowing is paused: the collateral security is ${tail('COLLATERAL_ASSET_')} at the registry. Repaying and withdrawing are unaffected.`;
  }
  if (reason.startsWith('COLLATERAL_BOND_')) {
    return `New borrowing is paused: the payment status of the collateral bond is ${tail('COLLATERAL_BOND_')}. Repaying and withdrawing are unaffected.`;
  }
  return null;
}
