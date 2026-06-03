import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { StepUpService } from '../../../core/api/step-up.service';

export interface StepUpDialogData {
  /** Whether the action requires a second approver (4-eyes principle). */
  requireDualControl: boolean;
  /** Human-readable reason shown to the user (e.g. "Accept false-positive screening hit"). */
  reason: string;
}

export interface StepUpDialogResult {
  stepUpToken: string;
  dualControlToken?: string;
}

/**
 * Step-Up Authentication dialog.
 *
 * Collects the user's TOTP code, calls POST /auth/step-up, and returns the
 * resulting step-up JWT to the caller.  For 4-eyes actions (requireDualControl=true)
 * also collects the second approver's pre-issued step-up token.
 */
@Component({
  selector: 'app-step-up-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
  ],
  styles: [`
    .warning-banner {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 14px 16px;
      border-radius: 8px;
      background: rgba(245, 158, 11, 0.10);
      border: 1px solid rgba(245, 158, 11, 0.25);
      margin-bottom: 20px;

      mat-icon {
        color: #F59E0B;
        flex-shrink: 0;
        margin-top: 1px;
      }
    }

    .reason-text {
      font-size: 13px;
      color: var(--rw-text-primary);
      line-height: 1.5;
    }

    .reason-action {
      font-weight: 600;
    }

    .section-label {
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.6px;
      text-transform: uppercase;
      color: var(--rw-text-muted);
      margin: 20px 0 10px;
    }

    .dual-control-note {
      font-size: 12px;
      color: var(--rw-text-secondary);
      line-height: 1.5;
      margin-bottom: 10px;
      padding: 10px 12px;
      border-radius: 6px;
      background: rgba(59, 130, 246, 0.07);
      border: 1px solid rgba(59, 130, 246, 0.18);
    }

    .full-width {
      width: 100%;
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
      <mat-icon style="vertical-align:middle;margin-right:8px;color:#F59E0B">security</mat-icon>
      Step-Up Authentication Required
    </h2>

    <mat-dialog-content>
      <div class="warning-banner">
        <mat-icon>lock</mat-icon>
        <div class="reason-text">
          You are about to perform a regulated action:
          <span class="reason-action">{{ data.reason }}</span>.
          @if (data.requireDualControl) {
            This requires <strong>dual control (4-eyes principle)</strong> — both your
            identity and a second approver must authenticate with a fresh TOTP code.
          } @else {
            Please verify your identity with a fresh TOTP code.
          }
        </div>
      </div>

      <div class="section-label">Your authentication</div>
      <mat-form-field class="full-width" appearance="outline">
        <mat-label>Your TOTP code (6 digits)</mat-label>
        <mat-icon matPrefix>smartphone</mat-icon>
        <input matInput
               [(ngModel)]="totpCode"
               inputmode="numeric"
               autocomplete="one-time-code"
               maxlength="6"
               placeholder="123456"
               (keydown.enter)="submit()" />
      </mat-form-field>

      @if (data.requireDualControl) {
        <div class="section-label">Second approver</div>
        <div class="dual-control-note">
          A second REGISTRY_ADMIN must separately authenticate via
          <code>/api/v1/auth/step-up</code> and paste their step-up token below.
          Both tokens must be from different users.
        </div>
        <mat-form-field class="full-width" appearance="outline">
          <mat-label>Second approver's step-up token (JWT)</mat-label>
          <mat-icon matPrefix>verified_user</mat-icon>
          <input matInput
                 [(ngModel)]="approverToken"
                 placeholder="eyJ…" />
        </mat-form-field>
      }

      @if (errorMessage) {
        <div class="error-msg">
          <mat-icon>error</mat-icon>
          {{ errorMessage }}
        </div>
      }
    </mat-dialog-content>

    <mat-dialog-actions class="dialog-actions">
      <button mat-stroked-button (click)="cancel()" [disabled]="loading">Cancel</button>
      <button mat-raised-button color="primary"
              (click)="submit()"
              [disabled]="loading || !totpCode || totpCode.length < 6 || (data.requireDualControl && !approverToken)">
        <mat-icon>{{ loading ? 'hourglass_empty' : 'lock_open' }}</mat-icon>
        {{ loading ? 'Verifying…' : 'Verify & Confirm' }}
      </button>
    </mat-dialog-actions>
  `,
})
export class StepUpDialogComponent {
  protected readonly data = inject<StepUpDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<StepUpDialogComponent>);
  private readonly stepUpService = inject(StepUpService);
  private readonly cdr = inject(ChangeDetectorRef);

  totpCode = '';
  approverToken = '';
  loading = false;
  errorMessage: string | null = null;

  cancel(): void {
    this.dialogRef.close(undefined);
  }

  submit(): void {
    if (!this.totpCode || this.totpCode.length < 6) return;
    if (this.data.requireDualControl && !this.approverToken.trim()) return;

    this.loading = true;
    this.errorMessage = null;
    this.cdr.markForCheck();

    this.stepUpService.issueToken(this.totpCode.trim()).subscribe({
      next: (res) => {
        const result: StepUpDialogResult = {
          stepUpToken: res.stepUpToken,
          dualControlToken: this.data.requireDualControl
            ? this.approverToken.trim()
            : undefined,
        };
        this.dialogRef.close(result);
      },
      error: (err) => {
        this.loading = false;
        this.errorMessage = err?.error?.message
          ?? 'Step-up verification failed. Please check your TOTP code and try again.';
        this.cdr.markForCheck();
      },
    });
  }
}
