import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
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
import { ChainDriftService } from '../../../core/api/chain-drift.service';
import { ChainDriftEvent } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * Case management for {@code chain_drift_event} — a registry-vs-chain balance divergence
 * (eWpG §16 / KryptoFAV §6). Previously this control terminated in a log line: the detection
 * job wrote rows, and nothing read or closed them without direct database access.
 */
@Component({
  selector: 'app-chain-drift-queue',
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
      title="Chain Drift"
      subtitle="eWpG §16 / KryptoFAV §6 — registry balances that diverge from the indexed on-chain balance">
      <mat-button-toggle-group [value]="statusFilter" (change)="onStatusChange($event.value)">
        <mat-button-toggle value="OPEN">Open</mat-button-toggle>
        <mat-button-toggle value="RESOLVED">Resolved</mat-button-toggle>
      </mat-button-toggle-group>
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="events"
      [state]="state"
      (retry)="load()"
      filterPlaceholder="Filter by wallet, asset…"
      emptyMessage="No drift events. The registry and indexed chain balances agree."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-event>
      @if (event.status === 'OPEN' && canResolve) {
        <button type="button" mat-stroked-button color="primary" (click)="openResolveDialog(event)">
          <mat-icon>task_alt</mat-icon>
          Resolve
        </button>
      } @else {
        <span class="resolved-note" [matTooltip]="event.resolutionNotes ?? ''">
          Resolved {{ event.resolvedAt | date:'short' }}
        </span>
      }
    </ng-template>

    <ng-template #resolveDialogTpl>
      <h2 mat-dialog-title>Resolve Drift Case</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px">
        @if (selected) {
          <div class="drift-summary">
            <div><span class="label">Wallet</span><code>{{ selected.walletAddress }}</code></div>
            <div><span class="label">Registry balance</span>{{ selected.dbBalance }}</div>
            <div><span class="label">On-chain balance</span>{{ selected.onchainBalance }}</div>
            <div><span class="label">Delta</span>{{ selected.delta }}</div>
            <div><span class="label">Severity</span>{{ selected.severity }}</div>
          </div>
        }
        <mat-form-field appearance="outline">
          <mat-label>Resolution notes</mat-label>
          <textarea matInput rows="4" [(ngModel)]="resolutionNotes"
            placeholder="What was corrected — the registry, the chain, or an explanation for the divergence — and why."></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary" [disabled]="!resolutionNotes.trim()" (click)="submitResolve()">
          <mat-icon>task_alt</mat-icon>
          Resolve
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .resolved-note {
      font-size: 12px;
      color: var(--rw-text-secondary);
      cursor: help;
    }
    .drift-summary {
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
  `],
})
export class ChainDriftQueueComponent implements OnInit {
  @ViewChild('resolveDialogTpl') resolveDialogTpl!: TemplateRef<unknown>;

  private readonly chainDriftService = inject(ChainDriftService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auth = inject(AuthService);

  readonly canResolve = this.auth.hasRole('REGISTRY_ADMIN') || this.auth.hasRole('COMPLIANCE_OFFICER');

  events: ChainDriftEvent[] = [];
  state: AsyncSectionStatus = 'pending';
  statusFilter: 'OPEN' | 'RESOLVED' = 'OPEN';
  selected: ChainDriftEvent | null = null;
  resolutionNotes = '';

  readonly columns: TableColumn[] = [
    { key: 'severity', header: 'Severity', cell: (e: ChainDriftEvent) => e.severity, type: 'badge' },
    { key: 'walletAddress', header: 'Wallet', cell: (e: ChainDriftEvent) => e.walletAddress, type: 'mono' },
    { key: 'assetId', header: 'Asset', cell: (e: ChainDriftEvent) => e.assetId, type: 'mono' },
    { key: 'dbBalance', header: 'Registry Balance', cell: (e: ChainDriftEvent) => String(e.dbBalance), type: 'number' },
    { key: 'onchainBalance', header: 'On-chain Balance', cell: (e: ChainDriftEvent) => String(e.onchainBalance), type: 'number' },
    { key: 'delta', header: 'Delta', cell: (e: ChainDriftEvent) => String(e.delta), type: 'number' },
    { key: 'firstDetectedAt', header: 'First seen', cell: (e: ChainDriftEvent) => e.firstDetectedAt, type: 'date' },
    { key: 'detectedAt', header: 'Last confirmed', cell: (e: ChainDriftEvent) => e.detectedAt, type: 'date' },
  ];

  ngOnInit(): void {
    this.load();
  }

  onStatusChange(status: 'OPEN' | 'RESOLVED'): void {
    this.statusFilter = status;
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.chainDriftService.list(this.statusFilter).subscribe({
      next: (page) => {
        this.events = page.content;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openResolveDialog(event: ChainDriftEvent): void {
    this.selected = event;
    this.resolutionNotes = '';
    this.dialog.open(this.resolveDialogTpl, { width: '520px' });
  }

  submitResolve(): void {
    if (!this.selected || !this.resolutionNotes.trim()) return;
    const id = this.selected.id;
    this.dialog.closeAll();

    this.chainDriftService.resolve(id, this.resolutionNotes.trim()).subscribe({
      next: () => {
        this.snackBar.open('Drift case resolved.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to resolve drift case.', 'Dismiss', { duration: 6000 });
      },
    });
  }
}
