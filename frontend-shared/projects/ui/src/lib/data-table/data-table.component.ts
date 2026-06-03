import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  Input,
  OnChanges,
  OnInit,
  SimpleChanges,
  TemplateRef,
  ViewChild,
} from '@angular/core';
import { CommonModule, DatePipe, NgTemplateOutlet } from '@angular/common';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';
import { MatSortModule, MatSort } from '@angular/material/sort';
import { MatPaginatorModule, MatPaginator } from '@angular/material/paginator';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { StatusBadgeComponent } from '../status-badge/status-badge.component';
import { AsyncSectionStatus } from '../async-section';

export interface TableColumn {
  key: string;
  header: string;
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
    StatusBadgeComponent,
  ],
  styles: [`
    :host { display: block; }
    .toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
    .filter-field { width: 280px; font-size: 13px; }
    .table-wrap { overflow-x: auto; border-radius: 8px; border: 1px solid var(--rw-border); }
    .rw-table { width: 100%; font-size: 13px; }
    th.mat-header-cell { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.5px; color: var(--rw-text-muted); white-space: nowrap; padding: 10px 16px; }
    td.mat-cell { padding: 10px 16px; vertical-align: middle; color: var(--rw-text-primary); }
    tr.mat-row:hover td { background: var(--rw-table-row-hover, rgba(255,255,255,0.03)); cursor: default; }
    .cell-mono { font-family: 'IBM Plex Mono', monospace; font-size: 12px; }
    .cell-number { text-align: right; font-variant-numeric: tabular-nums; }
    td.actions-cell { white-space: nowrap; width: 1px; }
    .state-row { display: flex; align-items: center; justify-content: center; padding: 48px 0; gap: 12px; color: var(--rw-text-muted); font-size: 13px; }
    .empty-icon { font-size: 32px; width: 32px; height: 32px; color: var(--rw-text-muted); opacity: 0.4; }
  `],
  template: `
    <div class="toolbar">
      <mat-form-field class="filter-field" appearance="outline" subscriptSizing="dynamic">
        <mat-icon matPrefix>search</mat-icon>
        <input matInput [placeholder]="filterPlaceholder" (input)="applyFilter($event)" />
      </mat-form-field>
      <ng-content select="[tableToolbar]"></ng-content>
    </div>

    @if (state === 'pending') {
      <div class="state-row">
        <mat-spinner diameter="28"></mat-spinner>
        <span>Loading…</span>
      </div>
    } @else if (state === 'error') {
      <div class="state-row">
        <mat-icon class="empty-icon">error_outline</mat-icon>
        <span>Failed to load data. Please try again.</span>
      </div>
    } @else {
      <div class="table-wrap">
        <table mat-table [dataSource]="dataSource" matSort class="rw-table">
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
              <td mat-cell *matCellDef="let row" class="actions-cell">
                <ng-container
                  [ngTemplateOutlet]="actionsTemplate"
                  [ngTemplateOutletContext]="{ $implicit: row }">
                </ng-container>
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

      <mat-paginator
        [pageSize]="pageSize"
        [pageSizeOptions]="[10, 20, 50, 100]"
        showFirstLastButtons>
      </mat-paginator>
    }
  `,
})
export class DataTableComponent implements OnInit, OnChanges, AfterViewInit {
  @Input({ required: true }) columns: TableColumn[] = [];
  @Input() rows: any[] = [];
  @Input() state: AsyncSectionStatus = 'ready';
  @Input() filterPlaceholder = 'Filter…';
  @Input() pageSize = 20;
  @Input() emptyMessage = 'No records found.';
  @Input() actionsTemplate?: TemplateRef<{ $implicit: any }>;

  @ViewChild(MatSort) sort!: MatSort;
  @ViewChild(MatPaginator) paginator!: MatPaginator;

  readonly dataSource = new MatTableDataSource<any>([]);
  displayedColumns: string[] = [];

  ngOnInit(): void { this.computeDisplayedColumns(); }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['rows']) this.dataSource.data = this.rows ?? [];
    if (changes['columns'] || changes['actionsTemplate']) this.computeDisplayedColumns();
  }

  ngAfterViewInit(): void {
    this.dataSource.sort = this.sort;
    this.dataSource.paginator = this.paginator;
  }

  applyFilter(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.dataSource.filter = value.trim().toLowerCase();
    if (this.dataSource.paginator) this.dataSource.paginator.firstPage();
  }

  private computeDisplayedColumns(): void {
    this.displayedColumns = [
      ...this.columns.map(c => c.key),
      ...(this.actionsTemplate ? ['_actions'] : []),
    ];
  }
}
