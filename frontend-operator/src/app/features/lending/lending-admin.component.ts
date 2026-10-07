import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, PageHeaderComponent, TableColumn } from '@registerwerk/ui';
import {
  LendingAdminService,
  LendingMarketAdminView,
  LendingReconciliationTask,
  RegisterMarketRequest,
  summarizeLegacyPause,
} from '../../core/api/lending-admin.service';
import { AsyncSectionStatus } from '../../core/async/async-section';
import { StepUpDialogComponent, StepUpDialogResult } from '../../shared/components/step-up/step-up-dialog.component';
import { withDualControl } from '../../shared/components/step-up/with-dual-control';
import { DualControlTokens } from '../../core/api/dual-control-headers';
import { MarketRegisterDialogComponent } from './market-register-dialog.component';
import { pauseReasonText } from './lending-pause-reason';


/**
 * Operator view of the lending read-model: registered markets (with binding verification and pause reason) and the
 * collateral-reconciliation task queue. Registration, reconcile and borrow-pause are step-up + second-approver
 * actions; re-verify is a single step-up. A shortfall pauses the market for customers until reconciled.
 */
@Component({
  selector: 'app-lending-admin',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSlideToggleModule,
    MatTabsModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  template: `
    <app-page-header
      title="Lending Markets"
      subtitle="Registered on-chain repo markets and the collateral-reconciliation queue">
    </app-page-header>

    <mat-tab-group animationDuration="150ms">
      <mat-tab [label]="'Markets (' + markets.length + ')'">
        <div class="tab-body">
          <rw-data-table
            [columns]="marketColumns"
            [rows]="markets"
            [state]="marketsState"
            (retry)="loadMarkets()"
            filterPlaceholder="Filter by asset, address…"
            emptyMessage="No lending markets are registered."
            [actionsTemplate]="marketActions">
            <span tableToolbar class="toolbar">
              <button type="button" mat-raised-button color="primary" (click)="openRegister()">
                <mat-icon>add</mat-icon>
                Register market
              </button>
              <button type="button" mat-stroked-button (click)="reverify()">
                <mat-icon>verified</mat-icon>
                Re-verify all
              </button>
              <button type="button" mat-stroked-button color="warn" (click)="pauseLegacyMarkets()"
                      matTooltip="Pauses borrowing on-chain for every unverified or legacy-risk-parameter market. Run Re-verify all first. Lifting a pause stays a separate action.">
                <mat-icon>pause_circle</mat-icon>
                Pause borrowing (legacy markets)
              </button>
            </span>
          </rw-data-table>
        </div>
      </mat-tab>

      <mat-tab [label]="'Reconciliation tasks (' + tasks.length + ')'">
        <div class="tab-body">
          <p class="hint">
            A task opens when a market holds less collateral than it recorded (for example after a forced transfer or
            burn on the collateral token). The market is paused for customers and quotes are blocked until the
            shortfall is gone. Reconcile attributes the outflow to one borrower on-chain; it needs step-up, a second
            approver and a legal basis.
          </p>
          <rw-data-table
            [columns]="taskColumns"
            [rows]="tasks"
            [state]="tasksState"
            (retry)="loadTasks()"
            filterPlaceholder="Filter tasks…"
            emptyMessage="No open reconciliation tasks."
            [actionsTemplate]="taskActions">
          </rw-data-table>
        </div>
      </mat-tab>
    </mat-tab-group>

    <ng-template #marketActions let-market>
      <div class="action-row">
        @if (market.pauseReason === 'BORROW_PAUSED_ONCHAIN') {
          <button type="button" mat-stroked-button (click)="openPause(market, false)">Resume borrowing</button>
        } @else {
          <button type="button" mat-stroked-button color="warn" (click)="openPause(market, true)">Pause borrowing</button>
        }
        @if (market.collateralShortfall) {
          <button type="button" mat-stroked-button color="primary" (click)="openReconcile(market.id)">Reconcile</button>
        }
      </div>
    </ng-template>

    <ng-template #taskActions let-task>
      <button type="button" mat-stroked-button color="primary" (click)="openReconcile(task.marketId)">Reconcile collateral</button>
    </ng-template>

    <ng-template #reconcileDialogTpl>
      <h2 mat-dialog-title>Reconcile collateral</h2>
      <mat-dialog-content class="dialog-body">
        <div class="note-box">
          Writes the borrower's pledged collateral down to the attributable amount on the market contract (durable
          on-chain call). Needs step-up and a second approver. Recorded in the audit trail.
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Borrower wallet</mat-label>
          <input matInput [(ngModel)]="reconcile.borrowerWallet" placeholder="0x…" class="mono">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Attributable collateral (token base units)</mat-label>
          <input matInput inputmode="numeric" [(ngModel)]="reconcile.attributableCollateral">
          <mat-hint>What the borrower still has pledged after the outflow; 0 if all of it left.</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Forced-transfer reference (32-byte tx hash)</mat-label>
          <input matInput [(ngModel)]="reconcile.forcedTransferRef" placeholder="0x…64 hex" class="mono">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Legal basis (required)</mat-label>
          <textarea matInput rows="2" [(ngModel)]="reconcile.legalBasis"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary" [disabled]="!reconcileValid()" (click)="submitReconcile()">Continue</button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #pauseDialogTpl>
      <h2 mat-dialog-title>{{ pausing ? 'Pause borrowing' : 'Resume borrowing' }}</h2>
      <mat-dialog-content class="dialog-body">
        <div class="note-box">
          Sets the market's on-chain borrow-pause flag (durable call). Existing loans can still be repaid, topped up
          and claimed. Needs step-up and a second approver.
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Reason (required)</mat-label>
          <textarea matInput rows="2" [(ngModel)]="pauseReason"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary" [disabled]="!pauseReason.trim()" (click)="submitPause()">Continue</button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .tab-body { padding: 16px 0; }
    .toolbar { display: inline-flex; gap: 8px; }
    .hint { margin: 0 0 12px; font-size: 12.5px; color: var(--rw-text-secondary); max-width: 900px; }
    .action-row { display: flex; gap: 6px; flex-wrap: wrap; }
    .dialog-body { display: flex; flex-direction: column; gap: 12px; padding-top: 8px; min-width: 480px; max-width: 100%; }
    .note-box { font-size: 12px; color: var(--rw-text-secondary); padding: 10px 12px; border-radius: 6px; border: 1px solid var(--rw-border); }
    .mono { font-family: 'IBM Plex Mono', monospace; }
    @media (max-width: 620px) { .dialog-body { min-width: 0; } }
  `],
})
export class LendingAdminComponent implements OnInit {
  @ViewChild('reconcileDialogTpl') reconcileDialogTpl!: TemplateRef<unknown>;
  @ViewChild('pauseDialogTpl') pauseDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(LendingAdminService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  markets: LendingMarketAdminView[] = [];
  marketsState: AsyncSectionStatus = 'pending';
  tasks: LendingReconciliationTask[] = [];
  tasksState: AsyncSectionStatus = 'pending';

  reconcileMarketId = '';
  reconcile = { borrowerWallet: '', attributableCollateral: '', forcedTransferRef: '', legalBasis: '' };
  pauseMarket: LendingMarketAdminView | null = null;
  pausing = true;
  pauseReason = '';

  readonly marketColumns: TableColumn[] = [
    { key: 'asset', header: 'Collateral asset', cell: (m: LendingMarketAdminView) => m.collateralAssetName ?? m.collateralIsin ?? '—' },
    { key: 'marketAddress', header: 'Market', cell: (m: LendingMarketAdminView) => m.marketAddress, type: 'mono' },
    { key: 'loanRailCode', header: 'Loan rail', cell: (m: LendingMarketAdminView) => m.loanRailCode ?? '—' },
    { key: 'status', header: 'Status', cell: (m: LendingMarketAdminView) => m.status, type: 'badge' },
    {
      key: 'pauseReason',
      header: 'Paused because',
      cell: (m: LendingMarketAdminView) => (m.pauseReason ? pauseReasonText(m.pauseReason) : '—'),
    },
    {
      key: 'binding',
      header: 'Binding',
      cell: (m: LendingMarketAdminView) => (m.bindingVerified ? 'Verified' : `UNVERIFIED: ${m.bindingFailure ?? 'see re-verify'}`),
    },
    { key: 'legacy', header: 'Risk parameters', cell: (m: LendingMarketAdminView) => (m.riskParametersLegacy ? 'Legacy (no new borrowing)' : 'Current') },
  ];

  readonly taskColumns: TableColumn[] = [
    { key: 'status', header: 'Status', cell: (t: LendingReconciliationTask) => t.status, type: 'badge' },
    { key: 'market', header: 'Market', cell: (t: LendingReconciliationTask) => this.marketLabel(t.marketId) },
    { key: 'source', header: 'Source', cell: (t: LendingReconciliationTask) => t.source },
    { key: 'shortfall', header: 'Shortfall (base units)', cell: (t: LendingReconciliationTask) => t.shortfall ?? '—', type: 'mono' },
    { key: 'method', header: 'Token admin method', cell: (t: LendingReconciliationTask) => t.tokenAdminMethod ?? '—' },
    { key: 'detectedAt', header: 'Detected', cell: (t: LendingReconciliationTask) => t.detectedAt, type: 'date' },
    { key: 'suggestedCalls', header: 'Suggested calls', cell: (t: LendingReconciliationTask) => t.suggestedCalls },
    { key: 'tx', header: 'Reconcile tx', cell: (t: LendingReconciliationTask) => t.reconcileTxHash ?? '—', type: 'mono' },
  ];

  ngOnInit(): void {
    this.loadMarkets();
    this.loadTasks();
  }

  marketLabel(marketId: string): string {
    const market = this.markets.find((m) => m.id === marketId);
    return market ? (market.collateralAssetName ?? market.marketAddress) : marketId;
  }

  loadMarkets(): void {
    this.marketsState = 'pending';
    this.cdr.markForCheck();
    this.service.listMarkets().subscribe({
      next: (markets) => { this.markets = markets; this.marketsState = 'ready'; this.cdr.markForCheck(); },
      error: () => { this.marketsState = 'error'; this.cdr.markForCheck(); },
    });
  }

  loadTasks(): void {
    this.tasksState = 'pending';
    this.cdr.markForCheck();
    this.service.listReconciliationTasks().subscribe({
      next: (tasks) => { this.tasks = tasks; this.tasksState = 'ready'; this.cdr.markForCheck(); },
      error: () => { this.tasksState = 'error'; this.cdr.markForCheck(); },
    });
  }

  openRegister(): void {
    this.dialog.open(MarketRegisterDialogComponent, { width: '560px', maxWidth: '95vw' })
      .afterClosed().subscribe((request: RegisterMarketRequest | undefined) => {
        if (!request) return;
        this.withDualControl('Lending market registration', 'POST /api/v1/lending/markets', request, (tokens) =>
          this.service.registerMarket(request, tokens).subscribe({
            next: () => {
              this.snackBar.open('Market registered and verified against the factory, asset and payment rail.', 'Dismiss', { duration: 6000 });
              this.loadMarkets();
            },
            error: (err) => this.snackBar.open(err?.error?.message ?? 'Registration was refused.', 'Dismiss', { duration: 9000 }),
          }));
      });
  }

  reverify(): void {
    this.dialog.open(StepUpDialogComponent, {
      data: { requireDualControl: false, reason: 'Lending market re-verification', action: 'Lending market re-verification' },
      width: '500px',
      disableClose: true,
    }).afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken) return;
      this.service.reverify(result.stepUpToken).subscribe({
        next: (results) => {
          const broken = results.filter((r) => !r.verified).length;
          this.snackBar.open(
            broken === 0 ? `All ${results.length} markets verified.` : `${broken} of ${results.length} markets failed verification and are hidden from customers.`,
            'Dismiss', { duration: 8000 });
          this.loadMarkets();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Re-verification failed.', 'Dismiss', { duration: 7000 }),
      });
    });
  }

  openReconcile(marketId: string): void {
    this.reconcileMarketId = marketId;
    this.reconcile = { borrowerWallet: '', attributableCollateral: '', forcedTransferRef: '', legalBasis: '' };
    this.dialog.open(this.reconcileDialogTpl, { width: '560px', maxWidth: '95vw' });
  }

  reconcileValid(): boolean {
    return /^0x[0-9a-fA-F]{40}$/.test(this.reconcile.borrowerWallet.trim())
      && /^\d+$/.test(this.reconcile.attributableCollateral.trim())
      && /^0x[0-9a-fA-F]{64}$/.test(this.reconcile.forcedTransferRef.trim())
      && !!this.reconcile.legalBasis.trim();
  }

  submitReconcile(): void {
    if (!this.reconcileValid()) return;
    const marketId = this.reconcileMarketId;
    const body = {
      borrowerWallet: this.reconcile.borrowerWallet.trim(),
      attributableCollateral: this.reconcile.attributableCollateral.trim(),
      forcedTransferRef: this.reconcile.forcedTransferRef.trim(),
      legalBasis: this.reconcile.legalBasis.trim(),
    };
    this.dialog.closeAll();
    this.withDualControl('Lending collateral reconciliation', `POST /api/v1/lending/markets/${marketId}/reconcile-collateral`, body, (tokens) =>
      this.service.reconcileCollateral(marketId, body, tokens).subscribe({
        next: () => {
          this.snackBar.open('Reconciliation submitted. The task closes once the market balance matches its record.', 'Dismiss', { duration: 7000 });
          this.loadTasks();
          this.loadMarkets();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Reconciliation failed.', 'Dismiss', { duration: 8000 }),
      }));
  }

  openPause(market: LendingMarketAdminView, pause: boolean): void {
    this.pauseMarket = market;
    this.pausing = pause;
    this.pauseReason = '';
    this.dialog.open(this.pauseDialogTpl, { width: '520px', maxWidth: '95vw' });
  }

  submitPause(): void {
    const market = this.pauseMarket;
    const reason = this.pauseReason.trim();
    if (!market || !reason) return;
    const paused = this.pausing;
    this.dialog.closeAll();
    this.withDualControl('Lending borrow pause', `POST /api/v1/lending/markets/${market.id}/borrow-paused`, { paused, reason }, (tokens) =>
      this.service.setBorrowPaused(market.id, paused, reason, tokens).subscribe({
        next: () => {
          this.snackBar.open(paused ? 'Borrow pause submitted.' : 'Borrow resume submitted.', 'Dismiss', { duration: 6000 });
          this.loadMarkets();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'The call failed.', 'Dismiss', { duration: 8000 }),
      }));
  }

  /**
   * Pauses borrowing on-chain for every unverified / legacy market (H11). Only pauses: the pause is lifted
   * per market with the existing resume action. Step-up + second approver bound to the exact request.
   */
  pauseLegacyMarkets(): void {
    this.withDualControl('Lending legacy market borrow pause', 'POST /api/v1/lending/markets/legacy-borrow-pause', {}, (tokens) =>
      this.service.legacyBorrowPause(tokens).subscribe({
        next: (results) => {
          this.snackBar.open(summarizeLegacyPause(results), 'Dismiss', { duration: 10000 });
          this.loadMarkets();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'The legacy-market pause failed.', 'Dismiss', { duration: 8000 }),
      }));
  }

  /**
   * The step-up scope must equal the endpoint's `@RequiresStepUp(reason)` exactly, and the approval is bound to
   * the exact method, path and body that is sent next, so the caller passes the very objects it then sends.
   */
  private withDualControl(action: string, target: string, targetBody: unknown, run: (tokens: DualControlTokens) => void): void {
    withDualControl(this.dialog, { action, reason: action, target, targetBody }, run);
  }
}
