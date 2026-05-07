import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';

export interface DonutSlice {
  label: string;
  value: number;
  color: string;
}

interface ComputedSlice extends DonutSlice {
  dasharray: string;
  dashoffset: number;
  percentage: number;
}

@Component({
  selector: 'app-donut-chart',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="donut-wrap">
      <svg viewBox="0 0 100 100" class="donut-svg" aria-hidden="true">
        @if (total === 0) {
          <circle cx="50" cy="50" r="35" fill="none" class="empty-ring" stroke-width="14" />
        }
        @for (s of computed; track s.label) {
          <circle
            cx="50" cy="50" r="35"
            fill="none"
            [attr.stroke]="s.color"
            stroke-width="14"
            stroke-linecap="butt"
            [attr.stroke-dasharray]="s.dasharray"
            [attr.stroke-dashoffset]="s.dashoffset"
            transform="rotate(-90 50 50)"
          />
        }
        <text x="50" y="47" text-anchor="middle" class="center-label">{{ centerLabel }}</text>
        <text x="50" y="60" text-anchor="middle" class="center-value">{{ centerValue }}</text>
      </svg>

      <div class="legend">
        @for (s of computed; track s.label) {
          <div class="legend-item">
            <span class="legend-dot" [style.background]="s.color"></span>
            <span class="legend-text">{{ s.label }}</span>
            <span class="legend-pct">{{ s.percentage | number:'1.0-1' }}%</span>
          </div>
        }
        @if (computed.length === 0) {
          <span class="legend-empty">No data</span>
        }
      </div>
    </div>
  `,
  styles: [`
    .donut-wrap {
      display: flex;
      align-items: center;
      gap: 20px;
    }
    .donut-svg {
      width: 140px;
      min-width: 140px;
      height: 140px;
    }
    .center-label {
      font-size: 8px;
      fill: var(--rw-text-muted);
      font-family: 'Manrope', sans-serif;
    }
    .center-value {
      font-size: 11px;
      font-weight: 700;
      fill: var(--rw-text-primary);
      font-family: 'Manrope', sans-serif;
    }
    .empty-ring { stroke: var(--rw-border); }
    .legend {
      display: flex;
      flex-direction: column;
      gap: 8px;
      min-width: 0;
    }
    .legend-item {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 13px;
    }
    .legend-dot {
      width: 10px;
      height: 10px;
      border-radius: 50%;
      flex-shrink: 0;
    }
    .legend-text {
      color: var(--rw-text-secondary);
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .legend-pct {
      margin-left: auto;
      font-weight: 600;
      color: var(--rw-text-primary);
      padding-left: 8px;
    }
    .legend-empty {
      font-size: 13px;
      color: var(--rw-text-muted);
    }
  `],
})
export class DonutChartComponent implements OnChanges {
  @Input() slices: DonutSlice[] = [];
  @Input() centerLabel = 'Total';
  @Input() centerValue = '';

  computed: ComputedSlice[] = [];
  total = 0;

  private readonly r = 35;
  private readonly circumference = 2 * Math.PI * this.r;

  ngOnChanges(): void {
    this.total = this.slices.reduce((s, x) => s + x.value, 0);
    if (this.total === 0) {
      this.computed = [];
      return;
    }
    let accumulated = 0;
    this.computed = this.slices
      .filter(s => s.value > 0)
      .map(s => {
        const len = (s.value / this.total) * this.circumference;
        const dasharray = `${len} ${this.circumference - len}`;
        const dashoffset = -accumulated;
        accumulated += len;
        return {
          ...s,
          dasharray,
          dashoffset,
          percentage: (s.value / this.total) * 100,
        };
      });
  }
}
