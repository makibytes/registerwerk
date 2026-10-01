import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';

import { DataTableComponent, PageHeaderComponent, TableColumn } from '@registerwerk/ui';
import {
  RepoAdminService,
  RepoDispute,
  RepoDisputeResolution,
  allowedDisputeResolutions,
} from '../../core/api/repo-admin.service';
import { AsyncSectionStatus } from '../../core/async/async-section';
import { StepUpDialogComponent, StepUpDialogResult } from '../../shared/components/step-up/step-up-dialog.component';

const RESOLUTION_COPY: Record<RepoDisputeResolution, { label: string; explanation: string }> = {
  RESUME: {
    label: 'Resume the trade',
    explanation: 'The freeze is lifted and the trade returns to the state it was in before the dispute.',
  },
  CLOSE: {
    label: 'Record as closed',
    explanation: 'Only for a dispute raised during closing: the parties agreed the trade is closed.',
  },
  CANCEL: {
    label: 'Record as cancelled',
    explanation: 'Only for a dispute raised before opening: the parties agreed the trade never settled.',
  },
};

/**
 * Operator queue for disputed repo trades. The operator does not decide who is right: it records the parties'
 * agreed outcome (resume, close or cancel) under a legal basis. Needs step-up and a second approver
 * (`REPO_DISPUTE_RESOLVE`); the trade stays frozen until then.
 */
