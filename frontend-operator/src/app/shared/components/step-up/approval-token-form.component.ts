import { ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { StepUpService } from '../../../core/api/step-up.service';
import { ApprovalRequest, describeApprovalRequest, parseApprovalRequestBlock } from '../../utils/approval-request';
import { submitTotpForStepUpToken } from './step-up-totp-submit';

/**
 * Second-approver token generator (inline form). Runs in the approver's OWN session: the token is minted
 * for whoever is signed in here, and the backend refuses a token whose approver equals the initiator.
 * The approver pastes the request block the initiator copied, reviews it, enters their own TOTP code and
 * copies the single-use token back to the initiator.
 */
@Component({
  selector: 'app-approval-token-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatIconModule, MatTooltipModule],
  styles: [`
    .intro-text { font-size: 13px; color: var(--rw-text-secondary); line-height: 1.5; margin-bottom: 16px; }
    .intro-text ul { margin: 6px 0 0; padding-left: 18px; }
    .full-width { width: 100%; }
    .summary { margin: 0 0 16px; padding: 12px 14px; border: 1px solid var(--rw-border); border-radius: 8px; background: var(--rw-surface-soft, var(--rw-surface)); }
    .summary dl { display: grid; grid-template-columns: 90px 1fr; gap: 4px 12px; margin: 0; font-size: 13px; }
    .summary dt { color: var(--rw-text-muted); }
    .summary dd { margin: 0; color: var(--rw-text-primary); }
    .summary pre { margin: 0; white-space: pre-wrap; word-break: break-all; font-family: 'IBM Plex Mono', monospace; font-size: 11px; }
    .token-result { display: flex; align-items: flex-start; gap: 8px; margin-top: 8px; }
    .token-warning { font-size: 12px; color: var(--rw-text-warning); margin: 4px 0 0; }
    .error-msg { font-size: 12px; color: var(--rw-text-danger); margin-top: 8px; display: flex; align-items: center; gap: 6px; }
    .error-msg mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .form-actions { display: flex; justify-content: flex-end; gap: 10px; padding-top: 4px; }
  `],
  template: `
    <div class="intro-text">
      Approve a request <strong>in your own signed-in session</strong>: use a separate browser profile or device,
      never the initiator's browser. Tokens are single-use, valid for 5 minutes and bound to exactly this request.
      <ul>
        <li>Never type your password or authenticator seed into someone else's session.</li>
        <li>Do not leave tokens in shared chat history longer than needed.</li>
      </ul>
    </div>

    @if (!issuedToken) {
      @if (!preset) {
        <mat-form-field class="full-width" appearance="outline">
          <mat-label>Approval request block (pasted from the initiator)</mat-label>
          <textarea matInput rows="6" [ngModel]="blockText" (ngModelChange)="onBlockChange($event)"
                    placeholder='{"action":"…","target":"POST /api/v1/…"}'
                    [attr.aria-describedby]="errorMessage ? 'approval-token-error' : null"></textarea>
        </mat-form-field>
      }

      @if (request) {
        <div class="summary" role="group" aria-label="Request to approve">
          <dl>
            @for (line of summary; track line.label) {
              <dt>{{ line.label }}</dt>
              <dd>@if (line.label === 'Body') { <pre>{{ line.value }}</pre> } @else { {{ line.value }} }</dd>
            }
          </dl>
        </div>
        <mat-form-field class="full-width" appearance="outline">
          <mat-label>Your own TOTP code (6 digits)</mat-label>
          <mat-icon matPrefix>smartphone</mat-icon>
          <input matInput [(ngModel)]="totpCode" inputmode="numeric" autocomplete="one-time-code" maxlength="6"
                 placeholder="123456" (keydown.enter)="generate()"
                 [attr.aria-describedby]="errorMessage ? 'approval-token-error' : null" />
        </mat-form-field>
      }
    } @else {
      <div class="token-result">
        <mat-form-field class="full-width" appearance="outline">
          <mat-label>Scoped approval token</mat-label>
          <input matInput [value]="issuedToken" readonly />
        </mat-form-field>
        <button type="button" mat-icon-button matTooltip="Copy" aria-label="Copy token" (click)="copy()">
          <mat-icon>content_copy</mat-icon>
        </button>
      </div>
      <p class="token-warning">Send the token to the initiator now. It expires in 5 minutes and works once.</p>
    }

    @if (errorMessage) {
      <div class="error-msg" id="approval-token-error" role="alert">
        <mat-icon>error</mat-icon>
        {{ errorMessage }}
      </div>
    }

    <div class="form-actions">
      @if (!issuedToken) {
        <button type="button" mat-raised-button color="primary" (click)="generate()"
                [disabled]="loading || !request || !totpCode || totpCode.length < 6">
          <mat-icon>{{ loading ? 'hourglass_empty' : 'lock_open' }}</mat-icon>
          {{ loading ? 'Verifying…' : 'Generate token' }}
        </button>
      } @else {
        <button type="button" mat-stroked-button (click)="reset()">Approve another request</button>
      }
    </div>
  `,
})
export class ApprovalTokenFormComponent {
  private readonly stepUpService = inject(StepUpService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  /** A request supplied by the caller instead of a pasted block. */
  @Input() set preset(value: ApprovalRequest | null | undefined) {
    this._preset = value ?? null;
    this.request = this._preset;
  }
  get preset(): ApprovalRequest | null { return this._preset; }
  private _preset: ApprovalRequest | null = null;

  @Output() readonly tokenIssued = new EventEmitter<string>();

  blockText = '';
  request: ApprovalRequest | null = null;
  totpCode = '';
  issuedToken: string | null = null;
  loading = false;
  errorMessage: string | null = null;

  get summary() {
    return this.request ? describeApprovalRequest(this.request) : [];
  }

  onBlockChange(text: string): void {
    this.blockText = text;
    this.issuedToken = null;
    if (!text.trim()) {
      this.request = null;
      this.errorMessage = null;
      return;
    }
    const parsed = parseApprovalRequestBlock(text);
    this.request = parsed.request ?? null;
    this.errorMessage = parsed.error ?? null;
    this.cdr.markForCheck();
  }

  generate(): void {
    if (!this.request || !this.totpCode || this.totpCode.length < 6) return;
    submitTotpForStepUpToken(
      this.stepUpService, this.cdr, this, this.totpCode,
      { action: this.request.action, target: this.request.target, targetBody: this.request.targetBody },
      (res) => {
        this.issuedToken = res.stepUpToken;
        this.totpCode = '';
        this.tokenIssued.emit(res.stepUpToken);
      },
    );
  }

  copy(): void {
    if (!this.issuedToken) return;
    void navigator.clipboard.writeText(this.issuedToken);
    this.snackBar.open('Token copied to clipboard.', 'Dismiss', { duration: 2500 });
  }

  reset(): void {
    this.issuedToken = null;
    this.blockText = '';
    this.request = this._preset;
    this.totpCode = '';
    this.errorMessage = null;
  }
}
