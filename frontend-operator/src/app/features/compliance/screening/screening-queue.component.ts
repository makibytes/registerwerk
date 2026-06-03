import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';


import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { ScreeningService } from '../../../core/api/screening.service';
import { OpenHitView } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';

@Component({
  selector: 'app-screening-queue',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [``],
  template: `
    <app-page-header
      title="Sanctions &amp; PEP Screening Queue"
      subtitle="GwG §10 · MiCAR Art. 60 · AMLD6 — Unresolved screening hits requiring review">
      <button mat-raised-button color="primary" (click)="openRunScreeningDialog()">
        <mat-icon>search</mat-icon>
        Run Screening
      </button>
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="hits"
      [state]="state"
      filterPlaceholder="Filter by subject, list, field…"
      emptyMessage="No open screening hits. All entities are clear."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-hit>
      <button mat-icon-button color="primary"
              (click)="viewRun(hit)"
              matTooltip="View run & hits">
        <mat-icon>open_in_new</mat-icon>
      </button>
    </ng-template>

    <!-- Run Screening Dialog -->
    <ng-template #runScreeningDialog>
      <h2 mat-dialog-title>Run On-Demand Screening</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:400px">
        <mat-form-field appearance="outline">
          <mat-label>Subject type</mat-label>
          <mat-select [(ngModel)]="screenForm.type">
            <mat-option value="entity">Legal Entity</mat-option>
            <mat-option value="person">Natural Person (Beneficial Owner)</mat-option>
          </mat-select>
        </mat-form-field>
        @if (screenForm.type === 'entity') {
          <mat-form-field appearance="outline">
            <mat-label>Entity ID (UUID)</mat-label>
            <input matInput [(ngModel)]="screenForm.entityId" placeholder="xxxxxxxx-…" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Legal name</mat-label>
            <input matInput [(ngModel)]="screenForm.name" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Country code (ISO-2, optional)</mat-label>
            <input matInput [(ngModel)]="screenForm.countryCode" placeholder="DE" maxlength="2" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>LEI code (optional)</mat-label>
            <input matInput [(ngModel)]="screenForm.lei" />
          </mat-form-field>
        } @else {
          <mat-form-field appearance="outline">
            <mat-label>Natural Person ID (UUID)</mat-label>
            <input matInput [(ngModel)]="screenForm.personId" placeholder="xxxxxxxx-…" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Full name</mat-label>
            <input matInput [(ngModel)]="screenForm.name" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Country code (ISO-2, optional)</mat-label>
            <input matInput [(ngModel)]="screenForm.countryCode" placeholder="DE" maxlength="2" />
          </mat-form-field>
        }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" (click)="submitScreening()">
          <mat-icon>search</mat-icon>
          Run
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class ScreeningQueueComponent implements OnInit {
  @ViewChild('runScreeningDialog') runScreeningDialogTpl!: TemplateRef<unknown>;

  private readonly screeningService = inject(ScreeningService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  hits: OpenHitView[] = [];
  state: AsyncSectionStatus = 'pending';

  screenForm: {
    type: 'entity' | 'person';
    entityId: string;
    personId: string;
    name: string;
    countryCode: string;
    lei: string;
  } = { type: 'entity', entityId: '', personId: '', name: '', countryCode: '', lei: '' };

  readonly columns: TableColumn[] = [
    {
      key: 'subject',
      header: 'Subject',
      cell: (h: OpenHitView) => h.entityId ?? h.naturalPersonId ?? '—',
      type: 'mono',
    },
    {
      key: 'listSource',
      header: 'Sanctions List',
      cell: (h: OpenHitView) => h.listSource,
    },
    {
      key: 'matchedField',
      header: 'Matched Field',
      cell: (h: OpenHitView) => h.matchedField,
    },
    {
      key: 'matchedValue',
      header: 'Matched Value',
      cell: (h: OpenHitView) => h.matchedValue,
    },
    {
      key: 'matchScore',
      header: 'Score',
      cell: (h: OpenHitView) =>
        h.matchScore != null ? (h.matchScore * 100).toFixed(0) + '%' : '—',
      type: 'number',
    },
    {
      key: 'runStatus',
      header: 'Run Status',
      cell: (h: OpenHitView) => h.runStatus ?? '—',
      type: 'badge',
    },
    {
      key: 'triggerType',
      header: 'Trigger',
      cell: (h: OpenHitView) => h.triggerType?.replace(/_/g, ' ') ?? '—',
    },
    {
      key: 'createdAt',
      header: 'Detected',
      cell: (h: OpenHitView) => h.createdAt ?? null,
      type: 'date',
    },
  ];

  ngOnInit(): void {
    this.loadQueue();
  }

  loadQueue(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.screeningService.listOpenHits().subscribe({
      next: (hits) => {
        this.hits = hits;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  viewRun(hit: OpenHitView): void {
    this.router.navigate(['/compliance/screening/runs', hit.runId]);
  }

  openRunScreeningDialog(): void {
    this.screenForm = { type: 'entity', entityId: '', personId: '', name: '', countryCode: '', lei: '' };
    this.dialog.open(this.runScreeningDialogTpl, { width: '480px' });
  }

  submitScreening(): void {
    this.dialog.closeAll();

    if (this.screenForm.type === 'entity') {
      this.screeningService.screenEntity(this.screenForm.entityId, {
        name: this.screenForm.name,
        countryCode: this.screenForm.countryCode || undefined,
        lei: this.screenForm.lei || undefined,
      }).subscribe({
        next: (run) => {
          this.snackBar.open(
            `Screening complete — status: ${run.status}`,
            'Dismiss',
            { duration: 5000 },
          );
          this.loadQueue();
        },
        error: (err) => {
          this.snackBar.open(
            err?.error?.message ?? 'Screening failed.',
            'Dismiss',
            { duration: 6000 },
          );
        },
      });
    } else {
      this.screeningService.screenPerson(this.screenForm.personId, {
        fullName: this.screenForm.name,
        countryCode: this.screenForm.countryCode || undefined,
      }).subscribe({
        next: (run) => {
          this.snackBar.open(
            `Screening complete — status: ${run.status}`,
            'Dismiss',
            { duration: 5000 },
          );
          this.loadQueue();
        },
        error: (err) => {
          this.snackBar.open(
            err?.error?.message ?? 'Screening failed.',
            'Dismiss',
            { duration: 6000 },
          );
        },
      });
    }
  }
}
