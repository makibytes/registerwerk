import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin } from 'rxjs';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { AccessReviewCampaign, AccessReviewItem, AccessReviewService } from '../../../core/api/access-review.service';
import { AsyncSectionStatus } from '../../../core/async/async-section';

@Component({
  selector: 'app-access-review-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
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
    <app-page-header [title]="campaign?.name ?? 'Access Review'" subtitle="Review each account's snapshotted roles">
      <button mat-stroked-button routerLink="/compliance/access-reviews">
        <mat-icon>arrow_back</mat-icon>
        All Campaigns
      </button>
      @if (campaign?.status === 'OPEN') {
        <button mat-raised-button color="primary" [disabled]="pendingCount() > 0" (click)="closeCampaign()"
                [matTooltip]="pendingCount() > 0 ? pendingCount() + ' item(s) still awaiting a decision' : 'Close campaign'">
          <mat-icon>task_alt</mat-icon>
          Close Campaign
        </button>
      }
    </app-page-header>

    @if (campaign) {
      <div class="summary">
        <span class="chip" [class.open]="campaign.status === 'OPEN'">{{ campaign.status }}</span>
        <span>{{ items.length }} account(s)</span>
        <span>{{ pendingCount() }} pending</span>
        <span>{{ confirmedCount() }} confirmed</span>
        <span>{{ revokedCount() }} revoked</span>
        @if (campaign.dueDate) { <span>Due {{ campaign.dueDate }}</span> }
      </div>
    }

    <rw-data-table
      [columns]="columns"
      [rows]="items"
      [state]="state"
      filterPlaceholder="Filter by email…"
      emptyMessage="No items in this campaign."
      [actionsTemplate]="itemActions">
    </rw-data-table>

    <ng-template #itemActions let-item>
      @if (item.decision === 'PENDING' && campaign?.status === 'OPEN') {
        <button mat-stroked-button color="primary" (click)="decide(item, 'CONFIRMED')">
          <mat-icon>check_circle</mat-icon>
          Confirm
        </button>
        <button mat-stroked-button color="warn" (click)="openRevokeDialog(item)">
          <mat-icon>block</mat-icon>
          Revoke
        </button>
      } @else if (item.notes) {
        <span class="notes" [matTooltip]="item.notes">{{ item.decision }}</span>
      }
    </ng-template>

    <ng-template #revokeDialogTpl>
      <h2 mat-dialog-title>Revoke Access</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        @if (selectedItem) {
          <p class="hint">
            This disables <strong>{{ selectedItem.email }}</strong>'s account immediately —
            roles at time of review: <code>{{ selectedItem.roles }}</code>.
          </p>
        }
        <mat-form-field appearance="outline">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" [(ngModel)]="decisionNotes"
            placeholder="Why this access is no longer needed"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="warn" [disabled]="!decisionNotes.trim()" (click)="submitRevoke()">
          <mat-icon>block</mat-icon>
          Revoke Access
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .summary {
      display: flex;
      gap: 16px;
      align-items: center;
      font-size: 13px;
      color: var(--rw-text-secondary);
      margin-bottom: 12px;
    }
    .chip {
      padding: 2px 8px;
      border-radius: 10px;
      background: var(--rw-surface-soft, rgba(0,0,0,0.06));
      font-weight: 600;
    }
    .chip.open { background: rgba(245,158,11,.15); color: #f59e0b; }
    .notes { font-size: 12px; color: var(--rw-text-secondary); cursor: help; }
    .hint { font-size: 13px; color: var(--rw-text-secondary); margin: 0; }
  `],
})
export class AccessReviewDetailComponent implements OnInit {
  @ViewChild('revokeDialogTpl') revokeDialogTpl!: TemplateRef<unknown>;

  private readonly accessReviewService = inject(AccessReviewService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly route = inject(ActivatedRoute);

  campaign: AccessReviewCampaign | null = null;
  items: AccessReviewItem[] = [];
  state: AsyncSectionStatus = 'pending';
  selectedItem: AccessReviewItem | null = null;
  decisionNotes = '';

  readonly columns: TableColumn[] = [
    { key: 'email', header: 'Account', cell: (i: AccessReviewItem) => i.email },
    { key: 'fullName', header: 'Name', cell: (i: AccessReviewItem) => i.fullName ?? '—' },
    { key: 'roles', header: 'Roles (at review start)', cell: (i: AccessReviewItem) => i.roles, type: 'mono' },
    { key: 'decision', header: 'Decision', cell: (i: AccessReviewItem) => i.decision, type: 'badge' },
    { key: 'reviewedAt', header: 'Reviewed', cell: (i: AccessReviewItem) => i.reviewedAt ?? '—' },
  ];

  pendingCount(): number { return this.items.filter(i => i.decision === 'PENDING').length; }
  confirmedCount(): number { return this.items.filter(i => i.decision === 'CONFIRMED').length; }
  revokedCount(): number { return this.items.filter(i => i.decision === 'REVOKED').length; }

  ngOnInit(): void {
    this.load();
  }

  private campaignId(): string {
    return this.route.snapshot.paramMap.get('id')!;
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();
    const id = this.campaignId();
    forkJoin({
      campaign: this.accessReviewService.getCampaign(id),
      items: this.accessReviewService.listItems(id),
    }).subscribe({
      next: ({ campaign, items }) => {
        this.campaign = campaign;
        this.items = items;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  decide(item: AccessReviewItem, decision: 'CONFIRMED' | 'REVOKED'): void {
    this.accessReviewService.recordDecision(this.campaignId(), item.id, decision).subscribe({
      next: () => {
        this.snackBar.open(`${item.email}: ${decision.toLowerCase()}.`, 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to record decision.', 'Dismiss', { duration: 6000 }),
    });
  }

  openRevokeDialog(item: AccessReviewItem): void {
    this.selectedItem = item;
    this.decisionNotes = '';
    this.dialog.open(this.revokeDialogTpl, { width: '480px' });
  }

  submitRevoke(): void {
    if (!this.selectedItem || !this.decisionNotes.trim()) return;
    const item = this.selectedItem;
    this.dialog.closeAll();
    this.accessReviewService.recordDecision(this.campaignId(), item.id, 'REVOKED', this.decisionNotes.trim()).subscribe({
      next: () => {
        this.snackBar.open(`${item.email}: access revoked.`, 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to revoke access.', 'Dismiss', { duration: 6000 }),
    });
  }

  closeCampaign(): void {
    this.accessReviewService.closeCampaign(this.campaignId()).subscribe({
      next: () => {
        this.snackBar.open('Campaign closed.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to close campaign.', 'Dismiss', { duration: 6000 }),
    });
  }
}
