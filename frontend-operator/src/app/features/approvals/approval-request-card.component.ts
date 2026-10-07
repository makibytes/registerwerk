import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ApprovalRequestView, StatusBadgeComponent, formatCountdown, prettyCanonicalBody } from '@registerwerk/ui';

/**
 * One approval request as the approver (or its requester) reviews it: action, `METHOD path?query`, the
 * pretty-printed canonical body (the exact text the approval is bound to), who asked / decided and the
 * time left. Actions are projected in.
 */
@Component({
  selector: 'app-approval-request-card',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, StatusBadgeComponent],
  styles: [`
    :host { display: block; }
    .card {
      border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius);
      background: var(--rw-surface);
      padding: 16px 18px;
      margin-bottom: 12px;
    }
    .head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
    .action { font-weight: 700; font-size: 14px; color: var(--rw-text-primary); }
    .expiry { margin-left: auto; font-size: 12px; color: var(--rw-text-secondary); }
    .expiry.soon { color: var(--rw-text-warning); font-weight: 600; }
    .target {
      font-family: 'IBM Plex Mono', monospace; font-size: 12px; color: var(--rw-text-primary);
      word-break: break-all; margin: 0 0 8px;
    }
    .method { font-weight: 700; color: var(--rw-accent); margin-right: 6px; }
    .body {
      margin: 0 0 10px; padding: 10px 12px; border-radius: var(--rw-radius-sm);
      background: var(--rw-code-bg); color: var(--rw-code-fg);
      font-family: 'IBM Plex Mono', monospace; font-size: 11px; white-space: pre-wrap; word-break: break-all;
      max-height: 260px; overflow: auto;
    }
    .note { font-size: 12px; color: var(--rw-text-muted); margin: 0 0 10px; }
    .meta { display: flex; gap: 18px; flex-wrap: wrap; font-size: 12px; color: var(--rw-text-secondary); }
    .meta strong { color: var(--rw-text-primary); font-weight: 600; }
    .decision { font-size: 12px; color: var(--rw-text-secondary); margin-top: 6px; }
    .actions { margin-top: 12px; }
  `],
  template: `
    <article class="card" [attr.data-testid]="'approval-' + request().id" [attr.aria-label]="request().action + ' request'">
      <div class="head">
        <span class="action">{{ request().action }}</span>
        <app-status-badge [status]="request().status" />
        @if (open()) {
          <span class="expiry" [class.soon]="remaining() < 120">Expires in {{ countdown() }}</span>
        }
      </div>
      <p class="target"><span class="method">{{ request().method }}</span>{{ request().path }}@if (request().query) {?{{ request().query }}}</p>
      @if (body()) {
        <pre class="body" aria-label="Request body (canonical JSON)">{{ body() }}</pre>
      } @else if (request().canonicalBody === null) {
        <p class="note">The body of this request is not part of the approval (secret-bearing or non-JSON payload).</p>
      } @else {
        <p class="note">This request has no body.</p>
      }
      <div class="meta">
        <span>Requested by <strong>{{ request().requesterEmail ?? request().requesterUserId }}</strong></span>
        <span>at {{ request().createdAt | date: 'medium' }}</span>
      </div>
      @if (request().decidedAt) {
        <div class="decision">
          {{ request().status === 'REJECTED' ? 'Rejected' : 'Approved' }}
          @if (request().approverEmail) { by <strong>{{ request().approverEmail }}</strong> }
          at {{ request().decidedAt | date: 'medium' }}@if (request().decisionNote) { - {{ request().decisionNote }} }
        </div>
      }
      <div class="actions"><ng-content /></div>
    </article>
  `,
})
export class ApprovalRequestCardComponent {
  readonly request = input.required<ApprovalRequestView>();
  /** Epoch millis of "now" (one ticker per page keeps all countdowns in step). */
  readonly now = input<number>(Date.now());

  protected readonly body = computed(() => prettyCanonicalBody(this.request().canonicalBody));
  protected readonly open = computed(() => ['PENDING', 'APPROVED'].includes(this.request().status));
  protected readonly remaining = computed(() => Math.floor((Date.parse(this.request().expiresAt) - this.now()) / 1000));
  protected readonly countdown = computed(() => formatCountdown(this.remaining()));
}
