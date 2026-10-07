import { MatDialog } from '@angular/material/dialog';
import { DualControlTokens } from '../../../core/api/dual-control-headers';
import { openStepUp } from './open-step-up';

/**
 * Step-up plus second approver for a decision; calls back with the tokens, or not at all when the dialog is
 * cancelled. `targetBody` is the JSON body of the request that follows (undefined when it sends none): an
 * approval is bound to the canonical body, so the approver must be shown, and bind, exactly what is then sent.
 */
export function withDualControl(
  dialog: MatDialog,
  spec: { action: string; reason: string; target: string; targetBody: unknown },
  then: (tokens: DualControlTokens) => void,
): void {
  openStepUp(dialog, { requireDualControl: true, ...spec }).subscribe((result) => {
    if (!result?.stepUpToken || !result.dualControlToken) return;
    then({ stepUpToken: result.stepUpToken, dualControlToken: result.dualControlToken });
  });
}
