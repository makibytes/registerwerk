import { pauseReasonText } from './lending-pause-reason';

describe('pauseReasonText (9A-06)', () => {
  it('keeps the fixed wording', () => {
    expect(pauseReasonText('COLLATERAL_SHORTFALL')).toBe('Collateral shortfall (reconcile)');
    expect(pauseReasonText('BORROW_PAUSED_ONCHAIN')).toBe('Borrowing paused on-chain');
  });

  it('words the automatic collateral pauses and says unpause stays 4-eyes', () => {
    expect(pauseReasonText('COLLATERAL_ASSET_SUSPENDED')).toContain('Collateral asset suspended');
    expect(pauseReasonText('COLLATERAL_ASSET_REDEMPTION_PENDING')).toContain('redemption pending');
    expect(pauseReasonText('COLLATERAL_BOND_DEFAULTED')).toContain('Collateral bond defaulted');
    expect(pauseReasonText('COLLATERAL_BOND_OVERDUE')).toContain('4-eyes');
  });

  it('shows an unknown reason as is', () => {
    expect(pauseReasonText('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});
