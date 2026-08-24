import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, SlicePipe } from '@angular/common';
import { PortfolioMigrationService } from '../../../../core/api/portfolio-migration.service';
import { PortfolioMigrationRequest } from '../../../../core/models';
import { StepUpDialogComponent } from '../../../../shared/components/step-up/step-up-dialog.component';

/**
 * Operator view of investor-side portfolio migration: one holding moved to a successor
 * registrar. `PortfolioMigrationController` previously had no frontend caller at all.
 */
@Component({
  selector: 'app-portfolio-migration',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatTooltipModule, DatePipe, SlicePipe],
  template: `
    <div class="pm-shell">
      <div class="pm-header">
        <div>
          <h3 class="pm-title">Portfolio migration to successor registrar</h3>
          <p class="dimmed small" style="margin:4px 0 0">
            Moves one holding at a time. The on-chain transfer itself uses the existing
            token-admin tooling — this only records the handover.
          </p>
        </div>
        <button type="button" mat-raised-button color="primary" (click)="openInitiateDialog()">
          <mat-icon>move_up</mat-icon>
          Initiate Migration
        </button>
      </div>

      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:24px">Loading…</p>
      } @else if (migrations.length === 0) {
        <div class="empty-state">
          <mat-icon class="empty-icon">move_up</mat-icon>
          <p>No portfolio migration has been initiated for this investor.</p>
        </div>
      } @else {
        <div class="pm-table">
          <div class="pm-row header">
            <span>Holder / Asset</span>
            <span>Destination</span>
            <span>Status</span>
            <span>Initiated</span>
            <span></span>
          </div>

          @for (m of migrations; track m.id) {
            <div class="pm-row">
              <span class="mono small">{{ m.holderId | slice:0:8 }}…</span>
              <span class="dimmed small">
                {{ m.destinationRegistrarName ?? 'Not set' }}
                @if (m.destinationWalletAddress) { <br /><span class="mono">{{ m.destinationWalletAddress | slice:0:14 }}…</span> }
              </span>
              <span class="status-badge" [class]="m.status.toLowerCase()">{{ m.status.replace('_', ' ') }}</span>
              <span class="dimmed">{{ m.initiatedAt | date:'dd MMM yyyy' }}</span>
              <div class="row-actions">
                @if (m.status === 'INITIATED' && !m.destinationWalletAddress) {
                  <button type="button" mat-stroked-button (click)="openDestinationDialog(m)">
                    <mat-icon>edit_location</mat-icon>
                    Set Destination
                  </button>
                }
                @if (m.status === 'INITIATED' && m.destinationWalletAddress) {
                  <button type="button" mat-stroked-button [disabled]="exporting.has(m.id)" (click)="exportPackage(m)">
                    <mat-icon>download</mat-icon>
                    {{ exporting.has(m.id) ? 'Exporting…' : 'Export' }}
                  </button>
                }
                @if (m.status === 'EXPORTED') {
                  <button type="button" mat-stroked-button color="warn" matTooltip="Requires step-up + a second approver"
                          (click)="openTransferDialog(m)">
                    <mat-icon>link</mat-icon>
                    Record Transfer
                  </button>
                }
                @if (m.status === 'HANDED_OVER') {
                  <button type="button" mat-stroked-button color="primary" (click)="complete(m)">
                    <mat-icon>task_alt</mat-icon>
                    Complete
                  </button>
                }
                @if (m.status !== 'COMPLETED' && m.status !== 'CANCELLED') {
                  <button type="button" mat-icon-button color="warn" matTooltip="Cancel" (click)="openCancelDialog(m)">
                    <mat-icon>block</mat-icon>
                  </button>
                }
              </div>
            </div>
          }
        </div>
      }
    </div>

    <ng-template #initiateDialogTpl>
      <h2 mat-dialog-title>Initiate Portfolio Migration</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        <mat-form-field appearance="outline">
          <mat-label>Holder ID</mat-label>
          <input matInput [(ngModel)]="initiateForm.holderId" placeholder="xxxxxxxx-…" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" [(ngModel)]="initiateForm.reason"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary"
                [disabled]="!initiateForm.holderId.trim() || !initiateForm.reason.trim()"
                (click)="submitInitiate()">
          Initiate
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #destinationDialogTpl>
      <h2 mat-dialog-title>Set Destination Registrar</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        <mat-form-field appearance="outline">
          <mat-label>Destination registrar name</mat-label>
          <input matInput [(ngModel)]="destinationForm.destinationRegistrarName" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Destination registrar identifier</mat-label>
          <input matInput [(ngModel)]="destinationForm.destinationRegistrarIdentifier" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Destination wallet address</mat-label>
          <input matInput [(ngModel)]="destinationForm.destinationWalletAddress" placeholder="0x…" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary"
                [disabled]="!destinationForm.destinationWalletAddress.trim()"
                (click)="submitDestination()">
          Save
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #transferDialogTpl>
      <h2 mat-dialog-title>Record On-Chain Transfer</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:400px">
        <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
          Enter the transaction hash after moving the holding on-chain via the existing
          token-admin tooling. This step requires step-up authentication and a second approver.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Transaction hash</mat-label>
          <input matInput [(ngModel)]="transferTxHash" placeholder="0x…" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="warn" [disabled]="!transferTxHash.trim()" (click)="submitTransfer()">
          Continue to step-up
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #cancelDialogTpl>
      <h2 mat-dialog-title>Cancel Portfolio Migration</h2>
      <mat-dialog-content style="min-width:400px">
        <mat-form-field appearance="outline" style="width:100%">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" [(ngModel)]="cancelReason"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Back</button>
        <button type="button" mat-raised-button color="warn" [disabled]="!cancelReason.trim()" (click)="submitCancel()">
          Cancel Migration
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    :host { display: block; }
    .pm-shell { padding: 1.5rem 0; }
    .pm-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 1.25rem; gap: 12px; }
    .pm-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .empty-state { display: flex; flex-direction: column; align-items: center; padding: 3rem 0; color: var(--rw-text-secondary); }
    .empty-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; margin-bottom: .75rem; opacity: .6; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }
    .mono { font-family: 'IBM Plex Mono', monospace; }

    .pm-table { display: flex; flex-direction: column; }
    .pm-row {
      display: grid;
      grid-template-columns: 130px 1fr 130px 120px 220px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border);
      font-size: .8125rem;
    }
    .pm-row.header {
      font-size: .6875rem;
      letter-spacing: .06em;
      text-transform: uppercase;
      color: var(--rw-text-muted);
    }

    .status-badge {
      display: inline-flex;
      align-items: center;
      padding: .125rem .5rem;
      border-radius: 3px;
      font-size: .6875rem;
      font-weight: 700;
      width: fit-content;
    }
    .status-badge.initiated   { background: rgba(148,163,184,.15); color: #94a3b8; }
    .status-badge.exported    { background: rgba(96,165,250,.15); color: #60a5fa; }
    .status-badge.handed_over { background: rgba(245,158,11,.15); color: #f59e0b; }
    .status-badge.completed   { background: rgba(74,222,128,.15); color: #4ade80; }
    .status-badge.cancelled   { background: rgba(248,113,113,.15); color: #f87171; }

    .row-actions { display: flex; justify-content: flex-end; align-items: center; gap: 4px; flex-wrap: wrap; }
  `],
})
export class PortfolioMigrationComponent implements OnInit {
  @Input() investorEntityId!: string;
  @ViewChild('initiateDialogTpl') initiateDialogTpl!: TemplateRef<unknown>;
  @ViewChild('destinationDialogTpl') destinationDialogTpl!: TemplateRef<unknown>;
  @ViewChild('transferDialogTpl') transferDialogTpl!: TemplateRef<unknown>;
  @ViewChild('cancelDialogTpl') cancelDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(PortfolioMigrationService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  migrations: PortfolioMigrationRequest[] = [];
  loading = false;
  exporting = new Set<string>();

  activeMigration: PortfolioMigrationRequest | null = null;
  transferTxHash = '';
  cancelReason = '';
  initiateForm = { holderId: '', reason: '' };
  destinationForm = { destinationRegistrarName: '', destinationRegistrarIdentifier: '', destinationWalletAddress: '' };

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.service.listForInvestor(this.investorEntityId).subscribe({
      next: (migrations) => {
        this.migrations = migrations;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  openInitiateDialog(): void {
    this.initiateForm = { holderId: '', reason: '' };
    this.dialog.open(this.initiateDialogTpl, { width: '480px' });
  }

  submitInitiate(): void {
    this.dialog.closeAll();
    this.service.initiate(this.initiateForm.holderId.trim(), this.initiateForm.reason.trim()).subscribe({
      next: () => {
        this.snackBar.open('Portfolio migration initiated.', 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to initiate migration.', 'Dismiss', { duration: 6000 }),
    });
  }

  openDestinationDialog(migration: PortfolioMigrationRequest): void {
    this.activeMigration = migration;
    this.destinationForm = { destinationRegistrarName: '', destinationRegistrarIdentifier: '', destinationWalletAddress: '' };
    this.dialog.open(this.destinationDialogTpl, { width: '480px' });
  }

  submitDestination(): void {
    const migration = this.activeMigration;
    if (!migration || !this.destinationForm.destinationWalletAddress.trim()) return;
    this.dialog.closeAll();

    this.service.setDestination(
      migration.id,
      this.destinationForm.destinationRegistrarName.trim() || undefined,
      this.destinationForm.destinationRegistrarIdentifier.trim() || undefined,
      this.destinationForm.destinationWalletAddress.trim(),
    ).subscribe({
      next: () => {
        this.snackBar.open('Destination set.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to set destination.', 'Dismiss', { duration: 6000 }),
    });
  }

  exportPackage(migration: PortfolioMigrationRequest): void {
    this.exporting.add(migration.id);
    this.cdr.markForCheck();
    this.service.export(migration.id).subscribe({
      next: (json) => {
        const url = URL.createObjectURL(json);
        const link = document.createElement('a');
        link.href = url;
        link.download = `portfolio-migration-${migration.id}.json`;
        link.click();
        URL.revokeObjectURL(url);
        this.exporting.delete(migration.id);
        this.snackBar.open('Data package exported.', 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => {
        this.exporting.delete(migration.id);
        this.cdr.markForCheck();
        this.snackBar.open(err?.error?.message ?? 'Failed to export data package.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  openTransferDialog(migration: PortfolioMigrationRequest): void {
    this.activeMigration = migration;
    this.transferTxHash = '';
    this.dialog.open(this.transferDialogTpl, { width: '460px' });
  }

  submitTransfer(): void {
    const migration = this.activeMigration;
    const txHash = this.transferTxHash.trim();
    if (!migration || !txHash) return;
    this.dialog.closeAll();

    const stepUpRef = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Record on-chain transfer for portfolio migration ${migration.id}`,
        action: 'PORTFOLIO_MIGRATION_ONCHAIN_TRANSFER',
      },
      width: '500px',
      disableClose: true,
    });

    stepUpRef.afterClosed().subscribe((result) => {
      if (!result) return;
      this.service.recordOnchainTransfer(migration.id, txHash, result.stepUpToken, result.dualControlToken!).subscribe({
        next: () => {
          this.snackBar.open('On-chain transfer recorded.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to record transfer.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  complete(migration: PortfolioMigrationRequest): void {
    this.service.complete(migration.id).subscribe({
      next: () => {
        this.snackBar.open('Portfolio migration completed.', 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to complete migration.', 'Dismiss', { duration: 6000 }),
    });
  }

  openCancelDialog(migration: PortfolioMigrationRequest): void {
    this.activeMigration = migration;
    this.cancelReason = '';
    this.dialog.open(this.cancelDialogTpl, { width: '460px' });
  }

  submitCancel(): void {
    const migration = this.activeMigration;
    const reason = this.cancelReason.trim();
    if (!migration || !reason) return;
    this.dialog.closeAll();

    this.service.cancel(migration.id, reason).subscribe({
      next: () => {
        this.snackBar.open('Portfolio migration cancelled.', 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to cancel migration.', 'Dismiss', { duration: 6000 }),
    });
  }
}
