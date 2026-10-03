import { ChangeDetectorRef, Component, OnInit, ViewChild, inject } from '@angular/core';
import { downloadBlob } from '../../core/utils/download.util';
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
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, JsonPipe } from '@angular/common';
import { AuditService } from '../../core/api/audit.service';
import { AuditEvent, ChainVerificationResult } from '../../core/models';
import { AuthService } from '../../core/auth/auth.service';
import { ChainVerdict, canAcknowledgeChain, chainVerdict } from '../../core/utils/chain-verdict';
import { MatDialog } from '@angular/material/dialog';
import { StepUpDialogComponent, StepUpDialogResult } from '../../shared/components/step-up/step-up-dialog.component';

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
    MatTooltipModule,
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

    .request-error {
      display: grid;
      justify-items: center;
      gap: 12px;
      padding: 48px 20px;
      text-align: center;
      color: var(--rw-text-danger);
    }

    .header-actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      justify-content: flex-end;
    }

    .table-scroll { overflow-x: auto; }
    table { min-width: 980px; }

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

    .chain-status-card .ack-note { width: 240px; }

    .chain-status-card.valid {
      background: var(--rw-approved-bg);
      border: 1px solid color-mix(in srgb, var(--rw-approved-fg) 25%, transparent);
      mat-icon.status-icon { color: var(--rw-text-success); }
    }

    .chain-status-card.broken {
      background: var(--rw-rejected-bg);
      border: 1px solid color-mix(in srgb, var(--rw-rejected-fg) 28%, transparent);
      mat-icon.status-icon { color: var(--rw-text-danger); }
    }

    .chain-status-card.pending-ack {
      background: var(--rw-pending-bg);
      border: 1px solid color-mix(in srgb, var(--rw-pending-fg) 28%, transparent);
      mat-icon.status-icon { color: var(--rw-text-warning); }
    }

    .chain-status-card.unknown {
      background: var(--rw-bg);
      border: 1px solid var(--rw-border);
      mat-icon.status-icon { color: var(--rw-text-muted); }
    }

    @media (max-width: 720px) {
      .page-header { align-items: flex-start; gap: 12px; flex-direction: column; }
      .header-actions { justify-content: flex-start; }
      .chain-status-card { align-items: flex-start; flex-wrap: wrap; }
      .chain-status-card button { margin-left: 36px; }
      .filter-row mat-form-field { width: 100%; }
    }
  `],
  template: `
    <div class="page-header">
      <h1>Audit Log</h1>
      <div class="header-actions">
        @if (reportMode === 'all') {
        <button type="button" mat-stroked-button (click)="exportEvents()">
          <mat-icon>download</mat-icon>
          Export (CSV)
        </button>
        <button type="button" mat-stroked-button (click)="exportEventsSigned()"
                matTooltip="CSV with per-row hash-chain columns, Ed25519-signed for independent verification">
          <mat-icon>verified</mat-icon>
          Signed export
        </button>
        }
        <button type="button" mat-stroked-button (click)="clearFilters()">
          <mat-icon>clear</mat-icon>
          Clear Filters
        </button>
      </div>
    </div>

    <div class="chain-status-card" [class.valid]="verdict?.kind === 'valid'"
         [class.broken]="verdict?.kind === 'broken'"
         [class.pending-ack]="verdict?.kind === 'pending-ack'"
         [class.unknown]="!verdict || verdict.kind === 'unknown'">
      <mat-icon class="status-icon">
        {{ !verdict || verdict.kind === 'unknown' ? 'help_outline' : verdict.kind === 'valid' ? 'verified' : verdict.kind === 'pending-ack' ? 'pending_actions' : 'gpp_bad' }}
      </mat-icon>
      <div class="chain-status-text" [attr.role]="verdict?.kind === 'broken' ? 'alert' : null">
        @if (chainStatusLoading) {
          Loading the latest hash-chain verification…
        } @else if (chainStatusError) {
          <strong>Hash-chain status unavailable.</strong> Verify again to retry the integrity check.
        } @else if (!verdict) {
          Hash-chain integrity has not been checked yet in this session.
        } @else {
          <strong>{{ verdict.title }}</strong>
          @if (verdict.kind === 'valid') { — {{ verdict.detail }} } @else { {{ verdict.detail }} }
          @if (chainStatus?.checkedAt) {
            <div class="chain-status-detail">Last checked {{ chainStatus.checkedAt | date:'short' }}</div>
          }
        }
      </div>
      @if (canAcknowledge) {
        <mat-form-field appearance="outline" subscriptSizing="dynamic" class="ack-note">
          <mat-label>Acknowledgement note (optional)</mat-label>
          <input matInput [(ngModel)]="ackNote" maxlength="500" />
        </mat-form-field>
        <button type="button" mat-stroked-button (click)="acknowledgeChain()" [disabled]="acknowledging">
          <mat-icon>how_to_reg</mat-icon>
          Acknowledge
        </button>
      }
      <button type="button" mat-stroked-button (click)="verifyChainNow()" [disabled]="verifyingChain">
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
      } @else if (loadError) {
        <div class="request-error" role="alert">
          <mat-icon>cloud_off</mat-icon>
          <span>Audit events could not be loaded.</span>
          <button mat-stroked-button type="button" (click)="loadData()">Retry</button>
        </div>
      } @else {
        <div class="table-scroll">
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
        </div>

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
  private readonly snackBar = inject(MatSnackBar);
  private readonly auditService = inject(AuditService);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);

  readonly displayedColumns = [
    'occurredAt', 'eventType', 'subjectType', 'subjectId', 'actorId', 'metadata',
  ];

  dataSource = new MatTableDataSource<AuditEvent>([]);
  loading = false;
  loadError = false;
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
  chainStatusLoading = true;
  chainStatusError = false;
  verifyingChain = false;
  acknowledging = false;
  ackNote = '';

  get verdict(): ChainVerdict | null {
    return this.chainStatus ? chainVerdict(this.chainStatus) : null;
  }

  get canAcknowledge(): boolean {
    return canAcknowledgeChain(this.chainStatus, this.auth.hasRole('REGISTRY_ADMIN'));
  }

  /** Dual-control acknowledgement of a broken verdict: initiator's TOTP + a second approver's request-bound token. */
  acknowledgeChain(): void {
    const status = this.chainStatus;
    if (!status?.verificationId || this.acknowledging) return;
    const id = status.verificationId;
    const note = this.ackNote;
    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: 'Acknowledge a broken audit hash-chain verification',
        action: 'AUDIT_CHAIN_VERIFICATION_ACK',
        target: `POST ${this.auditService.chainAckPath(id, note)}`,
        targetBody: {},
      },
      width: '500px',
      disableClose: true,
    });
    ref.afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken || !result.dualControlToken) return;
      this.acknowledging = true;
      this.cdr.markForCheck();
      this.auditService.acknowledgeChainVerification(id, note, {
        stepUpToken: result.stepUpToken, dualControlToken: result.dualControlToken,
      }).subscribe({
        next: () => {
          this.acknowledging = false;
          this.snackBar.open('Acknowledged. The alert clears once a later run is clean.', 'Dismiss', { duration: 5000 });
          this.reloadChainStatus();
        },
        error: (err) => {
          this.acknowledging = false;
          this.snackBar.open(
            `${err?.error?.message ?? 'Acknowledgement failed.'} The approver token was consumed - obtain a new one before retrying.`,
            'Dismiss', { duration: 8000 });
          this.cdr.markForCheck();
        },
      });
    });
  }

  private reloadChainStatus(): void {
    this.auditService.chainStatus().subscribe({
      next: (result) => { this.chainStatus = result; this.cdr.markForCheck(); },
      error: () => { this.chainStatusError = true; this.cdr.markForCheck(); },
    });
  }

  @ViewChild(MatPaginator) paginator!: MatPaginator;

  ngOnInit(): void {
    this.loadData();
    this.auditService.chainStatus().subscribe({
      next: (result) => {
        this.chainStatus = result;
        this.chainStatusLoading = false;
        this.chainStatusError = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.chainStatusLoading = false;
        this.chainStatusError = true;
        this.cdr.markForCheck();
      },
    });
  }

  verifyChainNow(): void {
    this.verifyingChain = true;
    this.chainStatusError = false;
    this.cdr.markForCheck();
    this.auditService.verifyChainNow().subscribe({
      next: (result) => {
        this.chainStatus = result;
        this.verifyingChain = false;
        this.chainStatusError = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.verifyingChain = false;
        this.chainStatusError = true;
        this.snackBar.open('Hash-chain verification failed.', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  loadData(): void {
    this.loading = true;
    this.loadError = false;
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
          from: this.toIsoDateTime(this.filterFrom, 'start'),
          to: this.toIsoDateTime(this.filterTo, 'end'),
          page: this.pageIndex,
          size: this.pageSize,
        });

    request$.subscribe({
      next: (resp) => {
        this.dataSource.data = resp.content;
        this.totalElements = resp.totalElements;
        this.loading = false;
        this.loadError = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.loadError = true;
        this.cdr.markForCheck();
      },
    });
  }

  /** The backend's KYC-overrides report expects full ISO date-times, unlike the plain
   *  yyyy-MM-dd the "From/To Date" inputs produce — anchor to the start/end of that day. */
  private toIsoDateTime(date: string, bound: 'start' | 'end'): string | undefined {
    if (!date) return undefined;
    return bound === 'start' ? `${date}T00:00:00Z` : `${date}T23:59:59Z`;
  }

  /** CSV/evidence export for the currently-applied filters — a compliance officer or auditor
   *  previously had no way to get a data extract for a date range or case without DB access. */
  exportEvents(): void {
    this.auditService.exportEvents({
      eventType: this.filterEventType || undefined,
      subjectType: this.filterSubjectType || undefined,
      subjectId: this.filterSubjectId || undefined,
      from: this.toIsoDateTime(this.filterFrom, 'start'),
      to: this.toIsoDateTime(this.filterTo, 'end'),
    }).subscribe({
      next: blob => {
        this.download(blob, `audit-export-${new Date().toISOString().slice(0, 10)}.csv`);
      },
      error: () => this.snackBar.open('Failed to export audit events.', 'Close', { duration: 4000 }),
    });
  }

  /** Same filters as {@link exportEvents}, but the CSV carries each row's hash-chain position
   *  and signature, and the whole export is itself Ed25519-signed (headers) so an auditor can
   *  verify the exact file they received against `/audit/signing-key`. */
  exportEventsSigned(): void {
    this.auditService.exportEventsSigned({
      eventType: this.filterEventType || undefined,
      subjectType: this.filterSubjectType || undefined,
      subjectId: this.filterSubjectId || undefined,
      from: this.toIsoDateTime(this.filterFrom, 'start'),
      to: this.toIsoDateTime(this.filterTo, 'end'),
    }).subscribe({
      next: response => {
        const blob = response.body;
        if (!blob) return;
        this.download(blob, `audit-export-signed-${new Date().toISOString().slice(0, 10)}.csv`);

        const signed = response.headers.get('X-Export-Signed') === 'true';
        this.snackBar.open(
          signed
            ? `Signed with ${response.headers.get('X-Audit-Signing-Key-Name')} — digest ${response.headers.get('X-Export-Digest-Sha256')?.slice(0, 16)}…`
            : 'No signing key configured in this environment — export is unsigned.',
          'Close',
          { duration: 8000 },
        );
      },
      error: () => this.snackBar.open('Failed to export signed audit events.', 'Close', { duration: 4000 }),
    });
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

  private download(blob: Blob, filename: string): void {
    downloadBlob(blob, filename);
  }
}
