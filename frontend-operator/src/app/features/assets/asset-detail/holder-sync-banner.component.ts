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
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { StepUpDialogComponent, StepUpDialogResult } from '../../../shared/components/step-up/step-up-dialog.component';
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
    @if ((asset?.holderSyncOffchainRows ?? 0) > 0) {
      <section class="offchain-banner" role="status" aria-labelledby="offchain-rows-title">
        <mat-icon>link_off</mat-icon>
        <div>
          <strong id="offchain-rows-title">
            {{ asset!.holderSyncOffchainRows }} register {{ asset!.holderSyncOffchainRows === 1 ? 'entry is' : 'entries are' }} not backed by chain
          </strong>
          <p class="sync-text">
            These active entries carry a balance the token contract does not show (off-chain trade settlement or
            manual entries). The holder sync does not overwrite them; reconcile them with an on-chain transfer or
            close the entries.
          </p>
        </div>
      </section>
    }
    @if (asset?.holderSyncStatus === 'BLOCKED') {
      <section class="sync-banner" role="alert" aria-labelledby="holder-sync-banner-title">
        <div class="sync-head">
          <mat-icon>sync_problem</mat-icon>
          <strong id="holder-sync-banner-title">
            Register not reconciled since
            {{ asset!.lastSuccessfulHolderSyncAt ? (asset!.lastSuccessfulHolderSyncAt | date:'medium') : 'never' }}
          </strong>
        </div>
        @if (asset!.holderSyncBlockedReason) {
          <p class="sync-text sync-reason" data-testid="holder-sync-reason">
            <strong>Reason:</strong> {{ asset!.holderSyncBlockedReason }}
          </p>
        }
        @if (isIndexingProblem()) {
          <p class="sync-text">
            The register cannot be derived from an incomplete chain history. Configure or repair the indexer for the
            named deployment (Indexers screen), let it catch up, then re-run the holder sync. Corporate-action
            snapshots and settlement confirmation are refused until then. Nothing is clamped or guessed.
          </p>
        }
        @if ((asset!.holderSyncUnmappedWallets ?? []).length > 0) {
          <p class="sync-text">
            Unmapped wallets hold finalized balances. Corporate-action snapshots and settlement confirmation
            are refused until every wallet is registered as a pool or mapped to an investor.
          </p>
        }
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
                    @if (mode === 'INVESTOR') {
                      <mat-form-field appearance="outline" subscriptSizing="dynamic">
                        <mat-label>Instruction from</mat-label>
                        <mat-select [(ngModel)]="instructingParty" [ngModelOptions]="{ standalone: true }">
                          <mat-option value="HOLDER">Holder</mat-option>
                          <mat-option value="BENEFICIARY">Beneficiary</mat-option>
                          <mat-option value="COURT">Court</mat-option>
                          <mat-option value="INSOLVENCY_ADMINISTRATOR">Insolvency administrator</mat-option>
                        </mat-select>
                      </mat-form-field>
                      <mat-form-field appearance="outline" subscriptSizing="dynamic">
                        <mat-label>Instruction reference</mat-label>
                        <input matInput [(ngModel)]="instructionReference" [ngModelOptions]="{ standalone: true }" />
                      </mat-form-field>
                    }
                    <mat-form-field appearance="outline" subscriptSizing="dynamic" class="entity-field">
                      <mat-label>{{ mode === 'POOL' ? 'Holding legal entity ID (blank = operator default)' : 'Investor legal entity ID' }}</mat-label>
                      <input matInput [(ngModel)]="entityId" [ngModelOptions]="{ standalone: true }" />
                    </mat-form-field>
                    <button type="button" mat-flat-button color="warn" [disabled]="busy || (mode === 'INVESTOR' && (!entityId.trim() || !instructingParty || !instructionReference.trim()))"
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
    .offchain-banner {
      margin: 0 0 16px;
      padding: 12px 16px;
      display: flex;
      gap: 10px;
      align-items: flex-start;
      border: 1px solid var(--rw-border);
      border-radius: var(--rw-radius);
      background: var(--rw-surface-soft);
    }
    .offchain-banner .sync-text { margin: 4px 0 0; }
    .sync-reason { overflow-wrap: anywhere; }
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
  private readonly dialog = inject(MatDialog);

  editing: string | null = null;
  mode: 'POOL' | 'INVESTOR' = 'POOL';
  poolKind: NomineePoolKind = 'LENDING_MARKET';
  entityId = '';
  instructingParty = '';
  instructionReference = '';
  busy = false;

  /** Reasons that are not about unmapped wallets: chain history not indexed / negative net balance (P4-01, P4-04). */
  isIndexingProblem(): boolean {
    const reason = this.asset?.holderSyncBlockedReason ?? '';
    return /not indexed|indexed history incomplete|negative net balance/i.test(reason);
  }

  startEdit(wallet: string): void {
    this.editing = wallet;
    this.mode = 'POOL';
    this.poolKind = 'LENDING_MARKET';
    this.entityId = '';
    this.instructingParty = '';
    this.instructionReference = '';
  }

  submit(wallet: string): void {
    if (!this.asset) return;
    const entity = this.entityId.trim();
    if (this.mode === 'POOL') {
      this.run(this.holderSync.registerNomineePool(this.asset.id, wallet, this.poolKind, entity || null));
      return;
    }
    // Mapping a wallet to an investor is a register entry: step-up + recorded instruction (T3-13).
    const assetId = this.asset.id;
    this.dialog.open(StepUpDialogComponent, {
      data: { requireDualControl: false, reason: `Map wallet ${wallet} to investor ${entity}`, action: 'REGISTER_ENTRY_CREATE' },
      width: '500px',
      disableClose: true,
    }).afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken) return;
      this.run(this.holderSync.mapWalletToInvestor(assetId, wallet, entity, this.instructingParty,
        this.instructionReference.trim(), result.stepUpToken));
    });
  }

  private run(action: Observable<unknown>): void {
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
