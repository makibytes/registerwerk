import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { DecimalPipe } from '@angular/common';
import { WalletAddressComponent } from '../wallet-address/wallet-address.component';
import { LiveHolder } from '../models';

export interface HolderTableAction {
  type: 'burn' | 'force-transfer' | 'mint';
  holder: LiveHolder;
}

/**
 * Table displaying live token holders with identity, balance, and status.
 *
 * Features:
 * - Entity name or "Unknown" display
 * - Shortened wallet addresses with copy button
 * - Balance display
 * - Known/Unknown status badge
 * - Action buttons (conditional on canAdmin input)
 * - Unknown holders highlighted with amber left-border
 */
@Component({
  selector: 'lib-holder-table',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatChipsModule,
    DecimalPipe,
    WalletAddressComponent,
  ],
  template: `
    <div class="holder-table-container">
      @if (loading) {
        <div class="loading-overlay">
          <mat-spinner diameter="40"></mat-spinner>
        </div>
      } @else if (holders.length === 0) {
        <div class="empty-state">
          <mat-icon>people_outline</mat-icon>
          <p>No token holders found</p>
        </div>
      } @else {
        <div class="table-wrapper">
          <table mat-table [dataSource]="holders" class="holder-table">
            <!-- Entity Name Column -->
            <ng-container matColumnDef="entityName">
              <th mat-header-cell>Holder</th>
              <td mat-cell [class.unknown-row]="!row.known">
                {{ row.entityName || 'Unknown' }}
              </td>
            </ng-container>

            <!-- Wallet Address Column -->
            <ng-container matColumnDef="wallet">
              <th mat-header-cell>Wallet Address</th>
              <td mat-cell [class.unknown-row]="!row.known">
                <lib-wallet-address [address]="row.walletAddress"></lib-wallet-address>
              </td>
            </ng-container>

            <!-- Balance Column -->
            <ng-container matColumnDef="balance">
              <th mat-header-cell class="numeric">Balance</th>
              <td mat-cell [class.unknown-row]="!row.known" class="numeric">
                {{ row.balance | number: '1.0-18' }}
              </td>
            </ng-container>

            <!-- Status Column -->
            <ng-container matColumnDef="status">
              <th mat-header-cell>Status</th>
              <td mat-cell [class.unknown-row]="!row.known">
                @if (row.known) {
                  @if (row.whitelisted) {
                    <mat-chip class="status-chip status-whitelisted">
                      <mat-icon>check_circle</mat-icon>
                      Whitelisted
                    </mat-chip>
                  } @else {
                    <mat-chip class="status-chip status-pending">
                      <mat-icon>pending</mat-icon>
                      Pending
                    </mat-chip>
                  }
                } @else {
                  <mat-chip class="status-chip status-unknown">
                    <mat-icon>warning</mat-icon>
                    Unknown
                  </mat-chip>
                }
              </td>
            </ng-container>

            <!-- Actions Column (conditional) -->
            @if (canAdmin) {
              <ng-container matColumnDef="actions">
                <th mat-header-cell class="actions-header">Actions</th>
                <td mat-cell [class.unknown-row]="!row.known" class="actions-cell">
                  <button 
                    mat-icon-button 
                    (click)="onBurn(row)"
                    matTooltip="Burn tokens"
                    [disabled]="!row.known"
                    class="action-burn">
                    <mat-icon>local_fire_department</mat-icon>
                  </button>
                  <button 
                    mat-icon-button 
                    (click)="onForceTransfer(row)"
                    matTooltip="Force transfer"
                    class="action-force">
                    <mat-icon>swap_horiz</mat-icon>
                  </button>
                </td>
              </ng-container>
            }

            <!-- Table Header and Rows -->
            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr 
              mat-row 
              *matRowDef="let row; columns: displayedColumns; let rowIndex = index"
              [class.unknown-holder-row]="!row.known"
              class="holder-row">
            </tr>
          </table>
        </div>
      }
    </div>
  `,
  styles: [`
    .holder-table-container {
      position: relative;
      background: var(--rw-surface, white);
      border-radius: 4px;
      overflow: hidden;
    }

    .loading-overlay {
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 60px 20px;
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 60px 20px;
      color: var(--rw-text-muted, #666);

      mat-icon {
        font-size: 48px;
        width: 48px;
        height: 48px;
        margin-bottom: 12px;
        opacity: 0.5;
      }

      p {
        margin: 0;
        font-size: 14px;
      }
    }

    .table-wrapper {
      overflow-x: auto;
    }

    .holder-table {
      width: 100%;
      border-collapse: collapse;

      th {
        background: var(--rw-bg, #f5f5f5);
        color: var(--rw-text-muted, #666);
        font-size: 11px;
        font-weight: 600;
        letter-spacing: 0.5px;
        text-transform: uppercase;
        padding: 12px 16px;
        text-align: left;
        border-bottom: 1px solid var(--rw-border, #e0e0e0);
      }

      td {
        padding: 16px;
        border-bottom: 1px solid var(--rw-border, #e0e0e0);
        color: var(--rw-text-primary, #0d1526);
        font-size: 14px;

        &.numeric {
          text-align: right;
          font-family: 'IBM Plex Mono', monospace;
          font-size: 12px;
        }

        &.actions-cell {
          text-align: right;
          padding: 8px 16px;

          button {
            margin: 0 4px;
          }
        }

        &.unknown-row {
          color: var(--rw-text-secondary, #666);
        }
      }

      tr:hover {
        background: var(--rw-surface-raised, #f9f9f9);
      }
    }

    .unknown-holder-row {
      border-left: 4px solid var(--rw-accent, #f59e0b) !important;

      td:first-child {
        padding-left: 12px;
      }
    }

    .status-chip {
      font-size: 11px;
      height: 24px;
      padding: 0 8px;
      display: inline-flex;
      align-items: center;
      gap: 4px;

      mat-icon {
        font-size: 12px;
        width: 12px;
        height: 12px;
      }

      &.status-whitelisted {
        background: rgba(34, 197, 94, 0.12);
        color: #22c55e;
      }

      &.status-pending {
        background: rgba(245, 158, 11, 0.12);
        color: #f59e0b;
      }

      &.status-unknown {
        background: rgba(239, 68, 68, 0.12);
        color: #ef4444;
      }
    }

    .actions-header {
      width: 120px;
    }

    .action-burn:hover {
      color: #ef4444;
    }

    .action-force:hover {
      color: #0d9488;
    }

    @media (max-width: 768px) {
      .holder-table {
        th, td {
          padding: 12px 8px;
          font-size: 12px;
        }
      }

      .status-chip {
        font-size: 10px;
        padding: 0 6px;
      }
    }
  `]
})
export class HolderTableComponent {
  @Input() holders: LiveHolder[] = [];
  @Input() loading = false;
  @Input() canAdmin = false;

  @Output() burn = new EventEmitter<LiveHolder>();
  @Output() forceTransfer = new EventEmitter<LiveHolder>();
  @Output() mint = new EventEmitter<LiveHolder>();

  displayedColumns: string[] = ['entityName', 'wallet', 'balance', 'status'];

  constructor() {
    // Dynamically add actions column if needed
  }

  ngOnInit() {
    if (this.canAdmin) {
      this.displayedColumns = [...this.displayedColumns, 'actions'];
    }
  }

  onBurn(holder: LiveHolder): void {
    this.burn.emit(holder);
  }

  onForceTransfer(holder: LiveHolder): void {
    this.forceTransfer.emit(holder);
  }

  onMint(holder: LiveHolder): void {
    this.mint.emit(holder);
  }
}
