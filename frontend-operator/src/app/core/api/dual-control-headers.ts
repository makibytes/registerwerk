import { HttpHeaders } from '@angular/common/http';

/** Step-up + 4-eyes tokens from `StepUpDialogComponent` for a `@RequiresStepUp(requireSecondApprover = true)` call. */
export interface DualControlTokens {
  stepUpToken: string;
  dualControlToken: string;
}

/** The step-up token replaces the session bearer; the second approver's token goes in `X-Dual-Control-Token`. */
export function dualControlHeaders(tokens: DualControlTokens): HttpHeaders {
  return new HttpHeaders({
    Authorization: `Bearer ${tokens.stepUpToken}`,
    'X-Dual-Control-Token': tokens.dualControlToken,
  });
}
