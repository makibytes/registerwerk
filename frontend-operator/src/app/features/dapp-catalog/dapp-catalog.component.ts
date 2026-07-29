import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { MarketplaceAdminService } from '../../core/api/marketplace-admin.service';
import { DappListingView } from '../../core/models';
import { AsyncSectionStatus } from '../../core/async/async-section';
import {
  StepUpDialogComponent,
  StepUpDialogResult,
} from '../../shared/components/step-up/step-up-dialog.component';

@Component({
  selector: 'app-dapp-catalog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [``],
  template: `
    <app-page-header
      title="dApp Catalog"
      subtitle="All marketplace listings — deprecate or delist published dApps (mirrored onchain)">
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="listings"
      [state]="state"
      filterPlaceholder="Filter by name, slug, publisher…"
      emptyMessage="No listings yet."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-listing>
      @if (listing.status === 'PUBLISHED') {
        <button mat-icon-button (click)="deprecate(listing)"
                matTooltip="Deprecate — stays visible, flagged as outdated (step-up)">
          <mat-icon>history_toggle_off</mat-icon>
        </button>
      }
      @if (listing.status === 'PUBLISHED' || listing.status === 'DEPRECATED') {
        <button mat-icon-button color="warn" (click)="delist(listing)"
                matTooltip="Delist — removed from catalog, suspended onchain (step-up)">
          <mat-icon>visibility_off</mat-icon>
        </button>
      }
    </ng-template>
  `,
})
export class DappCatalogComponent implements OnInit {
  private readonly marketplaceAdminService = inject(MarketplaceAdminService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  listings: DappListingView[] = [];
  state: AsyncSectionStatus = 'pending';

  readonly columns: TableColumn[] = [
    { key: 'name', header: 'dApp', cell: (r: DappListingView) => r.name },
    { key: 'slug', header: 'Slug', cell: (r: DappListingView) => r.slug, type: 'mono' },
    { key: 'publisherName', header: 'Publisher', cell: (r: DappListingView) => r.publisherName ?? '—' },
    { key: 'chainIdentifier', header: 'Chain', cell: (r: DappListingView) => r.chainIdentifier ?? '—' },
    { key: 'status', header: 'Status', cell: (r: DappListingView) => r.status, type: 'badge' },
    { key: 'updatedAt', header: 'Updated', cell: (r: DappListingView) => r.updatedAt, type: 'date' },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();
    this.marketplaceAdminService.allListings().subscribe({
      next: (listings) => {
        this.listings = listings;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  deprecate(listing: DappListingView): void {
    this.withStepUp('dApp deprecation', (token) =>
      this.marketplaceAdminService.deprecate(listing.id, token).subscribe({
        next: () => {
          this.snackBar.open(`${listing.name} deprecated.`, 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Deprecation failed.', 'Dismiss', { duration: 6000 });
        },
      }),
    );
  }

  delist(listing: DappListingView): void {
    this.withStepUp('dApp delisting', (token) =>
      this.marketplaceAdminService.delist(listing.id, token).subscribe({
        next: () => {
          this.snackBar.open(`${listing.name} delisted.`, 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Delisting failed.', 'Dismiss', { duration: 6000 });
        },
      }),
    );
  }

  private withStepUp(reason: string, action: (stepUpToken: string) => void): void {
    this.dialog
      .open(StepUpDialogComponent, {
        data: { requireDualControl: false, reason, action: reason },
        width: '500px',
        disableClose: true,
      })
      .afterClosed()
      .subscribe((result: StepUpDialogResult | undefined) => {
        if (result?.stepUpToken) {
          action(result.stepUpToken);
        }
      });
  }
}
