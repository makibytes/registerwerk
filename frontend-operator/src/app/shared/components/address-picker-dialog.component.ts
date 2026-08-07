import { ChangeDetectorRef, Component, inject, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EndpointService } from '../../core/api/endpoint.service';
import { Endpoint, EndpointAddressType } from '../../core/models';

export interface AddressPickerDialogData {
  mode: 'WALLET' | 'CONTRACT' | 'ANY';
  title?: string;
}

@Component({
  selector: 'app-address-picker-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatTabsModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  styles: [`
    .dialog-content { min-width: 500px; max-width: 620px; padding: 8px 0 4px; }

    .search-row {
      display: flex;
      gap: 8px;
      align-items: center;
      margin-bottom: 12px;

      mat-form-field { flex: 1; }
    }

    .filter-chips {
      display: flex;
      gap: 6px;
      margin-bottom: 12px;
    }

    .chip {
      padding: 3px 12px;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      border: 1px solid var(--rw-border);
      background: var(--rw-surface);
      color: var(--rw-text-secondary);
      transition: all 0.15s;

      &.active {
        background: var(--rw-accent);
        color: #fff;
        border-color: var(--rw-accent);
      }

      &:hover:not(.active) {
        background: var(--rw-border);
      }
    }

    .endpoint-list {
      max-height: 340px;
      overflow-y: auto;
      border: 1px solid var(--rw-border);
      border-radius: 8px;
    }

    .endpoint-item {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 14px;
      cursor: pointer;
      border-bottom: 1px solid var(--rw-border);
      transition: background 0.12s;

      &:last-child { border-bottom: none; }
      &:hover { background: var(--rw-bg); }
    }

    .type-badge {
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.5px;
      text-transform: uppercase;
      padding: 2px 7px;
      border-radius: 4px;
      flex-shrink: 0;

      &.wallet { background: rgba(16,185,129,0.12); color: #10b981; }
      &.contract { background: rgba(99,102,241,0.12); color: #6366f1; }
    }

    .endpoint-info {
      flex: 1;
      min-width: 0;

      .ep-name { font-size: 13px; font-weight: 600; color: var(--rw-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .ep-addr { font-size: 11px; font-family: 'IBM Plex Mono', monospace; color: var(--rw-text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    }

    .risk-badge {
      font-size: 10px;
      font-weight: 600;
      padding: 2px 6px;
      border-radius: 4px;
      flex-shrink: 0;

      &.LOW { background: rgba(16,185,129,0.12); color: #10b981; }
      &.MEDIUM { background: rgba(245,158,11,0.12); color: #f59e0b; }
      &.HIGH { background: rgba(239,68,68,0.12); color: #ef4444; }
    }

    .empty-state {
      text-align: center;
      padding: 32px 16px;
      color: var(--rw-text-muted);
      font-size: 13px;
    }

    .custom-section {
      padding: 4px 0;

      mat-form-field { width: 100%; }

      .custom-hint {
        font-size: 12px;
        color: var(--rw-text-muted);
        margin-top: 4px;
      }
    }
  `],
  template: `
    <h2 mat-dialog-title>
      <mat-icon style="vertical-align:middle;margin-right:6px;font-size:20px">contacts</mat-icon>
      {{ data.title ?? 'Select address' }}
    </h2>

    <mat-dialog-content>
      <div class="dialog-content">
        <mat-tab-group animationDuration="150ms">
          <!-- Address book tab -->
          <mat-tab label="Address book">
            <div style="padding-top:14px">
              <div class="search-row">
                <mat-form-field appearance="outline" subscriptSizing="dynamic">
                  <mat-label>Search</mat-label>
                  <input matInput [(ngModel)]="searchQuery" (ngModelChange)="applyFilter()" placeholder="Name or address…" />
                  <mat-icon matSuffix>search</mat-icon>
                </mat-form-field>
              </div>

              @if (data.mode === 'ANY') {
                <div class="filter-chips">
                  <span class="chip" role="button" tabindex="0" [class.active]="typeFilter === 'ALL'" (click)="setTypeFilter('ALL')" (keydown.enter)="setTypeFilter('ALL')" (keydown.space)="setTypeFilter('ALL'); $event.preventDefault()">All</span>
                  <span class="chip" role="button" tabindex="0" [class.active]="typeFilter === 'WALLET'" (click)="setTypeFilter('WALLET')" (keydown.enter)="setTypeFilter('WALLET')" (keydown.space)="setTypeFilter('WALLET'); $event.preventDefault()">Wallets</span>
                  <span class="chip" role="button" tabindex="0" [class.active]="typeFilter === 'CONTRACT'" (click)="setTypeFilter('CONTRACT')" (keydown.enter)="setTypeFilter('CONTRACT')" (keydown.space)="setTypeFilter('CONTRACT'); $event.preventDefault()">Contracts</span>
                </div>
              }

              @if (loading) {
                <div style="display:flex;justify-content:center;padding:24px"><mat-spinner diameter="32" /></div>
              } @else if (filteredEndpoints.length === 0) {
                <div class="empty-state">
                  @if (searchQuery) {
                    No endpoints match "{{ searchQuery }}"
                  } @else {
                    No endpoints in your address book yet.
                  }
                </div>
              } @else {
                <div class="endpoint-list">
                  @for (ep of filteredEndpoints; track ep.id) {
                    <div class="endpoint-item" role="button" tabindex="0" (click)="pick(ep.address)" (keydown.enter)="pick(ep.address)" (keydown.space)="pick(ep.address); $event.preventDefault()" [matTooltip]="ep.address">
                      <span class="type-badge" [class.wallet]="ep.addressType === 'WALLET'" [class.contract]="ep.addressType === 'CONTRACT'">
                        {{ ep.addressType === 'WALLET' ? 'Wallet' : 'Contract' }}
                      </span>
                      <div class="endpoint-info">
                        <div class="ep-name">{{ ep.name }}</div>
                        <div class="ep-addr">{{ ep.address }}</div>
                      </div>
                      @if (ep.riskLevel) {
                        <span class="risk-badge" [class]="ep.riskLevel">{{ ep.riskLevel }}</span>
                      }
                      <mat-icon style="color:var(--rw-text-muted);font-size:18px;flex-shrink:0">chevron_right</mat-icon>
                    </div>
                  }
                </div>
              }
            </div>
          </mat-tab>

          <!-- Custom address tab -->
          <mat-tab label="Custom address">
            <div style="padding-top:14px">
              <div class="custom-section">
                <mat-form-field appearance="outline">
                  <mat-label>Address</mat-label>
                  <input matInput [(ngModel)]="customAddress" placeholder="0x…" style="font-family:'IBM Plex Mono',monospace;font-size:13px" autocomplete="off" />
                  <mat-hint>Enter a custom {{ modeLabel }} address</mat-hint>
                </mat-form-field>
                <div style="margin-top:12px">
                  <button mat-flat-button color="primary" [disabled]="!customAddress.trim()" (click)="pick(customAddress.trim())">
                    Use this address
                  </button>
                </div>
              </div>
            </div>
          </mat-tab>
        </mat-tab-group>
      </div>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
    </mat-dialog-actions>
  `,
})
export class AddressPickerDialogComponent implements OnInit {
  private readonly endpointService = inject(EndpointService);
  private readonly dialogRef = inject<MatDialogRef<AddressPickerDialogComponent, string>>(MatDialogRef);
  private readonly cdr = inject(ChangeDetectorRef);
  readonly data: AddressPickerDialogData = inject(MAT_DIALOG_DATA);

  loading = true;
  endpoints: Endpoint[] = [];
  filteredEndpoints: Endpoint[] = [];
  searchQuery = '';
  typeFilter: 'ALL' | EndpointAddressType = 'ALL';
  customAddress = '';

  get modeLabel(): string {
    return this.data.mode === 'WALLET' ? 'wallet' : this.data.mode === 'CONTRACT' ? 'contract' : 'wallet or contract';
  }

  ngOnInit(): void {
    if (this.data.mode !== 'ANY') {
      this.typeFilter = this.data.mode;
    }
    this.endpointService.listEndpoints().subscribe({
      next: (eps) => {
        this.endpoints = eps;
        this.loading = false;
        this.applyFilter();
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  setTypeFilter(filter: 'ALL' | EndpointAddressType): void {
    this.typeFilter = filter;
    this.applyFilter();
  }

  applyFilter(): void {
    let result = this.endpoints;
    if (this.typeFilter !== 'ALL') {
      result = result.filter(ep => ep.addressType === this.typeFilter);
    } else if (this.data.mode !== 'ANY') {
      result = result.filter(ep => ep.addressType === this.data.mode);
    }
    if (this.searchQuery.trim()) {
      const q = this.searchQuery.toLowerCase();
      result = result.filter(ep =>
        ep.name.toLowerCase().includes(q) || ep.address.toLowerCase().includes(q)
      );
    }
    this.filteredEndpoints = result;
  }

  pick(address: string): void {
    this.dialogRef.close(address);
  }
}
