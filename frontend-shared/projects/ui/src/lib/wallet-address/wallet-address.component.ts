import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

/**
 * Displays a wallet address in shortened form with copy and hover functionality.
 *
 * Display: 0x + first 6 chars + "..." + last 6 chars
 * Actions: copy full address, hover to see full address
 */
@Component({
  selector: 'lib-wallet-address',
  standalone: true,
  imports: [CommonModule, MatButtonModule, MatIconModule, MatTooltipModule, MatSnackBarModule],
  template: `
    <div class="wallet-address-container">
      <code 
        [matTooltip]="address" 
        matTooltipPosition="above"
        class="wallet-address-code"
        [attr.aria-label]="'Wallet address: ' + address">
        {{ shortAddress }}
      </code>
      <button 
        mat-icon-button 
        (click)="copyToClipboard()" 
        matTooltip="Copy full address"
        matTooltipPosition="above"
        class="copy-button"
        [attr.aria-label]="'Copy wallet address ' + address">
        <mat-icon>content_copy</mat-icon>
      </button>
    </div>
  `,
  styles: [`
    .wallet-address-container {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-family: 'IBM Plex Mono', monospace;
      font-size: 12px;
      background: var(--rw-surface, #f5f5f5);
      padding: 4px 8px;
      border-radius: 4px;
      border: 1px solid var(--rw-border, #e0e0e0);
    }

    .wallet-address-code {
      cursor: help;
      color: var(--rw-text-primary, #0d1526);
      margin: 0;
      padding: 0;
      user-select: all;

      &:hover {
        background: var(--rw-border, #e0e0e0);
        border-radius: 2px;
      }
    }

    .copy-button {
      margin: 0 !important;
      width: 24px !important;
      height: 24px !important;

      mat-icon {
        font-size: 14px !important;
        width: 14px !important;
        height: 14px !important;
        line-height: 14px !important;
      }

      &:hover {
        background: rgba(0, 0, 0, 0.04);
      }
    }
  `]
})
export class WalletAddressComponent {
  @Input() address!: string;

  get shortAddress(): string {
    if (!this.address || this.address.length < 13) return this.address;
    return `${this.address.slice(0, 8)}...${this.address.slice(-6)}`;
  }

  constructor(private snackBar: MatSnackBar) {}

  copyToClipboard(): void {
    if (!this.address) return;
    navigator.clipboard.writeText(this.address).then(() => {
      this.snackBar.open('Address copied!', 'Close', { duration: 2000 });
    });
  }
}
