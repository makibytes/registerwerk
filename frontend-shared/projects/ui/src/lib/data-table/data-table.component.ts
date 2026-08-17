import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  Input,
  OnChanges,
  OnDestroy,
  OnInit,
  Output,
  EventEmitter,
  SimpleChanges,
  TemplateRef,
  ViewChild,
} from '@angular/core';
import { CommonModule, DatePipe, NgTemplateOutlet } from '@angular/common';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatSortModule, MatSort, Sort } from '@angular/material/sort';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatButtonModule } from '@angular/material/button';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { StatusBadgeComponent } from '../status-badge/status-badge.component';
import { AsyncSectionStatus } from '../async-section';

export interface TableColumn {
  key: string;
  header: string;
  // Deliberately `any`, not generic: making TableColumn<T>/DataTableComponent<T> generic was
  // tried and reverted — it forces every one of the ~18 call sites across both apps (each with
  // its own row type) to add an explicit type argument under strict contravariance, which is a
  // real but out-of-scope refactor for what this component's "any" is actually about: a single
  // reusable table shell consumed with heterogeneous row shapes.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  cell: (row: any) => string | null | undefined;
  sortable?: boolean;
  type?: 'text' | 'date' | 'badge' | 'number' | 'mono';
}

@Component({
  selector: 'rw-data-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    DatePipe,
    NgTemplateOutlet,
    MatTableModule,
    MatSortModule,
    MatPaginatorModule,
    MatInputModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatButtonModule,
    StatusBadgeComponent,
  ],
  styles: [`
    :host { display: block; }
    .toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
    .filter-field { width: 280px; font-size: 13px; }
    .table-wrap { overflow-x: auto; border-radius: 8px; border: 1px solid var(--rw-border); }
    .rw-table { width: 100%; font-size: 13px; }
    th.mat-mdc-header-cell { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px; color: var(--rw-text-muted); white-space: nowrap; padding: 10px 16px; }
    td.mat-mdc-cell { padding: 10px 16px; vertical-align: middle; color: var(--rw-text-primary); }
    tr.mat-mdc-row:hover td { background: var(--rw-table-row-hover, rgba(255,255,255,0.03)); cursor: default; }
    .cell-mono { font-family: 'IBM Plex Mono', monospace; font-size: 12px; }
    .cell-number { text-align: right; font-variant-numeric: tabular-nums; }
    th.mat-column-_actions,
    td.rw-actions-cell {
      width: 1%;
      min-width: max-content;
      white-space: nowrap;
    }
    th.mat-column-_actions { padding: 10px 16px 10px 8px; }
    td.rw-actions-cell { padding: 10px 16px 10px 8px; overflow: visible; }
    .actions { display: flex; align-items: center; justify-content: flex-end; gap: 4px; width: max-content; }
    .state-row { display: flex; align-items: center; justify-content: center; padding: 48px 16px; gap: 12px; color: var(--rw-text-muted); font-size: 13px; text-align: center; }
    .state-row.error { flex-wrap: wrap; }
    .empty-icon { font-size: 32px; width: 32px; height: 32px; color: var(--rw-text-muted); opacity: 0.4; }
    @media (max-width: 640px) {
      .toolbar { align-items: stretch; flex-direction: column; }
      .filter-field { width: 100%; }
    }
  `],
  template: `
    <div class="toolbar">
      @if (showFilter) {
        <mat-form-field class="filter-field" appearance="outline" subscriptSizing="dynamic">
          <mat-icon matPrefix>search</mat-icon>
          <input matInput [placeholder]="filterPlaceholder" [attr.aria-label]="filterPlaceholder" (input)="applyFilter($event)" />
        </mat-form-field>
      }
      <ng-content select="[tableToolbar]"></ng-content>
    </div>

    @if (state === 'pending') {
      <div class="state-row">
        <mat-spinner diameter="28"></mat-spinner>
        <span>Loading…</span>
      </div>
    } @else if (state === 'error') {
      <div class="state-row error" role="alert">
        <mat-icon class="empty-icon">error_outline</mat-icon>
        <span>Failed to load data. Please try again.</span>
        <button mat-stroked-button type="button" (click)="retry.emit()">Retry</button>
      </div>
    } @else {
      <div class="table-wrap">
        <table mat-table [dataSource]="dataSource" matSort (matSortChange)="onSortChange($event)" class="rw-table">
          @for (col of columns; track col.key) {
            <ng-container [matColumnDef]="col.key">
              @if (col.sortable !== false) {
                <th mat-header-cell *matHeaderCellDef mat-sort-header>{{ col.header }}</th>
              } @else {
                <th mat-header-cell *matHeaderCellDef>{{ col.header }}</th>
              }
              <td mat-cell *matCellDef="let row"
                  [class.cell-mono]="col.type === 'mono'"
                  [class.cell-number]="col.type === 'number'">
                @switch (col.type) {
                  @case ('date') {
                    {{ (col.cell(row) ?? '') | date:'dd MMM yyyy, HH:mm' }}
                  }
                  @case ('badge') {
                    @if (col.cell(row)) {
                      <app-status-badge [status]="col.cell(row)!" />
                    }
                  }
                  @default {
                    {{ col.cell(row) ?? '—' }}
                  }
                }
              </td>
            </ng-container>
          }

          @if (actionsTemplate) {
            <ng-container matColumnDef="_actions">
              <th mat-header-cell *matHeaderCellDef></th>
              <td mat-cell *matCellDef="let row" class="rw-actions-cell">
                <div class="actions">
                  <ng-container
                    [ngTemplateOutlet]="actionsTemplate"
                    [ngTemplateOutletContext]="{ $implicit: row }">
                  </ng-container>
                </div>
              </td>
            </ng-container>
          }

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>

        @if (dataSource.filteredData.length === 0) {
          <div class="state-row">
            <mat-icon class="empty-icon">inbox</mat-icon>
            <span>{{ emptyMessage }}</span>
          </div>
        }
      </div>

      @if (paginationMode === 'server') {
        <!-- Bindings kept off the client-mode paginator below entirely, not just conditioned to
             no-ops: an unconditional [length] binding here would fight MatTableDataSource's own
             internal paginator.length assignment every change-detection cycle in client mode
             (see ngAfterViewInit's comment on why the two modes can't share one attached
             paginator/sort either). -->
        <mat-paginator
          [pageSize]="pageSize"
          [pageSizeOptions]="[10, 20, 50, 100]"
          [length]="totalItems"
          [pageIndex]="pageIndex"
          (page)="page.emit($event)"
          showFirstLastButtons>
        </mat-paginator>
      } @else {
        <mat-paginator
          [pageSize]="pageSize"
          [pageSizeOptions]="[10, 20, 50, 100]"
          showFirstLastButtons>
        </mat-paginator>
      }
    }
  `,
})
export class DataTableComponent implements OnInit, OnChanges, AfterViewInit, OnDestroy {
  @Input({ required: true }) columns: TableColumn[] = [];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  @Input() rows: any[] = [];
  @Input() state: AsyncSectionStatus = 'ready';
  @Input() filterPlaceholder = 'Filter…';
  /** Set false for a server-mode table whose backend endpoint has no free-text filter param —
   *  a visible filter box that silently does nothing (it can no longer search rows the current
   *  page doesn't contain) is worse than not offering one. Client mode always has something
   *  real to filter (the full local dataset), so this only matters in server mode. */
  @Input() showFilter = true;
  @Input() pageSize = 20;
  @Input() emptyMessage = 'No records found.';
  @Output() readonly retry = new EventEmitter<void>();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  @Input() actionsTemplate?: TemplateRef<{ $implicit: any }>;

