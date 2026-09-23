import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, EventEmitter, Input, Output, inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Observable } from 'rxjs';
import { Asset } from '../../../core/models';
import { HolderSyncService, NomineePoolKind } from '../../../core/api/holder-sync.service';

/**
 * T2-18: red "register not reconciled" banner on the operator asset detail. Shown while the
 * asset's holder sync is BLOCKED — finalized balances sit on wallets with no holder row (usually a
 * pool contract such as a lending market or DvP escrow). Corporate-action snapshots and settlement
 * confirmation are refused until it clears. Each unmapped wallet can be registered as a
 * nominee-pool holder or mapped to an investor; either action re-runs the holder sync.
 */
@Component({
  selector: 'app-holder-sync-banner',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
  template: `
    @if (asset?.holderSyncStatus === 'BLOCKED') {
      <section class="sync-banner" role="alert" aria-labelledby="holder-sync-banner-title">
        <div class="sync-head">
          <mat-icon>sync_problem</mat-icon>
          <strong id="holder-sync-banner-title">
            Register not reconciled since
            {{ asset!.lastSuccessfulHolderSyncAt ? (asset!.lastSuccessfulHolderSyncAt | date:'medium') : 'never' }}
          </strong>
        </div>
        <p class="sync-text">
          Unmapped wallets hold finalized balances. Corporate-action snapshots and settlement confirmation
          are refused until every wallet is registered as a pool or mapped to an investor.
        </p>
        <ul class="wallet-list">
          @for (w of asset!.holderSyncUnmappedWallets ?? []; track w) {
            <li class="wallet-row">
              <code class="wallet">{{ w }}</code>
              @if (canMutate) {
                @if (editing === w) {
                  <div class="wallet-form">
                    <mat-form-field appearance="outline" subscriptSizing="dynamic">
                      <mat-label>Action</mat-label>
                      <mat-select [(ngModel)]="mode" [ngModelOptions]="{ standalone: true }">
                        <mat-option value="POOL">Register pool address</mat-option>
                        <mat-option value="INVESTOR">Map to investor</mat-option>
                      </mat-select>
                    </mat-form-field>
                    @if (mode === 'POOL') {
                      <mat-form-field appearance="outline" subscriptSizing="dynamic">
                        <mat-label>Pool kind</mat-label>
                        <mat-select [(ngModel)]="poolKind" [ngModelOptions]="{ standalone: true }">
                          <mat-option value="LENDING_MARKET">Lending market</mat-option>
                          <mat-option value="DVP_ESCROW">DvP escrow</mat-option>
                          <mat-option value="DESK">Desk inventory</mat-option>
                          <mat-option value="FACILITY">Facility</mat-option>
                        </mat-select>
                      </mat-form-field>
                    }
                    <mat-form-field appearance="outline" subscriptSizing="dynamic" class="entity-field">
                      <mat-label>{{ mode === 'POOL' ? 'Holding legal entity ID (blank = operator default)' : 'Investor legal entity ID' }}</mat-label>
                      <input matInput [(ngModel)]="entityId" [ngModelOptions]="{ standalone: true }" />
                    </mat-form-field>
                    <button type="button" mat-flat-button color="warn" [disabled]="busy || (mode === 'INVESTOR' && !entityId.trim())"
                            (click)="submit(w)">
                      {{ busy ? 'Saving…' : 'Save & refresh' }}
                    </button>
                    <button type="button" mat-button [disabled]="busy" (click)="editing = null">Cancel</button>
                  </div>
                } @else {
                  <button type="button" mat-stroked-button (click)="startEdit(w)">
                    <mat-icon>link</mat-icon> Resolve
                  </button>
                }
              }
            </li>
          }
        </ul>
        @if (canMutate) {
          <button type="button" mat-stroked-button [disabled]="busy" (click)="refresh()">
            <mat-icon>refresh</mat-icon> Re-run holder sync
          </button>
        }
      </section>
    }
  `,
  styles: [`
    .sync-banner {
      margin: 0 0 16px;
      padding: 14px 16px;
      border: 1px solid var(--rw-text-danger);
      border-radius: var(--rw-radius);
      background: var(--rw-rejected-bg);
      color: var(--rw-rejected-fg);
    }
    .sync-head { display: flex; align-items: center; gap: 8px; }
    .sync-text { margin: 6px 0 10px; font-size: 13px; }
    .wallet-list { list-style: none; margin: 0 0 10px; padding: 0; display: grid; gap: 8px; }
    .wallet-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
    .wallet { font-family: 'IBM Plex Mono', monospace; font-size: 12px; overflow-wrap: anywhere; }
    .wallet-form { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; width: 100%; }
    .entity-field { min-width: 280px; flex: 1 1 280px; }
  `],
})
export class HolderSyncBannerComponent {
  @Input() asset: Asset | null = null;
  @Input() canMutate = false;
  /** Emitted after any action so the parent reloads the asset (and its sync state). */
  @Output() readonly changed = new EventEmitter<void>();

  private readonly holderSync = inject(HolderSyncService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  editing: string | null = null;
  mode: 'POOL' | 'INVESTOR' = 'POOL';
  poolKind: NomineePoolKind = 'LENDING_MARKET';
  entityId = '';
  busy = false;

  startEdit(wallet: string): void {
    this.editing = wallet;
    this.mode = 'POOL';
    this.poolKind = 'LENDING_MARKET';
    this.entityId = '';
  }

  submit(wallet: string): void {
    if (!this.asset) return;
    const entity = this.entityId.trim();
    const action: Observable<unknown> = this.mode === 'POOL'
      ? this.holderSync.registerNomineePool(this.asset.id, wallet, this.poolKind, entity || null)
      : this.holderSync.mapWalletToInvestor(this.asset.id, wallet, entity);
    this.busy = true;
    action.subscribe({
      next: () => {
        this.editing = null;
        this.refresh();
      },
      error: (err) => {
        this.busy = false;
        this.snackBar.open(err?.error?.message ?? 'Could not resolve the wallet.', 'Dismiss', { duration: 6000 });
        this.cdr.markForCheck();
      },
    });
  }

  refresh(): void {
    if (!this.asset) return;
    this.busy = true;
    this.holderSync.refreshHolders(this.asset.id).subscribe({
      next: () => {
        this.busy = false;
        this.snackBar.open('Register reconciled.', 'OK', { duration: 3000 });
        this.changed.emit();
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.busy = false;
        this.snackBar.open(err?.error?.message ?? 'Holder sync still blocked.', 'Dismiss', { duration: 8000 });
        this.changed.emit();
        this.cdr.markForCheck();
      },
    });
  }
}
