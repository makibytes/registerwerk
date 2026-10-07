import { vi } from 'vitest';
import { of } from 'rxjs';
import { StepUpDialogComponent, StepUpDialogData } from './step-up-dialog.component';

/**
 * Test double for MatDialog: the step-up dialog answers with both tokens (and records what it was opened with);
 * any other dialog answers with `otherResult` (e.g. the form dialog's request body).
 */
export function stepUpDialogSpy(otherResult?: unknown) {
  const stepUps: StepUpDialogData[] = [];
  const dialog = {
    open: vi.fn((component: unknown, config: { data: StepUpDialogData }) => {
      if (component === StepUpDialogComponent) {
        stepUps.push(config.data);
        return { afterClosed: () => of({ stepUpToken: 'su', dualControlToken: 'dc' }) };
      }
      return { afterClosed: () => of(otherResult) };
    }),
    closeAll: vi.fn(),
  };
  return { dialog, stepUps };
}

export const TOKENS = { stepUpToken: 'su', dualControlToken: 'dc' };
