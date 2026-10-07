import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnDestroy,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ApprovalRequestSession, CreateApprovalRequest, formatCountdown, parseApprovalTarget } from '@registerwerk/ui';
import { ApprovalQueueService } from '../../../core/api/approval-queue.service';
import { StepUpService } from '../../../core/api/step-up.service';
import { buildApprovalRequestBlock } from '../../utils/approval-request';
import { canonicalJson } from '../../utils/canonical-json';
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
  /**
   * JSON body of that request. Every dual-control approval is bound to the canonical body (only a few
   * documented non-JSON / secret-bearing reasons are exempt), so a caller whose request has a body MUST
   * pass exactly what it then sends - otherwise the approver's token does not match and the call is refused.
   * Leave undefined when the request sends no body.
   */
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
    MatProgressBarModule,
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

    .queue-box {
      border: 1px solid var(--rw-border);
      border-radius: 8px;
      background: var(--rw-surface-soft);
      padding: 12px 14px;
      margin-bottom: 12px;
      font-size: 13px;
      color: var(--rw-text-secondary);
      line-height: 1.5;
    }
    .queue-box .queue-title {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--rw-text-primary);
      font-weight: 600;
      margin-bottom: 4px;
    }
    .queue-box.approved { background: var(--rw-approved-bg); border-color: color-mix(in srgb, var(--rw-approved-fg) 30%, transparent); }
    .queue-box.approved .queue-title { color: var(--rw-approved-fg); }
    .queue-box.failed { background: var(--rw-rejected-bg); border-color: color-mix(in srgb, var(--rw-rejected-fg) 30%, transparent); }
    .queue-box.failed .queue-title { color: var(--rw-rejected-fg); }
    .queue-actions { display: flex; gap: 8px; margin-top: 10px; }
    .manual-fallback { margin: 4px 0 12px; }
    .manual-fallback summary {
      cursor: pointer;
      font-size: 12px;
      color: var(--rw-text-secondary);
      padding: 4px 0;
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
        @if (queueRequest) {
          @switch (session.phase) {
            @case ('idle') {
              <div class="queue-box">
                <div class="queue-title"><mat-icon>how_to_reg</mat-icon> Ask a second approver in the app</div>
                A second REGISTRY_ADMIN or COMPLIANCE_OFFICER sees this exact request in their
                <em>Approvals</em> inbox and approves it with their own authenticator code. You then confirm
                here, with a current code of your own, and the action runs.
                <div class="request-box">{{ data.target }}@if (bodyText) {
{{ bodyText }}}</div>
                <div class="queue-actions">
                  <button type="button" mat-stroked-button color="primary" (click)="requestApproval()"
                          [disabled]="loading">
                    <mat-icon>send</mat-icon> Request approval
                  </button>
                </div>
              </div>
            }
            @case ('waiting') {
              <div class="queue-box" role="status" aria-live="polite">
                <div class="queue-title"><mat-icon>hourglass_top</mat-icon> Waiting for a second approver…</div>
                @if (session.view) {
                  Request filed. It expires in {{ countdown }}; keep this dialog open or cancel the request.
                } @else {
                  Filing the request…
                }
                <mat-progress-bar mode="indeterminate" style="margin-top:10px" />
                <div class="queue-actions">
                  <button type="button" mat-button (click)="session.cancel()">Cancel request</button>
                </div>
              </div>
            }
            @case ('approved') {
              <div class="queue-box approved" role="status" aria-live="polite">
                <div class="queue-title"><mat-icon>verified_user</mat-icon>
                  Approved@if (session.view?.approverEmail; as approver) { by {{ approver }} }
                </div>
                Enter a current authenticator code above and confirm. The approval is used once and must be
                used before the request expires ({{ countdown }} left).
              </div>
            }
            @case ('failed') {
              <div class="queue-box failed" role="alert">
                <div class="queue-title"><mat-icon>error</mat-icon> {{ failureTitle }}</div>
                {{ session.error }}
                <div class="queue-actions">
                  <button type="button" mat-stroked-button color="primary" (click)="requestApproval()"
                          [disabled]="loading">
                    <mat-icon>refresh</mat-icon> Request approval again
                  </button>
                </div>
              </div>
            }
          }
        }
        <details class="manual-fallback" [open]="!queueRequest">
          <summary>Manual fallback: hand the request to the approver yourself</summary>
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
        </details>
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
              [disabled]="!canConfirm">
        <mat-icon>{{ loading ? 'hourglass_empty' : 'lock_open' }}</mat-icon>
        {{ loading ? 'Verifying…' : 'Verify & Confirm' }}
      </button>
    </mat-dialog-actions>
  `,
})
export class StepUpDialogComponent implements OnDestroy {
  protected readonly data = inject<StepUpDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<StepUpDialogComponent>);
  private readonly stepUpService = inject(StepUpService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  totpCode = '';
  approverToken = '';
  loading = false;
  errorMessage: string | null = null;

  /** The in-app approval request of this dialog (request -> wait -> claim); see `@registerwerk/ui`. */
  readonly session = new ApprovalRequestSession(inject(ApprovalQueueService), () => this.onSessionChange());
  private lastPhase = this.session.phase;

  /** The request to file, or null when the dialog has no `target` the queue could be bound to. */
  protected readonly queueRequest: CreateApprovalRequest | null = this.buildQueueRequest();

  /** The body in canonical form: the text the backend binds and the approver sees. */
  get bodyText(): string {
    return this.data.targetBody === undefined ? '' : canonicalJson(this.data.targetBody);
  }

  get countdown(): string {
    return formatCountdown(this.session.remainingSeconds);
  }

  get failureTitle(): string {
    switch (this.session.failureCode) {
      case 'REJECTED': return 'Rejected';
      case 'EXPIRED': return 'Expired';
      case 'CANCELLED': return 'Cancelled';
      case 'TIMEOUT': return 'No decision yet';
      case 'ALREADY_CLAIMED':
      case 'CLAIM_REFUSED': return 'The approval cannot be used';
      default: return 'The approval request failed';
    }
  }

  /** TOTP complete, nothing in flight, and the second approval is in hand (or not needed / pasted manually). */
  get canConfirm(): boolean {
    if (this.loading || !this.totpCode || this.totpCode.length < 6) return false;
    if (!this.data.requireDualControl) return true;
    if (this.session.phase === 'waiting') return false;
    return this.session.phase === 'approved' || !!this.approverToken.trim() || !!this.data.dualControlOptional;
  }

  ngOnDestroy(): void {
    this.session.destroy();
  }

  cancel(): void {
    // Abandoning the dialog withdraws an open request so it does not linger in the approvers' inbox.
    if (this.session.phase === 'waiting' || this.session.phase === 'approved') this.session.cancel();
    this.dialogRef.close(undefined);
  }

  requestApproval(): void {
    if (this.queueRequest) this.session.start(this.queueRequest);
  }

  private buildQueueRequest(): CreateApprovalRequest | null {
    if (!this.data.requireDualControl || !this.data.target) return null;
    const parts = parseApprovalTarget(this.data.target);
    if (!parts) return null;
    return {
      action: this.data.action,
      method: parts.method,
      path: parts.path,
      ...(parts.query ? { query: parts.query } : {}),
      ...(this.data.targetBody !== undefined ? { body: this.data.targetBody } : {}),
    };
  }

  private onSessionChange(): void {
    // A code typed while waiting is stale once the approval arrives (30 s steps): ask for a fresh one.
    if (this.lastPhase !== 'approved' && this.session.phase === 'approved') this.totpCode = '';
    this.lastPhase = this.session.phase;
    this.cdr.markForCheck();
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
    if (!this.canConfirm) return;

    submitTotpForStepUpToken(
      this.stepUpService, this.cdr, this, this.totpCode, null,
      (res) => {
        if (this.data.requireDualControl && this.session.phase === 'approved') {
          this.claimAndClose(res.stepUpToken);
          return;
        }
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

  /**
   * Claims the approver token only now (it is valid for a few minutes from the claim), then hands both tokens
   * to the caller, which sends the original request with `X-Dual-Control-Token` exactly as for a pasted token.
   */
  private claimAndClose(stepUpToken: string): void {
    this.loading = true;
    this.errorMessage = null;
    this.cdr.markForCheck();
    this.session.claim().subscribe({
      next: (claim) => {
        this.loading = false;
        this.dialogRef.close({ stepUpToken, dualControlToken: claim.approvalToken } satisfies StepUpDialogResult);
      },
      error: () => {
        // The session is now `failed` and explains why; the user files a fresh request.
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }
}