  /**
   * 'client' (default) is the original, fully backward-compatible behavior: `rows` is the
   * complete dataset and MatTableDataSource paginates/sorts/filters it locally — every existing
   * consumer keeps working unmodified. 'server' hands all three off to the parent instead: `rows`
   * is just the current page, `totalItems`/`pageIndex` drive the paginator's `[length]`, and
   * `(page)`/`(sortChange)`/`(filterChange)` tell the parent what to fetch next — needed for any
   * dataset too large to ship to the browser in one response (see `rw-data-table`'s own findings
   * in the 23 consumers were all client-side-only, several papering over it with
   * a hardcoded `size: 100`/`size: 200` request ceiling instead of real pagination).
   */
  @Input() paginationMode: 'client' | 'server' = 'client';
  /** Server mode only — total row count across all pages, for the paginator's `[length]`. */
  @Input() totalItems = 0;
  /** Server mode only — current zero-based page index, settable by the parent (e.g. reset to 0
   *  after a filter change or a delete that could leave the current page short). */
  @Input() pageIndex = 0;

  @Output() readonly page = new EventEmitter<PageEvent>();
  @Output() readonly sortChange = new EventEmitter<Sort>();
  /** Server mode only — debounced (300ms) filter text; client mode filters locally instead and
   *  never emits this. */
  @Output() readonly filterChange = new EventEmitter<string>();

  @ViewChild(MatSort) sort!: MatSort;
  @ViewChild(MatPaginator) paginator!: MatPaginator;

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  readonly dataSource = new MatTableDataSource<any>([]);
  displayedColumns: string[] = [];

  private readonly filterInput$ = new Subject<string>();

  constructor() {
    this.filterInput$.pipe(
      debounceTime(300),
      distinctUntilChanged(),
    ).subscribe(value => this.filterChange.emit(value));
  }

  ngOnInit(): void { this.computeDisplayedColumns(); }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['rows']) this.dataSource.data = this.rows ?? [];
    if (changes['columns'] || changes['actionsTemplate']) this.computeDisplayedColumns();
  }

  ngAfterViewInit(): void {
    // Server mode: the paginator/sort are driven externally (totalItems/pageIndex inputs,
    // page/sortChange outputs above) — attaching them here too would make MatTableDataSource
    // ALSO locally re-paginate/re-sort the single page of rows the parent already fetched,
    // silently hiding rows that are actually on a different server page.
    if (this.paginationMode === 'client') {
      this.dataSource.sort = this.sort;
      this.dataSource.paginator = this.paginator;
    }
  }

  ngOnDestroy(): void {
    this.filterInput$.complete();
  }

  applyFilter(event: Event): void {
    const value = (event.target as HTMLInputElement).value.trim().toLowerCase();
    if (this.paginationMode === 'server') {
      this.filterInput$.next(value);
      return;
    }
    this.dataSource.filter = value;
    if (this.dataSource.paginator) this.dataSource.paginator.firstPage();
  }

  onSortChange(event: Sort): void {
    if (this.paginationMode === 'server') this.sortChange.emit(event);
  }

  private computeDisplayedColumns(): void {
    this.displayedColumns = [
      ...this.columns.map(c => c.key),
      ...(this.actionsTemplate ? ['_actions'] : []),
    ];
  }
}
