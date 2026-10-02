import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { StepUpService } from '../../../core/api/step-up.service';
import { buildApprovalRequestBlock } from '../../utils/approval-request';
import { submitTotpForStepUpToken } from './step-up-totp-submit';

export interface StepUpDialogData {
  /** Whether the action requires a second approver (4-eyes principle). */
  requireDualControl: boolean;
  /** Human-readable reason shown to the user (e.g. "Accept false-positive screening hit"). */
  reason: string;
  /**
   * The exact `@RequiresStepUp(reason=...)` value of the backend action this token will be used
   * to approve. The backend's StepUpTokenValidator requires every
   * dual-control approver token to carry a matching `stepup_scope` claim — without this, the
   * approver's token is unconditionally rejected with 403, regardless of a valid TOTP code.
   */
  action: string;
  /**
   * The exact request the second approver is approving, as `"METHOD /api/v1/path[?query]"`. An
   * approver token is bound to it; shown to the approver and pre-filled in the generator.
   */
  target?: string;
  /**
   * The second approver is only required outside bootstrap mode (fewer than two enrolled admins), so the
   * field may be left empty; the backend decides and refuses with 403 when one was needed.
   */
  dualControlOptional?: boolean;
  /** JSON body of that request, for body-bound reasons (mint, burn, forced transfer/allowance). */
  targetBody?: unknown;
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
      background: var(--rw-pending-bg);
      border: 1px solid color-mix(in srgb, var(--rw-pending-fg) 30%, transparent);
      margin-bottom: 20px;

      mat-icon {
        color: var(--rw-accent);
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
      background: var(--rw-draft-bg);
      border: 1px solid color-mix(in srgb, var(--rw-draft-fg) 25%, transparent);
    }

    .request-box {
      margin: 6px 0 0;
      white-space: pre-wrap;
      word-break: break-all;
      font-family: 'IBM Plex Mono', monospace;
      font-size: 11px;
      color: var(--rw-text-primary);
    }
    .generate-link {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      margin-bottom: 10px;
      padding: 0;

      mat-icon { font-size: 15px; width: 15px; height: 15px; }
    }

    .full-width {
      width: 100%;
    }

    .error-msg {
      font-size: 12px;
      color: var(--rw-text-danger);
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
      <mat-icon style="vertical-align:middle;margin-right:8px;color:var(--rw-accent)">security</mat-icon>
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
               [attr.aria-describedby]="errorMessage ? 'step-up-error' : null"
               [attr.aria-invalid]="errorMessage ? 'true' : null"
               (keydown.enter)="submit()" />
      </mat-form-field>

      @if (data.requireDualControl) {
        <div class="section-label">Second approver</div>
        <div class="dual-control-note">
          A second REGISTRY_ADMIN or COMPLIANCE_OFFICER must approve in <strong>their own signed-in
          session</strong> (separate browser profile or device, page <em>Approvals</em>) and send you
          their scoped token to paste below. Both tokens must be from different users; the approver's
          token is single-use, valid for 5 minutes and bound to this exact request.
          @if (data.dualControlOptional) {
            Leave it empty only while fewer than two administrators have an authenticator enrolled (bootstrap).
          }
        </div>
        @if (data.target) {
          <div class="dual-control-note">
            The approval is bound to exactly this request and works once for 5 minutes:
            <pre class="request-box">{{ data.target }}@if (bodyText) {
{{ bodyText }}}</pre>
          </div>
        }
        <button type="button" mat-button color="primary" class="generate-link" (click)="copyApprovalRequest()"
                [disabled]="!data.target">
          <mat-icon>content_copy</mat-icon>
          Copy approval request
        </button>
        <div class="dual-control-note">
          Send the copied request block to the second administrator. Never share a password or authenticator
          seed, and do not keep tokens in shared chat history longer than needed.
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
        <div class="error-msg" id="step-up-error" role="alert">
          <mat-icon>error</mat-icon>
          {{ errorMessage }}
        </div>
      }
    </mat-dialog-content>

    <mat-dialog-actions class="dialog-actions">
      <button type="button" mat-stroked-button (click)="cancel()" [disabled]="loading">Cancel</button>
      <button type="button" mat-raised-button color="primary"
              (click)="submit()"
              [disabled]="loading || !totpCode || totpCode.length < 6 || (data.requireDualControl && !data.dualControlOptional && !approverToken)">
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
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  totpCode = '';
  approverToken = '';
  loading = false;
  errorMessage: string | null = null;

  get bodyText(): string {
    return this.data.targetBody === undefined ? '' : JSON.stringify(this.data.targetBody);
  }

  cancel(): void {
    this.dialogRef.close(undefined);
  }

  /** The exact request block the second approver pastes into the Approvals page. */
  get approvalRequestBlock(): string | null {
    if (!this.data.target) return null;
    return buildApprovalRequestBlock({
      action: this.data.action,
      target: this.data.target,
      targetBody: this.data.targetBody,
    });
  }

  copyApprovalRequest(): void {
    const block = this.approvalRequestBlock;
    if (!block) return;
    void navigator.clipboard.writeText(block);
    this.snackBar.open('Approval request copied. Send it to the second administrator.', 'Dismiss', { duration: 3500 });
  }

  submit(): void {
    if (!this.totpCode || this.totpCode.length < 6) return;
    if (this.data.requireDualControl && !this.data.dualControlOptional && !this.approverToken.trim()) return;

    submitTotpForStepUpToken(
      this.stepUpService, this.cdr, this, this.totpCode, null,
      (res) => {
        const result: StepUpDialogResult = {
          stepUpToken: res.stepUpToken,
          dualControlToken: this.data.requireDualControl && this.approverToken.trim()
            ? this.approverToken.trim()
            : undefined,
        };
        this.dialogRef.close(result);
      },
    );
  }
}
