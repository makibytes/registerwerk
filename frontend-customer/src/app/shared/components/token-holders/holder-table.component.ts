import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { LiveHolder } from './models';

@Component({
  selector: 'app-holder-table',
  standalone: true,
  imports: [CommonModule, MatTableModule, MatIconModule, MatTooltipModule],
  template: `
    <div class="table-container">
      <h3 class="table-title">All Holders</h3>
      
      @if (holders.length === 0) {
        <div class="empty-state">
          <p>No holder data available</p>
        </div>
      } @else {
        <div class="table-wrapper">
          <table mat-table [dataSource]="holders" class="holder-table">
            <!-- Wallet Address Column -->
            <ng-container matColumnDef="wallet">
              <th mat-header-cell *matHeaderCellDef>Wallet Address</th>
              <td mat-cell *matCellDef="let holder">
                <code class="wallet-code" [title]="holder.walletAddress">
                  {{ shortenAddress(holder.walletAddress) }}
                </code>
              </td>
            </ng-container>

            <!-- Balance Column -->
            <ng-container matColumnDef="balance">
              <th mat-header-cell *matHeaderCellDef>Balance</th>
              <td mat-cell *matCellDef="let holder">
                {{ holder.tokenBalance | number:'1.0-2' }}
              </td>
            </ng-container>

            <!-- Percentage Column -->
            <ng-container matColumnDef="percentage">
              <th mat-header-cell *matHeaderCellDef>Share %</th>
              <td mat-cell *matCellDef="let holder">
                {{ (holder.tokenBalance / totalSupply * 100) | number:'1.0-2' }}%
              </td>
            </ng-container>

            <!-- Status Column -->
            <ng-container matColumnDef="status">
              <th mat-header-cell *matHeaderCellDef>Status</th>
              <td mat-cell *matCellDef="let holder">
                @if (holder.isWhitelisted !== false) {
                  <span class="status-badge whitelisted" matTooltip="Whitelisted holder">
                    <mat-icon>check_circle</mat-icon>
                    Whitelisted
                  </span>
                } @else {
                  <span class="status-badge not-whitelisted" matTooltip="Not whitelisted">
                    <mat-icon>cancel</mat-icon>
                    Not Whitelisted
                  </span>
                }
              </td>
            </ng-container>

            <!-- Header and Row -->
            <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
            <tr mat-row *matRowDef="let row; columns: displayedColumns;"></tr>
          </table>
        </div>

        <div class="table-footer">
          <span class="footer-text">Total: {{ holders.length }} holders</span>
        </div>
      }
    </div>
  `,
  styles: [`
    .table-container {
      padding: 16px;
      background: var(--rw-surface);
      border-radius: 8px;
      border: 1px solid var(--rw-border);
    }

    .table-title {
      margin: 0 0 16px 0;
      font-size: 14px;
      font-weight: 600;
      color: var(--rw-text-primary);
    }

    .empty-state {
      text-align: center;
      padding: 24px;
      color: var(--rw-text-muted);
      font-size: 13px;
    }

    .table-wrapper {
      overflow-x: auto;
    }

    .holder-table {
      width: 100%;
      border-collapse: collapse;
    }

    .holder-table th {
      background: var(--rw-bg);
      color: var(--rw-text-muted);
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.5px;
      text-transform: uppercase;
      padding: 12px 8px;
      text-align: left;
      border-bottom: 1px solid var(--rw-border);
    }

    .holder-table td {
      padding: 12px 8px;
      border-bottom: 1px solid var(--rw-border);
      font-size: 13px;
      color: var(--rw-text-primary);
    }

    .holder-table tr:hover td {
      background: var(--rw-bg);
    }

    .wallet-code {
      font-family: monospace;
      font-size: 12px;
      background: var(--rw-bg);
      padding: 4px 6px;
      border-radius: 3px;
      color: var(--rw-text-secondary);
    }

    .status-badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 4px 8px;
      border-radius: 12px;
      font-size: 11px;
      font-weight: 600;
      white-space: nowrap;
    }

    .status-badge mat-icon {
      font-size: 14px;
      height: 14px;
      width: 14px;
    }

    .whitelisted {
      background: rgba(56, 142, 60, 0.1);
      color: #388e3c;
    }

    .not-whitelisted {
      background: rgba(229, 57, 53, 0.1);
      color: #e53935;
    }

    .table-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 12px 0;
      border-top: 1px solid var(--rw-border);
      margin-top: 8px;
      font-size: 12px;
      color: var(--rw-text-muted);
    }

    .footer-text {
      font-weight: 600;
      color: var(--rw-text-secondary);
    }
  `],
})
export class HolderTableComponent {
  @Input() holders: LiveHolder[] = [];

  displayedColumns: string[] = ['wallet', 'balance', 'percentage', 'status'];

  get totalSupply(): number {
    return this.holders.reduce((sum, h) => sum + h.tokenBalance, 0);
  }

  shortenAddress(address: string): string {
    if (!address || address.length < 12) return address;
    return `${address.substring(0, 6)}...${address.substring(address.length - 4)}`;
  }
}
