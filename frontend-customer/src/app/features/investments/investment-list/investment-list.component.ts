import {
  ChangeDetectorRef,
  Component,
  OnInit,
  ViewChild,
  inject,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatSortModule, MatSort } from '@angular/material/sort';
import { MatPaginatorModule, MatPaginator } from '@angular/material/paginator';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { InvestmentService } from '../../../core/api/investment.service';
import { InvestmentRecord, TokenStandard } from '../../../core/models';
import { StatusBadgeComponent, DonutChartComponent, DonutSlice, BarChartComponent, BarItem } from '@registerwerk/ui';


import { AddressComponent } from '../../../shared/components/address.component';
import { TOKEN_STANDARD_COLORS } from '../../../shared/token-standard-colors';

interface Filters {
  search: string;
  tokenStandard: TokenStandard | null;
  whitelisted: 'all' | 'yes' | 'no';
  fromDate: string;
  toDate: string;
}

@Component({
  selector: 'app-investment-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatTableModule,
    MatSortModule,
    MatPaginatorModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatFormFieldModule,
    MatSelectModule,
    MatInputModule,
    MatChipsModule,
    StatusBadgeComponent,
    DonutChartComponent,
    BarChartComponent,
    AddressComponent,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>My Investments</h1>
      </div>

      @if (loading) {
        <div class="loading-overlay"><mat-spinner diameter="48"></mat-spinner></div>
      } @else if (loadError) {
        <div class="content-card load-error" role="alert">
          <mat-icon>error_outline</mat-icon>
          <p>Investments could not be loaded.</p>
          <button mat-stroked-button type="button" (click)="load()">Retry</button>
        </div>
      } @else {

        <!-- ── KPI Summary ─────────────────────────────────────────────────── -->
        <div class="kpi-bar">
          <div class="kpi-item">
            <span class="kpi-value">{{ totalNominal | number:'1.0-0' }} {{ totalNominalCurrency ?? '' }}</span>
            <span class="kpi-label">Total Invested</span>
          </div>
          <div class="kpi-divider"></div>
          <div class="kpi-item">
            <span class="kpi-value">{{ allRecords.length }}</span>
            <span class="kpi-label">Holdings</span>
          </div>
          <div class="kpi-divider"></div>
          <div class="kpi-item">
            <span class="kpi-value">{{ whitelistedCount }}</span>
            <span class="kpi-label">Whitelisted</span>
          </div>
          <div class="kpi-divider"></div>
          <div class="kpi-item">
            <span class="kpi-value">{{ uniqueAssets }}</span>
            <span class="kpi-label">Unique Assets</span>
          </div>
          <div class="kpi-divider"></div>
          <div class="kpi-item">
            <span class="kpi-value">{{ uniqueStandards }}</span>
            <span class="kpi-label">Token Standards</span>
          </div>
        </div>

        <!-- ── Analytics Charts ───────────────────────────────────────────── -->
        @if (allRecords.length > 0) {
          <div class="charts-row">
            <mat-card class="chart-card">
              <mat-card-header>
                <mat-card-title>Portfolio by Token Standard</mat-card-title>
                <mat-card-subtitle>Nominal value distribution</mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <app-donut-chart
                  [slices]="standardSlices"
                  centerLabel="Total"
                  [centerValue]="(totalNominalCurrency ? totalNominalCurrency + ' ' : '') + totalNominalShort">
                </app-donut-chart>
              </mat-card-content>
            </mat-card>

            <mat-card class="chart-card">
              <mat-card-header>
                <mat-card-title>Top Holdings</mat-card-title>
                <mat-card-subtitle>By nominal amount</mat-card-subtitle>
              </mat-card-header>
              <mat-card-content>
                <app-bar-chart
                  [items]="topHoldingsBars"
                  valueFormat="currency">
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
                <mat-label>Search asset</mat-label>
                <mat-icon matPrefix>search</mat-icon>
                <input matInput [(ngModel)]="filters.search" (ngModelChange)="applyFilters()" placeholder="Asset name or ISIN…">
                @if (filters.search) {
                  <button matSuffix mat-icon-button (click)="filters.search=''; applyFilters()">
                    <mat-icon>close</mat-icon>
                  </button>
                }
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

              <mat-form-field appearance="outline" class="filter-field">
                <mat-label>Whitelisted</mat-label>
                <mat-select [(ngModel)]="filters.whitelisted" (ngModelChange)="applyFilters()">
                  <mat-option value="all">All</mat-option>
                  <mat-option value="yes">Yes</mat-option>
                  <mat-option value="no">No</mat-option>
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

              <button mat-stroked-button (click)="resetFilters()" [disabled]="!hasActiveFilters">
                <mat-icon>filter_list_off</mat-icon>
                Reset
              </button>
            </div>

            @if (dataSource.filteredData.length !== allRecords.length) {
              <div class="filter-hint">
                Showing {{ dataSource.filteredData.length }} of {{ allRecords.length }} holdings
              </div>
            }
          </mat-card-content>
        </mat-card>

        <!-- ── Table ──────────────────────────────────────────────────────── -->
        <mat-card class="table-card">
          <table mat-table [dataSource]="dataSource" matSort class="mat-elevation-z0">

            <ng-container matColumnDef="assetName">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Asset</th>
              <td mat-cell *matCellDef="let r">
                <div class="asset-cell">
                  <span class="asset-name">{{ r.assetName ?? r.assetId }}</span>
                  @if (r.isin) {
                    <span class="asset-isin">{{ r.isin }}</span>
                  }
                </div>
              </td>
            </ng-container>

            <ng-container matColumnDef="tokenStandard">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Standard</th>
              <td mat-cell *matCellDef="let r">
                @if (r.tokenStandard) {
                  <mat-chip [style.background]="standardColor(r.tokenStandard)" style="color:var(--rw-accent-contrast); font-size:11px">
                    {{ r.tokenStandard }}
                  </mat-chip>
                } @else { — }
              </td>
            </ng-container>

            <ng-container matColumnDef="nominalAmount">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Nominal Amount</th>
              <td mat-cell *matCellDef="let r" class="amount-cell">
                {{ r.nominalAmount | number:'1.0-2' }} {{ r.currency ?? '' }}
              </td>
            </ng-container>

            <ng-container matColumnDef="acquisitionDate">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Acquired</th>
              <td mat-cell *matCellDef="let r">
                {{ r.acquisitionDate ? (r.acquisitionDate | date:'mediumDate') : '—' }}
              </td>
            </ng-container>

            <ng-container matColumnDef="walletAddress">
              <th mat-header-cell *matHeaderCellDef>Wallet</th>
              <td mat-cell *matCellDef="let r">
                <app-address [address]="r.walletAddress" />
              </td>
            </ng-container>

            <ng-container matColumnDef="whitelisted">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Whitelisted</th>
              <td mat-cell *matCellDef="let r">
                <app-status-badge [status]="r.whitelisted ? 'WHITELISTED' : 'NOT_WHITELISTED'"></app-status-badge>
              </td>
            </ng-container>

            <ng-container matColumnDef="assetStatus">
              <th mat-header-cell *matHeaderCellDef mat-sort-header>Asset Status</th>
              <td mat-cell *matCellDef="let r">
                @if (r.assetStatus) {
                  <app-status-badge [status]="r.assetStatus"></app-status-badge>
                } @else { — }
              </td>
            </ng-container>

            <ng-container matColumnDef="externalId">
              <th mat-header-cell *matHeaderCellDef>External ID</th>
              <td mat-cell *matCellDef="let r">
                {{ r.externalId || '—' }}
              </td>
            </ng-container>

            <ng-container matColumnDef="actions">
              <th mat-header-cell *matHeaderCellDef></th>
              <td mat-cell *matCellDef="let r" class="actions-cell">
                <a mat-icon-button [routerLink]="['/investments', r.id]" matTooltip="View details">
                  <mat-icon>arrow_forward</mat-icon>
                </a>
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>

            <tr class="mat-row" *matNoDataRow>
              <td class="mat-cell empty-row" [attr.colspan]="displayedColumns.length">
                No investments match the current filters.
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
    .kpi-item { display: flex; flex-direction: column; align-items: center; min-width: 100px; }
    .kpi-value { font-size: 26px; font-weight: 700; letter-spacing: -0.4px; color: var(--rw-text-primary); }
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
    .amount-cell { font-weight: 600; color: var(--rw-text-primary); }
    .address-code {
      font-family: 'IBM Plex Mono', 'Courier New', monospace;
      font-size: 12px;
      background: var(--rw-bg);
      color: var(--rw-text-secondary);
      padding: 2px 6px;
      border-radius: var(--rw-radius-sm);
      cursor: help;
    }
    .empty-row { text-align: center; padding: 32px; color: var(--rw-text-muted); }
    .table-card { overflow-x: auto; }
    .table-card table { min-width: 920px; }
    .load-error { display: grid; justify-items: center; gap: 10px; padding-block: 48px; text-align: center; }
    .load-error mat-icon { color: var(--rw-text-danger); }
    .load-error p { margin: 0; color: var(--rw-text-secondary); }
  `],
})
export class InvestmentListComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly investmentService = inject(InvestmentService);

  @ViewChild(MatSort)
  set sort(sort: MatSort | undefined) {
    if (sort) this.dataSource.sort = sort;
  }

  @ViewChild(MatPaginator)
  set paginator(paginator: MatPaginator | undefined) {
    if (paginator) this.dataSource.paginator = paginator;
  }

  allRecords: InvestmentRecord[] = [];
  dataSource = new MatTableDataSource<InvestmentRecord>();
  loading = true;
  loadError = false;

  filters: Filters = {
    search: '',
    tokenStandard: null,
    whitelisted: 'all',
    fromDate: '',
    toDate: '',
  };

  readonly displayedColumns = [
    'assetName', 'tokenStandard', 'nominalAmount', 'acquisitionDate',
    'walletAddress', 'whitelisted', 'assetStatus', 'externalId', 'actions',
  ];

  readonly standardOptions: TokenStandard[] = [
    'ERC20', 'ERC721', 'ERC1155', 'ERC3643', 'CONF_ERC20', 'CONF_ERC3643', 'SPL',
    'SPL_2022', 'STARKNET_ERC20', 'STELLAR_ASSET', 'CANTON_TOKEN',
  ];

  private readonly standardColors = TOKEN_STANDARD_COLORS;

  // ── Computed analytics ─────────────────────────────────────────────────────

  get totalNominal(): number {
    return this.allRecords.reduce((s, r) => s + r.nominalAmount, 0);
  }

  /** Null when holdings span more than one currency (or none is set) — the total is unitless then. */
  get totalNominalCurrency(): string | null {
    if (this.allRecords.length === 0) return null;
    const first = this.allRecords[0].currency;
    return first !== null && this.allRecords.every(r => r.currency === first) ? first : null;
  }

  get totalNominalShort(): string {
    const v = this.totalNominal;
    if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`;
    if (v >= 1_000) return `${(v / 1_000).toFixed(0)}K`;
    return String(Math.round(v));
  }

  get whitelistedCount(): number {
    return this.allRecords.filter(r => r.whitelisted).length;
  }

  get uniqueAssets(): number {
    return new Set(this.allRecords.map(r => r.assetId)).size;
  }

  get uniqueStandards(): number {
    return new Set(this.allRecords.filter(r => r.tokenStandard).map(r => r.tokenStandard)).size;
  }

  get standardSlices(): DonutSlice[] {
    const byStandard = new Map<string, number>();
    for (const r of this.allRecords) {
      const key = r.tokenStandard ?? 'Unknown';
      byStandard.set(key, (byStandard.get(key) ?? 0) + r.nominalAmount);
    }
    return Array.from(byStandard.entries())
      .sort((a, b) => b[1] - a[1])
      .map(([label, value]) => ({
        label,
        value,
        color: this.standardColors[label] ?? '#94A3B8',
      }));
  }

  get topHoldingsBars(): BarItem[] {
    return [...this.allRecords]
      .sort((a, b) => b.nominalAmount - a.nominalAmount)
      .slice(0, 7)
      .map(r => ({
        label: r.assetName ?? r.assetNumber ?? r.assetId.slice(0, 8),
        subtitle: r.tokenStandard ?? undefined,
        value: r.nominalAmount,
        color: this.standardColors[r.tokenStandard ?? ''] ?? '#00695c',
      }));
  }

  get hasActiveFilters(): boolean {
    return !!(
      this.filters.search ||
      this.filters.tokenStandard ||
      this.filters.whitelisted !== 'all' ||
      this.filters.fromDate ||
      this.filters.toDate
    );
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.dataSource.sortingDataAccessor = (item, column) => {
      switch (column) {
        case 'assetName':        return item.assetName ?? '';
        case 'tokenStandard':    return item.tokenStandard ?? '';
        case 'nominalAmount':    return item.nominalAmount;
        case 'acquisitionDate':  return item.acquisitionDate ?? '';
        case 'whitelisted':      return item.whitelisted ? 1 : 0;
        case 'assetStatus':      return item.assetStatus ?? '';
        default:                 return '';
      }
    };
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = false;
    this.investmentService
      .getMyInvestments({ page: 0, size: 200, sort: 'acquisitionDate,desc' })
      .subscribe({
        next: (res) => {
          this.allRecords = res.content;
          this.dataSource.data = res.content;
          this.dataSource.filterPredicate = this.buildFilterPredicate();
          this.loading = false;
          this.cdr.detectChanges();
        },
        error: () => {
          this.loading = false;
          this.loadError = true;
          this.cdr.detectChanges();
        },
      });
  }

  applyFilters(): void {
    // Trigger MatTableDataSource filterPredicate by changing the filter string
    this.dataSource.filter = JSON.stringify(this.filters);
  }

  resetFilters(): void {
    this.filters = { search: '', tokenStandard: null, whitelisted: 'all', fromDate: '', toDate: '' };
    this.dataSource.filter = '';
  }

  standardColor(std: string): string {
    return this.standardColors[std] ?? '#94A3B8';
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private buildFilterPredicate() {
    return (record: InvestmentRecord, filter: string): boolean => {
      if (!filter) return true;
      let f: Filters;
      try { f = JSON.parse(filter) as Filters; } catch { return true; }

      if (f.search) {
        const q = f.search.toLowerCase();
        const nameMatch = record.assetName?.toLowerCase().includes(q) ?? false;
        const isinMatch = record.isin?.toLowerCase().includes(q) ?? false;
        if (!nameMatch && !isinMatch) return false;
      }
      if (f.tokenStandard && record.tokenStandard !== f.tokenStandard) return false;
      if (f.whitelisted === 'yes' && !record.whitelisted) return false;
      if (f.whitelisted === 'no' && record.whitelisted) return false;
      if (f.fromDate && record.acquisitionDate && record.acquisitionDate < f.fromDate) return false;
      if (f.toDate && record.acquisitionDate && record.acquisitionDate > f.toDate) return false;

      return true;
    };
  }
}
