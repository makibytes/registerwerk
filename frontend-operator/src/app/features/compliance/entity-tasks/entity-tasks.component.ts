import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { EntityService, EntityTask } from '../../../core/api/entity.service';
import { KycService } from '../../../core/api/kyc.service';
import { AsyncSectionStatus } from '../../../core/async/async-section';

interface EvidenceGap {
  entityId: string;
  entityName: string;
  kycExpiryDate: string | null;
  gaps: string[];
}

const KIND_LABEL: Record<string, string> = {
  KYC_REVIEW_REQUIRED: 'KYC review required',
  CHAIN_REINSTATEMENT_REQUIRED: 'On-chain reinstatement required',
  SPERRVERMERK_EXPIRY_REVIEW: 'Sperrvermerk expired - confirm lift or extend',
};

/**
 * Operator follow-up queue: tasks raised by entity lifecycle events (offboarding obligations, risk-data changes
 * that need a KYC review, reactivation that needs a chain reinstatement, an expired Sperrvermerk), plus approved
 * entities whose KYC evidence would not pass today's approval checks.
 */
@Component({
  selector: 'app-entity-tasks',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, DataTableComponent, PageHeaderComponent],
  styles: [`
    h3 { margin: 28px 0 8px; font-size: 15px; font-weight: 600; }
    .hint { font-size: 12px; color: var(--rw-text-secondary); margin: 0 0 12px; }
  `],
  template: `
    <app-page-header title="Entity Tasks" subtitle="Open operator follow-ups from entity lifecycle, KYC and Sperrvermerk events" />

    <rw-data-table
      [columns]="columns"
      [rows]="tasks"
      [state]="state"
      (retry)="load()"
      filterPlaceholder="Filter tasks…"
      emptyMessage="No open tasks."
      [actionsTemplate]="taskActions">
    </rw-data-table>

    <ng-template #taskActions let-task>
      <a mat-icon-button [routerLink]="['/customers', task.entityId]" matTooltip="Open the customer">
        <mat-icon>open_in_new</mat-icon>
      </a>
      <button type="button" mat-stroked-button color="primary" (click)="complete(task)">
        <mat-icon>done</mat-icon>
        Mark done
      </button>
    </ng-template>

    <h3>KYC evidence gaps</h3>
    <p class="hint">Approved entities that would not pass today's approval checks. Existing approvals are not downgraded; review at the next KYC renewal.</p>
    <rw-data-table
      [columns]="gapColumns"
      [rows]="gaps"
      [state]="gapState"
      (retry)="load()"
      filterPlaceholder="Filter entities…"
      emptyMessage="No evidence gaps."
      [actionsTemplate]="gapActions">
    </rw-data-table>

    <ng-template #gapActions let-gap>
      <a mat-icon-button [routerLink]="['/customers', gap.entityId]" matTooltip="Open the customer">
        <mat-icon>open_in_new</mat-icon>
      </a>
    </ng-template>
  `,
})
export class EntityTasksComponent implements OnInit {
  private readonly entityService = inject(EntityService);
  private readonly kycService = inject(KycService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  tasks: EntityTask[] = [];
  gaps: EvidenceGap[] = [];
  state: AsyncSectionStatus = 'pending';
  gapState: AsyncSectionStatus = 'pending';

  readonly columns: TableColumn[] = [
    { key: 'kind', header: 'Task', cell: (t: EntityTask) => KIND_LABEL[t.kind] ?? t.kind.replace(/_/g, ' ').toLowerCase() },
    { key: 'entityId', header: 'Entity', cell: (t: EntityTask) => t.entityId, type: 'mono' },
    { key: 'detail', header: 'Detail', cell: (t: EntityTask) => t.detail ?? t.refId ?? '—' },
    { key: 'createdAt', header: 'Raised', cell: (t: EntityTask) => t.createdAt, type: 'date' },
  ];

  readonly gapColumns: TableColumn[] = [
    { key: 'entityName', header: 'Entity', cell: (g: EvidenceGap) => g.entityName },
    { key: 'kycExpiryDate', header: 'KYC expiry', cell: (g: EvidenceGap) => g.kycExpiryDate, type: 'date' },
    { key: 'gaps', header: 'Gaps', cell: (g: EvidenceGap) => g.gaps.join(', ') },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.gapState = 'pending';
    this.cdr.markForCheck();
    this.entityService.listOpenTasks().subscribe({
      next: (tasks) => { this.tasks = tasks; this.state = 'ready'; this.cdr.markForCheck(); },
      error: () => { this.state = 'error'; this.cdr.markForCheck(); },
    });
    this.kycService.listEvidenceGaps().subscribe({
      next: (gaps) => { this.gaps = gaps as EvidenceGap[]; this.gapState = 'ready'; this.cdr.markForCheck(); },
      error: () => { this.gapState = 'error'; this.cdr.markForCheck(); },
    });
  }

  complete(task: EntityTask): void {
    const note = prompt('Completion note (what was done; required for the audit trail):');
    if (!note || !note.trim()) return;
    this.entityService.completeTask(task.id, note.trim()).subscribe({
      next: () => {
        this.snackBar.open('Task completed.', 'Dismiss', { duration: 4000 });
        this.tasks = this.tasks.filter(t => t.id !== task.id);
        this.cdr.markForCheck();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to complete the task.', 'Dismiss', { duration: 6000 }),
    });
  }
}
