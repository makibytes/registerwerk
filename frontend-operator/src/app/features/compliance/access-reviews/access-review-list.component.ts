import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { AccessReviewCampaign, AccessReviewService } from '../../../core/api/access-review.service';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * Entitlement-review (access recertification) campaigns — BAIT and every bank's IAM policy
 * require periodic sign-off of user entitlements; previously there was no campaign tooling and
 * no "last reviewed" record for any account's roles.
 */
@Component({
  selector: 'app-access-review-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  template: `
    <app-page-header
      title="Access Reviews"
      subtitle="Periodic entitlement recertification — every enabled account's roles, attested by a second reviewer">
      @if (canStartCampaign) {
        <button mat-raised-button color="primary" (click)="openStartDialog()">
          <mat-icon>playlist_add_check</mat-icon>
          Start Campaign
        </button>
      }
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="campaigns"
      [state]="state"
      (retry)="load()"
      emptyMessage="No access review campaigns yet."
      [actionsTemplate]="campaignActions">
    </rw-data-table>

    <ng-template #campaignActions let-campaign>
      <button mat-icon-button (click)="openCampaign(campaign)" matTooltip="Open campaign">
        <mat-icon>chevron_right</mat-icon>
      </button>
    </ng-template>

    <ng-template #startDialogTpl>
      <h2 mat-dialog-title>Start Access Review Campaign</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        <p class="hint">
          Snapshots every enabled account's current roles for review. Each item must be decided
          CONFIRMED or REVOKED by a reviewer other than the account holder before the campaign can
          be closed.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Campaign name</mat-label>
          <input matInput [(ngModel)]="campaignName" placeholder="Q3 2026 entitlement review" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Due date (optional)</mat-label>
          <input matInput type="date" [(ngModel)]="campaignDueDate" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" [disabled]="!campaignName.trim()" (click)="submitStart()">
          <mat-icon>playlist_add_check</mat-icon>
          Start
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .hint { font-size: 13px; color: var(--rw-text-secondary); margin: 0; }
  `],
})
export class AccessReviewListComponent implements OnInit {
  @ViewChild('startDialogTpl') startDialogTpl!: TemplateRef<unknown>;

  private readonly accessReviewService = inject(AccessReviewService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  readonly canStartCampaign = this.auth.hasRole('REGISTRY_ADMIN');

  campaigns: AccessReviewCampaign[] = [];
  state: AsyncSectionStatus = 'pending';
  campaignName = '';
  campaignDueDate = '';

  readonly columns: TableColumn[] = [
    { key: 'name', header: 'Campaign', cell: (c: AccessReviewCampaign) => c.name },
    { key: 'status', header: 'Status', cell: (c: AccessReviewCampaign) => c.status, type: 'badge' },
    { key: 'dueDate', header: 'Due', cell: (c: AccessReviewCampaign) => c.dueDate ?? '—' },
    { key: 'startedAt', header: 'Started', cell: (c: AccessReviewCampaign) => c.startedAt, type: 'date' },
    { key: 'closedAt', header: 'Closed', cell: (c: AccessReviewCampaign) => c.closedAt ?? '—' },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();
    this.accessReviewService.listCampaigns().subscribe({
      next: (campaigns) => {
        this.campaigns = campaigns;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openCampaign(campaign: AccessReviewCampaign): void {
    this.router.navigate(['/compliance/access-reviews', campaign.id]);
  }

  openStartDialog(): void {
    this.campaignName = '';
    this.campaignDueDate = '';
    this.dialog.open(this.startDialogTpl, { width: '480px' });
  }

  submitStart(): void {
    if (!this.campaignName.trim()) return;
    this.dialog.closeAll();
    this.accessReviewService.startCampaign({
      name: this.campaignName.trim(),
      dueDate: this.campaignDueDate || undefined,
    }).subscribe({
      next: (campaign) => {
        this.snackBar.open(`Campaign "${campaign.name}" started.`, 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to start campaign.', 'Dismiss', { duration: 6000 }),
    });
  }
}
