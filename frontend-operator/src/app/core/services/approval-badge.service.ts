import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { EMPTY, Subscription, catchError, distinctUntilChanged, fromEvent, map, startWith, switchMap, timer } from 'rxjs';
import { ApprovalQueueService } from '../api/approval-queue.service';
import { AuthService } from '../auth/auth.service';

/** How often the nav badge asks for the number of requests waiting for the signed-in approver. */
export const APPROVAL_BADGE_POLL_MS = 30_000;

/**
 * Number of dual-control requests waiting for the signed-in approver (nav badge). Polls
 * `GET /approvals/pending/count` every 30 s while the tab is visible and not at all while it is hidden;
 * only REGISTRY_ADMIN / COMPLIANCE_OFFICER can be approvers, everyone else never calls the endpoint.
 */
@Injectable({ providedIn: 'root' })
export class ApprovalBadgeService implements OnDestroy {
  private readonly api = inject(ApprovalQueueService);
  private readonly auth = inject(AuthService);

  readonly pendingCount = signal(0);
  private polling?: Subscription;

  private get isApprover(): boolean {
    return this.auth.hasRole('REGISTRY_ADMIN') || this.auth.hasRole('COMPLIANCE_OFFICER');
  }

  /** Idempotent; called by the shell's sidebar. */
  start(): void {
    if (this.polling || !this.isApprover) return;
    this.polling = fromEvent(document, 'visibilitychange').pipe(
      map(() => !document.hidden),
      startWith(!document.hidden),
      distinctUntilChanged(),
      switchMap(visible => visible ? timer(0, APPROVAL_BADGE_POLL_MS) : EMPTY),
      switchMap(() => this.api.pendingCount().pipe(catchError(() => EMPTY))),
    ).subscribe(({ count }) => this.pendingCount.set(count));
  }

  stop(): void {
    this.polling?.unsubscribe();
    this.polling = undefined;
    this.pendingCount.set(0);
  }

  /** One-off refresh (e.g. after a decision), independent of the poll. */
  refresh(): void {
    if (!this.isApprover) return;
    this.api.pendingCount().pipe(catchError(() => EMPTY)).subscribe(({ count }) => this.pendingCount.set(count));
  }

  ngOnDestroy(): void {
    this.stop();
  }
}
