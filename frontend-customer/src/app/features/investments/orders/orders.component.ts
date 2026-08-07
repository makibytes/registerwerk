import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { StatusBadgeComponent } from '@registerwerk/ui';
import { SubscriptionOrderService } from '../../../core/api/subscription-order.service';
import { SubscriptionOrder } from '../../../core/models';
import { AddressComponent } from '../../../shared/components/address.component';

@Component({
  selector: 'app-orders',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule,
    StatusBadgeComponent,
    AddressComponent,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>My Orders</h1>
      </div>

      <mat-card class="submit-card">
        <mat-card-header>
          <mat-card-title>Place a Subscription Order</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <p class="hint">
            Enter the asset ID for the offering you've been invited to subscribe to (your issuer
            or relationship manager will have shared it with you), your settlement wallet address,
            and the amount you wish to subscribe.
          </p>
          <div class="submit-form">
            <mat-form-field appearance="outline">
              <mat-label>Asset ID</mat-label>
              <input matInput [(ngModel)]="form.assetId" placeholder="UUID">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Wallet address</mat-label>
              <input matInput [(ngModel)]="form.walletAddress" placeholder="0x…">
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Requested amount</mat-label>
              <input matInput type="number" [(ngModel)]="form.requestedAmount" min="0">
            </mat-form-field>
            <button mat-flat-button color="primary" [disabled]="submitting || !canSubmit" (click)="submitOrder()">
              <mat-icon>send</mat-icon>
              {{ submitting ? 'Submitting…' : 'Submit Order' }}
            </button>
          </div>
        </mat-card-content>
      </mat-card>

      @if (loading) {
        <div class="loading-overlay"><mat-spinner diameter="48"></mat-spinner></div>
      } @else {
        <mat-card>
          <table mat-table [dataSource]="orders" class="mat-elevation-z0">
            <ng-container matColumnDef="assetId">
              <th mat-header-cell *matHeaderCellDef>Asset</th>
              <td mat-cell *matCellDef="let o"><code>{{ o.assetId }}</code></td>
            </ng-container>
            <ng-container matColumnDef="requestedAmount">
              <th mat-header-cell *matHeaderCellDef>Requested</th>
              <td mat-cell *matCellDef="let o">{{ o.requestedAmount | number:'1.0-2' }}</td>
            </ng-container>
            <ng-container matColumnDef="allocatedAmount">
              <th mat-header-cell *matHeaderCellDef>Allocated</th>
              <td mat-cell *matCellDef="let o">{{ o.allocatedAmount !== null ? (o.allocatedAmount | number:'1.0-2') : '—' }}</td>
            </ng-container>
            <ng-container matColumnDef="walletAddress">
              <th mat-header-cell *matHeaderCellDef>Wallet</th>
              <td mat-cell *matCellDef="let o"><app-address [address]="o.walletAddress" /></td>
            </ng-container>
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let o"><app-status-badge [status]="o.status"></app-status-badge></td>
            </ng-container>
            <ng-container matColumnDef="submittedAt">
              <th mat-header-cell *matHeaderCellDef>Submitted</th>
              <td mat-cell *matCellDef="let o">{{ o.submittedAt | date:'medium' }}</td>
            </ng-container>
            <ng-container matColumnDef="actions">
              <th mat-header-cell *matHeaderCellDef></th>
              <td mat-cell *matCellDef="let o" class="actions-cell">
                @if (o.status === 'SUBMITTED') {
                  <button mat-stroked-button [disabled]="actingOn === o.id" (click)="cancel(o)">Cancel</button>
                }
                @if (o.status === 'ALLOCATED') {
                  <button mat-flat-button color="primary" [disabled]="actingOn === o.id" (click)="confirm(o)">
                    Confirm ({{ o.allocatedAmount | number:'1.0-2' }})
                  </button>
                }
                @if (o.status === 'REJECTED' && o.rejectionReason) {
                  <span class="rejection-reason" [title]="o.rejectionReason">Rejected — {{ o.rejectionReason }}</span>
                }
              </td>
            </ng-container>

            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
            <tr class="mat-row" *matNoDataRow>
              <td class="mat-cell empty-row" [attr.colspan]="displayedColumns.length">
                No orders yet.
              </td>
            </tr>
          </table>
        </mat-card>
      }
    </div>
  `,
  styles: [`
    .submit-card { margin-bottom: 16px; }
    .hint { font-size: 13px; color: var(--rw-text-secondary); margin: 0 0 12px; max-width: 640px; }
    .submit-form { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .submit-form mat-form-field { flex: 1; min-width: 200px; }
    code { word-break: break-all; font-size: 12px; }
    .actions-cell { display: flex; gap: 8px; justify-content: flex-end; }
    .rejection-reason { font-size: 12px; color: var(--rw-text-danger); max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .empty-row { text-align: center; padding: 32px; color: var(--rw-text-muted); }
  `],
})
export class OrdersComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly orderService = inject(SubscriptionOrderService);
  private readonly snackBar = inject(MatSnackBar);

  orders: SubscriptionOrder[] = [];
  loading = true;
  submitting = false;
  actingOn: string | null = null;

  form = { assetId: '', walletAddress: '', requestedAmount: null as number | null };

  readonly displayedColumns = [
    'assetId', 'requestedAmount', 'allocatedAmount', 'walletAddress', 'status', 'submittedAt', 'actions',
  ];

  get canSubmit(): boolean {
    return !!this.form.assetId.trim() && !!this.form.walletAddress.trim()
      && this.form.requestedAmount !== null && this.form.requestedAmount > 0;
  }

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading = true;
    this.orderService.myOrders().subscribe({
      next: (orders) => {
        this.orders = orders;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  submitOrder(): void {
    if (!this.canSubmit) return;
    this.submitting = true;
    this.orderService.submit(this.form.assetId.trim(), this.form.walletAddress.trim(), this.form.requestedAmount!).subscribe({
      next: () => {
        this.submitting = false;
        this.form = { assetId: '', walletAddress: '', requestedAmount: null };
        this.snackBar.open('Order submitted.', 'Dismiss', { duration: 4000 });
        this.cdr.markForCheck();
        this.load();
      },
      error: (err) => {
        this.submitting = false;
        this.snackBar.open(err?.error?.message ?? 'Failed to submit order.', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  cancel(order: SubscriptionOrder): void {
    this.actingOn = order.id;
    this.orderService.cancel(order.id).subscribe({
      next: () => {
        this.actingOn = null;
        this.snackBar.open('Order cancelled.', 'Dismiss', { duration: 4000 });
        this.cdr.markForCheck();
        this.load();
      },
      error: (err) => {
        this.actingOn = null;
        this.snackBar.open(err?.error?.message ?? 'Failed to cancel order.', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  confirm(order: SubscriptionOrder): void {
    this.actingOn = order.id;
    this.orderService.confirm(order.id).subscribe({
      next: () => {
        this.actingOn = null;
        this.snackBar.open('Order confirmed — position added to your holdings.', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
        this.load();
      },
      error: (err) => {
        this.actingOn = null;
        this.snackBar.open(err?.error?.message ?? 'Failed to confirm order.', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }
}
