import { Component, Input, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { DecimalPipe } from '@angular/common';
import { WalletAddressComponent } from '../wallet-address/wallet-address.component';
import { LiveHolder, MintAction, BurnAction, ForceTransferAction } from '../models';

/**
 * Admin panel for minting, burning, and force-transferring tokens.
 *
 * Features:
 * - Tabbed interface (Mint, Burn, Force Transfer)
 * - Known wallet picker or free-text unknown wallet entry
 * - Danger warnings for unknown wallets
 * - Amount input validation
 */
@Component({
  selector: 'lib-token-admin',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatTabsModule,
    MatDividerModule,
    MatChipsModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
    DecimalPipe,
    WalletAddressComponent,
  ],
  template: `
    <mat-card class="admin-panel-card">
      <mat-card-header>
        <mat-card-title>Token Administration</mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <mat-tab-group class="admin-tabs">
          <!-- MINT TAB -->
          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>add_circle</mat-icon>
              Mint
            </ng-template>

            <div class="tab-content">
              <form [formGroup]="mintForm" (ngSubmit)="onMint()">
                <div class="form-section">
                  <h3>Mint Tokens</h3>
                  <p class="form-description">
                    Issue new tokens to an existing investor wallet. The recipient must be whitelisted.
                  </p>

                  <!-- Wallet Mode Toggle -->
                  <div class="mode-toggle">
                    <button
                      type="button"
                      (click)="toggleMintMode('known')"
                      [class.active]="mintMode === 'known'"
                      class="mode-button">
                      <mat-icon>check_circle</mat-icon>
                      Known Wallet
                    </button>
                    <button
                      type="button"
                      (click)="toggleMintMode('unknown')"
                      [class.active]="mintMode === 'unknown'"
                      class="mode-button">
                      <mat-icon>help_circle</mat-icon>
                      Other Wallet
                    </button>
                  </div>

                  <!-- Known Wallet Mode -->
                  @if (mintMode === 'known') {
                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Select Wallet</mat-label>
                      <mat-select formControlName="mintWallet">
                        @for (holder of knownHolders; track holder.walletAddress) {
                          <mat-option [value]="holder.walletAddress">
                            <lib-wallet-address [address]="holder.walletAddress"></lib-wallet-address>
                            {{ holder.entityName }}
                          </mat-option>
                        }
                      </mat-select>
                      @if (knownHolders.length === 0) {
                        <mat-hint>No known wallets available</mat-hint>
                      }
                    </mat-form-field>
                  } @else {
                    <!-- Unknown Wallet Mode (Dangerous) -->
                    <div class="danger-banner">
                      <mat-icon>warning</mat-icon>
                      <div>
                        <strong>Caution:</strong> Minting to an unregistered wallet bypasses KYC controls.
                      </div>
                    </div>

                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Wallet Address</mat-label>
                      <input
                        matInput
                        formControlName="mintWallet"
                        placeholder="0x..."
                        [value]="mintForm.get('mintWallet')?.value">
                      <mat-hint>Paste a full 0x wallet address</mat-hint>
                    </mat-form-field>
                  }

                  <!-- Amount -->
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Amount</mat-label>
                    <input
                      matInput
                      type="number"
                      formControlName="mintAmount"
                      placeholder="0.0"
                      step="0.01"
                      min="0">
                    <mat-hint>Number of tokens to mint</mat-hint>
                  </mat-form-field>

                  <!-- Reason (optional) -->
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Reason (optional)</mat-label>
                    <input
                      matInput
                      formControlName="mintReason"
                      placeholder="e.g., Investor allocation, bonus issuance">
                  </mat-form-field>

                  <button
                    type="submit"
                    mat-raised-button
                    color="accent"
                    [disabled]="!mintForm.valid || minting"
                    class="submit-button">
                    @if (minting) {
                      <mat-spinner diameter="18"></mat-spinner>
                    } @else {
                      <mat-icon>add_circle</mat-icon>
                      Mint Tokens
                    }
                  </button>
                </div>
              </form>
            </div>
          </mat-tab>

          <!-- BURN TAB -->
          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>local_fire_department</mat-icon>
              Burn
            </ng-template>

            <div class="tab-content">
              <form [formGroup]="burnForm" (ngSubmit)="onBurn()">
                <div class="form-section">
                  <h3>Burn Tokens</h3>
                  <p class="form-description">
                    Permanently remove tokens from circulation. This cannot be undone.
                  </p>

                  <!-- Wallet Selection -->
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Select Holder</mat-label>
                    <mat-select formControlName="burnWallet">
                      @for (holder of holders; track holder.walletAddress) {
                        <mat-option [value]="holder.walletAddress">
                          <span class="option-label">
                            {{ holder.entityName || 'Unknown' }}
                          </span>
                          <lib-wallet-address [address]="holder.walletAddress"></lib-wallet-address>
                          <span class="option-balance">
                            {{ holder.balance | number: '1.0-2' }} tokens
                          </span>
                        </mat-option>
                      }
                    </mat-select>
                  </mat-form-field>

                  <!-- Amount -->
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Amount to Burn</mat-label>
                    <input
                      matInput
                      type="number"
                      formControlName="burnAmount"
                      placeholder="0.0"
                      step="0.01"
                      min="0">
                    <mat-hint>Number of tokens to remove</mat-hint>
                  </mat-form-field>

                  <!-- Confirmation -->
                  <div class="confirmation-checkbox">
                    <input
                      type="checkbox"
                      formControlName="burnConfirm"
                      id="burn-confirm">
                    <label for="burn-confirm">
                      I understand this action cannot be undone
                    </label>
                  </div>

                  <button
                    type="submit"
                    mat-raised-button
                    [color]="'warn'"
                    [disabled]="!burnForm.valid || burning"
                    class="submit-button danger">
                    @if (burning) {
                      <mat-spinner diameter="18"></mat-spinner>
                    } @else {
                      <mat-icon>local_fire_department</mat-icon>
                      Burn Tokens
                    }
                  </button>
                </div>
              </form>
            </div>
          </mat-tab>

          <!-- FORCE TRANSFER TAB -->
          <mat-tab>
            <ng-template mat-tab-label>
              <mat-icon>swap_horiz</mat-icon>
              Force Transfer
            </ng-template>

            <div class="tab-content">
              <form [formGroup]="forceTransferForm" (ngSubmit)="onForceTransfer()">
                <div class="form-section">
                  <h3>Forced Transfer</h3>
                  <p class="form-description">
                    Regulatory transfer bypassing all compliance checks. Legal basis required (eWpG §24).
                  </p>

                  <!-- From Wallet -->
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>From Holder</mat-label>
                    <mat-select formControlName="forceFromWallet">
                      @for (holder of holders; track holder.walletAddress) {
                        <mat-option [value]="holder.walletAddress">
                          {{ holder.entityName || 'Unknown' }}
                          <lib-wallet-address [address]="holder.walletAddress"></lib-wallet-address>
                        </mat-option>
                      }
                    </mat-select>
                  </mat-form-field>

                  <!-- To Wallet -->
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>To Wallet Address</mat-label>
                    <input
                      matInput
                      formControlName="forceToWallet"
                      placeholder="0x...">
                  </mat-form-field>

                  <!-- Amount -->
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Amount</mat-label>
                    <input
                      matInput
                      type="number"
                      formControlName="forceAmount"
                      placeholder="0.0"
                      step="0.01"
                      min="0">
                  </mat-form-field>

                  <!-- Legal Basis (Required) -->
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Legal Basis (Required)</mat-label>
                    <input
                      matInput
                      formControlName="forceLegalBasis"
                      placeholder="e.g., BaFin Az. 2025-001 / Court order ref">
                  </mat-form-field>

                  <button
                    type="submit"
                    mat-raised-button
                    [disabled]="!forceTransferForm.valid || forceTransferring"
                    class="submit-button">
                    @if (forceTransferring) {
                      <mat-spinner diameter="18"></mat-spinner>
                    } @else {
                      <mat-icon>swap_horiz</mat-icon>
                      Execute Transfer
                    }
                  </button>
                </div>
              </form>
            </div>
          </mat-tab>
        </mat-tab-group>
      </mat-card-content>
    </mat-card>
  `,
  styles: [`
    .admin-panel-card {
      background: var(--rw-surface, white);
      border: 1px solid var(--rw-border, #e0e0e0);
      border-radius: 4px;
      margin-top: 20px;
    }

    mat-card-header {
      margin-bottom: 0;

      mat-card-title {
        font-size: 16px;
        font-weight: 600;
        margin: 0;
      }
    }

    mat-card-content {
      padding: 20px 0;
    }

    .admin-tabs {
      ::ng-deep {
        .mat-mdc-tab-labels {
          background: var(--rw-bg, #f5f5f5);
          border-bottom: 1px solid var(--rw-border, #e0e0e0);
        }

        .mat-mdc-tab {
          min-width: 120px;

          mat-icon {
            margin-right: 8px;
          }
        }

        .mat-mdc-tab-body-content {
          padding: 0;
        }
      }
    }

    .tab-content {
      padding: 24px 20px;
      background: var(--rw-surface, white);
    }

    .form-section {
      display: flex;
      flex-direction: column;
      gap: 16px;

      h3 {
        margin: 0 0 8px;
        font-size: 16px;
        font-weight: 600;
        color: var(--rw-text-primary, #0d1526);
      }
    }

    .form-description {
      margin: 0;
      font-size: 13px;
      color: var(--rw-text-secondary, #666);
      line-height: 1.5;
    }

    .full-width {
      width: 100%;
    }

    .mode-toggle {
      display: flex;
      gap: 8px;
      margin-bottom: 12px;
    }

    .mode-button {
      flex: 1;
      padding: 8px 12px;
      border: 1px solid var(--rw-border, #e0e0e0);
      background: var(--rw-surface, white);
      border-radius: 4px;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 6px;
      font-size: 13px;
      font-weight: 500;
      color: var(--rw-text-secondary, #666);
      transition: all 200ms ease;

      mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
      }

      &:hover {
        border-color: var(--rw-accent, #0d9488);
      }

      &.active {
        background: rgba(13, 148, 136, 0.12);
        border-color: var(--rw-accent, #0d9488);
        color: var(--rw-accent, #0d9488);
      }
    }

    .danger-banner {
      display: flex;
      align-items: flex-start;
      gap: 12px;
      padding: 12px;
      background: rgba(239, 68, 68, 0.08);
      border: 1px solid rgba(239, 68, 68, 0.2);
      border-left: 4px solid #ef4444;
      border-radius: 4px;
      font-size: 13px;
      color: #d32f2f;

      mat-icon {
        margin-top: 2px;
        flex-shrink: 0;
      }
    }

    .confirmation-checkbox {
      display: flex;
      align-items: center;
      gap: 8px;

      input[type="checkbox"] {
        cursor: pointer;
        width: 16px;
        height: 16px;
      }

      label {
        cursor: pointer;
        font-size: 13px;
        color: var(--rw-text-secondary, #666);
        margin: 0;
      }
    }

    .submit-button {
      width: 100%;
      min-height: 40px;
      font-weight: 600;

      mat-icon {
        margin-right: 8px;
      }

      &.danger {
        background: linear-gradient(135deg, #ef4444, #dc2626);
        color: white;

        &:hover:not([disabled]) {
          background: linear-gradient(135deg, #dc2626, #b91c1c);
        }
      }

      &[disabled] {
        opacity: 0.5;
        cursor: not-allowed;
      }
    }

    .option-label {
      display: inline-block;
      width: 100px;
    }

    .option-balance {
      margin-left: auto;
      font-size: 11px;
      color: var(--rw-text-muted, #999);
      font-family: 'IBM Plex Mono', monospace;
    }

    @media (max-width: 600px) {
      .tab-content {
        padding: 16px;
      }

      .mode-toggle {
        flex-direction: column;
      }

      .mode-button {
        justify-content: flex-start;
      }
    }
  `]
})
export class TokenAdminPanelComponent {
  @Input() holders: LiveHolder[] = [];
  @Input() loading = false;

