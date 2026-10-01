import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { DataTableComponent, PageHeaderComponent, TableColumn } from '@registerwerk/ui';
import {
  TimeoutBacklog,
  TradingAdminService,
  UnresolvedResolution,
  UnresolvedTrade,
} from '../../core/api/trading-admin.service';
import { AsyncSectionStatus } from '../../core/async/async-section';
import { StepUpDialogComponent, StepUpDialogResult } from '../../shared/components/step-up/step-up-dialog.component';

interface ResolutionCopy {
  title: string;
  stepUpReason: string;
  button: string;
  color: 'primary' | 'warn';
  explanation: string;
}

const RESOLUTIONS: Record<UnresolvedResolution, ResolutionCopy> = {
  'force-settle': {
    title: 'Force-settle trade',
    stepUpReason: 'TRADE_UNRESOLVED_FORCE_SETTLE',
    button: 'Force-settle',
    color: 'primary',
    explanation:
      'You decide the buyer\'s payment DID arrive. Every settlement gate re-runs; the register moves to the buyer. ' +
      'Use only with evidence of receipt.',
  },
  'record-return-of-funds': {
    title: 'Record return of funds',
    stepUpReason: 'TRADE_UNRESOLVED_RETURN_OF_FUNDS',
    button: 'Record return',
    color: 'warn',
    explanation:
      'The buyer\'s money was returned (evidenced). The trade is closed as failed and the units are released back ' +
      'to the listing.',
  },
  release: {
    title: 'Release reservation',
    stepUpReason: 'TRADE_UNRESOLVED_RELEASE',
    button: 'Release',
    color: 'warn',
    explanation:
      'The seller proved that no payment was received. The trade is closed as failed and the units are released ' +
      'back to the listing.',
  },
};

/**
 * Operator queue for secondary-market trades in PAYMENT_UNRESOLVED: a declared payment that could not be settled
 * automatically (seller dispute, seller never confirmed, or a gate failed after cash may have moved). The units
 * stay reserved and the trade is never auto-failed; every resolution needs step-up, a second approver and a
 * legal basis. Also hosts the order-history CSV export (interim market-abuse record, not a validated RTS 22 report).
 */
