import { ChangeDetectorRef, Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AddressPickerDialogComponent, AddressPickerDialogData } from '../address-picker-dialog.component';
import { MintAction, BurnAction, ForceTransferAction, ForceApproveAction } from './models';

@Component({
  selector: 'app-token-admin-panel',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTabsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatTooltipModule,
  ],
  template: `
    <div class="admin-panel">
      <div class="panel-header">
        <mat-icon class="header-icon">admin_panel_settings</mat-icon>
        <h3 class="panel-title">Token Administration</h3>
        <p class="panel-subtitle">Mint, burn, and manage token transfers</p>
      </div>

      <mat-tab-group class="admin-tabs">
        <!-- Mint Tab -->
        <mat-tab label="Mint">
          <div class="tab-content">
            <p class="tab-description">Issue new tokens to a recipient wallet</p>

            <mat-form-field class="full-width">
              <mat-label>Recipient Wallet Address</mat-label>
              <input matInput
                [(ngModel)]="mintForm.recipient"
                placeholder="0x..."
                class="address-input">
              <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                      (click)="pickWallet(a => mintForm.recipient = a)">
                <mat-icon style="font-size:18px">contacts</mat-icon>
              </button>
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Amount to Mint</mat-label>
              <input matInput type="number"
                [(ngModel)]="mintForm.amount"
                placeholder="0.00"
                step="0.01">
            </mat-form-field>

            <div class="preview-box">
              <span class="preview-label">Preview:</span>
              <span class="preview-value">
                @if (mintForm.amount) {
                  Mint {{ mintForm.amount }} tokens to {{ shortenAddress(mintForm.recipient) }}
                } @else {
                  Enter values to preview
                }
              </span>
            </div>

            <button mat-raised-button color="accent" class="full-width" 
              (click)="submitMint()"
              [disabled]="!isValidMintForm()">
              <mat-icon>add_circle</mat-icon>
              Mint Tokens
            </button>
          </div>
        </mat-tab>

        <!-- Burn Tab -->
        <mat-tab label="Burn">
          <div class="tab-content">
            <p class="tab-description">Destroy tokens from a wallet</p>

            <mat-form-field class="full-width">
              <mat-label>Wallet Address (Optional)</mat-label>
              <input matInput
                [(ngModel)]="burnForm.fromWallet"
                placeholder="Leave empty to burn from msg.sender"
                class="address-input">
              <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                      (click)="pickWallet(a => burnForm.fromWallet = a)">
                <mat-icon style="font-size:18px">contacts</mat-icon>
              </button>
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Amount to Burn</mat-label>
              <input matInput type="number"
                [(ngModel)]="burnForm.amount"
                placeholder="0.00"
                step="0.01">
            </mat-form-field>

            <div class="preview-box warning">
              <mat-icon>warning</mat-icon>
              <span class="preview-label">This action is irreversible</span>
            </div>

            <button mat-raised-button color="warn" class="full-width"
              (click)="submitBurn()"
              [disabled]="!isValidBurnForm()">
              <mat-icon>delete_forever</mat-icon>
              Burn Tokens
            </button>
          </div>
        </mat-tab>

        <!-- Force Transfer Tab -->
        <mat-tab label="Force Transfer">
          <div class="tab-content">
            <p class="tab-description">Manually transfer tokens between wallets (admin action)</p>

            <mat-form-field class="full-width">
              <mat-label>From Wallet Address</mat-label>
              <input matInput
                [(ngModel)]="forceTransferForm.fromWallet"
                placeholder="0x..."
                class="address-input">
              <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                      (click)="pickWallet(a => forceTransferForm.fromWallet = a)">
                <mat-icon style="font-size:18px">contacts</mat-icon>
              </button>
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>To Wallet Address</mat-label>
              <input matInput
                [(ngModel)]="forceTransferForm.toWallet"
                placeholder="0x..."
                class="address-input">
              <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                      (click)="pickWallet(a => forceTransferForm.toWallet = a)">
                <mat-icon style="font-size:18px">contacts</mat-icon>
              </button>
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Amount to Transfer</mat-label>
              <input matInput type="number"
                [(ngModel)]="forceTransferForm.amount"
                placeholder="0.00"
                step="0.01">
            </mat-form-field>

            <div class="preview-box">
              <span class="preview-label">Preview:</span>
              <span class="preview-value">
                @if (forceTransferForm.amount) {
                  Transfer {{ forceTransferForm.amount }} tokens from 
                  {{ shortenAddress(forceTransferForm.fromWallet) }} to 
                  {{ shortenAddress(forceTransferForm.toWallet) }}
                } @else {
                  Enter values to preview
                }
              </span>
            </div>

            <button mat-raised-button color="primary" class="full-width"
              (click)="submitForceTransfer()"
              [disabled]="!isValidForceTransferForm()">
              <mat-icon>swap_horiz</mat-icon>
              Force Transfer
            </button>
          </div>
        </mat-tab>

        <!-- Force Approve Tab -->
        <mat-tab label="Force Approve">
          <div class="tab-content">
            <p class="tab-description">Force-set allowance/operator approval (admin action)</p>

            <mat-form-field class="full-width">
              <mat-label>Owner Wallet Address</mat-label>
              <input matInput
                [(ngModel)]="forceApproveForm.ownerWallet"
                placeholder="0x..."
                class="address-input">
              <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                      (click)="pickWallet(a => forceApproveForm.ownerWallet = a)">
                <mat-icon style="font-size:18px">contacts</mat-icon>
              </button>
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Spender Wallet Address</mat-label>
              <input matInput
                [(ngModel)]="forceApproveForm.spenderWallet"
                placeholder="0x..."
                class="address-input">
              <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                      (click)="pickWallet(a => forceApproveForm.spenderWallet = a)">
                <mat-icon style="font-size:18px">contacts</mat-icon>
              </button>
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Amount / Value</mat-label>
              <input matInput type="number"
                [(ngModel)]="forceApproveForm.amount"
                placeholder="0.00"
                step="0.01">
            </mat-form-field>

            <div class="preview-box">
              <span class="preview-label">Preview:</span>
              <span class="preview-value">
                @if (forceApproveForm.amount) {
                  Approve {{ forceApproveForm.amount }} from
                  {{ shortenAddress(forceApproveForm.ownerWallet) }} to
                  {{ shortenAddress(forceApproveForm.spenderWallet) }}
                } @else {
                  Enter values to preview
                }
              </span>
            </div>

            <button mat-raised-button color="primary" class="full-width"
              (click)="submitForceApprove()"
              [disabled]="!isValidForceApproveForm()">
              <mat-icon>verified</mat-icon>
              Force Approve
            </button>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    .admin-panel {
      border: 1px solid var(--rw-border);
      border-radius: 8px;
      background: var(--rw-surface);
      overflow: hidden;
    }

    .panel-header {
      padding: 20px;
      background: linear-gradient(135deg, #0d9488 0%, #06b6d4 100%);
      color: white;
      display: flex;
      align-items: flex-start;
      gap: 12px;
    }

    .header-icon {
      font-size: 24px;
      height: 24px;
      width: 24px;
      margin-top: 2px;
      flex-shrink: 0;
    }

    .panel-title {
      margin: 0;
      font-size: 16px;
      font-weight: 700;
      flex: 1;
    }

    .panel-subtitle {
      margin: 4px 0 0 0;
      font-size: 12px;
      opacity: 0.9;
      flex: 1;
    }

    .admin-tabs {
      --mdc-tab-indicator-active-indicator-color: #0d9488;
    }

    .tab-content {
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }

    .tab-description {
      font-size: 13px;
      color: var(--rw-text-muted);
      margin: 0;
    }

    .full-width {
      width: 100%;
    }

    .address-input {
      font-family: monospace;
      font-size: 12px;
    }

    .preview-box {
      padding: 12px;
      background: var(--rw-bg);
      border: 1px solid var(--rw-border);
      border-left: 3px solid #0d9488;
      border-radius: 6px;
      display: flex;
      gap: 8px;
      font-size: 12px;
    }

    .preview-box.warning {
      border-left-color: #f59e0b;
      background: rgba(245, 158, 11, 0.05);
    }

    .preview-box.warning mat-icon {
      color: #f59e0b;
      font-size: 16px;
      height: 16px;
      width: 16px;
      flex-shrink: 0;
      margin-top: 2px;
    }

    .preview-label {
      color: var(--rw-text-muted);
      font-weight: 600;
      flex-shrink: 0;
    }

    .preview-value {
      color: var(--rw-text-secondary);
      flex: 1;
      word-break: break-word;
    }

    button {
      margin-top: 8px;
      height: 40px;
      font-weight: 600;
    }

    button:disabled {
      opacity: 0.5;
    }
  `],
})
export class TokenAdminPanelComponent {
  @Input() assetId!: string;
  @Input() deploymentId!: string;

