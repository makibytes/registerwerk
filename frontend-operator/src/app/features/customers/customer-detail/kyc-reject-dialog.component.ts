import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { KycRejectionCategory } from '../../../core/api/kyc.service';

export interface KycRejectDialogResult {
  /** Internal reason: audit trail and operator UI only. */
  reason: string;
  /** What the customer is told (fixed category, no free text). */
  customerReasonCode: KycRejectionCategory;
}

export const KYC_REJECTION_CATEGORIES: { value: KycRejectionCategory; label: string }[] = [
  { value: 'INFORMATION_INCOMPLETE', label: 'Information incomplete' },
  { value: 'DOCUMENTS_UNREADABLE', label: 'Documents unreadable' },
  { value: 'INFORMATION_INCONSISTENT', label: 'Information inconsistent' },
  { value: 'CONTACT_SUPPORT', label: 'Contact support (no detail given)' },
];

export interface KycRejectDialogData {
  /** Dialog heading, e.g. "Reject KYC for LU_CSSF". */
  title?: string;
  /**
   * Jurisdiction rejections: the backend currently stores the reason as the jurisdiction's rejection reason and
   * the customer's KYC status page displays it, so the dialog must not promise confidentiality.
   */
  reasonVisibleToCustomer?: boolean;
}

/** Collects the internal reason and the customer-facing category for an entity-level KYC rejection. */
@Component({
  selector: 'app-kyc-reject-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>{{ data?.title || 'Reject KYC' }}</h2>
    <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px;max-width:100%">
      <mat-form-field appearance="outline">
        <mat-label>{{ data?.reasonVisibleToCustomer ? 'Reason (required)' : 'Internal reason (required)' }}</mat-label>
        <textarea matInput rows="4" maxlength="2000" [(ngModel)]="reason"
                  placeholder="What was found and why the file cannot be approved."></textarea>
        @if (data?.reasonVisibleToCustomer) {
          <mat-hint>Shown to the customer on their KYC status page for this jurisdiction. Keep internal findings out of it.</mat-hint>
        } @else {
          <mat-hint>The customer never sees the internal reason. It stays in the audit trail.</mat-hint>
        }
      </mat-form-field>
      <mat-form-field appearance="outline">
        <mat-label>Category shown to the customer</mat-label>
        <mat-select [(ngModel)]="category">
          @for (option of categories; track option.value) {
            <mat-option [value]="option.value">{{ option.label }}</mat-option>
          }
        </mat-select>
        <mat-hint>Sent to the customer by e-mail and webhook as a fixed category only.</mat-hint>
      </mat-form-field>
      <p style="margin:4px 0 0;font-size:12px;color:var(--rw-text-secondary)">
        Rejecting needs step-up and a second approver.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions style="justify-content:flex-end;gap:8px">
      <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
      <button type="button" mat-raised-button color="warn" [disabled]="!reason.trim()" (click)="submit()">Continue</button>
    </mat-dialog-actions>
  `,
})
export class KycRejectDialogComponent {
  private readonly ref = inject(MatDialogRef<KycRejectDialogComponent, KycRejectDialogResult>);
  protected readonly data = inject<KycRejectDialogData | null>(MAT_DIALOG_DATA, { optional: true });

  readonly categories = KYC_REJECTION_CATEGORIES;
  reason = '';
  category: KycRejectionCategory = 'CONTACT_SUPPORT';

  submit(): void {
    if (!this.reason.trim()) return;
    this.ref.close({ reason: this.reason.trim(), customerReasonCode: this.category } satisfies KycRejectDialogResult);
  }
}
