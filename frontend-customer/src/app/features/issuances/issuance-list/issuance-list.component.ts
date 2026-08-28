import {
  ChangeDetectorRef,
  Component,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatSortModule, MatSort } from '@angular/material/sort';
import { MatPaginatorModule, MatPaginator } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { IssuanceService } from '../../../core/api/issuance.service';
import { Asset, AssetStatus, TokenStandard } from '../../../core/models';
import { StatusBadgeComponent, ChainNamePipe, DonutChartComponent, DonutSlice, BarChartComponent, BarItem } from '@registerwerk/ui';



import { TOKEN_STANDARD_COLORS } from '../../../shared/token-standard-colors';

interface IssuanceFilters {
  search: string;
  status: AssetStatus | null;
  tokenStandard: TokenStandard | null;
  fromDate: string;
  toDate: string;
}

@Component({
  selector: 'app-issuance-list',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    FormsModule,
    MatTableModule,
    MatSortModule,
    MatPaginatorModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatChipsModule,
    MatTooltipModule,
    StatusBadgeComponent,
    ChainNamePipe,
    DonutChartComponent,
    BarChartComponent,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>My Issuances</h1>
        <a mat-raised-button color="primary" routerLink="/issuances/new">
          <mat-icon>add</mat-icon>
          New Issuance
        </a>
      </div>

      @if (loading) {
        <div class="loading-overlay"><mat-spinner diameter="48"></mat-spinner></div>
      } @else if (loadError) {
        <div class="content-card load-error" role="alert">
          <mat-icon>error_outline</mat-icon>
          <p>Issuances could not be loaded.</p>
          <button mat-stroked-button type="button" (click)="load()">Retry</button>
        </div>
      } @else {

        <!-- ── KPI bar ────────────────────────────────────────────────────── -->
        <div class="kpi-bar">
          <div class="kpi-item">
            <span class="kpi-value">{{ allIssuances.length }}</span>
            <span class="kpi-label">Total</span>
          </div>
          <div class="kpi-divider"></div>
          <div class="kpi-item">
            <span class="kpi-value issued">{{ countByStatus('ISSUED') }}</span>
            <span class="kpi-label">Issued</span>
          </div>
          <div class="kpi-divider"></div>
          <div class="kpi-item">
            <span class="kpi-value pending">{{ countByStatus('PENDING_APPROVAL') }}</span>
            <span class="kpi-label">Pending Approval</span>
          </div>
          <div class="kpi-divider"></div>
          <div class="kpi-item">
            <span class="kpi-value draft">{{ countByStatus('DRAFT') }}</span>
            <span class="kpi-label">Draft</span>
          </div>
          <div class="kpi-divider"></div>
          <div class="kpi-item">
            <span class="kpi-value">{{ uniqueStandards }}</span>
            <span class="kpi-label">Token Standards</span>
          </div>
        </div>

        <!-- ── Analytics Charts ───────────────────────────────────────────── -->
        @if (allIssuances.length > 0) {
          <div class="charts-row">
            <mat-card class="chart-card">
              <mat-card-header>
                <mat-card-title>Issuances by Token Standard</mat-card-title>
                <mat-card-subtitle>Asset count per standard</mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <app-donut-chart
                  [slices]="standardSlices"
                  centerLabel="Assets"
                  [centerValue]="allIssuances.length.toString()">
                </app-donut-chart>
              </mat-card-content>
            </mat-card>

            <mat-card class="chart-card">
              <mat-card-header>
                <mat-card-title>Issuances by Status</mat-card-title>
                <mat-card-subtitle>Asset count per lifecycle status</mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <app-bar-chart
                  [items]="statusBars"
                  valueFormat="number">
                </app-bar-chart>
              </mat-card-content>
            </mat-card>
          </div>
        }

        <!-- ── Filters ────────────────────────────────────────────────────── -->
        <mat-card class="filter-card">
          <mat-card-content>
            <div class="filter-row">
              <mat-form-field appearance="outline" class="filter-field filter-search">
                <mat-label>Search</mat-label>
                <mat-icon matPrefix>search</mat-icon>
                <input matInput [(ngModel)]="filters.search" (ngModelChange)="applyFilters()" placeholder="Name or ISIN…">
                @if (filters.search) {
                  <button type="button" matSuffix mat-icon-button (click)="filters.search=''; applyFilters()">
                    <mat-icon>close</mat-icon>
                  </button>
                }
              </mat-form-field>

              <mat-form-field appearance="outline" class="filter-field">
                <mat-label>Status</mat-label>
                <mat-select [(ngModel)]="filters.status" (ngModelChange)="applyFilters()">
                  <mat-option [value]="null">All Statuses</mat-option>
                  @for (s of statusOptions; track s.value) {
                    <mat-option [value]="s.value">{{ s.label }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>

              <mat-form-field appearance="outline" class="filter-field">
                <mat-label>Token Standard</mat-label>
                <mat-select [(ngModel)]="filters.tokenStandard" (ngModelChange)="applyFilters()">
                  <mat-option [value]="null">All</mat-option>
                  @for (s of standardOptions; track s) {
                    <mat-option [value]="s">{{ s }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>

              <mat-form-field appearance="outline" class="filter-field filter-date">
                <mat-label>From date</mat-label>
                <input matInput type="date" [(ngModel)]="filters.fromDate" (ngModelChange)="applyFilters()">
              </mat-form-field>

              <mat-form-field appearance="outline" class="filter-field filter-date">
                <mat-label>To date</mat-label>
                <input matInput type="date" [(ngModel)]="filters.toDate" (ngModelChange)="applyFilters()">
              </mat-form-field>

              <button type="button" mat-stroked-button (click)="resetFilters()" [disabled]="!hasActiveFilters">
                <mat-icon>filter_list_off</mat-icon>
                Reset
              </button>
            </div>

            @if (dataSource.filteredData.length !== allIssuances.length) {
              <div class="filter-hint">
                Showing {{ dataSource.filteredData.length }} of {{ allIssuances.length }} issuances
              </div>
            }
          </mat-card-content>
        </mat-card>

        <!-- ── Table ──────────────────────────────────────────────────────── -->
        <mat-card class="table-card">
          <table mat-table [dataSource]="dataSource" matSort class="mat-elevation-z0">

            <ng-container matColumnDef="assetNumber">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Number</th>
              <td mat-cell *matCellDef="let row">
                <code>{{ row.assetNumber }}</code>
              </td>
            </ng-container>

            <ng-container matColumnDef="name">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Name</th>
              <td mat-cell *matCellDef="let row">
                <div class="asset-cell">
                  <span class="asset-name">{{ row.name }}</span>
                  @if (row.isin) {
                    <span class="asset-isin">{{ row.isin }}</span>
                  }
                </div>
              </td>
            </ng-container>

            <ng-container matColumnDef="tokenStandard">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Standard</th>
              <td mat-cell *matCellDef="let row">
                @if (row.tokenStandard) {
                  <mat-chip [style.background]="standardColor(row.tokenStandard)" style="color:var(--rw-accent-contrast); font-size:11px">
                    {{ row.tokenStandard }}
                  </mat-chip>
                } @else { — }
              </td>
            </ng-container>

            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Status</th>
              <td mat-cell *matCellDef="let row">
                <app-status-badge [status]="row.status"></app-status-badge>
              </td>
            </ng-container>

            <ng-container matColumnDef="chain">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Chain</th>
              <td mat-cell *matCellDef="let row">{{ row.chain | chainName }}</td>
            </ng-container>

            <ng-container matColumnDef="createdAt">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Created</th>
              <td mat-cell *matCellDef="let row">{{ row.createdAt | date:'mediumDate' }}</td>
            </ng-container>

            <ng-container matColumnDef="actions">
              <th mat-header-cell *matHeaderCellDef></th>
              <td mat-cell *matCellDef="let row" class="actions-cell">
                <a mat-icon-button [routerLink]="['/issuances', row.id]" matTooltip="View details">
                  <mat-icon>arrow_forward</mat-icon>
                </a>
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>

            <tr class="mat-row" *matNoDataRow>
              <td class="mat-cell empty-row" [attr.colspan]="displayedColumns.length">
                No issuances match the current filters.
                @if (!hasActiveFilters) {
                  <a routerLink="/issuances/new">Create your first issuance.</a>
                }
              </td>
            </tr>
          </table>

          <mat-paginator
            [pageSizeOptions]="[10, 25, 50]"
            [pageSize]="25"
            showFirstLastButtons
          ></mat-paginator>
        </mat-card>

      }
    </div>
  `,
  styles: [`
    /* KPI bar */
    .kpi-bar {
      display: flex;
      align-items: center;
      background: var(--rw-surface);
      border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius-md);
      box-shadow: var(--rw-shadow-xs);
      padding: 20px 28px;
      margin-bottom: 24px;
      flex-wrap: wrap;
      gap: 16px;
    }
    .kpi-item { display: flex; flex-direction: column; align-items: center; min-width: 90px; }
    .kpi-value { font-size: 26px; font-weight: 700; letter-spacing: -0.4px; color: var(--rw-text-primary); }
    .kpi-value.issued { color: var(--rw-issued-fg); }
    .kpi-value.pending { color: var(--rw-pending-fg); }
    .kpi-value.draft { color: var(--rw-text-muted); }
    .kpi-label { font-size: 11px; color: var(--rw-text-muted); margin-top: 2px; text-transform: uppercase; letter-spacing: 0.5px; font-weight: 600; }
    .kpi-divider { width: 1px; height: 40px; background: var(--rw-border); flex-shrink: 0; }

    /* Charts */
    .charts-row {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
      margin-bottom: 24px;
    }
    @media (max-width: 768px) {
      .charts-row { grid-template-columns: 1fr; }
    }
    .chart-card mat-card-content { padding-top: 8px; }

    /* Filters */
    .filter-card { margin-bottom: 16px; }
    .filter-card mat-card-content { padding: 12px !important; }
    .filter-row {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 10px;
    }
    .filter-field { min-width: 140px; }
    .filter-search { min-width: 200px; flex: 1; }
    .filter-date { min-width: 150px; }
    .filter-hint { font-size: 12px; color: var(--rw-text-muted); margin-top: 4px; }

    /* Table */
    .asset-cell { display: flex; flex-direction: column; }
    .asset-name { font-size: 14px; color: var(--rw-text-primary); }
    .asset-isin { font-size: 11px; color: var(--rw-text-muted); }
    code {
      font-family: 'IBM Plex Mono', 'Courier New', monospace;
      font-size: 12px;
      background: var(--rw-bg);
      color: var(--rw-text-secondary);
      padding: 2px 6px;
      border-radius: var(--rw-radius-sm);
    }
    .empty-row { text-align: center; padding: 32px; color: var(--rw-text-muted); }
    .table-card { overflow-x: auto; }
    .table-card table { min-width: 760px; }
    .load-error { display: grid; justify-items: center; gap: 10px; padding-block: 48px; text-align: center; }
    .load-error mat-icon { color: var(--rw-text-danger); }
    .load-error p { margin: 0; color: var(--rw-text-secondary); }
  `],
})
export class IssuanceListComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly issuanceService = inject(IssuanceService);

  @ViewChild(MatSort)
  set sort(sort: MatSort | undefined) {
    if (sort) this.dataSource.sort = sort;
  }

  @ViewChild(MatPaginator)
  set paginator(paginator: MatPaginator | undefined) {
    if (paginator) this.dataSource.paginator = paginator;
  }

  allIssuances: Asset[] = [];
  dataSource = new MatTableDataSource<Asset>();
  loading = true;
  loadError = false;

  filters: IssuanceFilters = {
    search: '',
    status: null,
    tokenStandard: null,
    fromDate: '',
    toDate: '',
  };

  readonly displayedColumns = [
    'assetNumber', 'name', 'tokenStandard', 'status', 'chain', 'createdAt', 'actions',
  ];

  readonly statusOptions: { value: AssetStatus; label: string }[] = [
    { value: 'DRAFT',            label: 'Draft' },
    { value: 'PENDING_APPROVAL', label: 'Pending Approval' },
    { value: 'APPROVED',         label: 'Approved' },
    { value: 'ISSUED',           label: 'Issued' },
    { value: 'SUSPENDED',        label: 'Suspended' },
    { value: 'REDEEMED',         label: 'Redeemed' },
  ];

  readonly standardOptions: TokenStandard[] = [
    'ERC20', 'ERC721', 'ERC1155', 'ERC3643', 'CONF_ERC20', 'CONF_ERC3643', 'SPL',
    'SPL_2022', 'STARKNET_ERC20', 'STELLAR_ASSET', 'CANTON_TOKEN',
  ];

  private readonly standardColors = TOKEN_STANDARD_COLORS;

  private readonly statusColors: Record<string, string> = {
    DRAFT: '#94A3B8',
    PENDING_APPROVAL: '#F59E0B',
    APPROVED: '#38BDF8',
    ISSUED: '#22C55E',
    SUSPENDED: '#FB7185',
    REDEEMED: '#A78B6D',
  };

  // ── Computed analytics ─────────────────────────────────────────────────────

  countByStatus(status: AssetStatus): number {
    return this.allIssuances.filter(a => a.status === status).length;
  }

  get uniqueStandards(): number {
    return new Set(this.allIssuances.filter(a => a.tokenStandard).map(a => a.tokenStandard)).size;
  }

  get standardSlices(): DonutSlice[] {
    const counts = new Map<string, number>();
    for (const a of this.allIssuances) {
      const key = a.tokenStandard ?? 'Unknown';
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return Array.from(counts.entries())
      .sort((a, b) => b[1] - a[1])
      .map(([label, value]) => ({
        label,
        value,
        color: this.standardColors[label] ?? '#94A3B8',
      }));
  }

  get statusBars(): BarItem[] {
    return this.statusOptions
      .map(opt => ({
        label: opt.label,
        value: this.countByStatus(opt.value),
        color: this.statusColors[opt.value] ?? '#94A3B8',
      }))
      .filter(b => b.value > 0)
      .sort((a, b) => b.value - a.value);
  }

  get hasActiveFilters(): boolean {
    return !!(
      this.filters.search ||
      this.filters.status ||
      this.filters.tokenStandard ||
      this.filters.fromDate ||
      this.filters.toDate
    );
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.dataSource.sortingDataAccessor = (item, column) => {
      switch (column) {
        case 'assetNumber':   return item.assetNumber;
        case 'name':          return item.name;
        case 'tokenStandard': return item.tokenStandard ?? '';
        case 'status':        return item.status;
        case 'chain':         return item.chain ?? '';
        case 'createdAt':     return item.createdAt;
        default:              return '';
      }
    };
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = false;
    this.issuanceService.getIssuances({ page: 0, size: 200, sort: 'createdAt,desc' }).subscribe({
      next: (res) => {
        this.allIssuances = res.content;
        this.dataSource.data = res.content;
        this.dataSource.filterPredicate = this.buildFilterPredicate();
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.loadError = true;
        this.cdr.markForCheck();
      },
    });
  }

  applyFilters(): void {
    this.dataSource.filter = JSON.stringify(this.filters);
  }

  resetFilters(): void {
    this.filters = { search: '', status: null, tokenStandard: null, fromDate: '', toDate: '' };
    this.dataSource.filter = '';
  }

  standardColor(std: string): string {
    return this.standardColors[std] ?? '#94A3B8';
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private buildFilterPredicate() {
    return (asset: Asset, filter: string): boolean => {
      if (!filter) return true;
      let f: IssuanceFilters;
      try { f = JSON.parse(filter) as IssuanceFilters; } catch { return true; }

      if (f.search) {
        const q = f.search.toLowerCase();
        const nameMatch = asset.name.toLowerCase().includes(q);
        const isinMatch = asset.isin?.toLowerCase().includes(q) ?? false;
        const numMatch  = asset.assetNumber.toLowerCase().includes(q);
        if (!nameMatch && !isinMatch && !numMatch) return false;
      }
      if (f.status && asset.status !== f.status) return false;
      if (f.tokenStandard && asset.tokenStandard !== f.tokenStandard) return false;

      const dateStr = asset.createdAt?.slice(0, 10) ?? '';
      if (f.fromDate && dateStr && dateStr < f.fromDate) return false;
      if (f.toDate   && dateStr && dateStr > f.toDate)   return false;

      return true;
    };
  }
}
