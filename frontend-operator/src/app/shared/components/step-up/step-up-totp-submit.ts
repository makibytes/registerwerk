import { ChangeDetectorRef } from '@angular/core';
import { StepUpService, StepUpTokenResponse } from '../../../core/api/step-up.service';

export interface TotpSubmitState {
  loading: boolean;
  errorMessage: string | null;
}

/**
 * Shared loading/error/subscribe shape for the two dialogs that exchange a TOTP code for a
 * step-up token ({@link StepUpDialogComponent} and {@link ApprovalTokenGeneratorDialogComponent})
 * — only what happens on success differs between them.
 */
export function submitTotpForStepUpToken(
  stepUpService: StepUpService,
  cdr: ChangeDetectorRef,
  state: TotpSubmitState,
  totpCode: string,
  action: string,
  onSuccess: (res: StepUpTokenResponse) => void,
): void {
  state.loading = true;
  state.errorMessage = null;
  cdr.markForCheck();

  stepUpService.issueToken(totpCode.trim(), action).subscribe({
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
