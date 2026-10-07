import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NEVER } from 'rxjs';
import { HolderChangeRequest, RegisterEntry, RegisterEntryService } from '../../../../core/api/register-entry.service';
import { stepUpDialogSpy, TOKENS } from '../../../../shared/components/step-up/step-up-test-helper';
import { RegisterEntriesComponent } from './register-entries.component';

/** The two 4-eyes register-entry changes hand the approver the exact request the service then sends. */
describe('RegisterEntriesComponent - dual-control targets', () => {
  const service = { updateAttributes: vi.fn(), executeRequest: vi.fn() };

  function create() {
    const spy = stepUpDialogSpy();
    TestBed.configureTestingModule({
      imports: [RegisterEntriesComponent],
      providers: [provideZonelessChangeDetection(), { provide: RegisterEntryService, useValue: service }],
    });
    TestBed.overrideProvider(MatSnackBar, { useValue: { open: vi.fn() } });
    TestBed.overrideProvider(MatDialog, { useValue: spy.dialog });
    const component = TestBed.createComponent(RegisterEntriesComponent).componentInstance;
    component.assetId = 'a1';
    return { component, ...spy };
  }

  beforeEach(() => {
    vi.resetAllMocks();
    service.updateAttributes.mockReturnValue(NEVER);
    service.executeRequest.mockReturnValue(NEVER);
  });

  it('changing attributes binds PATCH .../single-entry-attributes and the body the service sends', () => {
    const { component, stepUps } = create();
    component.editing = { id: 'h1', holderReference: 'H-1' } as RegisterEntry;
    component.edit = {
      thirdPartyRights: ' pledge ', disposalRestrictions: '', legalCapacityNote: '',
      clearThirdPartyRights: false, clearDisposalRestrictions: true,
    };
    component.instruction = { party: 'COURT', reference: ' AZ-1 ' };
    component.submitEdit();

    const body = {
      thirdPartyRights: 'pledge', disposalRestrictions: undefined, legalCapacityNote: undefined,
      clearThirdPartyRights: false, clearDisposalRestrictions: true,
      instructingParty: 'COURT', instructionReference: 'AZ-1',
    };
    expect(stepUps[0]).toMatchObject({
      requireDualControl: true, action: 'REGISTER_ENTRY_RIGHTS_CHANGE',
      target: 'PATCH /api/v1/assets/a1/holders/h1/single-entry-attributes', targetBody: body,
    });
    expect(service.updateAttributes).toHaveBeenCalledWith('a1', 'h1', body, TOKENS);
  });

  it('executing an issuer request binds POST .../execute with the empty body', () => {
    const { component, stepUps } = create();
    component.execute({ id: 'q1', instructingParty: 'COURT', instructionReference: 'X' } as HolderChangeRequest);
    expect(stepUps[0]).toMatchObject({
      requireDualControl: true, action: 'REGISTER_ENTRY_RIGHTS_CHANGE',
      target: 'POST /api/v1/assets/a1/holders/change-requests/q1/execute', targetBody: {},
    });
    expect(service.executeRequest).toHaveBeenCalledWith('a1', 'q1', TOKENS);
  });
});
