import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { WalletService } from '../../core/api/wallet.service';
import { OperatorWallet, WalletBalance } from '../../core/models';

@Component({
  selector: 'app-wallet-detail',
  standalone: true,
  imports: [
    CommonModule, RouterLink, MatIconModule, MatButtonModule,
    MatProgressSpinnerModule, MatTooltipModule, MatSnackBarModule,
  ],
  styles: [`
    .page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
    .page-header h1 { font-size: 21px; font-weight: 700; color: var(--rw-text-primary); letter-spacing: -0.4px; margin: 0; }
    .back-btn { color: var(--rw-text-secondary); }

    .content-card { background: var(--rw-surface); border: 1px solid var(--rw-border); border-radius: 10px; overflow-x: auto; margin-bottom: 20px; }
    .card-header { padding: 16px 20px; border-bottom: 1px solid var(--rw-border); display: flex; align-items: center; gap: 8px; }
    .card-header h2 { font-size: 13px; font-weight: 600; color: var(--rw-text-muted); text-transform: uppercase; letter-spacing: 0.5px; margin: 0; }
    .card-body { padding: 20px; }

    .wallet-meta { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .meta-item { display: flex; flex-direction: column; gap: 4px; }
    .meta-label { font-size: 11px; font-weight: 600; letter-spacing: 0.5px; text-transform: uppercase; color: var(--rw-text-muted); }
    .meta-value { font-size: 14px; color: var(--rw-text-primary); font-weight: 500; }

    .address-box { background: var(--rw-bg); border: 1px solid var(--rw-border); border-radius: 8px; padding: 12px 16px; display: flex; align-items: center; gap: 12px; margin-top: 16px; }
    .address-text { font-family: 'IBM Plex Mono', monospace; font-size: 13px; color: var(--rw-text-primary); word-break: break-all; flex: 1; }
    .copy-btn { flex-shrink: 0; color: var(--rw-text-secondary); }

    .type-chip { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 4px; font-size: 10px; font-weight: 700; letter-spacing: 0.5px; text-transform: uppercase; }
    .type-chip.evm { background: rgba(98,126,234,.12); color: #627EEA; }
    .type-chip.solana { background: rgba(153,69,255,.12); color: #9945FF; }
    .type-chip.canton { background: rgba(255,107,53,.12); color: #FF6B35; }

    table { width: 100%; border-collapse: collapse; }
    thead th { font-size: 11px; font-weight: 600; letter-spacing: 0.5px; text-transform: uppercase; color: var(--rw-text-muted); padding: 12px 20px; text-align: left; border-bottom: 1px solid var(--rw-border); }
    tbody tr:not(:last-child) td { border-bottom: 1px solid var(--rw-border); }
    td { padding: 14px 20px; font-size: 13px; color: var(--rw-text-primary); vertical-align: middle; }

    .balance-amount { font-size: 15px; font-weight: 600; font-family: 'IBM Plex Mono', monospace; }
    .balance-symbol { font-size: 11px; font-weight: 600; color: var(--rw-text-muted); margin-left: 4px; }
    .balance-error { color: #ef4444; font-size: 12px; display: flex; align-items: center; gap: 4px; }
    .balance-loading { display: flex; align-items: center; gap: 8px; color: var(--rw-text-muted); font-size: 12px; }

    .chain-badge { display: inline-flex; align-items: center; gap: 6px; padding: 3px 8px; border-radius: 6px; font-size: 11px; font-weight: 600; }
    .chain-badge.evm { background: rgba(98,126,234,.08); color: #627EEA; }
    .chain-badge.solana { background: rgba(153,69,255,.08); color: #9945FF; }
    .chain-badge.canton { background: rgba(255,107,53,.08); color: #FF6B35; }

    .network-tag { font-size: 10px; font-weight: 600; padding: 1px 6px; border-radius: 3px; letter-spacing: 0.4px; text-transform: uppercase; margin-left: 6px; }
    .network-tag.mainnet { background: rgba(34,197,94,.1); color: #16a34a; }
    .network-tag.testnet { background: rgba(245,158,11,.1); color: #d97706; }

    .loading-overlay { padding: 48px; text-align: center; color: var(--rw-text-muted); }
    .load-error { display: grid; justify-items: center; gap: 10px; color: var(--rw-text-danger); }
    @media (max-width: 620px) {
      .wallet-meta { grid-template-columns: 1fr; }
      .card-body { padding: 16px; }
      table { min-width: 560px; }
    }
  `],
  template: `
    <div class="page-header">
      <button mat-icon-button class="back-btn" routerLink="/wallets" aria-label="Back to wallets">
        <mat-icon>arrow_back</mat-icon>
      </button>
      @if (wallet()) {
        <div>
          <h1>{{ wallet()!.name }}</h1>
        </div>
      } @else {
        <h1>Wallet</h1>
      }
    </div>

    @if (loading()) {
      <div class="loading-overlay">
        <mat-spinner diameter="36" />
      </div>
    } @else if (loadError()) {
      <div class="loading-overlay load-error" role="alert">
        <mat-icon>error_outline</mat-icon>
        <span>Wallet details could not be loaded.</span>
        <button mat-stroked-button type="button" (click)="loadWallet()">Retry</button>
      </div>
    } @else if (wallet()) {
      <!-- Wallet info card -->
      <div class="content-card">
        <div class="card-header">
          <mat-icon style="font-size:16px;width:16px;height:16px;color:var(--rw-text-muted)">account_balance_wallet</mat-icon>
          <h2>Wallet Info</h2>
        </div>
        <div class="card-body">
          <div class="wallet-meta">
            <div class="meta-item">
              <span class="meta-label">Name</span>
              <span class="meta-value">{{ wallet()!.name }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Type</span>
              <span class="type-chip" [class.evm]="wallet()!.type === 'EVM'" [class.solana]="wallet()!.type === 'SOLANA'" [class.canton]="wallet()!.type === 'CANTON'">
                {{ wallet()!.type }}
              </span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Created</span>
              <span class="meta-value">{{ wallet()!.createdAt | date:'medium' }}</span>
            </div>
            <div class="meta-item">
              <span class="meta-label">Default for</span>
              <span class="meta-value">{{ wallet()!.defaultForChains.length }} chain(s)</span>
            </div>
          </div>

          <div class="address-box">
            <span class="address-text">{{ wallet()!.address }}</span>
            <button mat-icon-button class="copy-btn" (click)="copyAddress()" matTooltip="Copy address" aria-label="Copy wallet address">
              <mat-icon>content_copy</mat-icon>
            </button>
          </div>
        </div>
      </div>

      <!-- Balances card -->
      <div class="content-card">
        <div class="card-header">
          <mat-icon style="font-size:16px;width:16px;height:16px;color:var(--rw-text-muted)">account_balance</mat-icon>
          <h2>Native Balances — Active Blockchains</h2>
        </div>

        @if (balancesLoading()) {
          <div class="loading-overlay">
            <div class="balance-loading">
              <mat-spinner diameter="20" />
              Fetching balances from connected nodes…
            </div>
          </div>
        } @else if (balancesError()) {
          <div class="loading-overlay load-error" role="alert">
            <mat-icon>cloud_off</mat-icon>
            <span>Balances could not be loaded.</span>
            <button mat-stroked-button type="button" (click)="loadBalances()">Retry</button>
          </div>
        } @else if (balances().length === 0) {
          <div class="loading-overlay">
            <p style="color:var(--rw-text-muted)">No active chains configured for this wallet type.</p>
          </div>
        } @else {
          <table>
            <thead>
              <tr>
                <th>Blockchain</th>
                <th>Network</th>
                <th>Balance</th>
              </tr>
            </thead>
            <tbody>
              @for (b of balances(); track b.chainConfigId) {
                <tr>
                  <td>
                    <span class="chain-badge" [class.evm]="wallet()!.type === 'EVM'" [class.solana]="wallet()!.type === 'SOLANA'" [class.canton]="wallet()!.type === 'CANTON'">
                      {{ b.chainDisplayName }}
                    </span>
                  </td>
                  <td>
                    <span class="network-tag" [class.mainnet]="isMainnet(b)" [class.testnet]="!isMainnet(b)">
                      {{ isMainnet(b) ? 'Mainnet' : 'Testnet' }}
                    </span>
                  </td>
                  <td>
                    @if (b.error) {
                      <span class="balance-error">
                        <mat-icon style="font-size:14px;width:14px;height:14px">error_outline</mat-icon>
                        {{ b.error }}
                      </span>
                    } @else {
                      <span class="balance-amount">{{ b.balance | number:'1.4-8' }}</span>
                      <span class="balance-symbol">{{ b.nativeCurrencySymbol }}</span>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>
    }
  `,
})
export class WalletDetailComponent implements OnInit {
  private readonly route         = inject(ActivatedRoute);
  private readonly walletService = inject(WalletService);
  private readonly snackBar = inject(MatSnackBar);

