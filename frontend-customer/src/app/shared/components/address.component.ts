import { Component, Input, OnChanges, inject } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Observable, of } from 'rxjs';
import { AddressResolutionService } from '../../core/services/address-resolution.service';
import { EndpointDialogComponent } from './endpoint-dialog.component';

/**
 * Displays a blockchain address in the customer frontend.
 *
 * - Shows resolved name from the address book when available.
 * - On hover, unresolved addresses show a bookmark-add button.
 */
@Component({
  selector: 'app-address',
  standalone: true,
  imports: [AsyncPipe, MatIconModule, MatTooltipModule, MatSnackBarModule, MatDialogModule],
  styles: [`
    :host { display: inline-flex; align-items: center; gap: 4px; }
    :host:hover .addr-add { opacity: 1; }

    .addr-name {
      font-size: 13px;
      font-weight: 500;
      color: var(--rw-text-primary);
    }

    .addr-short {
      font-family: monospace;
      font-size: 12px;
      color: var(--rw-text-secondary);
    }

    .addr-btn {
      background: none;
      border: none;
      padding: 0 2px;
      cursor: pointer;
      color: var(--rw-text-muted);
      display: inline-flex;
      align-items: center;
      border-radius: 3px;
      transition: color 0.15s ease;
    }
    .addr-btn:hover { color: var(--rw-text-primary); }
    .addr-btn mat-icon { font-size: 14px; width: 14px; height: 14px; }

    .addr-add {
      opacity: 0;
      transition: opacity 0.15s ease;
      color: var(--rw-nav-accent);
    }
    .addr-add:hover { opacity: 1 !important; }
  `],
  template: `
    @if (name) {
      <span class="addr-name" [matTooltip]="address">{{ name }}</span>
    } @else if (resolvedName$ | async; as resolved) {
      <span class="addr-name" [matTooltip]="address">{{ resolved }}</span>
    } @else {
      <span class="addr-short" [matTooltip]="address">{{ shortened }}</span>
      <button class="addr-btn addr-add" type="button" (click)="openEndpointDialog($event)"
              matTooltip="Add to address book" aria-label="Add address to address book">
        <mat-icon>bookmark_add</mat-icon>
      </button>
    }

    <button class="addr-btn" type="button" (click)="copy($event)" matTooltip="Copy address" aria-label="Copy address">
      <mat-icon>content_copy</mat-icon>
    </button>

    @if (explorerAddressUrl; as externalAddressUrl) {
      <a class="addr-btn" [href]="externalAddressUrl" target="_blank" rel="noopener noreferrer"
         matTooltip="View in explorer" aria-label="View address in block explorer">
        <mat-icon>open_in_new</mat-icon>
      </a>
    }
  `,
})
export class AddressComponent implements OnChanges {
  @Input({ required: true }) address!: string;
  @Input() name?: string;
  @Input() explorerUrl?: string;

  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly resolution = inject(AddressResolutionService);

  resolvedName$: Observable<string | null> = of(null);

  ngOnChanges(): void {
    if (this.address && !this.name) {
      this.resolvedName$ = this.resolution.resolve(this.address);
    } else {
      this.resolvedName$ = of(null);
    }
  }

  get shortened(): string {
    if (!this.address) return '';
    const a = this.address;
    if (a.length <= 16) return a;
    return a.startsWith('0x')
      ? a.slice(0, 8) + '…' + a.slice(-6)
      : a.slice(0, 6) + '…' + a.slice(-6);
  }

  copy(event: Event): void {
    event.stopPropagation();
    if (!navigator.clipboard) {
      this.snackBar.open('Clipboard access is unavailable.', 'Dismiss', { duration: 4000 });
      return;
    }
    navigator.clipboard.writeText(this.address).then(
      () => this.snackBar.open('Address copied', '', { duration: 1800 }),
      () => this.snackBar.open('Could not copy the address.', 'Dismiss', { duration: 4000 }),
    );
  }

  openEndpointDialog(event: Event): void {
    event.stopPropagation();
    this.dialog.open(EndpointDialogComponent, {
      width: '420px',
      maxWidth: '95vw',
      data: { address: this.address },
    });
  }

  get explorerAddressUrl(): string | null {
    if (!this.explorerUrl || !this.address) return null;
    try {
      const base = new URL(this.explorerUrl);
      if (base.protocol !== 'https:' && base.protocol !== 'http:') return null;
      const path = `${base.pathname.replace(/\/$/, '')}/address/${encodeURIComponent(this.address)}`;
      base.pathname = path;
      base.search = '';
      base.hash = '';
      return base.toString();
    } catch {
      return null;
    }
  }
}
