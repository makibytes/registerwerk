import { ChangeDetectorRef, Component, Input, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar } from '@angular/material/snack-bar';
import { numberToHex } from 'viem';
import { firstValueFrom } from 'rxjs';
import { ConfidentialService, ConfidentialContext, ReconciliationReport } from '../../../core/api/confidential.service';
import { TransactionService } from '../../../core/api/transaction.service';
import { WalletService } from '../../../core/wallet/wallet.service';
import { FheClientService } from '../../../core/fhe/fhe-client.service';
import { AddressComponent } from '../address.component';

/** Minimal ABI fragment for reading a confidential balance handle. */
const CONFIDENTIAL_BALANCE_ABI = [
  {
    name: 'confidentialBalanceOf', type: 'function', stateMutability: 'view',
    inputs: [{ name: 'account', type: 'address' }],
    outputs: [{ name: '', type: 'uint256' }],
  },
] as const;

/**
 * Operator/auditor confidential-balance reveal-and-reconcile panel — serves both roles the user
 * explicitly asked for: "the operator of Registerwerk needs to be able to decrypt all amounts of
 * all investors and the auditor role needs to be able to decrypt amount[s]." Two independent
 * decrypt paths, both real:
 *   - "Run Reconciliation" — headless, backend-side, via the registry's dedicated
 *     operator-decrypt key (`zama-relayer`'s `OPERATOR_DECRYPT_PRIVATE_KEY`). No wallet needed.
 *   - "Reveal via my wallet" — connect a viewer wallet in THIS browser and decrypt directly
 *     against Zama's relayer client-side, as an independent cross-check of the same value.
 * Both only work for a wallet/key actually registered as a viewer
 * (`ConfidentialERC20.isViewer` / `TokenAdminService.confidentialAddViewer`) — this panel's
 * "Manage Viewers" form is how that's granted/revoked.
 */
@Component({
  selector: 'app-confidential-viewer-panel',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatCardModule, MatButtonModule, MatIconModule,
    MatTableModule, MatFormFieldModule, MatInputModule, MatChipsModule, AddressComponent,
  ],
  template: `
    <div class="confidential-panel">
      <p class="intro">
        Balances and transfer amounts on this token are encrypted on-chain (Zama fhEVM). Only
        registered viewers — the operator, an auditor, and the issuer — can decrypt them.
      </p>

      <div class="action-row">
        <button type="button" mat-flat-button color="primary" (click)="runReconciliation()" [disabled]="loadingReport">
          <mat-icon>fact_check</mat-icon>
          {{ loadingReport ? 'Reconciling…' : 'Run Reconciliation' }}
        </button>
        @if (!walletService.isConnected()) {
          <button type="button" mat-stroked-button (click)="connectWallet()" [disabled]="connectingWallet">
            <mat-icon>account_balance_wallet</mat-icon>
            {{ connectingWallet ? 'Connecting…' : 'Connect Viewer Wallet' }}
          </button>
        }
      </div>

      @if (reportError) {
        <p class="error-text">{{ reportError }}</p>
      }

      @if (report) {
        <div class="report-summary">
          <mat-chip [color]="report.allMatch ? 'primary' : 'warn'" [highlighted]="true">
            {{ report.allMatch ? 'All balances match the register' : 'Mismatch detected' }}
          </mat-chip>
        </div>

        <table mat-table [dataSource]="report.holders" class="mat-elevation-z0">
          <ng-container matColumnDef="wallet">
            <th mat-header-cell *matHeaderCellDef>Wallet</th>
            <td mat-cell *matCellDef="let h"><app-address [address]="h.walletAddress" /></td>
          </ng-container>
          <ng-container matColumnDef="register">
            <th mat-header-cell *matHeaderCellDef>Register Amount</th>
            <td mat-cell *matCellDef="let h">{{ h.registerAmount }}</td>
          </ng-container>
          <ng-container matColumnDef="onchain">
            <th mat-header-cell *matHeaderCellDef>On-Chain (backend-decrypted)</th>
            <td mat-cell *matCellDef="let h">{{ h.onchainAmount ?? ('error: ' + h.error) }}</td>
          </ng-container>
          <ng-container matColumnDef="match">
            <th mat-header-cell *matHeaderCellDef>Match</th>
            <td mat-cell *matCellDef="let h">
              <mat-icon [style.color]="h.matches ? '#388e3c' : '#e53935'">
                {{ h.matches ? 'check_circle' : 'error' }}
              </mat-icon>
            </td>
          </ng-container>
          <ng-container matColumnDef="wallet-reveal">
            <th mat-header-cell *matHeaderCellDef>My Wallet Cross-Check</th>
            <td mat-cell *matCellDef="let h">
              @if (!walletService.isConnected()) {
                —
              } @else if (revealedByWallet[h.walletAddress]) {
                <span class="revealed">{{ revealedByWallet[h.walletAddress] }}</span>
              } @else {
                <button type="button" mat-stroked-button (click)="revealViaWallet(h.walletAddress)" [disabled]="revealingWallet === h.walletAddress">
                  {{ revealingWallet === h.walletAddress ? 'Decrypting…' : 'Reveal' }}
                </button>
              }
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="columns"></tr>
          <tr mat-row *matRowDef="let r; columns: columns;"></tr>
        </table>
        @if (revealError) {
          <p class="error-text">{{ revealError }}</p>
        }
      }

      <div class="viewer-management">
        <h3>Manage Viewers</h3>
        <p class="intro">
          Grant or revoke decrypt rights on every holder's balance — e.g. adding an auditor or the
          issuer's own wallet after deployment. Does not retroactively revoke already-decryptable
          historical handles (Zama's ACL has no revoke primitive).
        </p>
        <div class="viewer-form">
          <mat-form-field appearance="outline">
            <mat-label>Viewer address</mat-label>
            <input matInput [(ngModel)]="viewerAddress" placeholder="0x…" />
          </mat-form-field>
          <button type="button" mat-stroked-button color="primary" (click)="addViewer()" [disabled]="viewerActionLoading || !viewerAddress">
            <mat-icon>visibility</mat-icon> Add Viewer
          </button>
          <button type="button" mat-stroked-button color="warn" (click)="removeViewer()" [disabled]="viewerActionLoading || !viewerAddress">
            <mat-icon>visibility_off</mat-icon> Remove Viewer
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .confidential-panel { display: flex; flex-direction: column; gap: 16px; }
    .intro { color: var(--rw-text-secondary); font-size: 13px; margin: 0; max-width: 720px; }
    .action-row { display: flex; gap: 12px; flex-wrap: wrap; }
    .report-summary { margin: 4px 0; }
    .error-text { color: var(--rw-text-danger); font-size: 13px; }
    .revealed { font-weight: 700; color: var(--rw-accent); }
    .viewer-management { border-top: 1px solid var(--rw-border); padding-top: 16px; margin-top: 8px; }
    .viewer-management h3 { margin: 0 0 8px; font-size: 14px; color: var(--rw-text-primary); }
    .viewer-form { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .viewer-form mat-form-field { flex: 1; min-width: 280px; }
  `],
})
export class ConfidentialViewerPanelComponent {
  @Input({ required: true }) assetId!: string;
  @Input({ required: true }) deploymentId!: string;

