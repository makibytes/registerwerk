import { Component, EventEmitter, Input, Output, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import {
  Endpoint,
  EndpointAddressType,
  EndpointCreateRequest,
  EndpointUpdateRequest,
} from '../models';
import {
  EndpointFormDialogComponent,
  EndpointFormDialogData,
  EndpointFormDialogResult,
} from './endpoint-form-dialog.component';

@Component({
  selector: 'rw-endpoint-manager',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatDialogModule,
  ],
  styles: [`
    .page-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 24px;
    }
    .page-header h1 {
      font-size: 21px;
      font-weight: 700;
      color: var(--rw-text-primary);
      letter-spacing: -0.4px;
      margin: 0;
    }
    .page-subtitle {
      font-size: 13px;
      color: var(--rw-text-secondary);
      margin: 4px 0 0;
    }

    .filter-row {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 16px;
      flex-wrap: wrap;
    }
    .filter-label {
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.5px;
      text-transform: uppercase;
      color: var(--rw-text-muted);
      margin-right: 4px;
    }
    .filter-chip {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 4px 12px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      border: 1px solid var(--rw-border);
      background: transparent;
      color: var(--rw-text-secondary);
      transition: all 0.15s ease;
      &:hover { background: var(--rw-bg); }
      &.active {
        background: var(--rw-accent);
        color: #fff;
        border-color: var(--rw-accent);
      }
    }

    .content-card {
      background: var(--rw-surface);
      border: 1px solid var(--rw-border);
      border-radius: 10px;
      overflow: hidden;
    }

    table { width: 100%; border-collapse: collapse; }
    thead th {
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.5px;
      text-transform: uppercase;
      color: var(--rw-text-muted);
      padding: 12px 20px;
      text-align: left;
      border-bottom: 1px solid var(--rw-border);
    }
    tbody tr:not(:last-child) td { border-bottom: 1px solid var(--rw-border); }
    tbody tr:hover td { background: var(--rw-bg); }
    td {
      padding: 14px 20px;
      font-size: 13px;
      color: var(--rw-text-primary);
      vertical-align: middle;
    }

    .name-cell { font-weight: 600; }
    .addr-cell { display: inline-flex; align-items: center; gap: 4px; }
    .addr-mono {
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      color: var(--rw-text-secondary);
    }
    .addr-copy {
      background: none; border: none; padding: 0 2px; cursor: pointer;
      color: var(--rw-text-muted); display: inline-flex; align-items: center; border-radius: 3px;
      mat-icon { font-size: 13px; width: 13px; height: 13px; }
      &:hover { color: var(--rw-text-primary); }
    }
    .notes-cell {
      max-width: 200px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: var(--rw-text-secondary);
      font-size: 12px;
    }

    .type-chip {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.5px;
      text-transform: uppercase;
      &.wallet   { background: rgba(98,126,234,.12); color: #627EEA; }
      &.contract { background: rgba(245,158,11,.12);  color: #D97706; }
    }

    .risk-chip {
      display: inline-flex;
      align-items: center;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.5px;
      text-transform: uppercase;
      &.low    { background: rgba(34,197,94,.12);  color: #16a34a; }
      &.medium { background: rgba(245,158,11,.12);  color: #D97706; }
      &.high   { background: rgba(239,68,68,.12);  color: #DC2626; }
      &.none   { color: var(--rw-text-muted); font-weight: 400; }
    }

    .empty-state {
      padding: 60px 24px;
      text-align: center;
    }
    .empty-state mat-icon {
      font-size: 40px;
      width: 40px;
      height: 40px;
      color: var(--rw-text-muted);
      margin-bottom: 12px;
    }
    .empty-state p { color: var(--rw-text-muted); font-size: 14px; margin: 4px 0; }
  `],
  template: `
    <div class="page-header">
      <div>
        <h1>Endpoints</h1>
        <p class="page-subtitle">Named address-book entries for wallets and contracts</p>
      </div>
      <button mat-flat-button color="primary" (click)="openCreate()">
        <mat-icon>add</mat-icon> New endpoint
      </button>
    </div>

    <div class="filter-row">
      <span class="filter-label">Type</span>
      <button class="filter-chip" [class.active]="typeFilter() === null" (click)="typeFilter.set(null)">All</button>
      <button class="filter-chip" [class.active]="typeFilter() === 'WALLET'" (click)="typeFilter.set('WALLET')">
        <mat-icon style="font-size:14px;width:14px;height:14px">account_balance_wallet</mat-icon> Wallets
      </button>
      <button class="filter-chip" [class.active]="typeFilter() === 'CONTRACT'" (click)="typeFilter.set('CONTRACT')">
        <mat-icon style="font-size:14px;width:14px;height:14px">code</mat-icon> Contracts
      </button>
    </div>

    <div class="content-card">
      @if (filtered().length === 0) {
        <div class="empty-state">
          <mat-icon>contacts</mat-icon>
          <p><strong>No endpoints yet</strong></p>
          <p>Create a named entry for a wallet or contract address.</p>
        </div>
      } @else {
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Address</th>
              <th>Risk</th>
              <th>Notes</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (ep of filtered(); track ep.id) {
              <tr>
                <td class="name-cell">{{ ep.name }}</td>
                <td>
                  <span class="type-chip" [class.wallet]="ep.addressType === 'WALLET'" [class.contract]="ep.addressType === 'CONTRACT'">
                    {{ ep.addressType === 'WALLET' ? 'Wallet' : 'Contract' }}
                  </span>
                </td>
                <td>
                  <span class="addr-cell">
                    <span class="addr-mono" [title]="ep.address">{{ shorten(ep.address) }}</span>
                    <button class="addr-copy" (click)="copyAddress(ep.address)" type="button">
                      <mat-icon>content_copy</mat-icon>
                    </button>
                  </span>
                </td>
                <td>
                  @if (ep.riskLevel) {
                    <span class="risk-chip" [class.low]="ep.riskLevel === 'LOW'" [class.medium]="ep.riskLevel === 'MEDIUM'" [class.high]="ep.riskLevel === 'HIGH'">
                      {{ ep.riskLevel }}
                    </span>
                  } @else {
                    <span class="risk-chip none">—</span>
                  }
                </td>
                <td class="notes-cell" [matTooltip]="ep.notes || ''" [matTooltipDisabled]="!ep.notes">
                  {{ ep.notes || '' }}
                </td>
                <td>
                  <button mat-icon-button [matMenuTriggerFor]="actionMenu">
                    <mat-icon>more_vert</mat-icon>
                  </button>
                  <mat-menu #actionMenu>
                    <button mat-menu-item (click)="openEdit(ep)">
                      <mat-icon>edit</mat-icon> Edit
                    </button>
                    <button mat-menu-item (click)="confirmDelete(ep)" style="color:#ef4444">
                      <mat-icon>delete_outline</mat-icon> Delete
                    </button>
                  </mat-menu>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </div>
  `,
})
export class EndpointManagerComponent {
  private readonly dialog = inject(MatDialog);