@Component({
  selector: 'app-unresolved-trades',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  template: `
    <app-page-header
      title="Trading: Unresolved Payments"
      subtitle="Declared payments that could not be settled automatically. Units stay reserved until an operator resolves the trade.">
    </app-page-header>

    @if (backlog) {
      <div class="backlog" role="status">
        <div><span class="v">{{ backlog.unresolvedTotal }}</span><span class="l">Unresolved</span></div>
        <div [class.warn]="backlog.unresolvedAged > 0"><span class="v">{{ backlog.unresolvedAged }}</span><span class="l">Aged past the alert threshold</span></div>
        <div><span class="v">{{ backlog.overduePending }}</span><span class="l">Overdue, awaiting buyer payment</span></div>
        <div [class.warn]="backlog.overdueAwaiting > 0"><span class="v">{{ backlog.overdueAwaiting }}</span><span class="l">Overdue, awaiting seller (next run moves these here)</span></div>
        <div [class.warn]="backlog.failedAfterDeclaredPayment.length > 0"><span class="v">{{ backlog.failedAfterDeclaredPayment.length }}</span><span class="l">Historic failures after a declared payment (review only)</span></div>
      </div>
    }

    <rw-data-table
      [columns]="columns"
      [rows]="rows"
      [state]="state"
      (retry)="load()"
      filterPlaceholder="Filter by asset, trade id, reason…"
      emptyMessage="No unresolved payments."
      [actionsTemplate]="actions">
    </rw-data-table>

    <section class="export">
      <h3>Order history export</h3>
      <p class="hint">
        CSV of listings (including cancelled) and executions with actor and venue classification, for a
        market-abuse tool. Interim record only, not a validated RTS 22 report. Window defaults to the last 30 days
        and spans at most 366 days.
      </p>
      <div class="export-row">
        <mat-form-field appearance="outline">
          <mat-label>From</mat-label>
          <input matInput type="date" [(ngModel)]="exportFrom">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>To</mat-label>
          <input matInput type="date" [(ngModel)]="exportTo">
        </mat-form-field>
        <button type="button" mat-stroked-button [disabled]="exporting" (click)="exportCsv()">
          <mat-icon>download</mat-icon>
          Download CSV
        </button>
      </div>
    </section>

    <ng-template #actions let-row>
      <div class="action-row">
        <button type="button" mat-stroked-button color="primary" (click)="openResolve(row.source, 'force-settle')">Force-settle</button>
        <button type="button" mat-stroked-button color="warn" (click)="openResolve(row.source, 'record-return-of-funds')">Record return</button>
        <button type="button" mat-stroked-button color="warn" (click)="openResolve(row.source, 'release')">Release</button>
        <button type="button" mat-stroked-button (click)="openNotes(row.source)">
          <mat-icon>forum</mat-icon>
          Notes ({{ row.source.notes.length }})
        </button>
      </div>
    </ng-template>

    <ng-template #resolveDialogTpl>
      <h2 mat-dialog-title>{{ copy.title }}</h2>
      <mat-dialog-content class="dialog-body">
        @if (selected) {
          <div class="summary">
            <div><span class="label">Asset</span>{{ selected.trade.assetName }} ({{ selected.trade.assetNumber }})</div>
            <div><span class="label">Quantity and total</span>
              {{ selected.trade.executedQuantity | number:'1.0-4' }} units,
              {{ selected.trade.totalPrice | number:'1.2-4' }}
              {{ selected.trade.currency ?? '(currency not recorded)' }}</div>
            <div><span class="label">Buyer / Seller entity</span><code>{{ selected.buyerEntityId }}</code> / <code>{{ selected.sellerEntityId }}</code></div>
            <div><span class="label">Declared payment reference</span>{{ selected.trade.paymentReference ?? '—' }}</div>
            <div><span class="label">Why unresolved</span>{{ selected.trade.unresolvedReason ?? '—' }}</div>
            @if (selected.trade.disputeReason) {
              <div><span class="label">Seller's dispute</span>{{ selected.trade.disputeReason }}</div>
            }
            @if (selected.trade.instantSettlement) {
              <div class="sim">Instant demo settlement: no cash leg.</div>
            }
          </div>
        }
        <div class="note-box">{{ copy.explanation }} This needs step-up and a second approver and is recorded in the audit trail.</div>
        <mat-form-field appearance="outline">
          <mat-label>Legal basis (required)</mat-label>
          <textarea matInput rows="2" maxlength="500" [(ngModel)]="legalBasis"
                    placeholder="Evidence and instruction that justifies this decision"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Note (optional)</mat-label>
          <textarea matInput rows="2" maxlength="2000" [(ngModel)]="resolveNote"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button [color]="copy.color" [disabled]="!legalBasis.trim()" (click)="submitResolve()">
          {{ copy.button }}
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #notesDialogTpl>
      <h2 mat-dialog-title>Evidence notes</h2>
      <mat-dialog-content class="dialog-body">
        @if (selected && selected.notes.length === 0) {
          <p class="hint">No notes yet.</p>
        }
        @for (note of selected?.notes ?? []; track note.id) {
          <div class="note">
            <div class="meta">{{ note.actorRole === 'REGISTRY_ADMIN' ? 'Operator' : 'Party ' + (note.actorEntityId ?? '') }} · {{ note.createdAt | date:'medium' }}</div>
            <div>{{ note.text }}</div>
          </div>
        }
        <mat-form-field appearance="outline">
          <mat-label>Add an operator note</mat-label>
          <textarea matInput rows="3" maxlength="2000" [(ngModel)]="noteText"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Close</button>
        <button type="button" mat-raised-button color="primary" [disabled]="!noteText.trim()" (click)="submitNote()">Add note</button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .backlog { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 12px; margin-bottom: 16px; }
    .backlog > div { border: 1px solid var(--rw-border); border-radius: 8px; padding: 10px 12px; background: var(--rw-surface); }
    .backlog .warn { border-color: var(--rw-accent); }
    .backlog .v { display: block; font-size: 22px; font-weight: 700; color: var(--rw-text-primary); }
    .backlog .l { font-size: 11px; color: var(--rw-text-secondary); }
    .action-row { display: flex; gap: 6px; flex-wrap: wrap; }
    .export { margin-top: 28px; max-width: 760px; }
    .export h3 { margin: 0 0 4px; font-size: 15px; color: var(--rw-text-primary); }
    .export-row { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .hint { margin: 0 0 10px; font-size: 12.5px; color: var(--rw-text-secondary); }
    .dialog-body { display: flex; flex-direction: column; gap: 12px; padding-top: 8px; min-width: 460px; max-width: 100%; }
    .summary { display: grid; gap: 6px; padding: 12px; border-radius: 8px; background: var(--rw-surface-soft); font-size: 13px; }
    .summary .label { display: block; font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px; color: var(--rw-text-secondary); }
    .summary .sim { color: var(--rw-text-danger); font-weight: 600; }
    .note-box { font-size: 12px; color: var(--rw-text-secondary); padding: 10px 12px; border-radius: 6px; border: 1px solid var(--rw-border); }
    .note { border: 1px solid var(--rw-border); border-radius: 8px; padding: 8px 10px; font-size: 13px; }
    .note .meta { font-size: 11px; color: var(--rw-text-secondary); }
    @media (max-width: 620px) { .dialog-body { min-width: 0; } }
  `],
})
export class UnresolvedTradesComponent implements OnInit {
  @ViewChild('resolveDialogTpl') resolveDialogTpl!: TemplateRef<unknown>;
  @ViewChild('notesDialogTpl') notesDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(TradingAdminService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  state: AsyncSectionStatus = 'pending';
  items: UnresolvedTrade[] = [];
  rows: (Record<string, unknown> & { source: UnresolvedTrade })[] = [];
  backlog: TimeoutBacklog | null = null;

  selected: UnresolvedTrade | null = null;
  resolution: UnresolvedResolution = 'force-settle';
  legalBasis = '';
  resolveNote = '';
  noteText = '';
  exportFrom = '';
  exportTo = '';
  exporting = false;

  readonly columns: TableColumn[] = [
    { key: 'age', header: 'Age', cell: (r) => `${r['source'].ageHours} h${r['source'].aged ? ' (aged)' : ''}` },
    { key: 'asset', header: 'Asset', cell: (r) => `${r['source'].trade.assetName} (${r['source'].trade.assetNumber})` },
    { key: 'total', header: 'Total', cell: (r) => `${r['source'].trade.totalPrice} ${r['source'].trade.currency ?? '(currency not recorded)'}` },
    { key: 'reference', header: 'Payment reference', cell: (r) => r['source'].trade.paymentReference ?? '—', type: 'mono' },
    { key: 'reason', header: 'Why unresolved', cell: (r) => r['source'].trade.unresolvedReason ?? r['source'].trade.disputeReason ?? '—' },
    { key: 'notes', header: 'Notes', cell: (r) => String(r['source'].notes.length), type: 'number' },
    { key: 'id', header: 'Trade', cell: (r) => r['source'].trade.id, type: 'mono' },
  ];

  get copy(): ResolutionCopy {
    return RESOLUTIONS[this.resolution];
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();
    this.service.listUnresolved().subscribe({
      next: (items) => {
        this.items = items;
        this.rows = items.map((source) => ({ source }));
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
    this.service.backlog().subscribe({
      next: (backlog) => { this.backlog = backlog; this.cdr.markForCheck(); },
      error: () => { this.backlog = null; this.cdr.markForCheck(); },
    });
  }

  openResolve(item: UnresolvedTrade, resolution: UnresolvedResolution): void {
    this.selected = item;
    this.resolution = resolution;
    this.legalBasis = '';
    this.resolveNote = '';
    this.dialog.open(this.resolveDialogTpl, { width: '560px', maxWidth: '95vw' });
  }

  submitResolve(): void {
    const item = this.selected;
    const legalBasis = this.legalBasis.trim();
    if (!item || !legalBasis) return;
    const resolution = this.resolution;
    const note = this.resolveNote.trim() || null;
    this.dialog.closeAll();
    this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `${RESOLUTIONS[resolution].title} (trade ${item.trade.id})`,
        action: RESOLUTIONS[resolution].stepUpReason,
      },
      width: '500px',
      disableClose: true,
    }).afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken || !result.dualControlToken) return;
      this.service.resolve(item.trade.id, resolution, { legalBasis, note }, {
        stepUpToken: result.stepUpToken, dualControlToken: result.dualControlToken,
      }).subscribe({
        next: () => {
          this.snackBar.open('Trade resolved.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Resolution failed. Nothing was changed.', 'Dismiss', { duration: 8000 }),
      });
    });
  }

  openNotes(item: UnresolvedTrade): void {
    this.selected = item;
    this.noteText = '';
    this.dialog.open(this.notesDialogTpl, { width: '540px', maxWidth: '95vw' });
  }

  submitNote(): void {
    const item = this.selected;
    const text = this.noteText.trim();
    if (!item || !text) return;
    this.service.addNote(item.trade.id, text).subscribe({
      next: (note) => {
        item.notes = [...item.notes, note];
        this.noteText = '';
        this.rows = this.items.map((source) => ({ source }));
        this.cdr.markForCheck();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to add the note.', 'Dismiss', { duration: 6000 }),
    });
  }

  exportCsv(): void {
    if (this.exporting) return;
    this.exporting = true;
    this.service.orderHistoryCsv(this.exportFrom || undefined, this.exportTo || undefined).subscribe({
      next: (blob) => {
        this.exporting = false;
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = 'trading-order-history.csv';
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        setTimeout(() => URL.revokeObjectURL(url), 0);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.exporting = false;
        this.snackBar.open(err?.status === 400 ? 'Invalid window: from must precede to, at most 366 days.' : 'The export failed.', 'Dismiss', { duration: 6000 });
        this.cdr.markForCheck();
      },
    });
  }
}
