import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { PageHeaderComponent } from '@registerwerk/ui';
import { ApprovalTokenFormComponent } from '../../shared/components/step-up/approval-token-form.component';

/**
 * Approvals (second-approver side of dual control). Interim for the in-app approval queue (parked T8-02):
 * the initiator copies an approval request block from their step-up dialog and sends it out of band; the
 * approver pastes it here, in their own session, reviews it, mints a request-bound single-use token with
 * their own TOTP code and sends it back.
 */
@Component({
  selector: 'app-approvals',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatCardModule, PageHeaderComponent, ApprovalTokenFormComponent],
  styles: [`
    .approvals-card { max-width: 720px; }
  `],
  template: `
    <div class="page-container">
      <app-page-header title="Approvals"
        subtitle="Approve a colleague's dual-control request in your own session." />
      <mat-card class="approvals-card">
        <mat-card-content>
          <app-approval-token-form />
        </mat-card-content>
      </mat-card>
    </div>
  `,
})
export class ApprovalsComponent {}
