import { ChangeDetectorRef, Component, EventEmitter, Input, OnDestroy, Output, inject } from '@angular/core';
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
import { ApprovalRequestSession, CreateApprovalRequest } from '@registerwerk/ui';
import { ApprovalQueueService } from '../../../core/api/approval-queue.service';
import { ApprovalRequestBoxComponent } from '../approval/approval-request-box.component';
import { MintAction, BurnAction, ForceTransferAction, ForceApproveAction } from './models';

export type ApprovalKind = 'mint' | 'burn' | 'forceTransfer' | 'forceApprove';

/** The `@RequiresStepUp` reason and endpoint segment of each issuer action that needs a second approver. */
const APPROVAL_ACTIONS: Record<ApprovalKind, { action: string; segment: string }> = {
  mint: { action: 'ISSUER_MINT', segment: 'mint' },
  burn: { action: 'ISSUER_BURN_EWG26', segment: 'burn' },
  forceTransfer: { action: 'ISSUER_FORCED_TRANSFER_EWG24', segment: 'forced-transfer' },
  forceApprove: { action: 'ISSUER_FORCED_APPROVE_OVERRIDE', segment: 'forced-approve' },
};

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
    ApprovalRequestBoxComponent,
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
              <input matInput type="text" inputmode="numeric" autocomplete="off"
                [(ngModel)]="mintForm.amount"
                placeholder="Whole token units, e.g. 1000">
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Authenticator code</mat-label>
              <input matInput [(ngModel)]="mintForm.totpCode" inputmode="numeric" maxlength="6"
                     autocomplete="one-time-code">
              <mat-hint>Built-in sign-in only. With Microsoft sign-in you are asked to re-authenticate instead.</mat-hint>
            </mat-form-field>

            <app-approval-request-box [session]="sessions.mint" [request]="approvalRequestFor('mint')" />

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

            <button type="button" mat-raised-button color="accent" class="full-width"
              (click)="submitMint()"
              [disabled]="busy || !isValidMintForm()">
              <mat-icon>add_circle</mat-icon>
              Mint Tokens
            </button>
          </div>
        </mat-tab>

        <!-- Burn Tab -->
        <mat-tab label="Burn">
          <div class="tab-content">
            <p class="tab-description">Destroy tokens held by a register entry of this asset</p>

            <div class="preview-box">
              <mat-icon>verified_user</mat-icon>
              <span class="preview-label">
                A burn is a cancellation under eWpG §26. It needs an ASSET_TOKEN_ADMIN grant for this asset
                and the approval of a registry administrator (4-eyes), requested below.
              </span>
            </div>

            <mat-form-field class="full-width">
              <mat-label>Holder wallet address</mat-label>
              <input matInput
                [(ngModel)]="burnForm.fromWallet"
                placeholder="0x…"
                required
                class="address-input">
              <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                      (click)="pickWallet(a => burnForm.fromWallet = a)">
                <mat-icon style="font-size:18px">contacts</mat-icon>
              </button>
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Amount to Burn</mat-label>
              <input matInput type="text" inputmode="numeric" autocomplete="off"
                [(ngModel)]="burnForm.amount"
                placeholder="Whole token units, e.g. 1000">
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Authenticator code</mat-label>
              <input matInput [(ngModel)]="burnForm.totpCode" inputmode="numeric" maxlength="6"
                     autocomplete="one-time-code">
              <mat-hint>Built-in sign-in only. With Microsoft sign-in you are asked to re-authenticate instead.</mat-hint>
            </mat-form-field>

            <app-approval-request-box [session]="sessions.burn" [request]="approvalRequestFor('burn')" />

            <div class="preview-box warning">
              <mat-icon>warning</mat-icon>
              <span class="preview-label">This action is irreversible</span>
            </div>

            <button type="button" mat-raised-button color="warn" class="full-width"
              (click)="submitBurn()"
              [disabled]="busy || !isValidBurnForm()">
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
              <input matInput type="text" inputmode="numeric" autocomplete="off"
                [(ngModel)]="forceTransferForm.amount"
                placeholder="Whole token units, e.g. 1000">
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Legal authority / reference</mat-label>
              <textarea matInput rows="2" maxlength="2000"
                [(ngModel)]="forceTransferForm.legalBasis"
                placeholder="e.g. BaFin decision, court order, or §24 eWpG correction reference (min. 10 characters)"></textarea>
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Authenticator code</mat-label>
              <input matInput [(ngModel)]="forceTransferForm.totpCode" inputmode="numeric" maxlength="6"
                     autocomplete="one-time-code">
              <mat-hint>Built-in sign-in only. With Microsoft sign-in you are asked to re-authenticate instead.</mat-hint>
            </mat-form-field>

            <app-approval-request-box [session]="sessions.forceTransfer" [request]="approvalRequestFor('forceTransfer')" />

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

            <button type="button" mat-raised-button color="primary" class="full-width"
              (click)="submitForceTransfer()"
              [disabled]="busy || !isValidForceTransferForm()">
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
              <input matInput type="text" inputmode="numeric" autocomplete="off"
                [(ngModel)]="forceApproveForm.amount"
                placeholder="Whole token units, e.g. 1000">
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Legal authority / reference</mat-label>
              <textarea matInput rows="2" maxlength="2000"
                [(ngModel)]="forceApproveForm.legalBasis"
                placeholder="Reference authorizing this allowance override (min. 10 characters)"></textarea>
            </mat-form-field>

            <mat-form-field class="full-width">
              <mat-label>Authenticator code</mat-label>
              <input matInput [(ngModel)]="forceApproveForm.totpCode" inputmode="numeric" maxlength="6"
                     autocomplete="one-time-code">
              <mat-hint>Built-in sign-in only. With Microsoft sign-in you are asked to re-authenticate instead.</mat-hint>
            </mat-form-field>

            <app-approval-request-box [session]="sessions.forceApprove" [request]="approvalRequestFor('forceApprove')" />

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

            <button type="button" mat-raised-button color="primary" class="full-width"
              (click)="submitForceApprove()"
              [disabled]="busy || !isValidForceApproveForm()">
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
      color: var(--rw-text-warning);
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
export class TokenAdminPanelComponent implements OnDestroy {
  @Input() assetId!: string;
  @Input() deploymentId!: string;
  @Input() busy = false;

