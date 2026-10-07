import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { KycReviewComponent } from './kyc-review.component';
import { KycService } from '../../../core/api/kyc.service';
import { BeneficialOwnerService } from '../../../core/api/beneficial-owner.service';
import { AuthService } from '../../../core/auth/auth.service';
import { KycReview } from '../../../core/models';

const review = (over: Partial<KycReview> = {}): KycReview => ({
  entityId: 'e-1',
  entityNumber: 'E-0001',
  legalName: 'Acme GmbH',
  entityType: 'ISSUER',
  entityStatus: 'ACTIVE',
  registrationCountry: 'DE',
  registrationNumber: 'HRB 1',
  leiCode: null,
  incorporationDate: null,
  homeJurisdiction: 'DE_EWPG',
  kycStatus: 'IN_PROGRESS',
  kycExpiryDate: null,
  documents: [],
  checklist: {
    jurisdiction: 'DE_EWPG', jurisdictionDisplayName: 'Germany', entityId: 'e-1', documents: [],
    fullyCompliant: false, missingCount: 2, expiredCount: 0, tooOldCount: 0,
  },
  jurisdictionApprovals: [],
  ownership: { activeCount: 1, identifiedPct: 80, unexplainedPct: 20, smoFallback: false, coverageSufficient: true, requiredIdentifiedPct: 75 },
  beneficialOwners: [{
    owner: {
      id: 'bo-1', entityId: 'e-1', naturalPersonId: 'p-1', givenName: 'Erika', familyName: 'Mustermann',
      pepStatus: 'CONFIRMED_PEP', ownershipPct: 80, controlType: 'DIRECT_OWNERSHIP', registeredAt: '2026-01-01T00:00:00Z',
    },
    screeningUnresolved: false, eddInForce: false, eddReviewDue: null,
  }],
  screening: { entityHitUnresolved: false, beneficialOwnerHitUnresolved: true, relyingOnStaleResult: false },
  gaps: ['PEP_WITHOUT_EDD_APPROVAL'],
  decisions: [],
  ...over,
});

describe('KycReviewComponent', () => {
  const kyc = { getReview: vi.fn(), approveKyc: vi.fn(), rejectKyc: vi.fn(), downloadDocument: vi.fn(),
    approveJurisdiction: vi.fn(), rejectJurisdiction: vi.fn() };
  const owners = { verify: vi.fn(), cease: vi.fn(), approveEdd: vi.fn() };

  /** What the step-up dialog closes with: the tokens, or undefined when the user cancels. */
  const dialog = { open: vi.fn() };
  function dialogCloses(result: { stepUpToken: string; dualControlToken: string } | undefined) {
    dialog.open.mockReturnValue({ afterClosed: () => of(result) });
  }

  function create(roles: string[], data: KycReview | Error = review()) {
    kyc.getReview.mockReturnValue(data instanceof Error ? throwError(() => data) : of(data));
    TestBed.configureTestingModule({
      imports: [KycReviewComponent],
      providers: [
        provideRouter([]),
        { provide: MatDialog, useValue: dialog },
        { provide: KycService, useValue: kyc },
        { provide: BeneficialOwnerService, useValue: owners },
        { provide: AuthService, useValue: { hasRole: (r: string) => roles.includes(r) } },
      ],
    });
    const fixture = TestBed.createComponent(KycReviewComponent);
    fixture.componentInstance.id = 'e-1';
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: { nativeElement: unknown }) => f.nativeElement as HTMLElement;

  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('renders the scoped review from the single review endpoint', () => {
    const f = create(['COMPLIANCE_OFFICER']);
    const text = el(f).textContent ?? '';
    expect(kyc.getReview).toHaveBeenCalledWith('e-1');
    expect(text).toContain('Acme GmbH');
    expect(text).toContain('Confirmed PEP without EDD approval');
    expect(text).toContain('Beneficial owners: hit unresolved');
    expect(text).toContain('Erika Mustermann');
  });

  it('a compliance officer can approve/reject but sees neither the override nor the EDD approval', () => {
    const f = create(['COMPLIANCE_OFFICER']);
    expect(el(f).querySelector('#approve-kyc')).not.toBeNull();
    expect(el(f).querySelector('#reject-kyc')).not.toBeNull();
    expect(el(f).querySelector('#approve-kyc-override')).toBeNull();
    expect(f.componentInstance.canApproveEdd).toBe(false);
    expect(el(f).textContent).toContain('admin');
    expect(el(f).querySelector('button[mattooltip^="Approve EDD"]')).toBeNull();
  });

  it('a registry administrator also gets the override and the EDD approval', () => {
    const f = create(['REGISTRY_ADMIN']);
    expect(el(f).querySelector('#approve-kyc-override')).not.toBeNull();
    expect(f.componentInstance.canApproveEdd).toBe(true);
  });

  it('approving files the exact request through step-up + second approver, then reloads', () => {
    kyc.approveKyc.mockReturnValue(of(undefined));
    dialogCloses({ stepUpToken: 's', dualControlToken: 'd' });
    const f = create(['COMPLIANCE_OFFICER']);
    f.componentInstance.approveKyc();

    expect(dialog.open.mock.calls[0][1].data).toEqual({
      requireDualControl: true, action: 'KYC_APPROVE', reason: 'Approve KYC for this customer',
      target: 'POST /api/v1/entities/e-1/kyc/approve', targetBody: { overrideNote: undefined },
    });
    expect(kyc.approveKyc).toHaveBeenCalledWith('e-1', { overrideNote: undefined }, { stepUpToken: 's', dualControlToken: 'd' });
    expect(kyc.getReview).toHaveBeenCalledTimes(2);
  });

  it('does nothing when the step-up dialog is cancelled', () => {
    dialogCloses(undefined);
    const f = create(['COMPLIANCE_OFFICER']);
    f.componentInstance.approveKyc();
    expect(kyc.approveKyc).not.toHaveBeenCalled();
    expect(kyc.getReview).toHaveBeenCalledTimes(1);
  });

  it('shows a retry state when the review cannot be loaded', () => {
    const f = create(['COMPLIANCE_OFFICER'], new Error('403'));
    expect(f.componentInstance.loadError).toBe(true);
    expect(el(f).textContent).toContain('could not be loaded');
  });
});
