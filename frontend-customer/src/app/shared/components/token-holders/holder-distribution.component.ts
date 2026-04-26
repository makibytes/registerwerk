import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LiveHolder } from './models';

@Component({
  selector: 'app-holder-distribution',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="distribution-container">
      <h3 class="distribution-title">Holder Distribution</h3>
      
      @if (holders.length === 0) {
        <div class="empty-state">
          <p>No holder data available</p>
        </div>
      } @else {
        <div class="stats-grid">
          <div class="stat-item">
            <span class="stat-label">Total Holders</span>
            <span class="stat-value">{{ holders.length }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">Total Supply</span>
            <span class="stat-value">{{ totalSupply | number:'1.0-2' }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">Largest Holder</span>
            <span class="stat-value">{{ largestHolderAmount | number:'1.0-2' }}</span>
          </div>
          <div class="stat-item">
            <span class="stat-label">Average Balance</span>
            <span class="stat-value">{{ averageBalance | number:'1.0-2' }}</span>
          </div>
        </div>

        <!-- Simple bar chart representation -->
        <div class="chart-container" style="margin-top: 20px;">
          <div class="chart-title">Top Holders</div>
          @for (holder of topHolders; track holder.walletAddress) {
            <div class="bar-row">
              <span class="bar-label" [title]="holder.walletAddress">
                {{ holder.walletAddress.substring(0, 6) }}...{{ holder.walletAddress.substring(38) }}
              </span>
              <div class="bar-wrapper">
                <div class="bar" [style.width.%]="(holder.tokenBalance / largestHolderAmount) * 100"></div>
              </div>
              <span class="bar-value">{{ holder.tokenBalance | number:'1.0-0' }}</span>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .distribution-container {
      padding: 16px;
      background: var(--rw-surface);
      border-radius: 8px;
      border: 1px solid var(--rw-border);
    }

    .distribution-title {
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

    .stats-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 12px;
      margin-bottom: 16px;
    }

    .stat-item {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 12px;
      background: var(--rw-bg);
      border-radius: 6px;
      border: 1px solid var(--rw-border);
    }

    .stat-label {
      font-size: 11px;
      color: var(--rw-text-muted);
      text-transform: uppercase;
      letter-spacing: 0.5px;
      font-weight: 600;
    }

    .stat-value {
      font-size: 18px;
      font-weight: 700;
      color: var(--rw-text-primary);
    }

    .chart-container {
      border-top: 1px solid var(--rw-border);
      padding-top: 16px;
    }

    .chart-title {
      font-size: 12px;
      font-weight: 600;
      color: var(--rw-text-muted);
      text-transform: uppercase;
      letter-spacing: 0.5px;
      margin-bottom: 12px;
    }

    .bar-row {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 12px;
      font-size: 12px;
    }

    .bar-label {
      min-width: 60px;
      font-family: monospace;
      color: var(--rw-text-secondary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .bar-wrapper {
      flex: 1;
      height: 24px;
      background: var(--rw-bg);
      border-radius: 4px;
      overflow: hidden;
      border: 1px solid var(--rw-border);
    }

    .bar {
      height: 100%;
      background: linear-gradient(90deg, #0d9488, #06b6d4);
      transition: width 0.3s ease;
    }

    .bar-value {
      min-width: 60px;
      text-align: right;
      font-weight: 600;
      color: var(--rw-text-primary);
    }
  `],
})
export class HolderDistributionComponent {
  @Input() holders: LiveHolder[] = [];

  get totalSupply(): number {
    return this.holders.reduce((sum, h) => sum + h.tokenBalance, 0);
  }

  get largestHolderAmount(): number {
    if (this.holders.length === 0) return 1;
    return Math.max(...this.holders.map(h => h.tokenBalance));
  }

  get averageBalance(): number {
    if (this.holders.length === 0) return 0;
    return this.totalSupply / this.holders.length;
  }

  get topHolders(): LiveHolder[] {
    return [...this.holders]
      .sort((a, b) => b.tokenBalance - a.tokenBalance)
      .slice(0, 5);
  }
}