  @Input({ required: true }) set endpoints(value: Endpoint[]) { this._endpoints.set(value); }
  @Output() create = new EventEmitter<EndpointCreateRequest>();
  @Output() update = new EventEmitter<{ id: string; request: EndpointUpdateRequest }>();
  @Output() remove = new EventEmitter<Endpoint>();

  readonly _endpoints = signal<Endpoint[]>([]);
  readonly typeFilter = signal<EndpointAddressType | null>(null);
  readonly filtered = computed(() => {
    const f = this.typeFilter();
    return f ? this._endpoints().filter((e: Endpoint) => e.addressType === f) : this._endpoints();
  });

  openCreate(): void {
    const data: EndpointFormDialogData = { mode: 'create' };
    this.dialog.open<EndpointFormDialogComponent, EndpointFormDialogData, EndpointFormDialogResult>(
      EndpointFormDialogComponent, { width: '480px', data }
    ).afterClosed().subscribe((result: EndpointFormDialogResult | undefined) => {
      if (result) this.create.emit(result as EndpointCreateRequest);
    });
  }

  openEdit(ep: Endpoint): void {
    const data: EndpointFormDialogData = { mode: 'edit', endpoint: ep };
    this.dialog.open<EndpointFormDialogComponent, EndpointFormDialogData, EndpointFormDialogResult>(
      EndpointFormDialogComponent, { width: '480px', data }
    ).afterClosed().subscribe((result: EndpointFormDialogResult | undefined) => {
      if (result) this.update.emit({ id: ep.id, request: result as EndpointUpdateRequest });
    });
  }

  confirmDelete(ep: Endpoint): void {
    if (confirm(`Delete endpoint "${ep.name}"?\n\nThis action cannot be undone.`)) {
      this.remove.emit(ep);
    }
  }

  shorten(address: string): string {
    if (!address || address.length < 14) return address;
    return `${address.slice(0, 8)}…${address.slice(-6)}`;
  }

  copyAddress(address: string): void {
    navigator.clipboard.writeText(address).catch(() => {});
  }
}
