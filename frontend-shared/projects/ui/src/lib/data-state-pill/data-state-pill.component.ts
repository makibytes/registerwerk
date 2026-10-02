import { Component, Input } from '@angular/core';
import { AsyncSectionStatus } from '../async-section';

@Component({
  selector: 'app-data-state-pill',
  standalone: true,
  template: `
    @if (status !== 'ready') {
      <span class="data-state-pill" [class]="'data-state-pill status-' + status">
        {{ label }}@if (status === 'stale' && lastLoadedAt) { <span class="as-of">as of {{ asOf }}</span> }
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
      background: color-mix(in srgb, var(--rw-pending-bg, rgba(245,158,11,0.12)) 85%, transparent);
      color: var(--rw-pending-fg, #B45309);
      border-color: color-mix(in srgb, var(--rw-pending-fg, rgba(245,158,11,0.26)) 16%, transparent);
    }

    .status-updating {
      background: var(--rw-draft-bg);
      color: var(--rw-draft-fg);
      border-color: color-mix(in srgb, var(--rw-draft-fg) 24%, transparent);
    }

    .status-stale {
      background: var(--rw-pending-bg);
      color: var(--rw-pending-fg);
      border-color: color-mix(in srgb, var(--rw-pending-fg) 30%, transparent);
    }

    .status-error {
      background: var(--rw-rejected-bg);
      color: var(--rw-rejected-fg);
      border-color: color-mix(in srgb, var(--rw-rejected-fg) 24%, transparent);
    }

    .as-of { margin-left: 6px; text-transform: none; letter-spacing: 0; font-weight: 500; }
  `],
})
export class DataStatePillComponent {
  @Input({ required: true }) status: AsyncSectionStatus = 'ready';
  /** Epoch millis of the last successful load; shown as "as of HH:mm" on the stale pill. */
  @Input() lastLoadedAt?: number;

  get asOf(): string {
    return this.lastLoadedAt == null
      ? ''
      : new Date(this.lastLoadedAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  get label(): string {
    switch (this.status) {
      case 'pending':  return 'Pending';
      case 'updating': return 'Updating';
      case 'stale':    return 'Stale - refresh failed';
      case 'error':    return 'Unavailable';
      default:         return '';
    }
  }
}
