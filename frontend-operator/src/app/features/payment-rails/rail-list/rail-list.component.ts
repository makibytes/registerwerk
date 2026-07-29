import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { PaymentRailService } from '../../../core/api/payment-rail.service';
import { PaymentRailView } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import {
  StepUpDialogComponent,
  StepUpDialogResult,
} from '../../../shared/components/step-up/step-up-dialog.component';
import { RailFormDialogComponent, RailFormDialogData } from '../rail-form-dialog/rail-form-dialog.component';

const RAIL_TYPE_LABELS: Record<string, string> = {
  STABLECOIN: 'Stablecoin',
  PONTES_API: 'Pontes API',
  ERC7573_DVP: 'ERC-7573 DvP',
  OFFCHAIN_SEPA: 'Off-chain SEPA',
};

@Component({
  selector: 'app-rail-list',
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
  template: `
    <app-page-header
      title="Payment Rails"
      subtitle="Operator-curated payment methods dApps can inject instead of building their own — MiCAR stablecoins, Pontes, ERC-7573 DvP, SEPA">
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="rails"
      [state]="state"
      filterPlaceholder="Filter by code or name…"
      emptyMessage="No payment rails configured yet."
      [actionsTemplate]="actions">
      <button tableToolbar mat-raised-button color="primary" (click)="openCreateDialog()">
        <mat-icon>add</mat-icon>
        Add payment rail
      </button>
    </rw-data-table>

    <ng-template #actions let-rail>
      <button mat-icon-button color="primary" (click)="openEditDialog(rail)" matTooltip="Edit (step-up)">
        <mat-icon>edit</mat-icon>
      </button>
      @if (rail.enabled) {
        <button mat-icon-button color="warn" (click)="disable(rail)" matTooltip="Disable (step-up)">
          <mat-icon>toggle_off</mat-icon>
        </button>
      } @else {
        <button mat-icon-button color="primary" (click)="enable(rail)" matTooltip="Enable (step-up)">
          <mat-icon>toggle_on</mat-icon>
        </button>
      }
    </ng-template>
  `,
})
export class RailListComponent implements OnInit {
  private readonly railService = inject(PaymentRailService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  rails: PaymentRailView[] = [];
  state: AsyncSectionStatus = 'pending';

  readonly columns: TableColumn[] = [
    { key: 'code', header: 'Code', cell: (r: PaymentRailView) => r.code, type: 'mono' },
    { key: 'displayName', header: 'Name', cell: (r: PaymentRailView) => r.displayName },
    {
      key: 'railType',
      header: 'Type',
      cell: (r: PaymentRailView) => RAIL_TYPE_LABELS[r.railType] ?? r.railType,
    },
    { key: 'currency', header: 'Currency', cell: (r: PaymentRailView) => r.currency },
    {
      key: 'issuer',
      header: 'MiCAR issuer',
      cell: (r: PaymentRailView) => (r.railType === 'STABLECOIN' ? (r.issuerName ?? '—') : '—'),
    },
    {
      key: 'enabled',
      header: 'Status',
      cell: (r: PaymentRailView) => (r.enabled ? 'ENABLED' : 'DISABLED'),
      type: 'badge',
    },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();
    this.railService.list().subscribe({
      next: (rails) => {
        this.rails = rails;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openCreateDialog(): void {
    const data: RailFormDialogData = { rail: null };
    this.dialog
      .open(RailFormDialogComponent, { data, width: '600px' })
      .afterClosed()
      .subscribe((body) => {
        if (!body) return;
        this.withStepUp('Payment rail creation', (token) =>
          this.railService.create(body, token).subscribe({
            next: () => {
              this.snackBar.open('Payment rail created.', 'Dismiss', { duration: 5000 });
              this.load();
            },
            error: (err) => {
              this.snackBar.open(err?.error?.message ?? 'Failed to create payment rail.', 'Dismiss', {
                duration: 6000,
              });
            },
          }),
        );
      });
  }

  openEditDialog(rail: PaymentRailView): void {
    const data: RailFormDialogData = { rail };
    this.dialog
      .open(RailFormDialogComponent, { data, width: '600px' })
      .afterClosed()
      .subscribe((body) => {
        if (!body) return;
        this.withDualControlStepUp('Payment rail update', (token, dualControlToken) =>
          this.railService.update(rail.id, body, token, dualControlToken).subscribe({
            next: () => {
              this.snackBar.open('Payment rail updated.', 'Dismiss', { duration: 5000 });
              this.load();
            },
            error: (err) => {
              this.snackBar.open(err?.error?.message ?? 'Failed to update payment rail.', 'Dismiss', {
                duration: 6000,
              });
            },
          }),
        );
      });
  }

  enable(rail: PaymentRailView): void {
    this.withStepUp('Payment rail enablement', (token) =>
      this.railService.enable(rail.id, token).subscribe({
        next: () => {
          this.snackBar.open('Payment rail enabled.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to enable payment rail.', 'Dismiss', {
            duration: 6000,
          });
        },
      }),
    );
  }

  disable(rail: PaymentRailView): void {
    this.withStepUp('Payment rail deactivation', (token) =>
      this.railService.disable(rail.id, token).subscribe({
        next: () => {
          this.snackBar.open('Payment rail disabled.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to disable payment rail.', 'Dismiss', {
            duration: 6000,
          });
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

  /** "Payment rail update" is dual-control on the backend, unlike the other rail actions. */
  private withDualControlStepUp(
    reason: string,
    action: (stepUpToken: string, dualControlToken: string) => void,
  ): void {
    this.dialog
      .open(StepUpDialogComponent, {
        data: { requireDualControl: true, reason, action: reason },
        width: '500px',
        disableClose: true,
      })
      .afterClosed()
      .subscribe((result: StepUpDialogResult | undefined) => {
        if (result?.stepUpToken && result.dualControlToken) {
          action(result.stepUpToken, result.dualControlToken);
        }
      });
  }
}
