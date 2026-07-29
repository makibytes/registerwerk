import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { MarketplaceAdminService } from '../../../core/api/marketplace-admin.service';
import { ReviewQueueItemView } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';

@Component({
  selector: 'app-review-queue',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, DataTableComponent, PageHeaderComponent],
  styles: [``],
  template: `
    <app-page-header
      title="dApp Review Queue"
      subtitle="Submitted marketplace manifests awaiting 4-eyes review — approval anchors the manifest hash onchain">
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="queue"
      [state]="state"
      filterPlaceholder="Filter by dApp, publisher…"
      emptyMessage="No submissions waiting for review."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-item>
      <button mat-icon-button color="primary" (click)="openReview(item)" matTooltip="Review manifest">
        <mat-icon>rate_review</mat-icon>
      </button>
    </ng-template>
  `,
})
export class ReviewQueueComponent implements OnInit {
  private readonly marketplaceAdminService = inject(MarketplaceAdminService);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  queue: ReviewQueueItemView[] = [];
  state: AsyncSectionStatus = 'pending';

  readonly columns: TableColumn[] = [
    { key: 'name', header: 'dApp', cell: (r: ReviewQueueItemView) => r.name },
    { key: 'slug', header: 'Slug', cell: (r: ReviewQueueItemView) => r.slug, type: 'mono' },
    { key: 'publisherName', header: 'Publisher', cell: (r: ReviewQueueItemView) => r.publisherName ?? '—' },
    { key: 'version', header: 'Version', cell: (r: ReviewQueueItemView) => r.version },
    { key: 'status', header: 'Status', cell: (r: ReviewQueueItemView) => r.status, type: 'badge' },
    { key: 'submittedAt', header: 'Submitted', cell: (r: ReviewQueueItemView) => r.submittedAt ?? '—', type: 'date' },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();
    this.marketplaceAdminService.reviewQueue().subscribe({
      next: (queue) => {
        this.queue = queue;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openReview(item: ReviewQueueItemView): void {
    this.router.navigate(['/dapp-review', item.versionId]);
  }
}
