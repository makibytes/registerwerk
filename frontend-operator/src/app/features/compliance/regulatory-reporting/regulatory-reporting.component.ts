import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';


import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { RegulatoryReportingService } from '../../../core/api/regulatory-reporting.service';
import { RegulatorySubmission } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';

@Component({
  selector: 'app-regulatory-reporting',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [`

    .generate-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
      margin-bottom: 28px;

      @media (max-width: 720px) { grid-template-columns: 1fr; }
    }

    .gen-card {
      mat-card-content {
        display: flex;
        flex-direction: column;
        gap: 12px;
        padding-top: 8px;
      }

      .card-desc {
        font-size: 12px;
        color: var(--rw-text-secondary);
        line-height: 1.5;
      }

      .card-actions {
        display: flex;
        align-items: center;
        gap: 10px;
        flex-wrap: wrap;
      }
    }

    h3 { font-size: 15px; font-weight: 600; margin: 0 0 16px; }
  `],
  template: `
    <app-page-header
      title="Regulatory Reporting"
      subtitle="MiFIR Art. 26 / RTS 22 — Daily trade reporting · DAC8 / CARF — Annual crypto-asset reporting">
    </app-page-header>

    <div class="generate-grid">
      <!-- DAC8/CARF Card -->
      <mat-card class="gen-card">
        <mat-card-header>
          <mat-card-title style="font-size:14px;font-weight:600">DAC8 / CARF Annual Export</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="card-desc">
            Generates the annual crypto-asset reporting XML for DAC8 (EU Council Directive 2023/2226)
            and CARF (OECD). Submitted to the tax authority via the configured gateway.
          </div>
          <div class="card-actions">
            <mat-form-field appearance="outline" subscriptSizing="dynamic" style="width:100px">
              <mat-label>Tax year</mat-label>
              <input matInput type="number" [(ngModel)]="dac8Year" [min]="2020" [max]="currentYear" />
            </mat-form-field>
            <button mat-raised-button color="primary"
                    (click)="generateDac8()"
                    [disabled]="generatingDac8">
              <mat-icon>description</mat-icon>
              {{ generatingDac8 ? 'Generating…' : 'Generate' }}
            </button>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- MiFIR Card -->
      <mat-card class="gen-card">
        <mat-card-header>
          <mat-card-title style="font-size:14px;font-weight:600">MiFIR RTS 22 — Trade Report</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <div class="card-desc">
            Generates the MiFIR Art. 26 daily trade-by-trade transaction report (RTS 22 format).
            Normally scheduled T+1; this trigger re-generates on demand.
          </div>
          <div class="card-actions">
            <mat-form-field appearance="outline" subscriptSizing="dynamic" style="width:150px">
              <mat-label>Reporting date</mat-label>
              <input matInput type="date" [(ngModel)]="mifirDate" />
            </mat-form-field>
            <button mat-raised-button color="primary"
                    (click)="generateMifir()"
                    [disabled]="generatingMifir">
              <mat-icon>bar_chart</mat-icon>
              {{ generatingMifir ? 'Generating…' : 'Generate' }}
            </button>
          </div>
        </mat-card-content>
      </mat-card>
    </div>

    <h3>Recent Submissions</h3>
    <rw-data-table
      [columns]="submissionColumns"
      [rows]="submissions"
      [state]="state"
      filterPlaceholder="Filter submissions…"
      emptyMessage="No report submissions on record.">
    </rw-data-table>
  `,
})
export class RegulatoryReportingComponent implements OnInit {
  private readonly service = inject(RegulatoryReportingService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  submissions: RegulatorySubmission[] = [];
  state: AsyncSectionStatus = 'pending';

  readonly currentYear = new Date().getFullYear();
  dac8Year = this.currentYear - 1;
  mifirDate = new Date(Date.now() - 86400000).toISOString().split('T')[0]; // yesterday

  generatingDac8 = false;
  generatingMifir = false;

  readonly submissionColumns: TableColumn[] = [
    {
      key: 'report_type',
      header: 'Report Type',
      cell: (s: RegulatorySubmission) => s.report_type,
      type: 'badge',
    },
    {
      key: 'jurisdiction',
      header: 'Jurisdiction',
      cell: (s: RegulatorySubmission) => s.jurisdiction ?? '—',
    },
    {
      key: 'status',
      header: 'Status',
      cell: (s: RegulatorySubmission) => s.status,
      type: 'badge',
    },
    {
      key: 'reporting_period_start',
      header: 'Period Start',
      cell: (s: RegulatorySubmission) => s.reporting_period_start,
      type: 'date',
    },
    {
      key: 'reporting_period_end',
      header: 'Period End',
      cell: (s: RegulatorySubmission) => s.reporting_period_end,
      type: 'date',
    },
    {
      key: 'submitted_at',
      header: 'Submitted',
      cell: (s: RegulatorySubmission) => s.submitted_at,
      type: 'date',
    },
    {
      key: 'submission_ref',
      header: 'Reference',
      cell: (s: RegulatorySubmission) => s.submission_ref ?? '—',
      type: 'mono',
    },
  ];

  ngOnInit(): void {
    this.loadSubmissions();
  }

  loadSubmissions(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.service.listSubmissions().subscribe({
      next: (subs) => {
        this.submissions = subs;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  generateDac8(): void {
    this.generatingDac8 = true;
    this.cdr.markForCheck();

    this.service.generateDac8(this.dac8Year).subscribe({
      next: (res) => {
        this.generatingDac8 = false;
        this.cdr.markForCheck();
        this.snackBar.open(res.message, 'Dismiss', { duration: 7000 });
        setTimeout(() => this.loadSubmissions(), 3000);
      },
      error: (err) => {
        this.generatingDac8 = false;
        this.cdr.markForCheck();
        this.snackBar.open(err?.error?.message ?? 'Failed to generate DAC8 export.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  generateMifir(): void {
    this.generatingMifir = true;
    this.cdr.markForCheck();

    this.service.generateMifir(this.mifirDate).subscribe({
      next: (res) => {
        this.generatingMifir = false;
        this.cdr.markForCheck();
        this.snackBar.open(res.message, 'Dismiss', { duration: 7000 });
        setTimeout(() => this.loadSubmissions(), 3000);
      },
      error: (err) => {
        this.generatingMifir = false;
        this.cdr.markForCheck();
        this.snackBar.open(err?.error?.message ?? 'Failed to generate MiFIR report.', 'Dismiss', { duration: 6000 });
      },
    });
  }
}
