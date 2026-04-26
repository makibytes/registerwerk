import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AsyncSectionStatus } from '../../../core/async/async-section';

@Component({
  selector: 'app-data-state-pill',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (status !== 'ready') {
      <span class="data-state-pill" [class]="'data-state-pill status-' + status">
        {{ label }}
      </span>
    }
  `,
  styles: [`
    .data-state-pill {
      display: inline-flex;
      align-items: center;
      padding: 3px 10px;
      border-radius: 999px;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.7px;
      text-transform: uppercase;
      border: 1px solid transparent;
    }

    .status-pending {
      background: color-mix(in srgb, var(--rw-pending-bg) 85%, transparent);
      color: var(--rw-pending-fg);
      border-color: color-mix(in srgb, var(--rw-pending-fg) 16%, transparent);
    }

    .status-updating {
      background: rgba(59, 130, 246, 0.12);
      color: #2563EB;
      border-color: rgba(59, 130, 246, 0.24);
    }

    .status-error {
      background: rgba(239, 68, 68, 0.12);
      color: #DC2626;
      border-color: rgba(239, 68, 68, 0.24);
    }
  `],
})
export class DataStatePillComponent {
  @Input({ required: true }) status: AsyncSectionStatus = 'ready';

  get label(): string {
    switch (this.status) {
      case 'pending':
        return 'Pending';
      case 'updating':
        return 'Updating';
      case 'error':
        return 'Unavailable';
      default:
        return '';
    }
  }
}
