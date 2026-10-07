import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatCardModule } from '@angular/material/card';
import { MatTabsModule } from '@angular/material/tabs';
import { PageHeaderComponent } from '@registerwerk/ui';
import { timer } from 'rxjs';
import { ApprovalBadgeService } from '../../core/services/approval-badge.service';
import { ApprovalTokenFormComponent } from '../../shared/components/step-up/approval-token-form.component';
import { ApprovalInboxComponent } from './approval-inbox.component';
import { MyApprovalRequestsComponent } from './my-approval-requests.component';

/**
 * Approvals: the in-app dual-control queue (T8-02). "Inbox" lists the requests of other administrators that
 * wait for you (approve with your own TOTP code, or reject); "My requests" lists what you filed. The old
 * copy-a-request-block flow stays reachable as the manual fallback below.
 */
@Component({
  selector: 'app-approvals',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatCardModule, MatTabsModule, PageHeaderComponent, ApprovalTokenFormComponent, ApprovalInboxComponent, MyApprovalRequestsComponent],
  styles: [`
    .tab-body { padding-top: 16px; max-width: 820px; }
    .fallback { margin-top: 24px; max-width: 820px; }
    .fallback summary { cursor: pointer; font-size: 13px; font-weight: 600; color: var(--rw-text-secondary); padding: 8px 0; }
    .approvals-card { max-width: 720px; margin-top: 8px; }
  `],
  template: `
    <div class="page-container">
      <app-page-header title="Approvals"
        subtitle="Approve a colleague's dual-control request, or follow the requests you filed." />

      <mat-tab-group animationDuration="0ms" (selectedTabChange)="badge.refresh()">
        <mat-tab>
          <ng-template mat-tab-label>
            Inbox@if (badge.pendingCount() > 0) { ({{ badge.pendingCount() }}) }
          </ng-template>
          <div class="tab-body"><app-approval-inbox [now]="now()" /></div>
        </mat-tab>
        <mat-tab label="My requests">
          <div class="tab-body"><app-my-approval-requests [now]="now()" /></div>
        </mat-tab>
      </mat-tab-group>

      <details class="fallback">
        <summary>Manual fallback: approve a pasted request block</summary>
        <mat-card class="approvals-card">
          <mat-card-content>
            <app-approval-token-form />
          </mat-card-content>
        </mat-card>
      </details>
    </div>
  `,
})
export class ApprovalsComponent {
  protected readonly badge = inject(ApprovalBadgeService);
  protected readonly now = signal(Date.now());

  constructor() {
    timer(1000, 1000).pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(() => this.now.set(Date.now()));
  }
}
