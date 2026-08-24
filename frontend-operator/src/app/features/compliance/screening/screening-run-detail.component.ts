import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  Input,
  OnInit,
  inject,
} from '@angular/core';
import { Router } from '@angular/router';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin } from 'rxjs';

import { DataTableComponent, TableColumn, StatusBadgeComponent } from '@registerwerk/ui';
import { StepUpDialogComponent } from '../../../shared/components/step-up/step-up-dialog.component';
import { ScreeningService } from '../../../core/api/screening.service';
import { ScreeningHit, ScreeningRun } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';


@Component({
  selector: 'app-screening-run-detail',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatDialogModule,
    MatDividerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    DataTableComponent,
    StatusBadgeComponent,
  ],
  styles: [`
    .back-row { margin-bottom: 16px; }

    .page-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 24px;
      gap: 16px;
      flex-wrap: wrap;

      h2 { margin: 0 0 6px; font-size: 20px; font-weight: 600; }
      .subtitle { font-size: 12px; color: var(--rw-text-secondary); font-family: monospace; }
    }

    .run-meta {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: 8px 24px;
      margin-bottom: 28px;
    }

    .meta-item {
      padding: 10px 0;
      border-bottom: 1px solid var(--rw-border);

      .label {
        font-size: 11px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.5px;
        color: var(--rw-text-muted);
        margin-bottom: 4px;
      }

      .value {
        font-size: 14px;
        color: var(--rw-text-primary);
      }
    }

    .hits-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 16px;

      h3 { margin: 0; font-size: 15px; font-weight: 600; }
    }

    .hit-accepted-banner {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 12px;
      color: #10b981;
      padding: 4px 8px;
      background: rgba(16, 185, 129, 0.08);
      border-radius: 4px;
    }
  `],
  template: `
    <div class="back-row">
      <button type="button" mat-button (click)="goBack()">
        <mat-icon>arrow_back</mat-icon>
        Back to Screening Queue
      </button>
    </div>
    <h1 class="sr-only">Screening run details</h1>

    @if (loadState === 'pending') {
      <div style="display:flex;justify-content:center;padding:48px">
        <mat-spinner diameter="40"></mat-spinner>
      </div>
    } @else if (loadState === 'error' || !run) {
      <p style="color:var(--rw-text-secondary);text-align:center;padding:48px">
        Run not found or could not be loaded.
      </p>
    } @else {
      <div class="page-header">
        <div>
          <h2>Screening Run</h2>
          <div class="subtitle">{{ run.id }}</div>
        </div>
        <app-status-badge [status]="run.status" />
      </div>

      <div class="run-meta">
        <div class="meta-item">
          <div class="label">Subject</div>
          <div class="value" style="font-family:monospace;font-size:12px">
            {{ run.entityId ?? run.naturalPersonId ?? '—' }}
          </div>
        </div>
        <div class="meta-item">
          <div class="label">Subject Type</div>
          <div class="value">{{ run.entityId ? 'Legal Entity' : 'Natural Person' }}</div>
        </div>
        <div class="meta-item">
          <div class="label">Trigger</div>
          <div class="value">{{ run.triggerType.replace('_', ' ') }}</div>
        </div>
        <div class="meta-item">
          <div class="label">Provider</div>
          <div class="value">{{ run.provider }}</div>
        </div>
        <div class="meta-item">
          <div class="label">Started</div>
          <div class="value">{{ run.startedAt | date:'dd MMM yyyy, HH:mm' }}</div>
        </div>
        <div class="meta-item">
          <div class="label">Completed</div>
          <div class="value">{{ run.completedAt ? (run.completedAt | date:'dd MMM yyyy, HH:mm') : '—' }}</div>
        </div>
      </div>

      <div class="hits-header">
        <h3>
          Screening Hits
          @if (openHits.length > 0) {
            <span style="font-size:12px;font-weight:400;color:var(--rw-text-secondary);margin-left:8px">
              {{ openHits.length }} unresolved
            </span>
          }
        </h3>
        @if (run.entityId) {
          <button type="button" mat-stroked-button (click)="reScreenEntity()">
            <mat-icon>refresh</mat-icon>
            Re-screen Now
          </button>
        }
      </div>

      <rw-data-table
        [columns]="hitColumns"
        [rows]="openHits"
        [state]="hitsState"
        (retry)="loadRun()"
        filterPlaceholder="Filter hits…"
        emptyMessage="No unresolved hits for this run."
        [actionsTemplate]="hitActions">
      </rw-data-table>

      <ng-template #hitActions let-hit>
        @if (hit.accepted) {
          <div class="hit-accepted-banner">
            <mat-icon style="font-size:16px;width:16px;height:16px">check_circle</mat-icon>
            Accepted as false positive
          </div>
        } @else {
          <button type="button" mat-stroked-button color="warn"
                  (click)="acceptHit(hit)"
                  matTooltip="Accept as false positive — requires step-up auth + dual control">
            <mat-icon>gavel</mat-icon>
            Accept (False Positive)
          </button>
        }
      </ng-template>
    }
  `,
})
export class ScreeningRunDetailComponent implements OnInit {
  /** Route parameter injected by withComponentInputBinding(). */
  @Input() runId!: string;

