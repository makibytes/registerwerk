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
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { IndexerAdminService } from '../../core/api/indexer-admin.service';
import { ChainService } from '../../core/api/chain.service';
import { IndexerStateResponse } from '../../core/models';
import { AsyncSectionStatus } from '../../core/async/async-section';
import { AuthService } from '../../core/auth/auth.service';

/**
 * Operator visibility and recovery surface for `indexer_state` — previously a stuck (ERROR)
 * indexer had no application-level path back to ACTIVE; the only fix was a manual SQL update
 * against `indexer_state` directly. See {@code IndexerAdminController}/{@code IndexerAdminService}
 * (backend) for the recovery semantics this screen exposes.
 */
@Component({
  selector: 'app-indexers-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatIconModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [`
    .reset-warning {
      font-size: 12px;
      color: var(--rw-text-secondary);
      padding: 10px 12px;
      background: rgba(239,68,68,0.07);
      border-radius: 6px;
      border: 1px solid rgba(239,68,68,0.18);
      margin: 0 0 4px;
    }
    .error-cell {
      font-size: 12px;
      color: var(--rw-text-danger, #dc2626);
      max-width: 320px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  `],
  template: `
    <app-page-header
      title="Indexers"
      subtitle="Off-chain indexer sync state, one row per chain × indexer type. Reset clears a stuck ERROR state.">
      <button type="button" mat-stroked-button (click)="load()">
        <mat-icon>refresh</mat-icon>
        Refresh
      </button>
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="indexers"
      [state]="state"
      (retry)="load()"
      filterPlaceholder="Filter by chain, indexer type, status…"
      emptyMessage="No indexer state rows found."
      [actionsTemplate]="canManage ? rowActions : undefined">
    </rw-data-table>

    <ng-template #rowActions let-row>
      @if (row.status === 'ERROR') {
        <button type="button" mat-stroked-button color="warn" (click)="openResetDialog(row)" matTooltip="Clear the error state and resume indexing">
          <mat-icon>restart_alt</mat-icon>
          Reset
        </button>
      } @else {
        <button type="button" mat-icon-button (click)="openResetDialog(row)" matTooltip="Reset anyway (not in ERROR)">
          <mat-icon>restart_alt</mat-icon>
        </button>
      }
    </ng-template>

    <!-- Reset confirmation dialog -->
    <ng-template #resetDialog>
      <h2 mat-dialog-title>Reset indexer</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px">
        @if (selected; as row) {
          <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
            <strong>{{ chainName(row.chainConfigId) }}</strong> / {{ row.indexerType }}
            — currently <strong>{{ row.status }}</strong>
            @if (row.consecutiveErrors > 0) { ({{ row.consecutiveErrors }} consecutive errors) }
          </p>
          @if (row.lastError) {
            <p class="error-cell" style="white-space:normal;max-width:none" [matTooltip]="row.lastError">
              Last error: {{ row.lastError }}
            </p>
          }
        }
        <mat-checkbox [(ngModel)]="fullResync">Full resync</mat-checkbox>
        @if (fullResync) {
          <p class="reset-warning">
            <strong>Discards the existing sync cursor</strong> and forces a full re-index from
            genesis. Much slower than a plain reset — only do this if the cursor itself
            (not just a transient error streak) is suspect.
          </p>
        } @else {
          <p style="margin:0;font-size:12px;color:var(--rw-text-secondary)">
            Clears the error state so the indexer resumes from its last synced position on the
            next scheduled tick.
          </p>
        }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close [disabled]="resetting">Cancel</button>
        <button type="button" mat-raised-button color="warn" [disabled]="resetting" (click)="confirmReset()">
          <mat-icon>restart_alt</mat-icon>
          {{ resetting ? 'Resetting…' : 'Reset' }}
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class IndexersListComponent implements OnInit {
  @ViewChild('resetDialog') resetDialogTpl!: TemplateRef<unknown>;

  private readonly indexerAdminService = inject(IndexerAdminService);
  private readonly chainService = inject(ChainService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auth = inject(AuthService);

  readonly canManage = this.auth.hasRole('REGISTRY_ADMIN');

  indexers: IndexerStateResponse[] = [];
  state: AsyncSectionStatus = 'pending';
  selected: IndexerStateResponse | null = null;
  fullResync = false;
  resetting = false;

  private chainNamesById = new Map<string, string>();

  readonly columns: TableColumn[] = [
    { key: 'chain', header: 'Chain', cell: (r: IndexerStateResponse) => this.chainName(r.chainConfigId) },
    { key: 'indexerType', header: 'Indexer Type', cell: (r: IndexerStateResponse) => r.indexerType },
    { key: 'status', header: 'Status', cell: (r: IndexerStateResponse) => r.status, type: 'badge' },
    { key: 'lastSyncedBlock', header: 'Last Synced Block', cell: (r: IndexerStateResponse) => r.lastSyncedBlock != null ? `${r.lastSyncedBlock}` : (r.lastSyncedSignature ?? '—'), type: 'mono' },
    { key: 'lastFinalBlock', header: 'Last Final Block', cell: (r: IndexerStateResponse) => r.lastFinalBlock != null ? `${r.lastFinalBlock}` : '—', type: 'mono' },
    { key: 'consecutiveErrors', header: 'Consecutive Errors', cell: (r: IndexerStateResponse) => `${r.consecutiveErrors}`, type: 'number' },
    { key: 'lastError', header: 'Last Error', cell: (r: IndexerStateResponse) => r.lastError },
    { key: 'lastSyncedAt', header: 'Last Synced', cell: (r: IndexerStateResponse) => r.lastSyncedAt, type: 'date' },
  ];

  ngOnInit(): void {
    // Chain names are needed to render the "Chain" column, so load them before the indexer
    // rows — a chainConfigId with no resolved name yet would render as a bare UUID.
    this.chainService.getHealth().subscribe({
      next: (chains) => {
        this.chainNamesById = new Map(chains.map((c) => [c.id, c.displayName]));
        this.cdr.markForCheck();
        this.load();
      },
      error: () => this.load(),
    });
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.indexerAdminService.list().subscribe({
      next: (indexers) => {
        this.indexers = indexers;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  chainName(chainConfigId: string): string {
    return this.chainNamesById.get(chainConfigId) ?? chainConfigId;
  }

  openResetDialog(row: IndexerStateResponse): void {
    this.selected = row;
    this.fullResync = false;
    this.resetting = false;
    this.dialog.open(this.resetDialogTpl, { width: '500px' });
  }

  confirmReset(): void {
    if (!this.selected) return;
    const id = this.selected.id;
    this.resetting = true;
    this.cdr.markForCheck();

    this.indexerAdminService.reset(id, this.fullResync).subscribe({
      next: () => {
        this.resetting = false;
        this.dialog.closeAll();
        this.snackBar.open('Indexer reset. It will resume on its next scheduled tick.', 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => {
        this.resetting = false;
        this.cdr.markForCheck();
        this.snackBar.open(err?.error?.message ?? 'Failed to reset indexer.', 'Dismiss', { duration: 6000 });
      },
    });
  }
}
