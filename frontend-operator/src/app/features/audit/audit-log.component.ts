import { Component, OnInit, ViewChild, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatTableModule, MatTableDataSource } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { DatePipe, JsonPipe } from '@angular/common';
import { AuditService } from '../../core/api/audit.service';
import { AuditEvent } from '../../core/models';

@Component({
  selector: 'app-audit-log',
  standalone: true,
  imports: [
    FormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDatepickerModule,
    MatNativeDateModule,
    DatePipe,
    JsonPipe,
  ],
  styles: [`
    .filter-row {
      display: flex;
      gap: 16px;
      align-items: flex-start;
      margin-bottom: 20px;
      flex-wrap: wrap;

      mat-form-field { min-width: 180px; }
    }

    .spinner-container {
      display: flex;
      justify-content: center;
      padding: 48px;
    }

    .no-data {
      text-align: center;
      padding: 48px;
      color: rgba(0, 0, 0, 0.38);
    }

    .event-type-cell {
      font-family: monospace;
      font-size: 12px;
      background: #f5f5f5;
      padding: 2px 6px;
      border-radius: 3px;
    }

    .metadata-cell {
      max-width: 240px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 12px;
      color: rgba(0,0,0,0.54);
    }
  `],
  template: `
    <div class="page-header">
      <h1>Audit Log</h1>
      <button mat-stroked-button (click)="clearFilters()">
        <mat-icon>clear</mat-icon>
        Clear Filters
      </button>
    </div>

    <div class="content-card">
      <div class="filter-row">
        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Event Type</mat-label>
          <input matInput [(ngModel)]="filterEventType" (ngModelChange)="onFilterChange()" placeholder="ENTITY_CREATED..." />
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Subject Type</mat-label>
          <mat-select [(ngModel)]="filterSubjectType" (ngModelChange)="onFilterChange()">
            <mat-option value="">All</mat-option>
            <mat-option value="LEGAL_ENTITY">Legal Entity</mat-option>
            <mat-option value="ASSET">Asset</mat-option>
            <mat-option value="DEPLOYMENT">Deployment</mat-option>
            <mat-option value="KYC_DOCUMENT">KYC Document</mat-option>
            <mat-option value="ONBOARDING_TOKEN">Onboarding Token</mat-option>
          </mat-select>
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>Subject ID</mat-label>
          <input matInput [(ngModel)]="filterSubjectId" (ngModelChange)="onFilterChange()" placeholder="UUID..." />
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>From Date</mat-label>
          <input matInput type="date" [(ngModel)]="filterFrom" (ngModelChange)="onFilterChange()" />
        </mat-form-field>

        <mat-form-field appearance="outline" subscriptSizing="dynamic">
          <mat-label>To Date</mat-label>
          <input matInput type="date" [(ngModel)]="filterTo" (ngModelChange)="onFilterChange()" />
        </mat-form-field>
      </div>

      @if (loading) {
        <div class="spinner-container">
          <mat-spinner diameter="40" />
        </div>
      } @else {
        <table mat-table [dataSource]="dataSource" class="full-width-table">
          <ng-container matColumnDef="occurredAt">
            <th mat-header-cell *matHeaderCellDef>Occurred At</th>
            <td mat-cell *matCellDef="let row">{{ row.occurredAt | date:'short' }}</td>
          </ng-container>

          <ng-container matColumnDef="eventType">
            <th mat-header-cell *matHeaderCellDef>Event Type</th>
            <td mat-cell *matCellDef="let row">
              <span class="event-type-cell">{{ row.eventType }}</span>
            </td>
          </ng-container>

          <ng-container matColumnDef="subjectType">
            <th mat-header-cell *matHeaderCellDef>Subject Type</th>
            <td mat-cell *matCellDef="let row">{{ row.subjectType }}</td>
          </ng-container>

          <ng-container matColumnDef="subjectId">
            <th mat-header-cell *matHeaderCellDef>Subject ID</th>
            <td mat-cell *matCellDef="let row">
              <code style="font-size:11px">{{ row.subjectId }}</code>
            </td>
          </ng-container>

          <ng-container matColumnDef="actorId">
            <th mat-header-cell *matHeaderCellDef>Actor</th>
            <td mat-cell *matCellDef="let row">
              <span style="font-size:13px">{{ row.actorId ?? '—' }}</span>
              @if (row.actorRole) {
                <span class="text-muted"> ({{ row.actorRole }})</span>
              }
            </td>
          </ng-container>

          <ng-container matColumnDef="metadata">
            <th mat-header-cell *matHeaderCellDef>Metadata</th>
            <td mat-cell *matCellDef="let row">
              <span class="metadata-cell" [title]="row.metadata ? (row.metadata | json) : ''">
                {{ row.metadata ? (row.metadata | json) : '—' }}
              </span>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
        </table>

        @if (dataSource.data.length === 0) {
          <div class="no-data">No audit events found.</div>
        }

        <mat-paginator
          [length]="totalElements"
          [pageSize]="pageSize"
          [pageIndex]="pageIndex"
          [pageSizeOptions]="[25, 50, 100]"
          (page)="onPage($event)"
          showFirstLastButtons
        />
      }
    </div>
  `,
})
export class AuditLogComponent implements OnInit {
  private readonly auditService = inject(AuditService);

  readonly displayedColumns = [
    'occurredAt', 'eventType', 'subjectType', 'subjectId', 'actorId', 'metadata',
  ];

  dataSource = new MatTableDataSource<AuditEvent>([]);
  loading = false;
  totalElements = 0;
  pageSize = 25;
  pageIndex = 0;

  filterEventType = '';
  filterSubjectType = '';
  filterSubjectId = '';
  filterFrom = '';
  filterTo = '';

  @ViewChild(MatPaginator) paginator!: MatPaginator;

  ngOnInit(): void {
    this.loadData();
  }

  loadData(): void {
    this.loading = true;
    this.auditService
      .searchEvents({
        eventType: this.filterEventType || undefined,
        subjectType: this.filterSubjectType || undefined,
        subjectId: this.filterSubjectId || undefined,
        from: this.filterFrom || undefined,
        to: this.filterTo || undefined,
        page: this.pageIndex,
        size: this.pageSize,
      })
      .subscribe({
        next: (resp) => {
          this.dataSource.data = resp.content;
          this.totalElements = resp.totalElements;
          this.loading = false;
        },
        error: () => { this.loading = false; },
      });
  }

  onFilterChange(): void {
    this.pageIndex = 0;
    this.loadData();
  }

  onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadData();
  }

  clearFilters(): void {
    this.filterEventType = '';
    this.filterSubjectType = '';
    this.filterSubjectId = '';
    this.filterFrom = '';
    this.filterTo = '';
    this.pageIndex = 0;
    this.loadData();
  }
}
