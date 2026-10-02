import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { fetchAllPages } from '../../../../core/utils/paging.util';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, DecimalPipe } from '@angular/common';
import { StepUpDialogComponent, StepUpDialogResult } from '../../../../shared/components/step-up/step-up-dialog.component';
import { SubscriptionOrderService } from '../../../../core/api/subscription-order.service';
import { SubscriptionOrder } from '../../../../core/models';

type DecisionMode = 'allocate' | 'reject' | 'payment' | 'release';

/**
 * Issuer/operator queue for primary-market subscription orders — submit → allocate → investor
 * accepts → confirm payment → settle (enters the register / mints), with reject / release / lapse as
 * exits. Scoped per-asset, matching `SubscriptionOrderController`'s `GET /assets/{assetId}/orders`.
 */
@Component({
  selector: 'app-subscription-orders',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatTooltipModule, DecimalPipe, DatePipe],
  template: `
    <div class="so-shell">
      <div class="so-header">
        <h3 class="so-title">Subscription orders</h3>
        <button type="button" mat-stroked-button (click)="load()">
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
        @if (ordersTruncated) {
          <p class="dimmed" role="status">Showing {{ orders.length }} of {{ ordersTotal }} orders (newest first).</p>
        }
        <div class="so-table">
          <div class="so-row header">
            <span>Investor</span>
            <span>Wallet</span>
            <span>Requested</span>
            <span>Allocated</span>
            <span>Due / refund</span>
            <span>Status</span>
            <span></span>
          </div>

          @for (o of orders; track o.id) {
            <div class="so-row">
              <span class="dimmed small">{{ o.investorEntityId }}</span>
              <span class="dimmed small mono">{{ o.walletAddress }}</span>
              <span>{{ o.requestedAmount | number:'1.0-2' }}</span>
              <span>{{ o.allocatedAmount !== null ? (o.allocatedAmount | number:'1.0-2') : '—' }}</span>
              <span class="dimmed small">
                @if (o.amountDue !== null) { {{ o.amountDue | number:'1.2-2' }} {{ o.paymentCurrency }} }
                @if (o.refundDue && o.refundDue > 0) { <br><strong class="refund">refund {{ o.refundDue | number:'1.2-2' }}</strong> }
                @if (o.amountDue === null && !(o.refundDue && o.refundDue > 0)) { — }
              </span>
              <span class="status-badge" [class]="o.status.toLowerCase()">
                {{ statusLabel(o) }}
              </span>
              <div class="row-actions">
                @if (o.status === 'SUBMITTED') {
                  <button type="button" mat-icon-button color="primary" matTooltip="Allocate" (click)="openDecisionDialog(o, 'allocate')">
                    <mat-icon>check_circle</mat-icon>
                  </button>
                  <button type="button" mat-icon-button color="warn" matTooltip="Reject" (click)="openDecisionDialog(o, 'reject')">
                    <mat-icon>cancel</mat-icon>
                  </button>
                }
                @if (o.status === 'ALLOCATED') {
                  <button type="button" mat-icon-button color="primary" matTooltip="Confirm payment received"
                          [disabled]="!o.acceptedAt" (click)="openDecisionDialog(o, 'payment')">
                    <mat-icon>payments</mat-icon>
                  </button>
                  <button type="button" mat-icon-button color="warn" matTooltip="Release allocation" (click)="openDecisionDialog(o, 'release')">
                    <mat-icon>undo</mat-icon>
                  </button>
                  <span class="dimmed small" [matTooltip]="o.acceptedAt ? 'Investor accepted' : 'Waiting for the investor to accept'">
                    {{ o.acceptedAt ? 'accepted' : 'not accepted' }}
                    @if (o.allocationExpiresAt) { · until {{ o.allocationExpiresAt | date:'shortDate' }} }
                  </span>
                }
                @if (o.status === 'PAYMENT_CONFIRMED') {
                  <button type="button" mat-icon-button color="primary" matTooltip="Settle: enter on the register (mint if deployed)" (click)="settle(o)">
                    <mat-icon>verified</mat-icon>
                  </button>
                  <button type="button" mat-icon-button color="warn" matTooltip="Release and mark payment for refund" (click)="openDecisionDialog(o, 'release')">
                    <mat-icon>undo</mat-icon>
                  </button>
                }
                @if (o.status === 'SETTLED' && o.settlementTxId) {
                  <span class="dimmed small" matTooltip="Mint transaction id">mint {{ o.settlementTxId.slice(0, 8) }}</span>
                }
                @if (o.status === 'RELEASED' && o.releaseReason) {
                  <span class="dimmed small" [matTooltip]="o.releaseReason">Reason ⓘ</span>
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
      <h2 mat-dialog-title>{{ dialogTitle() }}</h2>
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
        } @else if (decisionMode === 'payment') {
          <p class="dimmed small" style="margin:0">
            Allocated {{ activeOrder?.allocatedAmount | number:'1.0-2' }}.
            @if (activeOrder?.amountDue !== null) {
              Amount due: {{ activeOrder?.amountDue | number:'1.2-2' }} {{ activeOrder?.paymentCurrency }}. Underpayment is refused;
              an overpayment is accepted and shown as refund due.
            } @else {
              No bond terms: the amount you enter is recorded as the amount due.
            }
          </p>
          <mat-form-field appearance="outline">
            <mat-label>Amount received</mat-label>
            <input matInput type="number" min="0" [(ngModel)]="paidAmount">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Payment reference</mat-label>
            <input matInput [(ngModel)]="paymentReference">
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Value date (optional)</mat-label>
            <input matInput type="date" [(ngModel)]="valueDate">
          </mat-form-field>
        } @else if (decisionMode === 'release') {
          <p class="dimmed small" style="margin:0">
            Gives the allocation back and frees its capacity.
            @if (activeOrder?.status === 'PAYMENT_CONFIRMED') { The received payment is marked as refund due. }
          </p>
          <mat-form-field appearance="outline">
            <mat-label>Reason</mat-label>
            <textarea matInput rows="3" [(ngModel)]="rejectionReason"></textarea>
          </mat-form-field>
        } @else {
          <mat-form-field appearance="outline">
            <mat-label>Rejection reason</mat-label>
            <textarea matInput rows="3" [(ngModel)]="rejectionReason" placeholder="e.g. KYC not yet approved"></textarea>
          </mat-form-field>
        }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button [color]="decisionMode === 'allocate' || decisionMode === 'payment' ? 'primary' : 'warn'"
                [disabled]="!decisionValid()"
                (click)="submitDecision()">
          {{ dialogTitle() }}
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
      grid-template-columns: 1fr 1.3fr 90px 90px 120px 130px minmax(150px, 1.2fr);
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
    .status-badge.submitted { background: var(--rw-pending-bg);  color: var(--rw-pending-fg); }
    .status-badge.allocated { background: var(--rw-draft-bg);    color: var(--rw-draft-fg); }
    .status-badge.confirmed { background: var(--rw-approved-bg); color: var(--rw-approved-fg); }
    .status-badge.payment_confirmed { background: var(--rw-issued-bg); color: var(--rw-issued-fg); }
    .status-badge.settled   { background: var(--rw-approved-bg); color: var(--rw-approved-fg); }
    .status-badge.lapsed    { background: var(--rw-rejected-bg); color: var(--rw-rejected-fg); }
    .status-badge.released  { background: var(--rw-border-subtle); color: var(--rw-text-secondary); }
    .refund { color: var(--rw-text-warning); }
    .status-badge.rejected  { background: var(--rw-rejected-bg); color: var(--rw-rejected-fg); }
    .status-badge.cancelled { background: var(--rw-border-subtle); color: var(--rw-text-secondary); }

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
  ordersTruncated = false;
  ordersTotal = 0;
  loading = false;

  activeOrder: SubscriptionOrder | null = null;
  decisionMode: DecisionMode = 'allocate';
  allocatedAmount: number | null = null;
  rejectionReason = '';
  paidAmount: number | null = null;
  paymentReference = '';
  valueDate = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    fetchAllPages((page, size) => this.service.listForAsset(this.assetId, page, size)).subscribe({
      next: (res) => {
        this.orders = [...res.items].sort((a, b) => (b.submittedAt ?? '').localeCompare(a.submittedAt ?? ''));
        this.ordersTruncated = res.truncated;
        this.ordersTotal = res.totalElements;
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
    this.paidAmount = order.amountDue;
    this.paymentReference = 'SUB-' + order.id.slice(0, 8).toUpperCase();
    this.valueDate = '';
    this.dialog.open(this.decisionDialogTpl, { width: '480px' });
  }

  statusLabel(o: SubscriptionOrder): string {
    return o.status === 'PAYMENT_CONFIRMED' ? 'PAID' : o.status;
  }

  dialogTitle(): string {
    switch (this.decisionMode) {
      case 'allocate': return 'Allocate';
      case 'payment': return 'Confirm payment';
      case 'release': return 'Release allocation';
      default: return 'Reject';
    }
  }

  decisionValid(): boolean {
    switch (this.decisionMode) {
      case 'allocate': return !!this.allocatedAmount && this.allocatedAmount > 0;
      case 'payment': return !!this.paidAmount && this.paidAmount > 0 && !!this.paymentReference.trim();
      default: return !!this.rejectionReason.trim();
    }
  }

  /** Runs `run` with a single step-up token (TOTP) for the given backend `@RequiresStepUp` reason. */
  private withStepUp(action: string, reason: string, run: (stepUpToken: string) => void): void {
    this.dialog.open(StepUpDialogComponent, {
      data: { requireDualControl: false, reason, action },
      width: '500px',
      disableClose: true,
    }).afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (result?.stepUpToken) run(result.stepUpToken);
    });
  }

  settle(order: SubscriptionOrder): void {
    this.withStepUp('SUBSCRIPTION_SETTLE', 'Settle subscription: enter the position on the register', (token) =>
      this.service.settle(order.id, token).subscribe({
        next: (o) => {
          this.snackBar.open(o.settlementTxId
            ? 'Settled — mint dispatched; the register is credited once the transfer is indexed.'
            : 'Settled — position entered on the register.', 'Dismiss', { duration: 7000 });
          this.load();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Settlement failed.', 'Dismiss', { duration: 9000 }),
      }));
  }

  submitDecision(): void {
    const order = this.activeOrder;
    if (!order) return;

    if (!this.decisionValid()) return;
    if (this.decisionMode === 'payment') {
      this.dialog.closeAll();
      this.withStepUp('SUBSCRIPTION_PAYMENT_CONFIRM', 'Confirm the subscription payment was received', (token) =>
        this.service.confirmPayment(order.id, {
          paidAmount: this.paidAmount!, paymentReference: this.paymentReference.trim(),
          valueDate: this.valueDate || undefined,
        }, token).subscribe({
          next: () => {
            this.snackBar.open('Payment confirmed. Settle the order to enter the position.', 'Dismiss', { duration: 6000 });
            this.load();
          },
          error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to confirm payment.', 'Dismiss', { duration: 8000 }),
        }));
      return;
    }
    if (this.decisionMode === 'release') {
      const reason = this.rejectionReason.trim();
      this.dialog.closeAll();
      this.withStepUp('SUBSCRIPTION_RELEASE', 'Release the subscription allocation', (token) =>
        this.service.release(order.id, reason, token).subscribe({
          next: () => {
            this.snackBar.open('Allocation released.', 'Dismiss', { duration: 5000 });
            this.load();
          },
          error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to release allocation.', 'Dismiss', { duration: 6000 }),
        }));
      return;
    }
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
