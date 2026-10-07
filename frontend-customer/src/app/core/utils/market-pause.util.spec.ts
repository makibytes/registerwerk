import { collateralPauseMessage } from './market-pause.util';

describe('collateralPauseMessage (9A-06)', () => {
  it('words a suspended or redeeming collateral asset', () => {
    expect(collateralPauseMessage('COLLATERAL_ASSET_SUSPENDED')).toContain('collateral security is suspended');
    expect(collateralPauseMessage('COLLATERAL_ASSET_REDEMPTION_PENDING')).toContain('redemption pending');
  });

  it('words an overdue or defaulted collateral bond', () => {
    expect(collateralPauseMessage('COLLATERAL_BOND_OVERDUE')).toContain('bond is overdue');
    expect(collateralPauseMessage('COLLATERAL_BOND_DEFAULTED')).toContain('bond is defaulted');
  });

  it('says that repay and withdraw stay possible', () => {
    expect(collateralPauseMessage('COLLATERAL_ASSET_SUSPENDED')).toContain('Repaying and withdrawing are unaffected');
  });

  it('leaves every other reason to the existing wording', () => {
    expect(collateralPauseMessage('COLLATERAL_SHORTFALL')).toBeNull();
    expect(collateralPauseMessage(null)).toBeNull();
    expect(collateralPauseMessage(undefined)).toBeNull();
  });
});