  private readonly screeningService = inject(ScreeningService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  run: ScreeningRun | null = null;
  openHits: ScreeningHit[] = [];
  loadState: AsyncSectionStatus = 'pending';
  hitsState: AsyncSectionStatus = 'pending';

  readonly hitColumns: TableColumn[] = [
    {
      key: 'category',
      header: 'Category',
      cell: (h: ScreeningHit) => h.category === 'PEP' ? 'PEP' : h.category === 'ADVERSE_MEDIA' ? 'Adverse Media' : 'Sanctions',
      type: 'badge',
    },
    {
      key: 'listSource',
      header: 'List Source',
      cell: (h: ScreeningHit) => h.listSource,
    },
    {
      key: 'matchedField',
      header: 'Matched Field',
      cell: (h: ScreeningHit) => h.matchedField,
    },
    {
      key: 'matchedValue',
      header: 'Matched Value',
      cell: (h: ScreeningHit) => h.matchedValue,
    },
    {
      key: 'matchScore',
      header: 'Score',
      cell: (h: ScreeningHit) =>
        h.matchScore != null ? (h.matchScore * 100).toFixed(0) + '%' : '—',
      type: 'number',
    },
    {
      key: 'status',
      header: 'Status',
      cell: (h: ScreeningHit) =>
        h.accepted ? 'FALSE_POSITIVE' : 'OPEN',
      type: 'badge',
    },
  ];

  ngOnInit(): void {
    this.loadRun();
  }

  loadRun(): void {
    this.loadState = 'pending';
    this.hitsState = 'pending';
    this.cdr.markForCheck();

    forkJoin({
      run: this.screeningService.getRun(this.runId),
      hits: this.screeningService.listHitsByRun(this.runId),
    }).subscribe({
      next: ({ run, hits }) => {
        this.run = run;
        this.openHits = hits;
        this.loadState = 'ready';
        this.hitsState = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.loadState = 'error';
        this.hitsState = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  acceptHit(hit: ScreeningHit): void {
    const dialogRef = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Accept false-positive screening hit (${hit.listSource} / ${hit.matchedValue})`,
        action: 'SCREENING_HIT_ACCEPT',
      },
      width: '500px',
      disableClose: true,
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (!result) return; // cancelled

      const reason = prompt('Please enter the acceptance justification (required for audit trail):');
      if (!reason) return;

      this.screeningService.acceptHit(
        hit.id,
        { reason },
        result.stepUpToken,
        result.dualControlToken,
      ).subscribe({
        next: () => {
          this.snackBar.open(
            'Hit accepted as false positive. Audit event recorded.',
            'Dismiss',
            { duration: 6000 },
          );
          this.loadRun();
        },
        error: (err) => {
          this.snackBar.open(
            err?.error?.message ?? 'Failed to accept hit. Check step-up authentication.',
            'Dismiss',
            { duration: 8000 },
          );
        },
      });
    });
  }

  reScreenEntity(): void {
    if (!this.run?.entityId) return;
    this.screeningService.screenEntity(this.run.entityId, { name: '' }).subscribe({
      next: (newRun) => {
        this.snackBar.open(
          `Re-screening submitted — status: ${newRun.status}`,
          'Open new run',
          { duration: 5000 },
        ).onAction().subscribe(() => {
          this.router.navigate(['/compliance/screening/runs', newRun.id]);
        });
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Re-screening failed.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  goBack(): void {
    this.router.navigate(['/compliance/screening']);
  }
}
