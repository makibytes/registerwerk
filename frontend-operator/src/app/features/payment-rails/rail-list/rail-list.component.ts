import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { AuthService } from '../../../core/auth/auth.service';
import { PaymentRailService } from '../../../core/api/payment-rail.service';
import { PaymentRailView } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import {
  StepUpDialogComponent,
  StepUpDialogResult,
} from '../../../shared/components/step-up/step-up-dialog.component';
import { withDualControl } from '../../../shared/components/step-up/with-dual-control';
import { DualControlTokens } from '../../../core/api/dual-control-headers';
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
  styles: [`
    .rail-note { margin: 0 0 12px; font-size: 12.5px; color: var(--rw-text-secondary); max-width: 900px; }
  `],
  template: `
    <app-page-header
      title="Payment Rails"
      subtitle="Operator-curated payment methods dApps can inject instead of building their own — MiCAR stablecoins, Pontes, ERC-7573 DvP, SEPA">
    </app-page-header>

    <p class="rail-note" role="note">
      Creating, updating, enabling and attesting a rail each need step-up and a second approver. New rails start
      disabled. The MiCAR fields are operator-entered claims: an attestation records that an operator checked them
      against an external source (Registerwerk does not verify them), is bound to the current token address and
      issuer details, and must be given by an operator who neither created nor last edited the rail. Changing any
      bound detail clears the attestation and switches an enabled EMT rail off automatically.
    </p>

    <rw-data-table
      [columns]="columns"
      [rows]="rails"
      [state]="state"
      (retry)="load()"
      filterPlaceholder="Filter by code or name…"
      emptyMessage="No payment rails configured yet."
      [actionsTemplate]="actions">
      <button type="button" tableToolbar mat-raised-button color="primary" (click)="openCreateDialog()">
        <mat-icon>add</mat-icon>
        Add payment rail
      </button>
    </rw-data-table>

    <ng-template #actions let-rail>
      <button type="button" mat-icon-button color="primary" (click)="openEditDialog(rail)" matTooltip="Edit (step-up + second approver)">
        <mat-icon>edit</mat-icon>
      </button>
      @if (rail.railType === 'STABLECOIN' && rail.emtFlag) {
        @if (rail.micarVerified) {
          <button type="button" mat-icon-button (click)="unverify(rail)" matTooltip="Clear MiCAR attestation (step-up)">
            <mat-icon>remove_done</mat-icon>
          </button>
        } @else {
          <button type="button" mat-icon-button color="primary" (click)="verify(rail)"
                  [disabled]="isCreatorOrEditor(rail)"
                  [matTooltip]="isCreatorOrEditor(rail) ? 'You created or last edited this rail: another operator must attest it' : 'Attest MiCAR details (step-up + second approver)'">
            <mat-icon>verified</mat-icon>
          </button>
        }
      }
      @if (rail.enabled) {
        <button type="button" mat-icon-button color="warn" (click)="disable(rail)" matTooltip="Disable (step-up)">
          <mat-icon>toggle_off</mat-icon>
        </button>
      } @else {
        <button type="button" mat-icon-button color="primary" (click)="enable(rail)" matTooltip="Enable (step-up + second approver)">
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
  private readonly auth = inject(AuthService);

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
      key: 'attestation',
      header: 'MiCAR attestation',
      cell: (r: PaymentRailView) =>
        r.railType !== 'STABLECOIN' ? '—'
          : r.micarVerified ? 'OPERATOR-ATTESTED'
          : r.emtFlag ? 'NOT ATTESTED' : 'NO EMT CLAIM',
    },
    {
      key: 'enabled',
      header: 'Status',
      cell: (r: PaymentRailView) => (r.enabled ? 'ENABLED' : 'DISABLED'),
      type: 'badge',
    },
    {
      key: 'disabledReason',
      header: 'Disabled because',
      cell: (r: PaymentRailView) => this.disabledText(r),
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
        this.withDualControlStepUp('Payment rail creation', 'POST /api/v1/payment-rails', body, (tokens) =>
          this.railService.create(body, tokens).subscribe({
            next: () => {
              this.snackBar.open('Payment rail created. It starts disabled; enable it (and attest it, for an EMT) separately.', 'Dismiss', { duration: 7000 });
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
        this.withDualControlStepUp('Payment rail update', `PUT /api/v1/payment-rails/${rail.id}`, body, (tokens) =>
          this.railService.update(rail.id, body, tokens).subscribe({
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
    this.withDualControlStepUp('Payment rail enablement', `POST /api/v1/payment-rails/${rail.id}/enable`, {}, (tokens) =>
      this.railService.enable(rail.id, tokens).subscribe({
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

  verify(rail: PaymentRailView): void {
    this.withDualControlStepUp('Payment rail MiCAR attestation', `POST /api/v1/payment-rails/${rail.id}/verify-micar`, {}, (tokens) =>
      this.railService.verifyMicar(rail.id, tokens).subscribe({
        next: () => {
          this.snackBar.open('MiCAR details attested. The attestation is bound to the current token address and issuer details.', 'Dismiss', { duration: 6000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to attest the rail.', 'Dismiss', { duration: 7000 });
        },
      }),
    );
  }

  unverify(rail: PaymentRailView): void {
    this.withStepUp('Payment rail MiCAR attestation cleared', (token) =>
      this.railService.unverifyMicar(rail.id, token).subscribe({
        next: () => {
          this.snackBar.open('Attestation cleared. An enabled EMT rail was switched off.', 'Dismiss', { duration: 6000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to clear the attestation.', 'Dismiss', { duration: 6000 });
        },
      }),
    );
  }

  /** The attester must differ from the rail's creator and last editor (403 otherwise). */
  isCreatorOrEditor(rail: PaymentRailView): boolean {
    const me = this.auth.getUserId();
    return !!me && (rail.createdBy === me || rail.updatedBy === me);
  }

  disabledText(rail: PaymentRailView): string {
    switch (rail.disabledReason) {
      case 'MICAR_ATTESTATION_INVALIDATED': return 'Attestation invalidated by an edit';
      case 'MICAR_ATTESTATION_MISSING': return 'EMT claim without attestation';
      case undefined:
      case null: return '—';
      default: return rail.disabledReason;
    }
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

  /**
   * Create, update, enable and MiCAR attestation are dual-control on the backend. `target` and `body` are exactly
   * the request the service then sends (the approver token is bound to its method, path and canonical body).
   */
  private withDualControlStepUp(
    reason: string,
    target: string,
    body: unknown,
    action: (tokens: DualControlTokens) => void,
  ): void {
    withDualControl(this.dialog, { action: reason, reason, target, targetBody: body }, action);
  }
}