  @Output() mint = new EventEmitter<MintAction>();
  @Output() burn = new EventEmitter<BurnAction>();
  @Output() forceTransfer = new EventEmitter<ForceTransferAction>();

  mintForm!: FormGroup;
  burnForm!: FormGroup;
  forceTransferForm!: FormGroup;

  mintMode: 'known' | 'unknown' = 'known';
  minting = false;
  burning = false;
  forceTransferring = false;

  get knownHolders(): LiveHolder[] {
    return this.holders.filter(h => h.known);
  }

  constructor(private fb: FormBuilder) {
    this.initializeForms();
  }

  private initializeForms(): void {
    this.mintForm = this.fb.group({
      mintWallet: ['', Validators.required],
      mintAmount: ['', [Validators.required, Validators.min(0.01)]],
      mintReason: [''],
    });

    this.burnForm = this.fb.group({
      burnWallet: ['', Validators.required],
      burnAmount: ['', [Validators.required, Validators.min(0.01)]],
      burnConfirm: [false, Validators.requiredTrue],
    });

    this.forceTransferForm = this.fb.group({
      forceFromWallet: ['', Validators.required],
      forceToWallet: ['', [Validators.required, this.validateEthAddress.bind(this)]],
      forceAmount: ['', [Validators.required, Validators.min(0.01)]],
      forceLegalBasis: ['', Validators.required],
    });
  }