@Component({
  selector: 'app-repo-disputes',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    DecimalPipe,
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  template: `
    <app-page-header
      title="Repo Desk: Disputes"
      subtitle="Repo trades frozen by a party. Registerwerk records an outcome under a legal basis; it does not judge the dispute.">
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="rows"
      [state]="state"
      (retry)="load()"
      filterPlaceholder="Filter by party, collateral, reason…"
      emptyMessage="No disputed repo trades."
      [actionsTemplate]="actions">
    </rw-data-table>

    <ng-template #actions let-row>
      <button type="button" mat-stroked-button color="primary" (click)="openResolve(row.source)">Record outcome</button>
    </ng-template>

    <ng-template #resolveDialogTpl>
      <h2 mat-dialog-title>Record dispute outcome</h2>
      <mat-dialog-content class="dialog-body">
        @if (selected; as d) {
          <div class="summary">
            <div><span class="label">Parties</span>Borrower {{ d.borrower }} / Lender {{ d.lender }}</div>
            <div><span class="label">Collateral</span>{{ d.collateralQuantity | number:'1.0-4' }} x {{ d.collateral }}{{ d.isin ? ' (' + d.isin + ')' : '' }}</div>
            <div><span class="label">Cash / repurchase</span>{{ d.cashAmount | number:'1.2-2' }} / {{ d.repurchaseAmount | number:'1.2-2' }} {{ d.currency }}</div>
            <div><span class="label">Frozen in state</span>{{ label(d.preDisputeStatus) }} since {{ d.disputedAt | date:'medium' }}</div>
            <div><span class="label">Dispute reason</span>{{ d.disputeReason ?? '—' }}</div>
          </div>
          @if (d.events.length > 0) {
            <details class="events">
              <summary>Lifecycle ({{ d.events.length }} events)</summary>
              @for (e of d.events; track $index) {
                <div class="event">
                  <strong>{{ label(e.type) }}</strong>
                  <span>{{ e.actor ?? 'Operator / system' }} · {{ e.at | date:'short' }}</span>
                  @if (e.reference) { <span>Reference: {{ e.reference }}</span> }
                  @if (e.note) { <span>{{ e.note }}</span> }
                </div>
              }
            </details>
          }
          <mat-form-field appearance="outline">
            <mat-label>Outcome</mat-label>
            <mat-select [(ngModel)]="resolution">
              @for (option of allowed(d); track option) {
                <mat-option [value]="option">{{ copy[option].label }}</mat-option>
              }
            </mat-select>
            <mat-hint>{{ copy[resolution].explanation }}</mat-hint>
          </mat-form-field>
        }
        <div class="note-box">
          This records the outcome the parties agreed; it is not a ruling. It needs step-up and a second approver and is
          written to the audit trail. No register entry or payment is moved.
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Legal basis (required)</mat-label>
          <textarea matInput rows="2" maxlength="500" [(ngModel)]="legalBasis"
                    placeholder="Evidence and instruction that justify recording this outcome"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Note (optional)</mat-label>
          <textarea matInput rows="2" maxlength="2000" [(ngModel)]="note"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary" [disabled]="!legalBasis.trim()" (click)="submit()">Continue</button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .dialog-body { display: flex; flex-direction: column; gap: 12px; padding-top: 8px; min-width: 460px; max-width: 100%; }
    .summary { display: grid; gap: 6px; padding: 12px; border-radius: 8px; background: var(--rw-surface-soft); font-size: 13px; }
    .summary .label { display: block; font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px; color: var(--rw-text-secondary); }
    .events { font-size: 12.5px; }
    .events summary { cursor: pointer; color: var(--rw-text-secondary); }
    .event { display: flex; flex-direction: column; padding: 6px 0; border-bottom: 1px solid var(--rw-border-subtle); }
    .event span { color: var(--rw-text-secondary); }
    .note-box { font-size: 12px; color: var(--rw-text-secondary); padding: 10px 12px; border-radius: 6px; border: 1px solid var(--rw-border); }
    @media (max-width: 620px) { .dialog-body { min-width: 0; } }
  `],
})
export class RepoDisputesComponent implements OnInit {
  @ViewChild('resolveDialogTpl') resolveDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(RepoAdminService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly copy = RESOLUTION_COPY;
  state: AsyncSectionStatus = 'pending';
  rows: (Record<string, unknown> & { source: RepoDispute })[] = [];
  selected: RepoDispute | null = null;
  resolution: RepoDisputeResolution = 'RESUME';
  legalBasis = '';
  note = '';

  readonly columns: TableColumn[] = [
    { key: 'disputedAt', header: 'Disputed', cell: (r) => (r['source'].disputedAt ? new Date(r['source'].disputedAt).toLocaleString() : '—') },
    { key: 'parties', header: 'Borrower / lender', cell: (r) => `${r['source'].borrower} / ${r['source'].lender}` },
    { key: 'collateral', header: 'Collateral', cell: (r) => `${r['source'].collateralQuantity} x ${r['source'].collateral}` },
    { key: 'cash', header: 'Cash', cell: (r) => `${r['source'].cashAmount} ${r['source'].currency}` },
    { key: 'from', header: 'Frozen in', cell: (r) => this.label(r['source'].preDisputeStatus) },
    { key: 'reason', header: 'Dispute reason', cell: (r) => r['source'].disputeReason ?? '—' },
    { key: 'id', header: 'Trade', cell: (r) => r['source'].id, type: 'mono' },
  ];

  ngOnInit(): void {
    this.load();
  }

  label(value: string | null): string {
    return value ? value.toLowerCase().split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ') : '—';
  }

  allowed(d: RepoDispute): RepoDisputeResolution[] {
    return allowedDisputeResolutions(d.preDisputeStatus);
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();
    this.service.listDisputes().subscribe({
      next: (items) => {
        this.rows = items.map((source) => ({ source }));
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openResolve(dispute: RepoDispute): void {
    this.selected = dispute;
    this.resolution = 'RESUME';
    this.legalBasis = '';
    this.note = '';
    this.dialog.open(this.resolveDialogTpl, { width: '600px', maxWidth: '95vw' });
  }

  submit(): void {
    const dispute = this.selected;
    const legalBasis = this.legalBasis.trim();
    if (!dispute || !legalBasis) return;
    const resolution = this.resolution;
    const note = this.note.trim() || null;
    this.dialog.closeAll();
    this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Record repo dispute outcome: ${RESOLUTION_COPY[resolution].label} (trade ${dispute.id})`,
        action: 'REPO_DISPUTE_RESOLVE',
        target: `POST /api/v1/admin/repo/disputes/${dispute.id}/resolve`,
      },
      width: '500px',
      disableClose: true,
    }).afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken || !result.dualControlToken) return;
      this.service.resolve(dispute.id, { resolution, legalBasis, note }, {
        stepUpToken: result.stepUpToken, dualControlToken: result.dualControlToken,
      }).subscribe({
        next: () => {
          this.snackBar.open('Dispute outcome recorded.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Recording failed. Nothing was changed.', 'Dismiss', { duration: 8000 }),
      });
    });
  }
}
