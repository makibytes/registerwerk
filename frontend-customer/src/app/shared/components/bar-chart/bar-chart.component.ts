import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

export interface BarItem {
  label: string;
  value: number;
  color?: string;
  subtitle?: string;
}

@Component({
  selector: 'app-bar-chart',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="bar-chart">
      @for (item of items; track item.label; let i = $index) {
        <div class="bar-row">
          <div class="bar-labels">
            <span class="bar-label" [title]="item.label">{{ item.label }}</span>
            @if (item.subtitle) {
              <span class="bar-subtitle">{{ item.subtitle }}</span>
            }
          </div>
          <div class="bar-track">
            <div
              class="bar-fill"
              [style.width.%]="maxValue > 0 ? (item.value / maxValue * 100) : 0"
              [style.background]="item.color ?? defaultColors[i % defaultColors.length]"
            ></div>
          </div>
          <div class="bar-value">{{ formatValue(item.value) }}</div>
        </div>
      }
      @if (items.length === 0) {
        <div class="empty">No data</div>
      }
    </div>
  `,
  styles: [`
    .bar-chart {
      display: flex;
      flex-direction: column;
      gap: 10px;
      width: 100%;
    }
    .bar-row {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    .bar-labels {
      width: 110px;
      min-width: 110px;
      display: flex;
      flex-direction: column;
    }
    .bar-label {
      font-size: 12px;
      color: #546e7a;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .bar-subtitle {
      font-size: 10px;
      color: #90a4ae;
    }
    .bar-track {
      flex: 1;
      height: 16px;
      background: #f5f5f5;
      border-radius: 8px;
      overflow: hidden;
    }
    .bar-fill {
      height: 100%;
      border-radius: 8px;
      transition: width 0.5s ease;
      min-width: 4px;
    }
    .bar-value {
      width: 80px;
      min-width: 80px;
      text-align: right;
      font-size: 12px;
      font-weight: 600;
      color: #37474f;
    }
    .empty {
      font-size: 13px;
      color: #90a4ae;
      text-align: center;
      padding: 12px;
    }
  `],
})
export class BarChartComponent {
  @Input() items: BarItem[] = [];
  @Input() valueFormat: 'number' | 'currency' | 'compact' = 'number';

  readonly defaultColors = [
    '#00695c', '#0288d1', '#6a1b9a', '#f57c00',
    '#2e7d32', '#c62828', '#1565c0', '#558b2f',
  ];

  get maxValue(): number {
    return Math.max(...this.items.map(i => i.value), 1);
  }

  formatValue(v: number): string {
    if (this.valueFormat === 'currency') {
      if (v >= 1_000_000) return `€${(v / 1_000_000).toFixed(1)}M`;
      if (v >= 1_000) return `€${(v / 1_000).toFixed(0)}K`;
      return `€${v.toLocaleString('de-DE', { maximumFractionDigits: 0 })}`;
    }
    if (this.valueFormat === 'compact') {
      if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`;
      if (v >= 1_000) return `${(v / 1_000).toFixed(0)}K`;
      return String(v);
    }
    return v.toLocaleString();
  }
}
