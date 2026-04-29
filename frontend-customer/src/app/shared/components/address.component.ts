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
      <button class="addr-btn addr-add" (click)="openEndpointDialog($event)"
              matTooltip="Add to address book">
        <mat-icon>bookmark_add</mat-icon>
      </button>
    }

    <button class="addr-btn" (click)="copy($event)" matTooltip="Copy address">
      <mat-icon>content_copy</mat-icon>
    </button>

    @if (explorerUrl) {
      <a class="addr-btn" [href]="explorerUrl + '/address/' + address" target="_blank" rel="noopener"
         matTooltip="View in explorer">
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
    return a.startsWith('0x')
      ? a.slice(0, 8) + '…' + a.slice(-6)
      : a.slice(0, 6) + '…' + a.slice(-6);
  }

  copy(event: Event): void {
    event.stopPropagation();
    navigator.clipboard.writeText(this.address).then(() => {
      this.snackBar.open('Address copied', '', { duration: 1800 });
    });
  }

  openEndpointDialog(event: Event): void {
    event.stopPropagation();
    this.dialog.open(EndpointDialogComponent, {
      width: '420px',
      data: { address: this.address },
    });
  }
}
