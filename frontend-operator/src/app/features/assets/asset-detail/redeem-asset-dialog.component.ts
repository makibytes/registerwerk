import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

export interface RedeemAssetDialogData {
  assetName: string;
}

export interface RedeemAssetDialogResult {
  legalBasis: string;
  reference: string;
}

/**
 * Collects the legal basis and reference for `POST /assets/{id}/redeem` (review phase 3, T3-01).
 * Redemption burns every paid holder on-chain, so the backend also requires step-up + a second
 * approver; the caller opens the step-up dialog after this one.
 */
@Component({
  selector: 'app-redeem-asset-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule],
  styles: [`
    .note {
      display: flex;
      gap: 8px;
      align-items: flex-start;
      padding: 10px 12px;
      margin-bottom: 16px;
      border-radius: 6px;
      background: var(--rw-pending-bg);
      color: var(--rw-pending-fg);
      font-size: 13px;
      line-height: 1.45;
    }
    .note mat-icon { color: var(--rw-text-warning); flex: 0 0 auto; }
    .full { width: 100%; }
  `],
  template: `
    <h2 mat-dialog-title>Redeem {{ data.assetName }}</h2>
    <mat-dialog-content>
      <div class="note">
        <mat-icon>warning</mat-icon>
        <span>
          Redemption is final and burns the holders' tokens. A bond needs a settled REDEMPTION or
          CALL corporate action first; only holders who were paid are burnt. No corporate action may be
          open and no units may remain in nominee pools. A second registry administrator must approve.
        </span>
      </div>
      <mat-form-field class="full">
        <mat-label>Legal basis</mat-label>
        <input matInput [(ngModel)]="legalBasis" maxlength="500" required
               placeholder="eWpG §26 Einziehung — final redemption per terms §5">
      </mat-form-field>
      <mat-form-field class="full">
        <mat-label>Reference</mat-label>
        <input matInput [(ngModel)]="reference" maxlength="200" required
               placeholder="Corporate action id, resolution or court order">
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button (click)="cancel()">Cancel</button>
      <button type="button" mat-raised-button color="warn"
              [disabled]="!legalBasis.trim() || !reference.trim()" (click)="confirm()">
        Continue to approval
      </button>
    </mat-dialog-actions>
  `,
})
export class RedeemAssetDialogComponent {
  protected readonly data = inject<RedeemAssetDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<RedeemAssetDialogComponent, RedeemAssetDialogResult>);

  legalBasis = '';
  reference = '';

  cancel(): void {
    this.dialogRef.close(undefined);
  }

  confirm(): void {
    if (!this.legalBasis.trim() || !this.reference.trim()) return;
    this.dialogRef.close({ legalBasis: this.legalBasis.trim(), reference: this.reference.trim() });
  }
}
