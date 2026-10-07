import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';

export interface ImpersonateDialogData {
  entityName: string;
  /** Production mode is read-only impersonation only (T6-05): act-on-behalf is never offered. */
  productionMode?: boolean;
}

export interface ImpersonateDialogResult {
  mode: 'READ_ONLY' | 'ACT_ON_BEHALF';
  reason: string;
  ticket: string;
}

/** Collects the mandatory reason and the mode of a customer-portal support session. */
@Component({
  selector: 'app-impersonate-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatRadioModule],
  styles: [`
    .intro { font-size: 13px; color: var(--rw-text-secondary); line-height: 1.5; margin: 0 0 12px; }
    .full-width { width: 100%; }
    .mode-hint { font-size: 12px; color: var(--rw-text-secondary); margin: 0 0 8px 28px; }
  `],
  template: `
    <h2 mat-dialog-title>Open {{ data.entityName }} in the customer portal</h2>
    <mat-dialog-content>
      <p class="intro">
        The reason is written to the audit trail and shown to the company's administrators.
        Sessions end after 30 minutes.
      </p>
      <mat-radio-group [(ngModel)]="mode" aria-label="Session mode">
        <mat-radio-button value="READ_ONLY">Read-only support session</mat-radio-button>
        <p class="mode-hint">You see what the customer sees; every change is blocked. Needs step-up.</p>
        @if (data.productionMode) {
          <p class="mode-hint">Acting on behalf of a customer is disabled in production mode; support sessions are read-only.</p>
        } @else {
          <mat-radio-button value="ACT_ON_BEHALF">Act on behalf of the customer</mat-radio-button>
          <p class="mode-hint">You can change data as the customer; some actions stay denied. Needs step-up and a second approver.</p>
        }
      </mat-radio-group>
      <mat-form-field class="full-width" appearance="outline">
        <mat-label>Reason (at least 15 characters)</mat-label>
        <textarea matInput rows="2" maxlength="500" [(ngModel)]="reason"></textarea>
      </mat-form-field>
      <mat-form-field class="full-width" appearance="outline">
        <mat-label>Ticket reference (optional)</mat-label>
        <input matInput maxlength="100" [(ngModel)]="ticket" />
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button mat-dialog-close>Cancel</button>
      <button type="button" mat-flat-button color="primary" [disabled]="reason.trim().length < 15" (click)="confirm()">Continue</button>
    </mat-dialog-actions>
  `,
})
export class ImpersonateDialogComponent {
  protected readonly data = inject<ImpersonateDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject<MatDialogRef<ImpersonateDialogComponent, ImpersonateDialogResult>>(MatDialogRef);

  mode: 'READ_ONLY' | 'ACT_ON_BEHALF' = 'READ_ONLY';
  reason = '';
  ticket = '';

  confirm(): void {
    this.dialogRef.close({ mode: this.mode, reason: this.reason.trim(), ticket: this.ticket.trim() });
  }
}
