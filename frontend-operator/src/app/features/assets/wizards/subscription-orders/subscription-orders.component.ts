import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DecimalPipe } from '@angular/common';
import { SubscriptionOrderService } from '../../../../core/api/subscription-order.service';
import { SubscriptionOrder } from '../../../../core/models';

type DecisionMode = 'allocate' | 'reject';

/**
 * Issuer/operator queue for primary-market subscription orders — submit → allocate → confirm
 * (investor-side) / reject / cancel. Scoped per-asset, matching `SubscriptionOrderController`'s
 * `GET /assets/{assetId}/orders`.
 */
@Component({
  selector: 'app-subscription-orders',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatTooltipModule, DecimalPipe],
  template: `
    <div class="so-shell">
      <div class="so-header">
        <h3 class="so-title">Subscription orders</h3>
        <button mat-stroked-button (click)="load()">
          <mat-icon>refresh</mat-icon> Refresh
        </button>
      </div>

      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:24px">Loading…</p>
      } @else if (orders.length === 0) {
        <div class="empty-state">
          <mat-icon class="empty-icon">receipt_long</mat-icon>
          <p>No subscription orders for this asset yet.</p>
        </div>
      } @else {
        <div class="so-table">
          <div class="so-row header">
            <span>Investor</span>
            <span>Wallet</span>
            <span>Requested</span>
            <span>Allocated</span>
            <span>Status</span>
            <span></span>
          </div>

          @for (o of orders; track o.id) {
            <div class="so-row">
              <span class="dimmed small">{{ o.investorEntityId }}</span>
              <span class="dimmed small mono">{{ o.walletAddress }}</span>
              <span>{{ o.requestedAmount | number:'1.0-2' }}</span>
              <span>{{ o.allocatedAmount !== null ? (o.allocatedAmount | number:'1.0-2') : '—' }}</span>
              <span class="status-badge" [class]="o.status.toLowerCase()">{{ o.status }}</span>
              <div class="row-actions">
                @if (o.status === 'SUBMITTED') {
                  <button mat-icon-button color="primary" matTooltip="Allocate" (click)="openDecisionDialog(o, 'allocate')">
                    <mat-icon>check_circle</mat-icon>
                  </button>
                  <button mat-icon-button color="warn" matTooltip="Reject" (click)="openDecisionDialog(o, 'reject')">
                    <mat-icon>cancel</mat-icon>
                  </button>
                }
                @if (o.status === 'REJECTED' && o.rejectionReason) {
                  <span class="dimmed small" [matTooltip]="o.rejectionReason">Reason ⓘ</span>
                }
              </div>
            </div>
          }
        </div>
      }
    </div>

    <ng-template #decisionDialogTpl>
      <h2 mat-dialog-title>{{ decisionMode === 'allocate' ? 'Allocate' : 'Reject' }} Order</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:400px">
        @if (decisionMode === 'allocate') {
          <p class="dimmed small" style="margin:0">
            Requested: {{ activeOrder?.requestedAmount | number:'1.0-2' }}. Allocation may be scaled
            down (partial allotment) but cannot exceed the requested amount or the asset's issue size.
          </p>
          <mat-form-field appearance="outline">
            <mat-label>Allocated amount</mat-label>
            <input matInput type="number" min="0" [(ngModel)]="allocatedAmount">
          </mat-form-field>
        } @else {
          <mat-form-field appearance="outline">
            <mat-label>Rejection reason</mat-label>
            <textarea matInput rows="3" [(ngModel)]="rejectionReason" placeholder="e.g. KYC not yet approved"></textarea>
          </mat-form-field>
        }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button [color]="decisionMode === 'allocate' ? 'primary' : 'warn'"
                [disabled]="decisionMode === 'allocate' ? !allocatedAmount || allocatedAmount <= 0 : !rejectionReason.trim()"
                (click)="submitDecision()">
          {{ decisionMode === 'allocate' ? 'Allocate' : 'Reject' }}
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    :host { display: block; }
    .so-shell { padding: 1.5rem 0; }
    .so-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.25rem; }
    .so-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .empty-state { display: flex; flex-direction: column; align-items: center; padding: 3rem 0; color: var(--rw-text-secondary); }
    .empty-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; margin-bottom: .75rem; opacity: .6; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }
    .mono { font-family: 'IBM Plex Mono', 'Courier New', monospace; }

    .so-table { display: flex; flex-direction: column; }
    .so-row {
      display: grid;
      grid-template-columns: 1fr 1.4fr 110px 110px 120px 90px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border);
      font-size: .8125rem;
    }
    .so-row.header {
      font-size: .6875rem;
      letter-spacing: .06em;
      text-transform: uppercase;
      color: var(--rw-text-muted);
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
    .status-badge.submitted { background: rgba(245,158,11,.15); color: #f59e0b; }
    .status-badge.allocated { background: rgba(96,165,250,.15); color: #60a5fa; }
    .status-badge.confirmed { background: rgba(74,222,128,.15); color: #4ade80; }
    .status-badge.rejected  { background: rgba(248,113,113,.15); color: #f87171; }
    .status-badge.cancelled { background: rgba(148,163,184,.15); color: #94a3b8; }

    .row-actions { display: flex; justify-content: flex-end; gap: 4px; align-items: center; }
  `],
})
export class SubscriptionOrdersComponent implements OnInit {
  @Input() assetId!: string;
  @ViewChild('decisionDialogTpl') decisionDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(SubscriptionOrderService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  orders: SubscriptionOrder[] = [];
  loading = false;

  activeOrder: SubscriptionOrder | null = null;
  decisionMode: DecisionMode = 'allocate';
  allocatedAmount: number | null = null;
  rejectionReason = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.service.listForAsset(this.assetId, 0, 100).subscribe({
      next: (page) => {
        this.orders = page.content;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  openDecisionDialog(order: SubscriptionOrder, mode: DecisionMode): void {
    this.activeOrder = order;
    this.decisionMode = mode;
    this.allocatedAmount = order.requestedAmount;
    this.rejectionReason = '';
    this.dialog.open(this.decisionDialogTpl, { width: '480px' });
  }

  submitDecision(): void {
    const order = this.activeOrder;
    if (!order) return;

    if (this.decisionMode === 'allocate') {
      if (!this.allocatedAmount || this.allocatedAmount <= 0) return;
      this.dialog.closeAll();
      this.service.allocate(order.id, this.allocatedAmount).subscribe({
        next: () => {
          this.snackBar.open('Order allocated.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to allocate order.', 'Dismiss', { duration: 6000 });
        },
      });
    } else {
      const reason = this.rejectionReason.trim();
      if (!reason) return;
      this.dialog.closeAll();
      this.service.reject(order.id, reason).subscribe({
        next: () => {
          this.snackBar.open('Order rejected.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'Failed to reject order.', 'Dismiss', { duration: 6000 });
        },
      });
    }
  }
}
