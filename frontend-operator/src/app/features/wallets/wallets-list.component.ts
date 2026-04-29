import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { forkJoin } from 'rxjs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatChipsModule } from '@angular/material/chips';
import { WalletService } from '../../core/api/wallet.service';
import { OperatorWallet, WalletDefault } from '../../core/models';
import { AddressComponent } from '../../shared/components/address.component';
import { GenerateWalletDialogComponent } from './dialogs/generate-wallet-dialog.component';
import { ImportRawDialogComponent } from './dialogs/import-raw-dialog.component';
import { ImportKeystoreDialogComponent } from './dialogs/import-keystore-dialog.component';
import { ExportDialogComponent } from './dialogs/export-dialog.component';
import { SetDefaultDialogComponent } from './dialogs/set-default-dialog.component';

@Component({
  selector: 'app-wallets-list',
  standalone: true,
  imports: [
    CommonModule, MatIconModule, MatButtonModule, MatMenuModule, MatTooltipModule,
    MatSnackBarModule, MatDialogModule, MatChipsModule, AddressComponent,
  ],
  styles: [`
    .page-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 24px; }
    .page-header h1 { font-size: 21px; font-weight: 700; color: var(--rw-text-primary); letter-spacing: -0.4px; margin: 0; }
    .page-subtitle { font-size: 13px; color: var(--rw-text-secondary); margin: 4px 0 0; }
    .header-actions { display: flex; gap: 8px; }

    .content-card { background: var(--rw-surface); border: 1px solid var(--rw-border); border-radius: 10px; overflow: hidden; }

    table { width: 100%; border-collapse: collapse; }
    thead th { font-size: 11px; font-weight: 600; letter-spacing: 0.5px; text-transform: uppercase; color: var(--rw-text-muted); padding: 12px 20px; text-align: left; border-bottom: 1px solid var(--rw-border); }
    tbody tr:not(:last-child) td { border-bottom: 1px solid var(--rw-border); }
    tbody tr:hover td { background: var(--rw-bg); }
    td { padding: 14px 20px; font-size: 13px; color: var(--rw-text-primary); vertical-align: middle; }

    .type-chip { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: 4px; font-size: 10px; font-weight: 700; letter-spacing: 0.5px; text-transform: uppercase; }
    .type-chip.evm { background: rgba(98,126,234,.12); color: #627EEA; }
    .type-chip.solana { background: rgba(153,69,255,.12); color: #9945FF; }

    .default-chips { display: flex; flex-wrap: wrap; gap: 4px; }
    .default-chip { display: inline-flex; align-items: center; padding: 2px 7px; border-radius: 4px; font-size: 10px; font-weight: 600; background: rgba(34,197,94,.1); color: #16a34a; }

    .empty-state { padding: 60px 24px; text-align: center; }
    .empty-state mat-icon { font-size: 40px; width: 40px; height: 40px; color: var(--rw-text-muted); margin-bottom: 12px; }
    .empty-state p { color: var(--rw-text-muted); font-size: 14px; margin: 4px 0; }
  `],
  template: `
    <div class="page-header">
      <div>
        <h1>Wallets</h1>
        <p class="page-subtitle">Operator signing wallets — one default per chain for gas fee payments</p>
      </div>
      <div class="header-actions">
        <button mat-stroked-button [matMenuTriggerFor]="importMenu">
          <mat-icon>upload</mat-icon> Import
        </button>
        <mat-menu #importMenu>
          <button mat-menu-item (click)="openImportRaw()"><mat-icon>key</mat-icon> Raw private key</button>
          <button mat-menu-item (click)="openImportKeystore()"><mat-icon>lock</mat-icon> Keystore file (JSON)</button>
        </mat-menu>
        <button mat-flat-button color="primary" (click)="openGenerate()">
          <mat-icon>add</mat-icon> Generate wallet
        </button>
      </div>
    </div>

    <div class="content-card">
      @if (wallets().length === 0) {
        <div class="empty-state">
          <mat-icon>wallet</mat-icon>
          <p><strong>No wallets configured</strong></p>
          <p>Generate a new wallet or import an existing one to get started.</p>
        </div>
      } @else {
        <table>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Address</th>
              <th>Default for chains</th>
              <th>Created</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (wallet of wallets(); track wallet.id) {
              <tr>
                <td><strong>{{ wallet.name }}</strong></td>
                <td><span class="type-chip" [class.evm]="wallet.type === 'EVM'" [class.solana]="wallet.type === 'SOLANA'">{{ wallet.type }}</span></td>
                <td><app-address [address]="wallet.address" /></td>
                <td>
                  <div class="default-chips">
                    @for (chainId of wallet.defaultForChains; track chainId) {
                      <span class="default-chip">{{ chainLabel(chainId) }}</span>
                    }
                    @if (wallet.defaultForChains.length === 0) {
                      <span style="color: var(--rw-text-muted); font-size: 12px">None</span>
                    }
                  </div>
                </td>
                <td style="font-size: 12px; color: var(--rw-text-muted)">{{ wallet.createdAt | date:'mediumDate' }}</td>
                <td>
                  <button mat-icon-button [matMenuTriggerFor]="actionMenu">
                    <mat-icon>more_vert</mat-icon>
                  </button>
                  <mat-menu #actionMenu>
                    <button mat-menu-item (click)="openSetDefault(wallet)"><mat-icon>star</mat-icon> Set as default</button>
                    <button mat-menu-item (click)="exportKeystore(wallet)" [disabled]="wallet.type !== 'EVM'"><mat-icon>download</mat-icon> Export keystore</button>
                    <button mat-menu-item (click)="exportRaw(wallet)"><mat-icon>key</mat-icon> Export raw key…</button>
                    <button mat-menu-item (click)="rename(wallet)"><mat-icon>edit</mat-icon> Rename</button>
                    <button mat-menu-item (click)="delete(wallet)" style="color: #ef4444"><mat-icon>delete_outline</mat-icon> Delete</button>
                  </mat-menu>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </div>
  `,
})
export class WalletsListComponent implements OnInit {
  private readonly walletService = inject(WalletService);
  private readonly snackBar      = inject(MatSnackBar);
  private readonly dialog        = inject(MatDialog);

