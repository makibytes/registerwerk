import { Component, Input } from '@angular/core';
import { MatChipsModule } from '@angular/material/chips';
import { NgStyle } from '@angular/common';

type StatusColor = 'green' | 'orange' | 'red' | 'grey' | 'blue';

const STATUS_COLOR_MAP: Record<string, StatusColor> = {
  // Green — healthy/complete
  ACTIVE: 'green',
  APPROVED: 'green',
  ISSUED: 'green',
  CONFIRMED: 'green',
  // Orange — in-progress / pending
  PENDING_ONBOARDING: 'orange',
  PENDING_APPROVAL: 'orange',
  IN_PROGRESS: 'orange',
  PENDING: 'orange',
  NOT_STARTED: 'orange',
  DRAFT: 'blue',
  // Red — bad states
  SUSPENDED: 'red',
  REJECTED: 'red',
  FAILED: 'red',
  EXPIRED: 'red',
  // Grey — terminal/archived
  DISSOLVED: 'grey',
  REDEEMED: 'grey',
};

const COLOR_STYLES: Record<StatusColor, { background: string; color: string }> = {
  green: { background: '#e8f5e9', color: '#2e7d32' },
  orange: { background: '#fff3e0', color: '#e65100' },
  red: { background: '#ffebee', color: '#c62828' },
  grey: { background: '#f5f5f5', color: '#616161' },
  blue: { background: '#e3f2fd', color: '#1565c0' },
};

@Component({
  selector: 'app-status-badge',
  standalone: true,
  imports: [MatChipsModule, NgStyle],
  template: `
    <span
      class="status-chip"
      [ngStyle]="styles"
    >{{ label }}</span>
  `,
  styles: [`
    .status-chip {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 12px;
      font-size: 12px;
      font-weight: 500;
      letter-spacing: 0.3px;
      white-space: nowrap;
    }
  `],
})
export class StatusBadgeComponent {
  @Input() status = '';

  get label(): string {
    return this.status.replace(/_/g, ' ');
  }

  get styles(): Record<string, string> {
    const colorKey: StatusColor = STATUS_COLOR_MAP[this.status] ?? 'grey';
    return COLOR_STYLES[colorKey] as Record<string, string>;
  }
}