  @Output() mint = new EventEmitter<MintAction>();
  @Output() burn = new EventEmitter<BurnAction>();
  @Output() forceTransfer = new EventEmitter<ForceTransferAction>();
  @Output() forceApprove = new EventEmitter<ForceApproveAction>();

  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);

  mintForm = {
    recipient: '',
    amount: 0,
  };

  burnForm = {
    fromWallet: '',
    amount: 0,
  };

  forceTransferForm = {
    fromWallet: '',
    toWallet: '',
    amount: 0,
  };

  forceApproveForm = {
    ownerWallet: '',
    spenderWallet: '',
    amount: 0,
  };

  pickWallet(setter: (addr: string) => void): void {
    this.dialog.open<AddressPickerDialogComponent, AddressPickerDialogData, string>(
      AddressPickerDialogComponent,
      { data: { mode: 'WALLET', title: 'Select wallet' }, width: '560px' }
    ).afterClosed().subscribe(addr => {
      if (addr) {
        setter(addr);
        this.cdr.markForCheck(); // zoneless: picked address must re-render
      }
    });
  }

  isValidMintForm(): boolean {
    return this.isValidAddress(this.mintForm.recipient) && this.mintForm.amount > 0;
  }

  isValidBurnForm(): boolean {
    return this.burnForm.amount > 0;
  }

  isValidForceTransferForm(): boolean {
    return (
      this.isValidAddress(this.forceTransferForm.fromWallet) &&
      this.isValidAddress(this.forceTransferForm.toWallet) &&
      this.forceTransferForm.amount > 0
    );
  }

  isValidForceApproveForm(): boolean {
    return (
      this.isValidAddress(this.forceApproveForm.ownerWallet) &&
      this.isValidAddress(this.forceApproveForm.spenderWallet) &&
      this.forceApproveForm.amount > 0
    );
  }

  private isValidAddress(address: string): boolean {
    return /^0x[a-fA-F0-9]{40}$/.test(address);
  }

  submitMint(): void {
    if (!this.isValidMintForm()) return;
    this.mint.emit({ recipient: this.mintForm.recipient, amount: this.mintForm.amount });
    this.mintForm = { recipient: '', amount: 0 };
  }

  submitBurn(): void {
    if (!this.isValidBurnForm()) return;
    this.burn.emit({ amount: this.burnForm.amount, fromWallet: this.burnForm.fromWallet || undefined });
    this.burnForm = { fromWallet: '', amount: 0 };
  }

  submitForceTransfer(): void {
    if (!this.isValidForceTransferForm()) return;
    this.forceTransfer.emit({
      fromWallet: this.forceTransferForm.fromWallet,
      toWallet: this.forceTransferForm.toWallet,
      amount: this.forceTransferForm.amount,
    });
    this.forceTransferForm = { fromWallet: '', toWallet: '', amount: 0 };
  }

  submitForceApprove(): void {
    if (!this.isValidForceApproveForm()) return;
    this.forceApprove.emit({
      ownerWallet: this.forceApproveForm.ownerWallet,
      spenderWallet: this.forceApproveForm.spenderWallet,
      amount: this.forceApproveForm.amount,
    });
    this.forceApproveForm = { ownerWallet: '', spenderWallet: '', amount: 0 };
  }

  shortenAddress(address: string): string {
    if (!address || address.length < 12) return address;
    return `${address.substring(0, 6)}...${address.substring(address.length - 4)}`;
  }
}
