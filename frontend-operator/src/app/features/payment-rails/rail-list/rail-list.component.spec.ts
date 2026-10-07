import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NEVER } from 'rxjs';
import { PaymentRailService } from '../../../core/api/payment-rail.service';
import { AuthService } from '../../../core/auth/auth.service';
import { PaymentRailView } from '../../../core/models';
import { stepUpDialogSpy, TOKENS } from '../../../shared/components/step-up/step-up-test-helper';
import { RailListComponent } from './rail-list.component';

/** Every dual-control rail action hands the approver the exact request the service then sends. */
describe('RailListComponent - dual-control targets', () => {
  const service = {
    list: vi.fn(), create: vi.fn(), update: vi.fn(), enable: vi.fn(), verifyMicar: vi.fn(), disable: vi.fn(),
  };
  const rail = { id: 'r1', createdBy: 'a', updatedBy: 'a' } as PaymentRailView;
  const formBody = { code: 'SEPA', name: 'SEPA', railType: 'OFFCHAIN_SEPA' };

  function create(otherResult?: unknown) {
    const spy = stepUpDialogSpy(otherResult);
    TestBed.configureTestingModule({
      imports: [RailListComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: PaymentRailService, useValue: service },
        { provide: AuthService, useValue: { getUserId: () => 'me' } },
      ],
    });
    TestBed.overrideProvider(MatSnackBar, { useValue: { open: vi.fn() } });
    TestBed.overrideProvider(MatDialog, { useValue: spy.dialog });
    return { component: TestBed.createComponent(RailListComponent).componentInstance, ...spy };
  }

  beforeEach(() => {
    vi.resetAllMocks();
    for (const m of [service.create, service.update, service.enable, service.verifyMicar, service.disable]) m.mockReturnValue(NEVER);
  });

  it('create binds POST /payment-rails and the form body', () => {
    const { component, stepUps } = create(formBody);
    component.openCreateDialog();
    expect(stepUps[0]).toMatchObject({
      requireDualControl: true, action: 'Payment rail creation',
      target: 'POST /api/v1/payment-rails', targetBody: formBody,
    });
    expect(service.create).toHaveBeenCalledWith(formBody, TOKENS);
  });

  it('update binds PUT /payment-rails/{id} and the form body', () => {
    const { component, stepUps } = create(formBody);
    component.openEditDialog(rail);
    expect(stepUps[0]).toMatchObject({
      action: 'Payment rail update', target: 'PUT /api/v1/payment-rails/r1', targetBody: formBody,
    });
    expect(service.update).toHaveBeenCalledWith('r1', formBody, TOKENS);
  });

  it('enable binds POST .../enable with the empty body the service sends', () => {
    const { component, stepUps } = create();
    component.enable(rail);
    expect(stepUps[0]).toMatchObject({
      action: 'Payment rail enablement', target: 'POST /api/v1/payment-rails/r1/enable', targetBody: {},
    });
    expect(service.enable).toHaveBeenCalledWith('r1', TOKENS);
  });

  it('MiCAR attestation binds POST .../verify-micar with the empty body', () => {
    const { component, stepUps } = create();
    component.verify(rail);
    expect(stepUps[0]).toMatchObject({
      action: 'Payment rail MiCAR attestation', target: 'POST /api/v1/payment-rails/r1/verify-micar', targetBody: {},
    });
    expect(service.verifyMicar).toHaveBeenCalledWith('r1', TOKENS);
  });

  it('the single step-up actions stay without a second approver', () => {
    const { component, stepUps } = create();
    component.disable(rail);
    expect(stepUps[0]).toMatchObject({ requireDualControl: false, action: 'Payment rail deactivation' });
  });
});
