import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatDividerModule } from '@angular/material/divider';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { LiveHolder } from '../models';
import { WalletAddressComponent } from '../wallet-address/wallet-address.component';

/**
 * Displays token holder distribution with donut chart and persistent warning for unknown holders.
 *
 * Features:
 * - Donut chart (known vs unknown)
 * - Side statistics
 * - Persistent amber warning panel for unknown holders (not a toast)
 */
@Component({
  selector: 'lib-holder-distribution',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatIconModule,
    MatDividerModule,
    DecimalPipe,
    PercentPipe,
    WalletAddressComponent,
  ],
  template: `
    <mat-card class="distribution-card">
      <mat-card-content>
        <div class="distribution-content">
          <!-- Donut Chart -->
          <div class="chart-section">
            <svg class="donut-chart" viewBox="0 0 120 120">
              <!-- Background circle -->
              <circle 
                cx="60" 
                cy="60" 
                r="45" 
                fill="none" 
                stroke="var(--rw-border, #e0e0e0)" 
                stroke-width="20">
              </circle>
              <!-- Known segment -->
              <circle 
                cx="60" 
                cy="60" 
                r="45"
                fill="none"
                stroke="var(--rw-accent, #0d9488)"
                stroke-width="20"
                [style.stroke-dasharray]="knownArc + ' ' + (282 - knownArc)"
                [style.stroke-dashoffset]="'0'"
                stroke-linecap="round"
                class="known-segment">
              </circle>
              <!-- Unknown segment -->
              @if (unknownCount > 0) {
                <circle 
                  cx="60" 
                  cy="60" 
                  r="45"
                  fill="none"
                  stroke="var(--rw-warning-fg, #ef4444)"
                  stroke-width="20"
                  [style.stroke-dasharray]="unknownArc + ' ' + (282 - unknownArc)"
                  [style.stroke-dashoffset]="-" + knownArc
                  stroke-linecap="round"
                  class="unknown-segment">
                </circle>
              }
              <!-- Center text -->
              <text x="60" y="55" text-anchor="middle" class="chart-label">
                Total
              </text>
              <text x="60" y="70" text-anchor="middle" class="chart-value">
                {{ holders.length }}
              </text>
            </svg>
          </div>

          <!-- Statistics -->
          <div class="stats-section">
            <div class="stat-item">
              <div class="stat-color known-color"></div>
              <div class="stat-details">
                <div class="stat-label">Known Holders</div>
                <div class="stat-value">{{ knownCount }}</div>
              </div>
            </div>

            <div class="stat-item">
              <div class="stat-color unknown-color"></div>
              <div class="stat-details">
                <div class="stat-label">Unknown Holders</div>
                <div class="stat-value">{{ unknownCount }}</div>
              </div>
            </div>

            <mat-divider></mat-divider>

            <div class="stat-item percentage">
              <div class="stat-label">Known %</div>
              <div class="stat-value">{{ knownPercentage | number: '1.0-1' }}%</div>
            </div>
          </div>
        </div>

        <!-- Unknown Holders Warning Panel -->
        @if (unknownCount > 0) {
          <mat-divider style="margin: 20px 0;"></mat-divider>

          <div class="warning-panel">
            <div class="warning-header">
              <mat-icon class="warning-icon">warning</mat-icon>
              <h3>Unidentified Token Holders</h3>
              <span class="warning-badge">{{ unknownCount }}</span>
            </div>

            <p class="warning-message">
              The following wallet addresses hold tokens but are not registered in Registerwerk.
              In a KYC'ed environment, all holders should be known identities.
            </p>

            <div class="unknown-addresses">
              @for (holder of unknownHolders; track holder.walletAddress) {
                <div class="unknown-address-item">
                  <div class="address-info">
                    <lib-wallet-address [address]="holder.walletAddress"></lib-wallet-address>
                    <span class="address-balance">
                      {{ holder.balance | number: '1.0-4' }} tokens
                    </span>
                  </div>
                </div>
              }
            </div>
          </div>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .distribution-card {
      background: var(--rw-surface, white);
      border: 1px solid var(--rw-border, #e0e0e0);
      border-radius: 4px;
      margin-bottom: 20px;
    }

    mat-card-content {
      padding: 20px;
    }

    .distribution-content {
      display: grid;
      grid-template-columns: 200px 1fr;
      gap: 40px;
      align-items: start;

      @media (max-width: 768px) {
        grid-template-columns: 1fr;
        gap: 20px;
      }
    }

    .chart-section {
      display: flex;
      justify-content: center;
      align-items: center;
    }

    .donut-chart {
      width: 160px;
      height: 160px;
      filter: drop-shadow(0 2px 8px rgba(0, 0, 0, 0.08));
    }

    .chart-label {
      font-size: 10px;
      fill: var(--rw-text-muted, #999);
      font-weight: 600;
      letter-spacing: 0.5px;
      text-transform: uppercase;
    }

    .chart-value {
      font-size: 24px;
      fill: var(--rw-text-primary, #0d1526);
      font-weight: 700;
    }

    .stats-section {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }

    .stat-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 0;

      &.percentage {
        display: flex;
        justify-content: space-between;
        padding: 12px 0;
        margin-top: 4px;
      }
    }

    .stat-color {
      width: 12px;
      height: 12px;
      border-radius: 2px;

      &.known-color {
        background: var(--rw-accent, #0d9488);
      }

      &.unknown-color {
        background: var(--rw-warning-fg, #ef4444);
      }
    }

    .stat-details {
      flex: 1;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .stat-label {
      font-size: 12px;
      color: var(--rw-text-muted, #999);
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .stat-value {
      font-size: 18px;
      color: var(--rw-text-primary, #0d1526);
      font-weight: 700;
      font-family: 'IBM Plex Mono', monospace;
    }

    /* Warning Panel */
    .warning-panel {
      background: linear-gradient(135deg, rgba(245, 158, 11, 0.08) 0%, rgba(245, 158, 11, 0.03) 100%);
      border: 1px solid rgba(245, 158, 11, 0.2);
      border-radius: 4px;
      padding: 16px;
      border-left: 4px solid var(--rw-accent, #f59e0b);
    }

    .warning-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 12px;

      h3 {
        margin: 0;
        font-size: 14px;
        color: var(--rw-text-primary, #0d1526);
        font-weight: 600;
        flex: 1;
      }
    }

    .warning-icon {
      color: var(--rw-accent, #f59e0b);
      font-size: 20px;
      width: 20px;
      height: 20px;
    }

    .warning-badge {
      background: var(--rw-accent, #f59e0b);
      color: white;
      font-size: 11px;
      font-weight: 700;
      padding: 2px 8px;
      border-radius: 12px;
      min-width: 24px;
      text-align: center;
    }

    .warning-message {
      font-size: 13px;
      color: var(--rw-text-secondary, #666);
      margin: 0 0 12px;
      line-height: 1.5;
    }

    .unknown-addresses {
      display: flex;
      flex-direction: column;
      gap: 8px;
      max-height: 300px;
      overflow-y: auto;
    }

    .unknown-address-item {
      background: var(--rw-surface, white);
      padding: 8px 12px;
      border-radius: 3px;
      border: 1px solid rgba(245, 158, 11, 0.1);
      display: flex;
      justify-content: space-between;
      align-items: center;

      &:hover {
        background: rgba(245, 158, 11, 0.02);
      }
    }

    .address-info {
      display: flex;
      align-items: center;
      gap: 8px;
      flex: 1;
    }

    .address-balance {
      font-size: 11px;
      color: var(--rw-text-muted, #999);
      font-family: 'IBM Plex Mono', monospace;
      white-space: nowrap;
      margin-left: auto;
      padding-left: 8px;
    }

    @media (max-width: 600px) {
      .donut-chart {
        width: 120px;
        height: 120px;
      }

      .chart-value {
        font-size: 18px;
      }

      .stat-value {
        font-size: 16px;
      }
    }
  `]
})
export class HolderDistributionComponent {
  @Input() holders: LiveHolder[] = [];

  get knownCount(): number {
    return this.holders.filter(h => h.known).length;
  }

  get unknownCount(): number {
    return this.holders.filter(h => !h.known).length;
  }

  get unknownHolders(): LiveHolder[] {
    return this.holders.filter(h => !h.known).sort((a, b) => {
      // Sort by balance descending
      const balA = typeof a.balance === 'string' ? parseFloat(a.balance) : a.balance;
      const balB = typeof b.balance === 'string' ? parseFloat(b.balance) : b.balance;
      return balB - balA;
    });
  }

  get knownPercentage(): number {
    if (this.holders.length === 0) return 0;
    return (this.knownCount / this.holders.length) * 100;
  }

  get knownArc(): number {
    // Calculate SVG circle arc for known holders (circumference = 282 for r=45)
    const percentage = this.knownPercentage / 100;
    return 282 * percentage;
  }

  get unknownArc(): number {
    return 282 * (this.unknownCount / this.holders.length);
  }
}
