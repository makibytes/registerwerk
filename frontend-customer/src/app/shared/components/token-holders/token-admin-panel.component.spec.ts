import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TokenAdminPanelComponent } from './token-admin-panel.component';
import { ForceTransferAction } from './models';

describe('TokenAdminPanelComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TokenAdminPanelComponent],
      providers: [provideZonelessChangeDetection()],
    });
  });

  it('requires and emits the legal authority for a forced transfer', () => {
    const fixture = TestBed.createComponent(TokenAdminPanelComponent);
    const component = fixture.componentInstance;
    const emitted: ForceTransferAction[] = [];
    component.forceTransfer.subscribe((action) => emitted.push(action));
    component.forceTransferForm = {
      fromWallet: '0x1111111111111111111111111111111111111111',
      toWallet: '0x2222222222222222222222222222222222222222',
      amount: 10,
      legalBasis: '',
    };

    component.submitForceTransfer();
    expect(emitted).toEqual([]);

    component.forceTransferForm.legalBasis = 'BaFin decision 2026-001';
    spyOn(window, 'confirm').and.returnValue(true);
    component.submitForceTransfer();

    expect(emitted).toEqual([{
      fromWallet: '0x1111111111111111111111111111111111111111',
      toWallet: '0x2222222222222222222222222222222222222222',
      amount: 10,
      legalBasis: 'BaFin decision 2026-001',
    }]);
  });

  it('rejects fractional amounts that the BigInteger backend cannot accept', () => {
    const fixture = TestBed.createComponent(TokenAdminPanelComponent);
    const component = fixture.componentInstance;
    component.mintForm = {
      recipient: '0x1111111111111111111111111111111111111111',
      amount: 1.5,
    };

    expect(component.isValidMintForm()).toBe(false);
  });
});
