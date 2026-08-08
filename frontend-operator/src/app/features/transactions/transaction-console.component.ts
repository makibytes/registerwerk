import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnDestroy,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { TransactionService, TxRecord } from '../../core/api/transaction.service';
import { AsyncSectionStatus } from '../../core/async/async-section';
import { Subscription } from 'rxjs';

/**
 * Cross-asset "what broke overnight" view — previously `transaction.service.ts` had exactly one
 * call site, filtered to a single deployment, so ops had no way to see every FAILED/TIMEOUT
 * transaction across the whole registry without direct database access.
 *
 * <p>No retry/gas-bump action here: {@code blockchain_transaction} doesn't capture a nonce or
 * unsigned calldata at submission time, so a safe automated resubmit through the shared signing
 * path isn't implementable without also touching that path — see
 * {@code BlockchainTransactionService#review}'s Javadoc. "Resolve" here only records what ops
 * actually did about a stuck/reverted transaction (usually out-of-band).
 */
@Component({
  selector: 'app-transaction-console',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatButtonToggleModule,
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
      title="Transactions"
      subtitle="Every on-chain transaction submitted by the registry, across all assets">
      <mat-button-toggle-group [value]="statusFilter" (change)="onStatusChange($event.value)">
        <mat-button-toggle value="FAILED">Failed</mat-button-toggle>
        <mat-button-toggle value="TIMEOUT">Timed Out</mat-button-toggle>
        <mat-button-toggle value="PENDING">Pending</mat-button-toggle>
        <mat-button-toggle value="SUCCESS">Success</mat-button-toggle>
      </mat-button-toggle-group>
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="transactions"
      [state]="state"
      (retry)="load()"
      filterPlaceholder="Filter by method, actor, chain…"
      emptyMessage="No transactions with this status."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-tx>
      @if ((tx.status === 'FAILED' || tx.status === 'TIMEOUT') && !tx.opsReviewedAt) {
        <button mat-stroked-button color="primary" (click)="openReviewDialog(tx)">
          <mat-icon>fact_check</mat-icon>
          Resolve
        </button>
      } @else if (tx.opsReviewedAt) {
        <span class="reviewed-note" [matTooltip]="tx.opsNote ?? ''">
          Reviewed {{ tx.opsReviewedAt | date:'short' }}
        </span>
      }
    </ng-template>

    <ng-template #reviewDialogTpl>
      <h2 mat-dialog-title>Resolve Transaction</h2>
      <mat-dialog-content class="review-dialog-content">
        @if (selected) {
          <div class="tx-summary">
            <div><span class="label">Method</span>{{ selected.methodName }}</div>
            <div><span class="label">Status</span>{{ selected.status }}</div>
            <div><span class="label">Tx hash</span><code>{{ selected.txHash ?? '—' }}</code></div>
            <div><span class="label">Error</span>{{ selected.errorMessage ?? '—' }}</div>
          </div>
        }
        <mat-form-field appearance="outline">
          <mat-label>Resolution notes</mat-label>
          <textarea matInput rows="4" [(ngModel)]="reviewNote"
            placeholder="What was done about this transaction (e.g. resubmitted manually with a bumped gas price via wallet tooling, confirmed the underlying action wasn't needed, etc.)."></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close [disabled]="reviewing">Cancel</button>
        <button mat-raised-button color="primary" [disabled]="!reviewNote.trim() || reviewing" (click)="submitReview()">
          <mat-icon>fact_check</mat-icon>
          {{ reviewing ? 'Resolving…' : 'Resolve' }}
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .reviewed-note {
      font-size: 12px;
      color: var(--rw-text-secondary);
      cursor: help;
    }
    .tx-summary {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px 16px;
      padding: 12px;
      border-radius: 8px;
      background: var(--rw-surface-soft, rgba(0,0,0,0.03));
      font-size: 13px;

      .label {
        display: block;
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.4px;
        color: var(--rw-text-secondary);
      }
    }
    .review-dialog-content {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding-top: 8px;
      width: min(440px, 80vw);
    }
    @media (max-width: 560px) {
      .tx-summary { grid-template-columns: 1fr; }
    }
  `],
})
export class TransactionConsoleComponent implements OnInit, OnDestroy {
  @ViewChild('reviewDialogTpl') reviewDialogTpl!: TemplateRef<unknown>;

  private readonly transactionService = inject(TransactionService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  transactions: TxRecord[] = [];
  state: AsyncSectionStatus = 'pending';
  statusFilter: TxRecord['status'] = 'FAILED';
  selected: TxRecord | null = null;
  reviewNote = '';
  reviewing = false;
  private loadSubscription?: Subscription;

  readonly columns: TableColumn[] = [
    { key: 'status', header: 'Status', cell: (t: TxRecord) => t.status, type: 'badge' },
    { key: 'methodName', header: 'Method', cell: (t: TxRecord) => t.methodName },
    { key: 'chain', header: 'Chain', cell: (t: TxRecord) => `${t.chain} / ${t.network}` },
    { key: 'txHash', header: 'Tx Hash', cell: (t: TxRecord) => t.txHash, type: 'mono' },
    { key: 'actorName', header: 'Actor', cell: (t: TxRecord) => t.actorName },
    { key: 'errorMessage', header: 'Error', cell: (t: TxRecord) => t.errorMessage },
    { key: 'createdAt', header: 'Submitted', cell: (t: TxRecord) => t.createdAt, type: 'date' },
  ];

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    this.loadSubscription?.unsubscribe();
  }

  onStatusChange(status: TxRecord['status']): void {
    this.statusFilter = status;
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.loadSubscription?.unsubscribe();
    this.loadSubscription = this.transactionService.listByStatus(this.statusFilter).subscribe({
      next: (page) => {
        this.transactions = page.content;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openReviewDialog(tx: TxRecord): void {
    this.selected = tx;
    this.reviewNote = '';
    this.reviewing = false;
    this.dialog.open(this.reviewDialogTpl, { width: '520px' });
  }

  submitReview(): void {
    if (!this.selected || !this.reviewNote.trim()) return;
    const id = this.selected.id;
    this.reviewing = true;
    this.cdr.markForCheck();

    this.transactionService.review(id, this.reviewNote.trim()).subscribe({
      next: () => {
        this.reviewing = false;
        this.dialog.closeAll();
        this.snackBar.open('Transaction resolved.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => {
        this.reviewing = false;
        this.snackBar.open(err?.error?.message ?? 'Failed to resolve transaction.', 'Dismiss', { duration: 6000 });
        this.cdr.markForCheck();
      },
    });
  }
}
