import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, PageHeaderComponent, TableColumn } from '@registerwerk/ui';
import { KycService } from '../../../core/api/kyc.service';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import { KycQueueItem } from '../../../core/models';
import { reasonLabel } from './kyc-reasons';

/**
 * The KYC work queue for the compliance officer and registry administrators (T8-03): entities awaiting a
 * decision, expiring KYC and evidence gaps. Rows open the scoped review; decisions are taken there.
 */
@Component({
  selector: 'app-kyc-queue',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, DataTableComponent, PageHeaderComponent],
  template: `
    <app-page-header title="KYC Queue"
      subtitle="Entities awaiting a KYC, beneficial-owner or EDD decision, expiring KYC and evidence gaps" />

    <rw-data-table
      [columns]="columns"
      [rows]="items"
      [state]="state"
      (retry)="load()"
      filterPlaceholder="Filter entities…"
      emptyMessage="Nothing needs a KYC decision."
      [actionsTemplate]="actions">
    </rw-data-table>

    <ng-template #actions let-item>
      <a mat-stroked-button color="primary" [routerLink]="['/compliance/kyc', item.entityId]"
         matTooltip="Open the scoped KYC review">
        <mat-icon>fact_check</mat-icon>
        Review
      </a>
    </ng-template>
  `,
})
export class KycQueueComponent implements OnInit {
  private readonly kycService = inject(KycService);
  private readonly cdr = inject(ChangeDetectorRef);

  items: KycQueueItem[] = [];
  state: AsyncSectionStatus = 'pending';

  readonly columns: TableColumn[] = [
    { key: 'entityName', header: 'Entity', cell: (i: KycQueueItem) => i.entityName, sortable: true },
    { key: 'homeJurisdiction', header: 'Jurisdiction', cell: (i: KycQueueItem) => i.homeJurisdiction },
    { key: 'kycStatus', header: 'KYC status', cell: (i: KycQueueItem) => i.kycStatus.replace(/_/g, ' ') },
    { key: 'kycExpiryDate', header: 'KYC expiry', cell: (i: KycQueueItem) => i.kycExpiryDate, type: 'date', sortable: true },
    { key: 'reasons', header: 'Why it is queued', cell: (i: KycQueueItem) => i.reasons.map(reasonLabel).join('; ') },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();
    this.kycService.getQueue().subscribe({
      next: (items) => { this.items = items; this.state = 'ready'; this.cdr.markForCheck(); },
      error: () => { this.state = 'error'; this.cdr.markForCheck(); },
    });
  }
}
