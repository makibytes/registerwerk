import { ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar } from '@angular/material/snack-bar';
import { WebhookService } from '../../core/api/webhook.service';
import { WebhookDelivery, WebhookEventType, WebhookSubscription } from '../../core/models';

const EVENT_TYPES: WebhookEventType[] = [
  'KYC_APPROVED', 'KYC_REJECTED', 'ASSET_APPROVED', 'ASSET_REJECTED',
  'SUBSCRIPTION_ORDER_ALLOCATED', 'SUBSCRIPTION_ORDER_CONFIRMED', 'SUBSCRIPTION_ORDER_REJECTED',
  'TRADE_EXECUTED', 'TRADE_PAYMENT_CONFIRMED', 'TRADE_PAYMENT_DISPUTED',
];

/**
 * Self-service outbound webhook management — previously the only way to learn a registry event
 * had happened was to poll REST. Curated event types only (see backend `WebhookEventType`).
 */
@Component({
  selector: 'app-webhooks',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatCheckboxModule, MatDialogModule, MatSlideToggleModule,
    MatTooltipModule,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>Webhooks</h1>
        <button mat-flat-button color="primary" (click)="openCreateDialog()">
          <mat-icon>add</mat-icon>
          New Webhook
        </button>
      </div>

      @if (newSecret) {
        <mat-card class="secret-card">
          <mat-card-content>
            <strong>Signing secret (shown once — store it now):</strong>
            <code class="secret-value">{{ newSecret }}</code>
            <button mat-icon-button (click)="newSecret = null" matTooltip="Dismiss">
              <mat-icon>close</mat-icon>
            </button>
          </mat-card-content>
        </mat-card>
      }

      @if (loading) {
        <div class="loading-overlay"><mat-icon class="spin">autorenew</mat-icon></div>
      } @else if (subscriptions.length === 0) {
        <mat-card><mat-card-content class="empty-text">No webhooks configured yet.</mat-card-content></mat-card>
      } @else {
        @for (sub of subscriptions; track sub.id) {
          <mat-card class="sub-card">
            <mat-card-content>
              <div class="sub-row">
                <div class="sub-main">
                  <code class="sub-url">{{ sub.url }}</code>
                  <div class="sub-types">
                    @for (type of sub.eventTypes.length ? sub.eventTypes : allEventTypes; track type) {
                      <span class="type-chip">{{ type }}</span>
                    }
                  </div>
                </div>
                <div class="sub-actions">
                  <mat-slide-toggle [checked]="sub.enabled" (change)="toggleEnabled(sub, $event.checked)">
                    {{ sub.enabled ? 'Enabled' : 'Disabled' }}
                  </mat-slide-toggle>
                  <button mat-stroked-button (click)="viewDeliveries(sub)">
                    <mat-icon>history</mat-icon>
                    Deliveries
                  </button>
                  <button mat-icon-button color="warn" (click)="deleteSubscription(sub)" matTooltip="Delete">
                    <mat-icon>delete</mat-icon>
                  </button>
                </div>
              </div>
            </mat-card-content>
          </mat-card>
        }
      }
    </div>

    <ng-template #createDialogTpl>
      <h2 mat-dialog-title>New Webhook</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        <mat-form-field appearance="outline">
          <mat-label>Endpoint URL</mat-label>
          <input matInput [(ngModel)]="createForm.url" placeholder="https://your-system.example.com/webhooks/registerwerk">
        </mat-form-field>
        <p class="dimmed small" style="margin:0">Leave all event types unchecked to subscribe to every curated event.</p>
        <div class="type-checkboxes">
          @for (type of allEventTypes; track type) {
            <mat-checkbox [checked]="createForm.eventTypes.includes(type)" (change)="toggleType(type, $event.checked)">
              {{ type }}
            </mat-checkbox>
          }
        </div>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" [disabled]="!createForm.url.trim()" (click)="submitCreate()">
          Create
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #deliveriesDialogTpl>
      <h2 mat-dialog-title>Recent Deliveries</h2>
      <mat-dialog-content style="min-width:480px">
        @if (activeDeliveries.length === 0) {
          <p class="dimmed">No deliveries yet.</p>
        } @else {
          <table class="deliveries-table">
            <thead>
              <tr><th>Event</th><th>Status</th><th>Response</th><th>Attempts</th><th>Last attempt</th></tr>
            </thead>
            <tbody>
              @for (d of activeDeliveries; track d.id) {
                <tr>
                  <td>{{ d.eventType }}</td>
                  <td class="status-{{ d.status.toLowerCase() }}">{{ d.status }}</td>
                  <td>{{ d.responseCode ?? '—' }}</td>
                  <td>{{ d.attemptCount }}</td>
                  <td>{{ d.lastAttemptedAt || '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end">
        <button mat-stroked-button mat-dialog-close>Close</button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .secret-card { margin-bottom: 16px; border-left: 4px solid var(--rw-accent); }
    .secret-value { display: inline-block; margin: 0 12px; font-family: monospace; word-break: break-all; }
    .loading-overlay { display: flex; justify-content: center; padding: 40px; }
    .spin { animation: spin 1.2s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .empty-text { color: var(--rw-text-muted); text-align: center; padding: 24px; }
    .sub-card { margin-bottom: 12px; }
    .sub-row { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
    .sub-main { display: flex; flex-direction: column; gap: 6px; flex: 1; min-width: 240px; }
    .sub-url { font-size: 13px; word-break: break-all; }
    .sub-types { display: flex; gap: 6px; flex-wrap: wrap; }
    .type-chip { font-size: 10px; background: var(--rw-surface-soft); border: 1px solid var(--rw-border); border-radius: 10px; padding: 2px 8px; color: var(--rw-text-secondary); }
    .sub-actions { display: flex; align-items: center; gap: 8px; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: 12px; }
    .type-checkboxes { display: flex; flex-direction: column; gap: 4px; max-height: 260px; overflow-y: auto; }
    .deliveries-table { width: 100%; border-collapse: collapse; font-size: 12px; }
    .deliveries-table th, .deliveries-table td { padding: 6px 8px; border-bottom: 1px solid var(--rw-border); text-align: left; }
    .status-success { color: var(--rw-text-success, #10b981); }
    .status-failed { color: var(--rw-text-danger, #ef4444); }
    .status-pending { color: var(--rw-pending-fg, #f59e0b); }
  `],
})
export class WebhooksComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly webhookService = inject(WebhookService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  @ViewChild('createDialogTpl') createDialogTpl!: TemplateRef<unknown>;
  @ViewChild('deliveriesDialogTpl') deliveriesDialogTpl!: TemplateRef<unknown>;

  readonly allEventTypes = EVENT_TYPES;

  subscriptions: WebhookSubscription[] = [];
  loading = true;
  newSecret: string | null = null;

  createForm: { url: string; eventTypes: WebhookEventType[] } = { url: '', eventTypes: [] };
  activeDeliveries: WebhookDelivery[] = [];

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading = true;
    this.webhookService.list().subscribe({
      next: (subs) => {
        this.subscriptions = subs;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => { this.loading = false; this.cdr.markForCheck(); },
    });
  }

  openCreateDialog(): void {
    this.createForm = { url: '', eventTypes: [] };
    this.dialog.open(this.createDialogTpl, { width: '480px' });
  }

  toggleType(type: WebhookEventType, checked: boolean): void {
    this.createForm.eventTypes = checked
      ? [...this.createForm.eventTypes, type]
      : this.createForm.eventTypes.filter(t => t !== type);
  }

  submitCreate(): void {
    const url = this.createForm.url.trim();
    if (!url) return;
    this.dialog.closeAll();
    this.webhookService.create(url, this.createForm.eventTypes).subscribe({
      next: (created) => {
        this.newSecret = created.secret;
        this.snackBar.open('Webhook created.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to create webhook.', 'Dismiss', { duration: 5000 }),
    });
  }

  toggleEnabled(sub: WebhookSubscription, enabled: boolean): void {
    this.webhookService.setEnabled(sub.id, enabled).subscribe({
      next: () => this.load(),
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to update webhook.', 'Dismiss', { duration: 5000 }),
    });
  }

  deleteSubscription(sub: WebhookSubscription): void {
    this.webhookService.delete(sub.id).subscribe({
      next: () => {
        this.snackBar.open('Webhook deleted.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to delete webhook.', 'Dismiss', { duration: 5000 }),
    });
  }

  viewDeliveries(sub: WebhookSubscription): void {
    this.webhookService.deliveries(sub.id).subscribe({
      next: (deliveries) => {
        this.activeDeliveries = deliveries;
        this.dialog.open(this.deliveriesDialogTpl, { width: '560px' });
        this.cdr.markForCheck();
      },
      error: () => this.snackBar.open('Failed to load deliveries.', 'Dismiss', { duration: 4000 }),
    });
  }
}
