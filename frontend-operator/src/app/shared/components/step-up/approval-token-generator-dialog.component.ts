import { ChangeDetectionStrategy, ChangeDetectorRef, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { StepUpService } from '../../../core/api/step-up.service';
import { submitTotpForStepUpToken } from './step-up-totp-submit';

export interface ApprovalTokenGeneratorDialogData {
  /** The exact `@RequiresStepUp(reason=...)` value the minted token must be scoped to. */
  action: string;
}

/**
 * Lets a second approver mint their own dual-control step-up token in-app, scoped to a specific
 * action, without needing to call POST /auth/step-up out of band (e.g. via curl/Postman).
 *
 * Intended to be opened FROM {@link StepUpDialogComponent}'s dual-control section — the approver
 * types their own TOTP code here, gets back a correctly `stepup_scope`-tagged JWT, and copies it
 * (or the caller auto-fills it) into the primary dialog's "second approver" field.
 */
@Component({
  selector: 'app-approval-token-generator-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatTooltipModule,
  ],
  styles: [`
    .intro-text {
      font-size: 13px;
      color: var(--rw-text-secondary);
      line-height: 1.5;
      margin-bottom: 16px;
    }

    .full-width { width: 100%; }

    .token-result {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      margin-top: 8px;
    }

    .error-msg {
      font-size: 12px;
      color: #DC2626;
      margin-top: 8px;
      display: flex;
      align-items: center;
      gap: 6px;

      mat-icon { font-size: 16px; width: 16px; height: 16px; }
    }

    .dialog-actions {
      display: flex;
      justify-content: flex-end;
      gap: 10px;
      padding-top: 4px;
    }
  `],
  template: `
    <h2 mat-dialog-title>
      <mat-icon style="vertical-align:middle;margin-right:8px;color:#F59E0B">verified_user</mat-icon>
      Generate Approver Token
    </h2>

    <mat-dialog-content>
      <div class="intro-text">
        Enter <strong>your own</strong> TOTP code to mint a step-up token scoped to
        <strong>{{ data.action }}</strong>. You must be a different REGISTRY_ADMIN than the
        person requesting approval.
      </div>

      @if (!issuedToken) {
        <mat-form-field class="full-width" appearance="outline">
          <mat-label>Your TOTP code (6 digits)</mat-label>
          <mat-icon matPrefix>smartphone</mat-icon>
          <input matInput
                 [(ngModel)]="totpCode"
                 inputmode="numeric"
                 autocomplete="one-time-code"
                 maxlength="6"
                 placeholder="123456"
                 (keydown.enter)="generate()" />
        </mat-form-field>
      } @else {
        <div class="token-result">
          <mat-form-field class="full-width" appearance="outline">
            <mat-label>Scoped approval token</mat-label>
            <input matInput [value]="issuedToken" readonly />
          </mat-form-field>
          <button mat-icon-button matTooltip="Copy" (click)="copy()">
            <mat-icon>content_copy</mat-icon>
          </button>
        </div>
      }

      @if (errorMessage) {
        <div class="error-msg">
          <mat-icon>error</mat-icon>
          {{ errorMessage }}
        </div>
      }
    </mat-dialog-content>

    <mat-dialog-actions class="dialog-actions">
      <button mat-stroked-button (click)="close()">{{ issuedToken ? 'Close' : 'Cancel' }}</button>
      @if (!issuedToken) {
        <button mat-raised-button color="primary"
                (click)="generate()"
                [disabled]="loading || !totpCode || totpCode.length < 6">
          <mat-icon>{{ loading ? 'hourglass_empty' : 'lock_open' }}</mat-icon>
          {{ loading ? 'Verifying…' : 'Generate token' }}
        </button>
      } @else {
        <button mat-raised-button color="primary" (click)="useToken()">
          <mat-icon>check</mat-icon>
          Use this token
        </button>
      }
    </mat-dialog-actions>
  `,
})
export class ApprovalTokenGeneratorDialogComponent {
  protected readonly data = inject<ApprovalTokenGeneratorDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<ApprovalTokenGeneratorDialogComponent>);
  private readonly stepUpService = inject(StepUpService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  totpCode = '';
  issuedToken: string | null = null;
  loading = false;
  errorMessage: string | null = null;

  generate(): void {
    if (!this.totpCode || this.totpCode.length < 6) return;

    submitTotpForStepUpToken(
      this.stepUpService, this.cdr, this, this.totpCode, this.data.action,
      (res) => { this.issuedToken = res.stepUpToken; },
    );
  }

  copy(): void {
    if (!this.issuedToken) return;
    navigator.clipboard.writeText(this.issuedToken);
    this.snackBar.open('Token copied to clipboard.', 'Dismiss', { duration: 2500 });
  }

  useToken(): void {
    this.dialogRef.close(this.issuedToken ?? undefined);
  }

  close(): void {
    this.dialogRef.close(undefined);
  }
}
