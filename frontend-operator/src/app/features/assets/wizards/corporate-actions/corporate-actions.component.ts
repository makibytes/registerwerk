import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, inject
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, DecimalPipe, SlicePipe } from '@angular/common';
import { CorporateActionsService } from '../../../../core/api/corporate-actions.service';
import { CorporateAction } from '../../../../core/models';
import { StepUpDialogComponent } from '../../../../shared/components/step-up/step-up-dialog.component';

/**
 * Operator view of the automated corporate-action lifecycle (coupon, dividend, split,
 * redemption, call, …). Actions are announced and advanced automatically by the backend's
 * scheduled job; this screen surfaces the ones stuck at AWAITING_SETTLEMENT so an operator
 * can record the required dual-control (Vieraugenprinzip) sign-off before the next
 * scheduled run actually settles them.
 */
@Component({
  selector: 'app-corporate-actions',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatDialogModule, MatTooltipModule, DatePipe, DecimalPipe, SlicePipe],
  template: `
    <div class="ca-shell">
      <div class="ca-header">
        <h3 class="ca-title">Corporate actions</h3>
        <button mat-stroked-button (click)="load()">
          <mat-icon>refresh</mat-icon> Refresh
        </button>
      </div>

      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:24px">Loading…</p>
      } @else if (actions.length === 0) {
        <div class="empty-state">
          <mat-icon class="empty-icon">event_available</mat-icon>
          <p>No corporate actions for this asset yet.</p>
        </div>
      } @else {
        <div class="ca-table">
          <div class="ca-row header">
            <span>Type</span>
            <span>Status</span>
            <span>Payment date</span>
            <span class="right">Amount / unit</span>
            <span>Settlement</span>
            <span></span>
          </div>

          @for (a of actions; track a.id) {
            <div class="ca-row">
              <span class="type-badge">{{ a.actionType.replace('_', ' ') }}</span>
              <span class="status-badge" [class]="a.status.toLowerCase()">{{ a.status.replace('_', ' ') }}</span>
              <span class="dimmed">{{ a.paymentDate ? (a.paymentDate | date:'dd MMM yyyy') : '—' }}</span>
              <span class="right mono">
                {{ a.amountPerUnit != null ? (a.amountPerUnit | number:'1.0-8') + ' ' + (a.currency ?? '') : '—' }}
              </span>
              <span class="dimmed">
                @if (a.status === 'SETTLED' || a.status === 'CLOSED') {
                  <span class="mono small">{{ a.settlementTxHash ? (a.settlementTxHash | slice:0:10) + '…' : 'off-chain' }}</span>
                } @else if (a.dualControlApproverId) {
                  Dual-control approved — awaiting next settlement run
                } @else {
                  Awaiting dual-control approval
                }
              </span>
              <div class="row-actions">
                @if (a.status === 'AWAITING_SETTLEMENT' && !a.dualControlApproverId) {
                  <button mat-stroked-button color="warn" [disabled]="approving.has(a.id)"
                          matTooltip="Requires step-up auth + a second approver (Vieraugenprinzip)"
                          (click)="approveSettlement(a)">
                    <mat-icon>gavel</mat-icon>
                    Approve settlement
                  </button>
                }
              </div>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }
    .ca-shell { padding: 1.5rem 0; }
    .ca-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.25rem; }
    .ca-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .empty-state { display: flex; flex-direction: column; align-items: center; padding: 3rem 0; color: var(--rw-text-secondary); }
    .empty-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; margin-bottom: .75rem; opacity: .6; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }
    .mono { font-family: 'IBM Plex Mono', monospace; }
    .right { text-align: right; }

    .ca-table { display: flex; flex-direction: column; }
    .ca-row {
      display: grid;
      grid-template-columns: 130px 160px 120px 140px 1fr 180px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border);
      font-size: .8125rem;
    }
    .ca-row.header {
      font-size: .6875rem;
      letter-spacing: .06em;
      text-transform: uppercase;
      color: var(--rw-text-muted);
    }

    .type-badge {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .6875rem;
      font-weight: 700;
      letter-spacing: .04em;
    }

    .status-badge {
      display: inline-flex;
      align-items: center;
      padding: .125rem .5rem;
      border-radius: 3px;
      font-size: .6875rem;
      font-weight: 700;
      width: fit-content;
    }
    .status-badge.announced, .status-badge.record_date_set, .status-badge.computed {
      background: rgba(148,163,184,.15); color: #94a3b8;
    }
    .status-badge.awaiting_settlement { background: rgba(245,158,11,.15); color: #f59e0b; }
    .status-badge.settled, .status-badge.closed { background: rgba(74,222,128,.15); color: #4ade80; }
    .status-badge.cancelled { background: rgba(248,113,113,.15); color: #f87171; }

    .row-actions { display: flex; justify-content: flex-end; }
  `],
})
export class CorporateActionsComponent implements OnInit {
  @Input() assetId!: string;

  private readonly corporateActionsService = inject(CorporateActionsService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  actions: CorporateAction[] = [];
  loading = false;
  approving = new Set<string>();

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.corporateActionsService.listForAsset(this.assetId).subscribe({
      next: (actions) => {
        this.actions = actions;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  approveSettlement(action: CorporateAction): void {
    const dialogRef = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Approve settlement of ${action.actionType} corporate action (${action.id})`,
        action: 'CORPORATE_ACTION_SETTLEMENT_APPROVAL',
      },
      width: '500px',
      disableClose: true,
    });

    dialogRef.afterClosed().subscribe((result) => {
      if (!result) return; // cancelled

      this.approving.add(action.id);
      this.corporateActionsService.approveSettlement(
        action.id,
        result.stepUpToken,
        result.dualControlToken,
      ).subscribe({
        next: () => {
          this.snackBar.open('Settlement approved. It will settle on the next scheduled run.', 'Dismiss', { duration: 6000 });
          this.approving.delete(action.id);
          this.load();
        },
        error: (err) => {
          this.snackBar.open(
            err?.error?.message ?? 'Failed to approve settlement. Check step-up authentication.',
            'Dismiss',
            { duration: 8000 },
          );
          this.approving.delete(action.id);
          this.cdr.markForCheck();
        },
      });
    });
  }
}
