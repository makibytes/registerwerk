import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-status-badge',
  standalone: true,
  template: `<span [class]="'status-badge status-' + cssClass">{{ label }}</span>`,
})
export class StatusBadgeComponent {
  @Input({ required: true }) status!: string;

  get label(): string {
    return this.status.replace(/_/g, ' ');
  }

  get cssClass(): string {
    return this.status.toLowerCase().replace(/_/g, '-');
  }
}