  @Output() mint = new EventEmitter<MintAction>();
  @Output() burn = new EventEmitter<BurnAction>();
  @Output() forceTransfer = new EventEmitter<ForceTransferAction>();
  @Output() forceApprove = new EventEmitter<ForceApproveAction>();

  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly approvalQueue = inject(ApprovalQueueService);

  /** One approval session per action; they live here so switching tabs does not lose a filed request. */
  readonly sessions: Record<ApprovalKind, ApprovalRequestSession> = {
    mint: this.newSession(),
    burn: this.newSession(),
    forceTransfer: this.newSession(),
    forceApprove: this.newSession(),
  };
  private readonly requestCache = new Map<ApprovalKind, { key: string; request: CreateApprovalRequest | null }>();

  private newSession(): ApprovalRequestSession {
    return new ApprovalRequestSession(this.approvalQueue, () => this.cdr.markForCheck());
  }

  ngOnDestroy(): void {
    Object.values(this.sessions).forEach(s => s.destroy());
  }

  mintForm = {
    recipient: '',
    amount: '',
    totpCode: '',
  };

  burnForm = {
    fromWallet: '',
    amount: '',
    totpCode: '',
  };

  forceTransferForm = {
    fromWallet: '',
    toWallet: '',
    amount: '',
    legalBasis: '',
    totpCode: '',
  };

  forceApproveForm = {
    ownerWallet: '',
    spenderWallet: '',
    amount: '',
    legalBasis: '',
    totpCode: '',
  };

  pickWallet(setter: (addr: string) => void): void {
    this.dialog.open<AddressPickerDialogComponent, AddressPickerDialogData, string>(
      AddressPickerDialogComponent,
      { data: { mode: 'WALLET', title: 'Select wallet' }, width: '560px', maxWidth: '95vw' }
    ).afterClosed().subscribe(addr => {
      if (addr) {
        setter(addr);
        this.cdr.markForCheck(); // zoneless: picked address must re-render
      }
    });
  }

  /** The id of the approved request of this action, or null while it is not approved. */
  private approvedId(kind: ApprovalKind): string | null {
    const session = this.sessions[kind];
    return session.phase === 'approved' ? (session.view?.id ?? null) : null;
  }

  private mintFieldsValid(): boolean {
    return this.isValidAddress(this.mintForm.recipient) && this.isValidAmount(this.mintForm.amount);
  }

  private burnFieldsValid(): boolean {
    return this.isValidAddress(this.burnForm.fromWallet) && this.isValidAmount(this.burnForm.amount);
  }

  private forceTransferFieldsValid(): boolean {
    return (
      this.isValidAddress(this.forceTransferForm.fromWallet) &&
      this.isValidAddress(this.forceTransferForm.toWallet) &&
      this.isValidAmount(this.forceTransferForm.amount) &&
      this.forceTransferForm.legalBasis.trim().length >= 10
    );
  }

  private forceApproveFieldsValid(): boolean {
    return (
      this.isValidAddress(this.forceApproveForm.ownerWallet) &&
      this.isValidAddress(this.forceApproveForm.spenderWallet) &&
      this.isValidAmount(this.forceApproveForm.amount) &&
      this.forceApproveForm.legalBasis.trim().length >= 10
    );
  }

  isValidMintForm(): boolean {
    return this.mintFieldsValid() && this.approvedId('mint') !== null;
  }

  isValidBurnForm(): boolean {
    return this.burnFieldsValid() && this.approvedId('burn') !== null;
  }