  toggleMintMode(mode: 'known' | 'unknown'): void {
    this.mintMode = mode;
    const wallet = this.mintForm.get('mintWallet');
    wallet?.reset();
    if (mode === 'known' && this.knownHolders.length > 0) {
      wallet?.setValue(this.knownHolders[0].walletAddress);
    }
  }

  onMint(): void {
    if (!this.mintForm.valid) return;
    this.minting = true;
    const action: MintAction = {
      toAddress: this.mintForm.value.mintWallet,
      amount: this.mintForm.value.mintAmount,
      reason: this.mintForm.value.mintReason,
    };
    this.mint.emit(action);
    setTimeout(() => { this.minting = false; }, 500);
  }

  onBurn(): void {
    if (!this.burnForm.valid) return;
    this.burning = true;
    const action: BurnAction = {
      fromAddress: this.burnForm.value.burnWallet,
      amount: this.burnForm.value.burnAmount,
    };
    this.burn.emit(action);
    setTimeout(() => { this.burning = false; }, 500);
  }

  onForceTransfer(): void {
    if (!this.forceTransferForm.valid) return;
    this.forceTransferring = true;
    const action: ForceTransferAction = {
      from: this.forceTransferForm.value.forceFromWallet,
      to: this.forceTransferForm.value.forceToWallet,
      value: this.forceTransferForm.value.forceAmount,
      legalBasis: this.forceTransferForm.value.forceLegalBasis,
    };
    this.forceTransfer.emit(action);
    setTimeout(() => { this.forceTransferring = false; }, 500);
  }

  private validateEthAddress(control: any): {[key: string]: any} | null {
    if (!control.value) return null;
    const ethRegex = /^0x[a-fA-F0-9]{40}$/;
    return ethRegex.test(control.value) ? null : { invalidEthAddress: true };
  }
}
