import { beforeEach, describe, expect, it, vi } from "vitest";
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
            amount: '10',
            legalBasis: '',
            totpCode: '123456',
            approvalToken: 'approver-token',
        };

        component.submitForceTransfer();
        expect(emitted).toEqual([]);

        component.forceTransferForm.legalBasis = 'short';
        component.submitForceTransfer();
        expect(emitted).toEqual([]); // legal basis needs >= 10 characters

        component.forceTransferForm.approvalToken = '';
        component.forceTransferForm.legalBasis = 'BaFin decision 2026-001';
        component.submitForceTransfer();
        expect(emitted).toEqual([]); // second approver's token is mandatory

        component.forceTransferForm.approvalToken = 'approver-token';
        vi.spyOn(window, 'confirm').mockReturnValue(true);
        component.submitForceTransfer();

        expect(emitted).toEqual([{
                fromWallet: '0x1111111111111111111111111111111111111111',
                toWallet: '0x2222222222222222222222222222222222222222',
                amount: '10',
                legalBasis: 'BaFin decision 2026-001',
                totpCode: '123456',
                approvalToken: 'approver-token',
            }]);
    });

    it('accepts an amount above 2^53 exactly as a string (no number round trip)', () => {
        const fixture = TestBed.createComponent(TokenAdminPanelComponent);
        const component = fixture.componentInstance;
        const emitted: { amount: string }[] = [];
        component.mint.subscribe((a) => emitted.push(a));
        component.mintForm = {
            recipient: '0x1111111111111111111111111111111111111111',
            amount: '1000000000000000000000',
            totpCode: '',
            approvalToken: 'tok',
        };
        component.submitMint();
        expect(emitted.map(e => e.amount)).toEqual(['1000000000000000000000']);
    });

    it('rejects fractional amounts that the BigInteger backend cannot accept', () => {
        const fixture = TestBed.createComponent(TokenAdminPanelComponent);
        const component = fixture.componentInstance;
        component.mintForm = {
            recipient: '0x1111111111111111111111111111111111111111',
            amount: '1.5',
            totpCode: '',
            approvalToken: '',
        };

        expect(component.isValidMintForm()).toBe(false);
    });
});
