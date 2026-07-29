import { ChangeDetectorRef, Component, OnInit, ViewChild, inject } from '@angular/core';
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
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { DatePipe, JsonPipe } from '@angular/common';
import { AuditService } from '../../core/api/audit.service';
import { AuditEvent, ChainVerificationResult } from '../../core/models';

type ReportMode = 'all' | 'kyc-overrides';

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
    MatButtonToggleModule,
    DatePipe,
    JsonPipe,
  ],
  styles: [`
    .report-toggle {
      margin-bottom: 16px;
    }

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
      color: var(--rw-text-muted);
    }

    .event-type-cell {
      font-family: 'IBM Plex Mono', 'Courier New', monospace;
      font-size: 12px;
      background: var(--rw-bg);
      padding: 2px 6px;
      border-radius: var(--rw-radius-sm);
      color: var(--rw-text-secondary);
    }

    .metadata-cell {
      max-width: 240px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 12px;
      color: var(--rw-text-muted);
    }

    .chain-status-card {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 14px 16px;
      border-radius: 8px;
      margin-bottom: 20px;

      mat-icon.status-icon { flex-shrink: 0; }

      .chain-status-text {
        flex: 1;
        font-size: 13px;
        line-height: 1.4;
      }

      .chain-status-detail {
        font-size: 12px;
        color: var(--rw-text-muted);
      }
    }

    .chain-status-card.valid {
      background: rgba(16, 185, 129, 0.08);
      border: 1px solid rgba(16, 185, 129, 0.22);
      mat-icon.status-icon { color: #10B981; }
    }

    .chain-status-card.broken {
      background: rgba(220, 38, 38, 0.08);
      border: 1px solid rgba(220, 38, 38, 0.25);
      mat-icon.status-icon { color: #DC2626; }
    }

    .chain-status-card.unknown {
      background: var(--rw-bg);
      border: 1px solid var(--rw-border);
      mat-icon.status-icon { color: var(--rw-text-muted); }
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

    <div class="chain-status-card" [class.valid]="chainStatus?.valid === true"
         [class.broken]="chainStatus?.valid === false"
         [class.unknown]="!chainStatus">
      <mat-icon class="status-icon">
        {{ chainStatus == null ? 'help_outline' : chainStatus.valid ? 'verified' : 'gpp_bad' }}
      </mat-icon>
      <div class="chain-status-text">
        @if (chainStatus == null) {
          Hash-chain integrity has not been checked yet in this session.
        } @else if (chainStatus.valid) {
          <strong>Audit hash chain intact</strong> — {{ chainStatus.rowsChecked }} rows verified.
          <div class="chain-status-detail">Last checked {{ chainStatus.checkedAt | date:'short' }}</div>
        } @else {
          <strong>Audit hash chain BROKEN</strong> at sequence_no={{ chainStatus.firstBrokenSequenceNo }}.
          <div class="chain-status-detail">{{ chainStatus.rowsChecked }} rows checked before failure — checked {{ chainStatus.checkedAt | date:'short' }}</div>
        }
      </div>
      <button mat-stroked-button (click)="verifyChainNow()" [disabled]="verifyingChain">
        <mat-icon>{{ verifyingChain ? 'hourglass_empty' : 'refresh' }}</mat-icon>
        {{ verifyingChain ? 'Verifying…' : 'Verify now' }}
      </button>
    </div>

    <div class="content-card">
      <mat-button-toggle-group class="report-toggle" [(ngModel)]="reportMode" (change)="onReportModeChange()">
        <mat-button-toggle value="all">All Events</mat-button-toggle>
        <mat-button-toggle value="kyc-overrides">KYC Overrides Report</mat-button-toggle>
      </mat-button-toggle-group>

      <div class="filter-row">
        @if (reportMode === 'all') {
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
        } @else {
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Jurisdiction</mat-label>
            <mat-select [(ngModel)]="filterJurisdiction" (ngModelChange)="onFilterChange()">
              <mat-option value="">All</mat-option>
              <mat-option value="DE_EWPG">Germany — eWpG / BaFin</mat-option>
              <mat-option value="LU_CSSF">Luxembourg — CSSF</mat-option>
              <mat-option value="FR_AMF">France — AMF</mat-option>
              <mat-option value="LI_TVTG">Liechtenstein — TVTG / FMA</mat-option>
            </mat-select>
          </mat-form-field>
        }

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
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auditService = inject(AuditService);

  readonly displayedColumns = [
    'occurredAt', 'eventType', 'subjectType', 'subjectId', 'actorId', 'metadata',
  ];

  dataSource = new MatTableDataSource<AuditEvent>([]);
  loading = false;
  totalElements = 0;
  pageSize = 25;
  pageIndex = 0;

  reportMode: ReportMode = 'all';
  filterEventType = '';
  filterSubjectType = '';
  filterSubjectId = '';
  filterJurisdiction = '';
  filterFrom = '';
  filterTo = '';

  chainStatus: ChainVerificationResult | null = null;
  verifyingChain = false;

  @ViewChild(MatPaginator) paginator!: MatPaginator;

  ngOnInit(): void {
    this.loadData();
    this.auditService.chainStatus().subscribe({
      next: (result) => {
        this.chainStatus = result;
        this.cdr.detectChanges();
      },
      error: () => {
        // Leave chainStatus null on request failure — rendered as "not checked yet".
      },
    });
  }

  verifyChainNow(): void {
    this.verifyingChain = true;
    this.cdr.detectChanges();
    this.auditService.verifyChainNow().subscribe({
      next: (result) => {
        this.chainStatus = result;
        this.verifyingChain = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.verifyingChain = false;
        this.cdr.detectChanges();
      },
    });
  }

  loadData(): void {
    this.loading = true;
    const request$ = this.reportMode === 'kyc-overrides'
      ? this.auditService.kycOverrideReport({
          jurisdiction: this.filterJurisdiction || undefined,
          from: this.toIsoDateTime(this.filterFrom, 'start'),
          to: this.toIsoDateTime(this.filterTo, 'end'),
          page: this.pageIndex,
          size: this.pageSize,
        })
      : this.auditService.searchEvents({
          eventType: this.filterEventType || undefined,
          subjectType: this.filterSubjectType || undefined,
          subjectId: this.filterSubjectId || undefined,
          from: this.filterFrom || undefined,
          to: this.filterTo || undefined,
          page: this.pageIndex,
          size: this.pageSize,
        });

    request$.subscribe({
      next: (resp) => {
        this.dataSource.data = resp.content;
        this.totalElements = resp.totalElements;
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.loading = false;
        this.cdr.detectChanges();
      },
    });
  }

  /** The backend's KYC-overrides report expects full ISO date-times, unlike the plain
   *  yyyy-MM-dd the "From/To Date" inputs produce — anchor to the start/end of that day. */
  private toIsoDateTime(date: string, bound: 'start' | 'end'): string | undefined {
    if (!date) return undefined;
    return bound === 'start' ? `${date}T00:00:00Z` : `${date}T23:59:59Z`;
  }

  onReportModeChange(): void {
    this.pageIndex = 0;
    this.loadData();
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
    this.filterJurisdiction = '';
    this.filterFrom = '';
    this.filterTo = '';
    this.pageIndex = 0;
    this.loadData();
  }
}
