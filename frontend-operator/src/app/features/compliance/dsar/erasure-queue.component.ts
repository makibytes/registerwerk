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
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { DsarService } from '../../../core/api/dsar.service';
import { ErasureRequestView } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import {
  StepUpDialogComponent,
  StepUpDialogResult,
} from '../../../shared/components/step-up/step-up-dialog.component';

type ResolveMode = 'complete' | 'reject';

@Component({
  selector: 'app-erasure-queue',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [``],
  template: `
    <app-page-header
      title="DSGVO Art. 17 Erasure Requests"
      subtitle="Art. 12(3) · 30-day response clock — open erasure requests awaiting review">
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="requests"
      [state]="state"
      (retry)="loadQueue()"
      filterPlaceholder="Filter by entity, requester…"
      emptyMessage="No open erasure requests."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-req>
      <button mat-icon-button color="primary"
              (click)="openResolveDialog(req, 'complete')"
              matTooltip="Mark as completed">
        <mat-icon>check_circle</mat-icon>
      </button>
      <button mat-icon-button color="warn"
              (click)="openResolveDialog(req, 'reject')"
              matTooltip="Reject (e.g. statutory retention applies)">
        <mat-icon>block</mat-icon>
      </button>
    </ng-template>

    <!-- Resolve Dialog (Complete / Reject) -->
    <ng-template #resolveDialog>
      <h2 mat-dialog-title>{{ resolveMode === 'complete' ? 'Complete' : 'Reject' }} Erasure Request</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
          Entity <code>{{ activeRequest?.entityId }}</code>
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Resolution note</mat-label>
          <textarea
            matInput
            rows="3"
            [(ngModel)]="resolutionNote"
            placeholder="{{ resolveMode === 'complete'
              ? 'e.g. erased marketing prefs; retained KYC per GwG §8'
              : 'e.g. all fields under statutory retention (GwG §8 / HGB §257)' }}">
          </textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button [color]="resolveMode === 'complete' ? 'primary' : 'warn'"
                [disabled]="!resolutionNote.trim()"
                (click)="submitResolve()">
          <mat-icon>{{ resolveMode === 'complete' ? 'check_circle' : 'block' }}</mat-icon>
          {{ resolveMode === 'complete' ? 'Complete' : 'Reject' }}
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class ErasureQueueComponent implements OnInit {
  @ViewChild('resolveDialog') resolveDialogTpl!: TemplateRef<unknown>;

  private readonly dsarService = inject(DsarService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  requests: ErasureRequestView[] = [];
  state: AsyncSectionStatus = 'pending';

  activeRequest: ErasureRequestView | null = null;
  resolveMode: ResolveMode = 'complete';
  resolutionNote = '';

  readonly columns: TableColumn[] = [
    {
      key: 'entityId',
      header: 'Entity',
      cell: (r: ErasureRequestView) => r.entityId,
      type: 'mono',
    },
    {
      key: 'requestedByUserId',
      header: 'Requested By',
      cell: (r: ErasureRequestView) => r.requestedByUserId ?? '—',
      type: 'mono',
    },
    {
      key: 'requestedAt',
      header: 'Requested',
      cell: (r: ErasureRequestView) => r.requestedAt,
      type: 'date',
    },
    {
      key: 'dueAt',
      header: 'Due (Art. 12(3))',
      cell: (r: ErasureRequestView) => r.dueAt,
      type: 'date',
    },
    {
      key: 'sla',
      header: 'SLA',
      cell: (r: ErasureRequestView) => (this.isOverdue(r) ? 'OVERDUE' : 'ON_TRACK'),
      type: 'badge',
    },
    {
      key: 'status',
      header: 'Status',
      cell: (r: ErasureRequestView) => r.status,
      type: 'badge',
    },
  ];

  ngOnInit(): void {
    this.loadQueue();
  }

  loadQueue(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.dsarService.listOpen().subscribe({
      next: (requests) => {
        this.requests = requests;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  isOverdue(r: ErasureRequestView): boolean {
    return new Date(r.dueAt).getTime() < Date.now();
  }

  openResolveDialog(req: ErasureRequestView, mode: ResolveMode): void {
    this.activeRequest = req;
    this.resolveMode = mode;
    this.resolutionNote = '';
    this.dialog.open(this.resolveDialogTpl, { width: '480px' });
  }

  submitResolve(): void {
    const req = this.activeRequest;
    if (!req) return;
    this.dialog.closeAll();

    const note = this.resolutionNote.trim();
    const isComplete = this.resolveMode === 'complete';

    this.dialog
      .open(StepUpDialogComponent, {
        data: isComplete
          ? {
              requireDualControl: true,
              reason: `Complete erasure request for entity ${req.entityId} (irreversibly tombstones user PII)`,
              action: 'DSAR_ERASURE_COMPLETE',
            }
          : {
              requireDualControl: false,
              reason: `Reject erasure request for entity ${req.entityId}`,
              action: 'DSAR_ERASURE_REJECT',
            },
        width: '520px',
        disableClose: true,
      })
      .afterClosed()
      .subscribe((result: StepUpDialogResult | undefined) => {
        if (!result?.stepUpToken) return;
        if (isComplete && !result.dualControlToken) return;

        const action$ = isComplete
          ? this.dsarService.complete(req.id, note, result.stepUpToken, result.dualControlToken!)
          : this.dsarService.reject(req.id, note, result.stepUpToken);

        action$.subscribe({
          next: () => {
            this.snackBar.open(
              `Erasure request ${isComplete ? 'completed' : 'rejected'}.`,
              'Dismiss',
              { duration: 5000 },
            );
            this.loadQueue();
          },
          error: (err) => {
            this.snackBar.open(
              err?.error?.message ?? 'Failed to resolve erasure request.',
              'Dismiss',
              { duration: 6000 },
            );
          },
        });
      });
  }
}