  isValidForceTransferForm(): boolean {
    return this.forceTransferFieldsValid() && this.approvedId('forceTransfer') !== null;
  }

  isValidForceApproveForm(): boolean {
    return this.forceApproveFieldsValid() && this.approvedId('forceApprove') !== null;
  }

  private isValidAddress(address: string): boolean {
    return /^0x[a-fA-F0-9]{40}$/.test(address.trim());
  }

  /** Amounts stay decimal STRINGS end to end (a JS number loses precision above 2^53, P4B-7). */
  private isValidAmount(amount: string): boolean {
    return /^[1-9]\d*$/.test((amount ?? '').trim());
  }

  submitMint(): void {
    if (this.busy || !this.isValidMintForm()) return;
    this.mint.emit({
      recipient: this.mintForm.recipient.trim(),
      amount: this.mintForm.amount.trim(),
      totpCode: this.mintForm.totpCode.trim() || undefined,
      approvalRequestId: this.approvedId('mint')!,
    });
    this.sessions.mint.reset(); // the approval is spent by this submit, win or lose
  }

  submitBurn(): void {
    if (this.busy || !this.isValidBurnForm()
        || !confirm('Burn these tokens? This action is irreversible.')) return;
    this.burn.emit({
      amount: this.burnForm.amount.trim(),
      fromWallet: this.burnForm.fromWallet.trim(),
      totpCode: this.burnForm.totpCode.trim() || undefined,
      approvalRequestId: this.approvedId('burn')!,
    });
    this.sessions.burn.reset();
  }

  submitForceTransfer(): void {
    if (this.busy || !this.isValidForceTransferForm()
        || !confirm('Execute this legally authorized forced transfer?')) return;
    this.forceTransfer.emit({
      fromWallet: this.forceTransferForm.fromWallet.trim(),
      toWallet: this.forceTransferForm.toWallet.trim(),
      amount: this.forceTransferForm.amount.trim(),
      legalBasis: this.forceTransferForm.legalBasis.trim(),
      totpCode: this.forceTransferForm.totpCode.trim() || undefined,
      approvalRequestId: this.approvedId('forceTransfer')!,
    });
    this.sessions.forceTransfer.reset();
  }

  submitForceApprove(): void {
    if (this.busy || !this.isValidForceApproveForm()
        || !confirm('Execute this legally authorized allowance override?')) return;
    this.forceApprove.emit({
      ownerWallet: this.forceApproveForm.ownerWallet.trim(),
      spenderWallet: this.forceApproveForm.spenderWallet.trim(),
      amount: this.forceApproveForm.amount.trim(),
      legalBasis: this.forceApproveForm.legalBasis.trim(),
      totpCode: this.forceApproveForm.totpCode.trim() || undefined,
      approvalRequestId: this.approvedId('forceApprove')!,
    });
    this.sessions.forceApprove.reset();
  }

  /**
   * The exact call the approval is bound to (what the parent will send: see issuance-detail's onMint/onBurn/...),
   * or null while the form is incomplete. Memoised so the box input keeps its identity between change detections.
   */
  approvalRequestFor(kind: ApprovalKind): CreateApprovalRequest | null {
    const request = this.buildApprovalRequest(kind);
    const key = JSON.stringify(request);
    const cached = this.requestCache.get(kind);
    if (cached && cached.key === key) return cached.request;
    this.requestCache.set(kind, { key, request });
    return request;
  }

  private buildApprovalRequest(kind: ApprovalKind): CreateApprovalRequest | null {
    const { action, segment } = APPROVAL_ACTIONS[kind];
    const base = {
      action, method: 'POST',
      path: `/api/v1/assets/${this.assetId}/deployments/${this.deploymentId}/issuer/${segment}`,
    };
    switch (kind) {
      case 'mint':
        return !this.mintFieldsValid() ? null
          : { ...base, body: { toAddress: this.mintForm.recipient.trim(), amount: this.mintForm.amount.trim() } };
      case 'burn':
        return !this.burnFieldsValid() ? null
          : { ...base, body: { fromAddress: this.burnForm.fromWallet.trim(), amount: this.burnForm.amount.trim() } };
      case 'forceTransfer':
        return !this.forceTransferFieldsValid() ? null : { ...base, body: {
          from: this.forceTransferForm.fromWallet.trim(), to: this.forceTransferForm.toWallet.trim(),
          value: this.forceTransferForm.amount.trim(), legalBasis: this.forceTransferForm.legalBasis.trim() } };
      case 'forceApprove':
        return !this.forceApproveFieldsValid() ? null : { ...base, body: {
          owner: this.forceApproveForm.ownerWallet.trim(), spender: this.forceApproveForm.spenderWallet.trim(),
          value: this.forceApproveForm.amount.trim(), legalBasis: this.forceApproveForm.legalBasis.trim() } };
    }
  }

  shortenAddress(address: string): string {
    if (!address || address.length < 12) return address;
    return `${address.substring(0, 6)}...${address.substring(address.length - 4)}`;
  }
}
