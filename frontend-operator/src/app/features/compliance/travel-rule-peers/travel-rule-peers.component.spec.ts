import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { TravelRulePeerService, TravelRulePeer } from '../../../core/api/travel-rule-peer.service';
import { TravelRulePeersComponent } from './travel-rule-peers.component';

const disabled: TravelRulePeer = { vaspId: 'did:x:1', legalName: 'Gone Bank', lei: null, status: 'DISABLED', createdAt: '2026-01-01T00:00:00Z' };

describe('TravelRulePeersComponent', () => {
  const service = { list: vi.fn(), enable: vi.fn(), disable: vi.fn(), register: vi.fn() };
  const dialog = { open: vi.fn(), closeAll: vi.fn() };
  const snack = { open: vi.fn() };

  function create(roles: string[]) {
    TestBed.configureTestingModule({
      imports: [TravelRulePeersComponent],
      providers: [
        { provide: TravelRulePeerService, useValue: service },
        { provide: AuthService, useValue: { hasRole: (r: string) => roles.includes(r) } },
        { provide: MatSnackBar, useValue: snack },
      ],
    });
    TestBed.overrideProvider(MatDialog, { useValue: dialog });
    const fixture = TestBed.createComponent(TravelRulePeersComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    vi.resetAllMocks();
    service.list.mockReturnValue(of([disabled]));
    // step-up dialog answers with both tokens
    dialog.open.mockReturnValue({ afterClosed: () => of({ stepUpToken: 'su', dualControlToken: 'dc' }) });
    service.enable.mockReturnValue(of(void 0));
  });

  it('enable() asks for step-up + second approver bound to POST .../enable and then enables the peer', () => {
    const c = create(['REGISTRY_ADMIN']);
    c.enable(disabled);

    const data = dialog.open.mock.calls[0][1].data;
    expect(data).toMatchObject({
      requireDualControl: true,
      action: 'TRAVEL_RULE_PEER_ENABLE',
      target: 'POST /api/v1/compliance/travel-rule/peers/did%3Ax%3A1/enable',
      targetBody: {},
    });
    expect(service.enable).toHaveBeenCalledWith('did:x:1', { stepUpToken: 'su', dualControlToken: 'dc' });
    expect(snack.open).toHaveBeenCalledWith('Enabled did:x:1.', 'OK', expect.anything());
  });

  it('does nothing when the step-up dialog is cancelled', () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
    create(['REGISTRY_ADMIN']).enable(disabled);
    expect(service.enable).not.toHaveBeenCalled();
  });

  it('only registry administrators get the management actions', () => {
    expect(create(['COMPLIANCE_OFFICER']).canManage).toBe(false);
  });
});
