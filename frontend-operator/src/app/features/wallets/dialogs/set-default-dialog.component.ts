import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ChainHealth } from '../../../core/models';
import { OperatorWallet } from '../../../core/models';
import { ChainService } from '../../../core/api/chain.service';

@Component({
  selector: 'app-set-default-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatCheckboxModule, MatDialogModule, MatProgressSpinnerModule],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 16px; }
    .chain-list { display: flex; flex-direction: column; gap: 8px; max-height: 300px; overflow-y: auto; }
    .chain-row { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-radius: 6px; border: 1px solid var(--rw-border); }
    .chain-type { font-size: 10px; font-weight: 700; padding: 1px 5px; border-radius: 3px; text-transform: uppercase; }
    .chain-type.evm { background: rgba(98,126,234,.12); color: #627EEA; }
    .chain-type.solana { background: rgba(153,69,255,.12); color: #9945FF; }
    .chain-name { font-size: 13px; font-weight: 500; color: var(--rw-text-primary); flex: 1; }
    .no-chains { font-size: 13px; color: var(--rw-text-muted); padding: 16px 0; text-align: center; }
    .no-chains.error { color: var(--rw-text-danger); }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>Set as default wallet</h2>
      <p class="subtitle">Select chains where "{{ data.wallet.name }}" will be used as the default signer.</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      @if (loading()) {
        <div class="no-chains"><mat-spinner diameter="28" /></div>
      } @else if (loadError()) {
        <div class="no-chains error" role="alert">
          <p>Compatible chains could not be loaded.</p>
          <button mat-stroked-button type="button" (click)="loadChains()">Retry</button>
        </div>
      } @else if (compatibleChains().length === 0) {
        <p class="no-chains">No compatible chains found for {{ data.wallet.type }} wallets.</p>
      } @else {
        <div class="chain-list">
          @for (chain of compatibleChains(); track chain.id) {
            <div class="chain-row">
              <mat-checkbox [(ngModel)]="selection[chain.id]">
                <span class="chain-type" [class.evm]="chain.chainType === 'EVM'" [class.solana]="chain.chainType === 'SOLANA'">
                  {{ chain.chainType }}
                </span>
                <span class="chain-name">{{ chain.displayName }}</span>
                <span style="font-size:11px; color: var(--rw-text-muted)">{{ chain.networkType.toLowerCase() }}</span>
              </mat-checkbox>
            </div>
          }
        </div>
      }
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button mat-button mat-dialog-close>Cancel</button>
        <button mat-flat-button color="primary" [disabled]="loading() || loadError() || selectedCount === 0" (click)="submit()">Apply</button>
      </div>
    </mat-dialog-actions>
  `,
})
export class SetDefaultDialogComponent implements OnInit {
  private readonly dialogRef  = inject(MatDialogRef<SetDefaultDialogComponent>);
  private readonly chainService  = inject(ChainService);
  readonly data: { wallet: OperatorWallet } = inject(MAT_DIALOG_DATA);

  compatibleChains = signal<ChainHealth[]>([]);
  loading = signal(true);
  loadError = signal(false);
  selection: Record<string, boolean> = {};

  get selectedCount() { return Object.values(this.selection).filter(Boolean).length; }

  ngOnInit() {
    this.loadChains();
  }

  loadChains(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.chainService.getHealth().subscribe({
      next: (chains) => {
        const filtered = chains.filter(c => c.chainType === this.data.wallet.type);
        this.selection = Object.fromEntries(
          filtered.map(c => [c.id, this.data.wallet.defaultForChains.includes(c.id)]),
        );
        this.compatibleChains.set(filtered);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set(true);
      },
    });
  }

  submit() {
    const selected = Object.entries(this.selection).filter(([, v]) => v).map(([k]) => k);
    this.dialogRef.close(selected);
  }
}
