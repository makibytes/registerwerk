import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { TokenAdminPanelComponent, ApprovalKind } from './token-admin-panel.component';
import { ForceTransferAction } from './models';
import { ApprovalQueueService } from '../../../core/api/approval-queue.service';

const A = '0x1111111111111111111111111111111111111111';
const B = '0x2222222222222222222222222222222222222222';

function view(status: string) {
    return {
        id: 'req-1', requesterUserId: 'u', requesterEmail: 'me@x', action: 'A', method: 'POST', path: '/p', query: null,
        canonicalBody: '{}', targetDigest: 'd', status, approverUserId: null, approverEmail: 'op@x',
        createdAt: '2026-01-01T00:00:00Z', expiresAt: '2099-01-01T00:00:00Z', decidedAt: null, decisionNote: null, claimedAt: null,
    };
}

describe('TokenAdminPanelComponent', () => {
    const queue = { create: vi.fn(), get: vi.fn(), claim: vi.fn(), cancel: vi.fn() };

    beforeEach(() => {
        vi.useFakeTimers();
        Object.values(queue).forEach(fn => fn.mockReset());
        queue.create.mockReturnValue(of(view('PENDING')));
        queue.get.mockReturnValue(of(view('APPROVED')));
        TestBed.configureTestingModule({
            imports: [TokenAdminPanelComponent],
            providers: [provideZonelessChangeDetection(), { provide: ApprovalQueueService, useValue: queue }],
        });
    });
    afterEach(() => vi.useRealTimers());

    function create() {
        const fixture = TestBed.createComponent(TokenAdminPanelComponent);
        const component = fixture.componentInstance;
        component.assetId = 'asset-1';
        component.deploymentId = 'dep-1';
        return component;
    }

    /** Files the request for `kind` and lets the (fake) approver approve it. */
    async function approve(component: TokenAdminPanelComponent, kind: ApprovalKind) {
        component.sessions[kind].start(component.approvalRequestFor(kind)!);
        await vi.advanceTimersByTimeAsync(4000);
    }

    it('requires and emits the legal authority for a forced transfer, only after the approval queue approved it', async () => {
        const component = create();
        const emitted: ForceTransferAction[] = [];
        component.forceTransfer.subscribe((action) => emitted.push(action));
        component.forceTransferForm = { fromWallet: A, toWallet: B, amount: '10', legalBasis: '', totpCode: '123456' };
        vi.spyOn(window, 'confirm').mockReturnValue(true);

        component.submitForceTransfer();
        expect(emitted).toEqual([]);
        expect(component.approvalRequestFor('forceTransfer')).toBeNull(); // no request can be filed without a legal basis

        component.forceTransferForm.legalBasis = 'short';
        expect(component.approvalRequestFor('forceTransfer')).toBeNull(); // legal basis needs >= 10 characters

        component.forceTransferForm.legalBasis = 'BaFin decision 2026-001';
        component.submitForceTransfer();
        expect(emitted).toEqual([]); // valid form, but nobody approved yet

        await approve(component, 'forceTransfer');
        component.submitForceTransfer();

        expect(emitted).toEqual([{
            fromWallet: A, toWallet: B, amount: '10', legalBasis: 'BaFin decision 2026-001',
            totpCode: '123456', approvalRequestId: 'req-1',
        }]);
        expect(component.sessions.forceTransfer.phase).toBe('idle'); // the approval is spent by the submit
        component.ngOnDestroy();
    });

    it('files exactly what the parent will send, per action', () => {
        const component = create();
        component.mintForm = { recipient: A, amount: '5', totpCode: '' };
        component.burnForm = { fromWallet: A, amount: '6', totpCode: '' };
        component.forceTransferForm = { fromWallet: A, toWallet: B, amount: '7', legalBasis: 'long enough basis', totpCode: '' };
        component.forceApproveForm = { ownerWallet: ` ${A} `, spenderWallet: B, amount: '8', legalBasis: 'long enough basis', totpCode: '' };
        const base = '/api/v1/assets/asset-1/deployments/dep-1/issuer';

        expect(component.approvalRequestFor('mint')).toEqual({
            action: 'ISSUER_MINT', method: 'POST', path: `${base}/mint`, body: { toAddress: A, amount: '5' } });
        expect(component.approvalRequestFor('burn')).toEqual({
            action: 'ISSUER_BURN_EWG26', method: 'POST', path: `${base}/burn`, body: { fromAddress: A, amount: '6' } });
        expect(component.approvalRequestFor('forceTransfer')).toEqual({
            action: 'ISSUER_FORCED_TRANSFER_EWG24', method: 'POST', path: `${base}/forced-transfer`,
            body: { from: A, to: B, value: '7', legalBasis: 'long enough basis' } });
        expect(component.approvalRequestFor('forceApprove')).toEqual({
            action: 'ISSUER_FORCED_APPROVE_OVERRIDE', method: 'POST', path: `${base}/forced-approve`,
            body: { owner: A, spender: B, value: '8', legalBasis: 'long enough basis' } });
        // memoised: the same form yields the same object (stable box input)
        expect(component.approvalRequestFor('mint')).toBe(component.approvalRequestFor('mint'));
    });

    it('accepts an amount above 2^53 exactly as a string (no number round trip)', async () => {
        const component = create();
        const emitted: { amount: string; approvalRequestId: string }[] = [];
        component.mint.subscribe((a) => emitted.push(a));
        component.mintForm = { recipient: A, amount: '1000000000000000000000', totpCode: '' };
        await approve(component, 'mint');
        component.submitMint();
        expect(emitted.map(e => [e.amount, e.approvalRequestId])).toEqual([['1000000000000000000000', 'req-1']]);
        component.ngOnDestroy();
    });

    it('rejects fractional amounts that the BigInteger backend cannot accept', () => {
        const component = create();
        component.mintForm = { recipient: A, amount: '1.5', totpCode: '' };

        expect(component.isValidMintForm()).toBe(false);
        expect(component.approvalRequestFor('mint')).toBeNull();
    });
});