  private readonly cdr = inject(ChangeDetectorRef);
  private readonly confidentialService = inject(ConfidentialService);
  private readonly txService = inject(TransactionService);
  private readonly snackBar = inject(MatSnackBar);
  protected readonly walletService = inject(WalletService);
  private readonly fheService = inject(FheClientService);

  readonly columns = ['wallet', 'register', 'onchain', 'match', 'wallet-reveal'];

  report: ReconciliationReport | null = null;
  loadingReport = false;
  reportError: string | null = null;

  connectingWallet = false;
  revealedByWallet: Record<string, string> = {};
  revealingWallet: string | null = null;
  revealError: string | null = null;

  viewerAddress = '';
  viewerActionLoading = false;

  private contextCache: ConfidentialContext | null = null;

  runReconciliation(): void {
    this.loadingReport = true;
    this.reportError = null;
    this.cdr.markForCheck();
    this.confidentialService.getReconciliation(this.assetId).subscribe({
      next: (report) => {
        this.report = report;
        this.loadingReport = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.reportError = err?.error?.message ?? 'Reconciliation failed.';
        this.loadingReport = false;
        this.cdr.markForCheck();
      },
    });
  }

  async connectWallet(): Promise<void> {
    this.connectingWallet = true;
    this.cdr.markForCheck();
    try {
      await this.walletService.connect();
    } catch (err: unknown) {
      this.snackBar.open((err as Error)?.message ?? 'Wallet connection failed.', 'Dismiss', { duration: 5000 });
    } finally {
      this.connectingWallet = false;
      this.cdr.markForCheck();
    }
  }

  async revealViaWallet(walletAddress: string): Promise<void> {
    this.revealingWallet = walletAddress;
    this.revealError = null;
    this.cdr.markForCheck();
    try {
      const ctx = await this.confidentialContext();
      const handleValue = await this.walletService.readContract<bigint>({
        address: ctx.contractAddress as `0x${string}`,
        abi: CONFIDENTIAL_BALANCE_ABI,
        functionName: 'confidentialBalanceOf',
        args: [walletAddress as `0x${string}`],
      });
      const handle = numberToHex(handleValue, { size: 32 });
      const cleartext = await this.fheService.userDecrypt(handle, ctx.contractAddress as `0x${string}`, ctx.chainId);
      this.revealedByWallet = { ...this.revealedByWallet, [walletAddress]: cleartext.toString() };
    } catch (err: unknown) {
      this.revealError = (err as Error)?.message ?? 'Failed to reveal — is your wallet a registered viewer?';
    } finally {
      this.revealingWallet = null;
      this.cdr.markForCheck();
    }
  }

  addViewer(): void {
    if (!this.viewerAddress) return;
    this.viewerActionLoading = true;
    this.cdr.markForCheck();
    this.confidentialService.addViewer(this.assetId, this.deploymentId, this.viewerAddress).subscribe({
      next: (r) => {
        this.txService.track(r.txId, 'Add confidential viewer');
        this.viewerAddress = '';
        this.viewerActionLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.snackBar.open('Failed to add viewer.', 'Close', { duration: 5000 });
        this.viewerActionLoading = false;
        this.cdr.markForCheck();
      },
    });
  }

  removeViewer(): void {
    if (!this.viewerAddress) return;
    this.viewerActionLoading = true;
    this.cdr.markForCheck();
    this.confidentialService.removeViewer(this.assetId, this.deploymentId, this.viewerAddress).subscribe({
      next: (r) => {
        this.txService.track(r.txId, 'Remove confidential viewer');
        this.viewerAddress = '';
        this.viewerActionLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.snackBar.open('Failed to remove viewer.', 'Close', { duration: 5000 });
        this.viewerActionLoading = false;
        this.cdr.markForCheck();
      },
    });
  }

  private async confidentialContext(): Promise<ConfidentialContext> {
    if (this.contextCache) return this.contextCache;
    this.contextCache = await firstValueFrom(
      this.confidentialService.getConfidentialContext(this.assetId, this.deploymentId));
    return this.contextCache;
  }
}
