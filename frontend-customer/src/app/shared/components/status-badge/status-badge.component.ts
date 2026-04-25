import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AssetStatus } from '../../../core/models';

type BadgeStatus = AssetStatus | 'WHITELISTED' | 'NOT_WHITELISTED' | 'PENDING' | 'CONFIRMED' | 'FAILED' | 'REJECTED';

@Component({
  selector: 'app-status-badge',
  standalone: true,
  imports: [CommonModule],
  template: `
    <span [class]="'status-badge status-' + cssClass">
      {{ label }}
    </span>
  `,
})
export class StatusBadgeComponent {
  @Input({ required: true }) status!: BadgeStatus;

  get cssClass(): string {
    return this.status.toLowerCase().replace(/_/g, '-');
  }

  get label(): string {
    const labels: Record<string, string> = {
      DRAFT: 'Draft',
      PENDING_APPROVAL: 'Pending Approval',
      APPROVED: 'Approved',
      ISSUED: 'Issued',
      SUSPENDED: 'Suspended',
      REDEEMED: 'Redeemed',
      WHITELISTED: 'Whitelisted',
      NOT_WHITELISTED: 'Not Whitelisted',
      PENDING: 'Pending',
      CONFIRMED: 'Confirmed',
      FAILED: 'Failed',
      REJECTED: 'Rejected',
    };
    return labels[this.status] ?? this.status;
  }
}