  readonly wallets  = signal<OperatorWallet[]>([]);
  readonly defaults = signal<WalletDefault[]>([]);

  ngOnInit() { this.load(); }

  private load() {
    this.walletService.listWallets().subscribe(w => this.wallets.set(w));
    this.walletService.listDefaults().subscribe(d => this.defaults.set(d));
  }

  chainLabel(chainId: string): string {
    const d = this.defaults().find(x => x.chainConfigId === chainId);
    return d ? d.chainIdentifier.replace('_', ' ').toLowerCase() : chainId.slice(0, 8) + '…';
  }

  openGenerate() {
    this.dialog.open(GenerateWalletDialogComponent, { width: '440px' }).afterClosed().subscribe(r => {
      if (!r) return;
      this.walletService.generate(r.name, r.type).subscribe({
        next: () => { this.load(); this.snackBar.open('Wallet generated', 'OK', { duration: 3000 }); },
        error: e => this.snackBar.open(e.error?.message ?? 'Failed to generate wallet', 'OK', { duration: 4000 }),
      });
    });
  }

  openImportRaw() {
    this.dialog.open(ImportRawDialogComponent, { width: '440px' }).afterClosed().subscribe(r => {
      if (!r) return;
      this.walletService.importRaw(r.name, r.type, r.privateKey).subscribe({
        next: () => { this.load(); this.snackBar.open('Wallet imported', 'OK', { duration: 3000 }); },
        error: e => this.snackBar.open(e.error?.message ?? 'Import failed', 'OK', { duration: 4000 }),
      });
    });
  }

  openImportKeystore() {
    this.dialog.open(ImportKeystoreDialogComponent, { width: '440px' }).afterClosed().subscribe(r => {
      if (!r) return;
      this.walletService.importKeystore(r.name, r.password, r.file).subscribe({
        next: () => { this.load(); this.snackBar.open('Keystore imported', 'OK', { duration: 3000 }); },
        error: e => this.snackBar.open(e.error?.message ?? 'Import failed — check your password', 'OK', { duration: 4000 }),
      });
    });
  }

  exportKeystore(wallet: OperatorWallet) {
    this.dialog.open(ExportDialogComponent, { width: '400px', data: { wallet } }).afterClosed().subscribe(r => {
      if (!r) return;
      this.walletService.exportKeystore(wallet.id, r.password).subscribe({
        next: blob => {
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a'); a.href = url; a.download = `wallet-${wallet.name}.json`; a.click();
          URL.revokeObjectURL(url);
          this.snackBar.open('Keystore exported', 'OK', { duration: 2500 });
        },
        error: () => this.snackBar.open('Export failed', 'OK', { duration: 3000 }),
      });
    });
  }

  exportRaw(wallet: OperatorWallet) {
    if (!confirm(`Export raw private key for "${wallet.name}"?\n\nKeep this secret. Anyone with this key controls the wallet.`)) return;
    this.walletService.exportRaw(wallet.id).subscribe({
      next: key => {
        navigator.clipboard.writeText(key).then(() =>
          this.snackBar.open('Private key copied to clipboard — store it safely!', 'OK', { duration: 6000 }));
      },
      error: () => this.snackBar.open('Export failed', 'OK', { duration: 3000 }),
    });
  }

  openSetDefault(wallet: OperatorWallet) {
    this.dialog.open(SetDefaultDialogComponent, { width: '460px', data: { wallet } }).afterClosed().subscribe(chainIds => {
      if (!chainIds || (chainIds as string[]).length === 0) return;
      const calls = (chainIds as string[]).map(cid => this.walletService.setDefault(cid, wallet.id));
      forkJoin(calls).subscribe({
        next: () => { this.load(); this.snackBar.open('Defaults updated', 'OK', { duration: 2500 }); },
        error: () => this.snackBar.open('Failed to update some defaults', 'OK', { duration: 3000 }),
      });
    });
  }

  rename(wallet: OperatorWallet) {
    const name = prompt('New name:', wallet.name);
    if (!name || name === wallet.name) return;
    this.walletService.rename(wallet.id, name).subscribe({
      next: () => { this.load(); this.snackBar.open('Renamed', 'OK', { duration: 2000 }); },
      error: e => this.snackBar.open(e.error?.message ?? 'Rename failed', 'OK', { duration: 3000 }),
    });
  }

  delete(wallet: OperatorWallet) {
    if (!confirm(`Delete wallet "${wallet.name}"?\n\nThis also removes its chain defaults. Ensure another wallet is set as default before deleting.`)) return;
    this.walletService.delete(wallet.id).subscribe({
      next: () => { this.load(); this.snackBar.open('Wallet deleted', 'OK', { duration: 2500 }); },
      error: e => this.snackBar.open(e.error?.message ?? 'Delete failed', 'OK', { duration: 3000 }),
    });
  }
}
