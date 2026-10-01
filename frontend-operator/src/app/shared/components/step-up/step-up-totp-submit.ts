import { ChangeDetectorRef } from '@angular/core';
import { StepUpService, StepUpTokenResponse } from '../../../core/api/step-up.service';

export interface TotpSubmitState {
  loading: boolean;
  errorMessage: string | null;
}

/**
 * Shared loading/error/subscribe shape for the two dialogs that exchange a TOTP code for a
 * step-up token ({@link StepUpDialogComponent} and {@link ApprovalTokenGeneratorDialogComponent})
 * — only what happens on success differs between them. `approval` is set only when minting a
 * dual-control approver token (action + the exact request being approved); the initiator's own
 * token carries neither.
 */
export function submitTotpForStepUpToken(
  stepUpService: StepUpService,
  cdr: ChangeDetectorRef,
  state: TotpSubmitState,
  totpCode: string,
  approval: { action: string; target?: string; targetBody?: unknown } | null,
  onSuccess: (res: StepUpTokenResponse) => void,
): void {
  state.loading = true;
  state.errorMessage = null;
  cdr.markForCheck();

  stepUpService.issueToken(totpCode.trim(), approval?.action, approval?.target, approval?.targetBody).subscribe({
    next: (res) => {
      state.loading = false;
      onSuccess(res);
      cdr.markForCheck();
    },
    error: (err) => {
      state.loading = false;
      state.errorMessage = err?.error?.message
        ?? 'Step-up verification failed. Please check your TOTP code and try again.';
      cdr.markForCheck();
    },
  });
}
