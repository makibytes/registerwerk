import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnChanges, OnDestroy, SimpleChanges, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import {
  ApprovalRequestSession, CreateApprovalRequest, formatCountdown, prettyCanonicalBody,
} from '@registerwerk/ui';

/**
 * Second-approver step of an issuer action (mint, burn, forced transfer, ...), replacing "hand this block to
 * your approver": the user files the exact request here, operator staff approve it in the operator portal, and
 * the host then submits (the host claims the single-use approval when it runs the action).
 *
 * Customer users cannot approve anything themselves: their approvers are registry administrators or
 * compliance officers of the operator. The session lives in the host so it survives tab switches; this box
 * only renders it and files the request. Editing the form after filing invalidates the approval (it is bound
 * to the filed request), so the request is withdrawn and the user asks again.
 */
@Component({
  selector: 'app-approval-request-box',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule],
  styles: [`
    :host { display: block; }
    .box {
      border: 1px solid var(--rw-border);
      border-left: 3px solid var(--rw-accent);
      border-radius: 6px;
      background: var(--rw-surface-soft);
      padding: 12px 14px;
      font-size: 13px;
      color: var(--rw-text-secondary);
      line-height: 1.5;
    }
    .box.approved { background: var(--rw-approved-bg); border-left-color: var(--rw-approved-fg); }
    .box.failed { background: var(--rw-rejected-bg); border-left-color: var(--rw-rejected-fg); }
    .title { display: flex; align-items: center; gap: 8px; font-weight: 600; color: var(--rw-text-primary); margin-bottom: 4px; }
    .approved .title { color: var(--rw-approved-fg); }
    .failed .title { color: var(--rw-rejected-fg); }
    .title mat-icon { font-size: 18px; width: 18px; height: 18px; }
    .request { margin: 8px 0 0; font-family: monospace; font-size: 11px; white-space: pre-wrap; word-break: break-all; color: var(--rw-text-primary); }
    .actions { margin-top: 10px; display: flex; gap: 8px; }
    mat-progress-bar { margin-top: 10px; }
  `],
  template: `
    @switch (session().phase) {
      @case ('idle') {
        <div class="box">
          <div class="title"><mat-icon>how_to_reg</mat-icon> Second approver required</div>
          This action needs the approval of a second person. You cannot approve it yourself: a registry
          administrator or compliance officer of the operator reviews exactly the request below in the operator
          portal. Once it is approved, submit the action here.
          @if (request(); as req) {
            <pre class="request">{{ req.method }} {{ req.path }}@if (body(); as b) {
{{ b }}}</pre>
          }
          <div class="actions">
            <button type="button" mat-stroked-button color="primary" [disabled]="!request()" (click)="file()">
              <mat-icon>send</mat-icon> Request approval
            </button>
          </div>
        </div>
      }
      @case ('waiting') {
        <div class="box" role="status" aria-live="polite">
          <div class="title"><mat-icon>hourglass_top</mat-icon> Waiting for the operator's approval…</div>
          @if (session().view) {
            The request is open for another {{ countdown() }}. You can keep working; this page checks for the decision.
          } @else {
            Filing the request…
          }
          <mat-progress-bar mode="indeterminate" />
          <div class="actions"><button type="button" mat-button (click)="session().cancel()">Withdraw request</button></div>
        </div>
      }
      @case ('approved') {
        <div class="box approved" role="status" aria-live="polite">
          <div class="title"><mat-icon>verified_user</mat-icon> Approved by the operator</div>
          Submit the action now: the approval works once and ends in {{ countdown() }}. Changing any value above
          withdraws it.
        </div>
      }
      @case ('failed') {
        <div class="box failed" role="alert">
          <div class="title"><mat-icon>error</mat-icon> {{ failureTitle() }}</div>
          {{ session().error }}
          <div class="actions">
            <button type="button" mat-stroked-button color="primary" [disabled]="!request()" (click)="file()">
              <mat-icon>refresh</mat-icon> Request approval again
            </button>
          </div>
        </div>
      }
    }
  `,
})
export class ApprovalRequestBoxComponent implements OnChanges, OnDestroy {
  private readonly cdr = inject(ChangeDetectorRef);
  private stopListening?: () => void;
  private listeningTo?: ApprovalRequestSession;

  /** The session owned by the host (one per action). */
  readonly session = input.required<ApprovalRequestSession>();
  /** The request exactly as the action will send it now; null while the form is incomplete. */
  readonly request = input.required<CreateApprovalRequest | null>();

  protected body(): string {
    const body = this.request()?.body;
    return body === undefined ? '' : prettyCanonicalBody(JSON.stringify(body));
  }

  protected countdown(): string {
    return formatCountdown(this.session().remainingSeconds);
  }

  protected failureTitle(): string {
    switch (this.session().failureCode) {
      case 'REJECTED': return 'Rejected';
      case 'EXPIRED': return 'Expired';
      case 'CANCELLED': return 'Cancelled';
      case 'TIMEOUT': return 'No decision yet';
      case 'ALREADY_CLAIMED':
      case 'CLAIM_REFUSED': return 'The approval cannot be used';
      default: return 'The approval request failed';
    }
  }

  protected file(): void {
    const req = this.request();
    if (req) this.session().start(req);
  }

  ngOnChanges(changes: SimpleChanges): void {
    const session = this.session();
    // The state lives in the host's mutable session: re-render this OnPush box whenever it changes.
    if (this.listeningTo !== session) {
      this.stopListening?.();
      this.listeningTo = session;
      this.stopListening = session.listen(() => this.cdr.markForCheck());
    }
    // The approval is bound to the filed request: any edit of the form withdraws it.
    const filed = session.filedFor;
    if (changes['request'] && filed && (session.phase === 'waiting' || session.phase === 'approved')
        && JSON.stringify(filed) !== JSON.stringify(this.request())) {
      session.cancel();
    }
  }

  ngOnDestroy(): void {
    this.stopListening?.();
  }
}
