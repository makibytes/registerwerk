import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { FinalityJournalService } from '../../../core/api/finality-journal.service';
import { ChainEffectView } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import { AuthService } from '../../../core/auth/auth.service';
import { StepUpDialogComponent, StepUpDialogResult } from '../../../shared/components/step-up/step-up-dialog.component';

/**
 * The operator "unresolved compensation" / reorg-impact queue — every {@code chain_effect} row a
 * reorg-driven compensation failed on, or that was escalated as irreversible. While a row here is
 * unacknowledged, {@code FinalityGate} blocks every gated operation on the affected asset,
 * regardless of that operation's own required finality level — see
 * {@code FinalityGateImpl}'s javadoc. Modelled on {@code ChainDriftQueueComponent}.
 */
@Component({
  selector: 'app-unresolved-compensation-queue',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
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
  template: `
    <app-page-header
      title="Unresolved Compensation"
      subtitle="Reorg-caused effects that failed to auto-correct or were escalated as irreversible — blocks every gated action on the affected asset until acknowledged">
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="effects"
      [state]="state"
      (retry)="load()"
      filterPlaceholder="Filter by effect type, entity, asset…"
      emptyMessage="No unresolved compensations. Every reorg-caused effect has been undone cleanly."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-effect>
      @if (!effect.acknowledgedAt) {
        <div class="action-row">
          <button mat-stroked-button (click)="retry(effect)" [disabled]="!canManage">
            <mat-icon>replay</mat-icon>
            Retry
          </button>
          <button mat-stroked-button color="primary" (click)="openAcknowledgeDialog(effect)" [disabled]="!canManage">
            <mat-icon>fact_check</mat-icon>
            Acknowledge
          </button>
        </div>
      } @else {
        <span class="ack-note" [matTooltip]="effect.acknowledgeReason ?? ''">
          Acknowledged {{ effect.acknowledgedAt | date:'short' }}
        </span>
      }
    </ng-template>

    <ng-template #acknowledgeDialogTpl>
      <h2 mat-dialog-title>Acknowledge Unresolved Compensation</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:460px">
        @if (selected) {
          <div class="effect-summary">
            <div><span class="label">Effect type</span>{{ selected.effectType }}</div>
            <div><span class="label">Entity</span>{{ selected.entityType }} / <code>{{ selected.entityId }}</code></div>
            <div><span class="label">Status</span>{{ selected.status }}</div>
            <div><span class="label">Attempts</span>{{ selected.attemptCount }}</div>
            @if (selected.resolutionDetail) {
              <div class="last-error"><span class="label">Resolution detail</span>{{ selected.resolutionDetail }}</div>
            }
          </div>
        }
        <div class="warn-box">
          This does not undo anything or change the compensation outcome — it records that an
          administrator has reviewed this and accepts proceeding despite it. Requires step-up
          authentication.
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Reason (required for audit trail)</mat-label>
          <textarea matInput rows="3" [(ngModel)]="acknowledgeReason"
            placeholder="Why it's acceptable to unblock this asset despite the unresolved effect."></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" [disabled]="!acknowledgeReason.trim()" (click)="submitAcknowledge()">
          <mat-icon>fact_check</mat-icon>
          Acknowledge
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .action-row { display: flex; gap: 6px; }
    .ack-note {
      font-size: 12px;
      color: var(--rw-text-secondary);
      cursor: help;
    }
    .effect-summary {
      display: grid;
      grid-template-columns: 1fr;
      gap: 6px 16px;
      padding: 12px;
      border-radius: 8px;
      background: var(--rw-surface-soft, rgba(0,0,0,0.03));
      font-size: 13px;

      .label {
        display: block;
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.4px;
        color: var(--rw-text-secondary);
      }
      .last-error { color: #dc2626; }
    }
    .warn-box {
      font-size: 12px;
      color: var(--rw-text-secondary);
      padding: 10px 12px;
      background: rgba(245,158,11,0.08);
      border-radius: 6px;
      border: 1px solid rgba(245,158,11,0.2);
    }
  `],
})
export class UnresolvedCompensationQueueComponent implements OnInit {
  @ViewChild('acknowledgeDialogTpl') acknowledgeDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(FinalityJournalService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auth = inject(AuthService);

  readonly canManage = this.auth.hasRole('REGISTRY_ADMIN');

  effects: ChainEffectView[] = [];
  state: AsyncSectionStatus = 'pending';
  selected: ChainEffectView | null = null;
  acknowledgeReason = '';

  readonly columns: TableColumn[] = [
    { key: 'status', header: 'Status', cell: (e: ChainEffectView) => e.status, type: 'badge' },
    { key: 'effectType', header: 'Effect Type', cell: (e: ChainEffectView) => e.effectType },
    { key: 'entityType', header: 'Entity', cell: (e: ChainEffectView) => `${e.entityType} / ${e.entityId}`, type: 'mono' },
    { key: 'assetId', header: 'Asset', cell: (e: ChainEffectView) => e.assetId ?? '—', type: 'mono' },
    { key: 'attemptCount', header: 'Attempts', cell: (e: ChainEffectView) => String(e.attemptCount), type: 'number' },
    { key: 'recordedAt', header: 'Recorded', cell: (e: ChainEffectView) => e.recordedAt, type: 'date' },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.service.listUnresolved().subscribe({
      next: (effects) => {
        this.effects = effects;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  retry(effect: ChainEffectView): void {
    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: false,
        reason: `Retry compensation for ${effect.effectType} (${effect.entityType} ${effect.entityId})`,
        action: 'CHAIN_EFFECT_RETRY',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken) return;

      this.service.retry(effect.id, result.stepUpToken).subscribe({
        next: (outcome) => {
          this.snackBar.open(`Retry result: ${outcome.outcome}${outcome.detail ? ' — ' + outcome.detail : ''}`,
            'Dismiss', { duration: 6000 });
          this.load();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Retry failed.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  openAcknowledgeDialog(effect: ChainEffectView): void {
    this.selected = effect;
    this.acknowledgeReason = '';
    this.dialog.open(this.acknowledgeDialogTpl, { width: '520px' });
  }

  submitAcknowledge(): void {
    if (!this.selected || !this.acknowledgeReason.trim()) return;
    const effect = this.selected;
    const reason = this.acknowledgeReason.trim();
    this.dialog.closeAll();

    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: false,
        reason: `Acknowledge unresolved compensation for ${effect.effectType} (${effect.entityType} ${effect.entityId})`,
        action: 'CHAIN_EFFECT_ACKNOWLEDGE',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken) return;

      this.service.acknowledge(effect.id, reason, result.stepUpToken).subscribe({
        next: () => {
          this.snackBar.open('Acknowledged. The asset is no longer blocked by this effect.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to acknowledge.', 'Dismiss', { duration: 6000 }),
      });
    });
  }
}
