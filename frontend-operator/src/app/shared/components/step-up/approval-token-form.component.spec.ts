import { beforeEach, describe, expect, it, vi } from 'vitest';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { ApprovalTokenFormComponent } from './approval-token-form.component';
import { StepUpService } from '../../../core/api/step-up.service';

describe('ApprovalTokenFormComponent', () => {
  let issueToken: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    issueToken = vi.fn().mockReturnValue(of({ stepUpToken: 'approver-token' }));
    TestBed.configureTestingModule({
      imports: [ApprovalTokenFormComponent],
      providers: [provideZonelessChangeDetection(), { provide: StepUpService, useValue: { issueToken } }],
    });
  });

  it('mints a token bound to exactly the pasted request, with only the approver\'s own TOTP', async () => {
    const fixture = TestBed.createComponent(ApprovalTokenFormComponent);
    const c = fixture.componentInstance;
    c.onBlockChange('{"action":"ISSUER_BURN_EWG26","target":"POST /api/v1/assets/a/deployments/d/issuer/burn","targetBody":{"amount":"5"}}');
    c.totpCode = '654321';
    c.generate();
    expect(issueToken).toHaveBeenCalledTimes(1);
    expect(issueToken).toHaveBeenCalledWith('654321', 'ISSUER_BURN_EWG26', 'POST /api/v1/assets/a/deployments/d/issuer/burn', { amount: '5' });
    expect(c.issuedToken).toBe('approver-token');
    expect(c.totpCode).toBe('');
  });

  it('refuses to mint without a valid request block', () => {
    const fixture = TestBed.createComponent(ApprovalTokenFormComponent);
    const c = fixture.componentInstance;
    c.totpCode = '123456';
    c.generate();
    c.onBlockChange('garbage');
    c.generate();
    expect(issueToken).not.toHaveBeenCalled();
    expect(c.errorMessage).toBeTruthy();
  });

  it('error block is announced and wired to the TOTP field', async () => {
    const fixture = TestBed.createComponent(ApprovalTokenFormComponent);
    fixture.componentInstance.preset = { action: 'A', target: 'POST /api/v1/x' };
    fixture.componentInstance.errorMessage = 'Step-up verification failed.';
    fixture.changeDetectorRef.markForCheck();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role="alert"]#approval-token-error')).not.toBeNull();
    expect(el.querySelector('input[inputmode="numeric"]')?.getAttribute('aria-describedby')).toBe('approval-token-error');
  });
});
