import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';
import { SlotAdminComponent } from './slot-admin.component';
import { SlotService } from '../../../../core/api/slot.service';

/** 9X-1: slot creation and slot mint now require step-up + a second approver (like the forced value transfer). */
describe('SlotAdminComponent dual-control (9X-1)', () => {
  let service: {
    getSlots: ReturnType<typeof vi.fn>;
    createSlot: ReturnType<typeof vi.fn>;
    mintIntoSlot: ReturnType<typeof vi.fn>;
  };
  let dialogOpen: ReturnType<typeof vi.fn>;
  const tokens = { stepUpToken: 'su-jwt', dualControlToken: 'dc-jwt' };

  function create(result: unknown) {
    const fixture = TestBed.createComponent(SlotAdminComponent);
    fixture.componentInstance.deploymentId = 'dep-1';
    const dialog = fixture.debugElement.injector.get(MatDialog);
    dialogOpen = vi.spyOn(dialog, 'open').mockReturnValue({ afterClosed: () => of(result) } as never) as never;
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service = {
      getSlots: vi.fn().mockReturnValue(of([])),
      createSlot: vi.fn().mockReturnValue(of({ txId: 't1' })),
      mintIntoSlot: vi.fn().mockReturnValue(of({ txId: 't2' })),
    };
    TestBed.configureTestingModule({
      imports: [SlotAdminComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: SlotService, useValue: service },
        { provide: MatSnackBar, useValue: { open: vi.fn() } },
      ],
    });
  });

  afterEach(() => vi.restoreAllMocks());

  it('createSlot opens the dual-control step-up dialog bound to the exact request and sends both tokens', () => {
    const c = create(tokens);
    c.createForm = { slotId: '7', name: 'Series A', supplyCap: '1000' };
    c.createSlot();

    const data = dialogOpen.mock.calls[0][1].data;
    expect(data.requireDualControl).toBe(true);
    expect(data.action).toBe('ERC3525_SLOT_CREATE');
    expect(data.target).toBe('POST /api/v1/deployments/dep-1/slots');
    expect(data.targetBody).toEqual({ slotId: '7', name: 'Series A', supplyCap: '1000' });
    expect(service.createSlot).toHaveBeenCalledWith('dep-1',
      { slotId: '7', name: 'Series A', supplyCap: '1000' }, tokens);
  });

  it('createSlot without a completed step-up does not call the API', () => {
    const c = create(undefined);
    c.createForm = { slotId: '7', name: '', supplyCap: '' };
    c.createSlot();
    expect(service.createSlot).not.toHaveBeenCalled();
  });

  it('mint opens the dual-control step-up dialog bound to the exact request and sends both tokens', () => {
    const c = create(tokens);
    c.mintForm = { toAddress: ' 0xabc ', value: '25' };
    c.mint({ slotId: '7' } as never);

    const data = dialogOpen.mock.calls[0][1].data;
    expect(data.requireDualControl).toBe(true);
    expect(data.action).toBe('ERC3525_SLOT_MINT');
    expect(data.target).toBe('POST /api/v1/deployments/dep-1/slots/7/mint');
    expect(data.targetBody).toEqual({ toAddress: '0xabc', value: '25' });
    expect(service.mintIntoSlot).toHaveBeenCalledWith('dep-1', '7', { toAddress: '0xabc', value: '25' }, tokens);
  });

  it('mint without a second approver token does not call the API', () => {
    const c = create({ stepUpToken: 'su-jwt' });
    c.mint({ slotId: '7' } as never);
    expect(service.mintIntoSlot).not.toHaveBeenCalled();
  });
});
