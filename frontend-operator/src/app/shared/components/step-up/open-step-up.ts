import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { StepUpDialogComponent, StepUpDialogData, StepUpDialogResult } from './step-up-dialog.component';

/** Opens the step-up dialog and emits the result once (undefined when cancelled). */
export function openStepUp(dialog: MatDialog, data: StepUpDialogData): Observable<StepUpDialogResult | undefined> {
  return dialog.open<StepUpDialogComponent, StepUpDialogData, StepUpDialogResult>(StepUpDialogComponent, {
    data,
    width: '500px',
    disableClose: true,
  }).afterClosed();
}