  private walletId = '';

  readonly wallet          = signal<OperatorWallet | null>(null);
  readonly balances        = signal<WalletBalance[]>([]);
  readonly loading         = signal(true);
  readonly balancesLoading = signal(true);
  readonly loadError = signal(false);
  readonly balancesError = signal(false);

  ngOnInit(): void {
    this.walletId = this.route.snapshot.paramMap.get('id') ?? '';
    this.loadWallet();
    this.loadBalances();
  }

  loadWallet(): void {
    if (!this.walletId) {
      this.loading.set(false);
      this.loadError.set(true);
      return;
    }
    this.loading.set(true);
    this.loadError.set(false);
    this.walletService.getById(this.walletId).subscribe({
      next: (wallet) => {
        this.wallet.set(wallet);
        this.loading.set(false);
      },
      error: () => {
        this.wallet.set(null);
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }

  loadBalances(): void {
    if (!this.walletId) {
      this.balancesLoading.set(false);
      this.balancesError.set(true);
      return;
    }
    this.balancesLoading.set(true);
    this.balancesError.set(false);
    this.walletService.getBalances(this.walletId).subscribe({
      next: (balances) => {
        this.balances.set(balances);
        this.balancesLoading.set(false);
      },
      error: () => {
        this.balances.set([]);
        this.balancesLoading.set(false);
        this.balancesError.set(true);
      },
    });
  }

  copyAddress(): void {
    const addr = this.wallet()?.address;
    if (!addr) return;
    navigator.clipboard.writeText(addr).then(
      () => this.snackBar.open('Address copied', 'OK', { duration: 2000 }),
      () => this.snackBar.open('Could not copy the address', 'OK', { duration: 4000 }),
    );
  }

  isMainnet(b: WalletBalance): boolean {
    return b.chainIdentifier.toUpperCase().includes('MAINNET');
  }
}
