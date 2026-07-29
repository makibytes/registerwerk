import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { PageHeaderComponent } from '@registerwerk/ui';
import { LendingService } from '../../../core/api/lending.service';
import { WalletService } from '../../../core/wallet/wallet.service';
import { erc20Abi, repoMarketAbi } from '../../../core/wallet/abi/repo-market.abi';
import { LendingMarket, LendingSupplyPosition } from '../../../core/models';
import type { Address } from 'viem';

/**
 * Lender side of the repo/lending facility — deliberately ungated (any stablecoin holder, no
 * KYC) per `EwpgRepoMarket`'s own asymmetric design: the fewer barriers to *supplying* capital,
 * the deeper the pool. See `contracts/src/lending/EwpgRepoMarket.sol` NatSpec.
 */
@Component({
  selector: 'app-supply-earn',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    PageHeaderComponent,
  ],
  template: `
    <div class="page-container">
      <app-page-header title="Supply & Earn" subtitle="Deposit stablecoin into any market and earn a transparent, utilization-based yield — no KYC needed.">
        <a mat-stroked-button routerLink="/lending">
          <mat-icon>arrow_back</mat-icon>
          Liquidity
        </a>
      </app-page-header>

      @if (loading) {
        <div class="loading-row"><mat-spinner diameter="32"></mat-spinner></div>
      } @else {
        <mat-card class="form-card">
          <mat-card-content>
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Market</mat-label>
              <mat-select [(ngModel)]="selectedMarketId">
                @for (m of markets; track m.id) {
                  <mat-option [value]="m.id">{{ m.collateralAssetName ?? m.marketAddress }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Amount</mat-label>
              <input matInput type="number" [(ngModel)]="amount" />
            </mat-form-field>

            @if (actionError) {
              <p class="error-text">{{ actionError }}</p>
            }

            <div class="action-row">
              <button mat-flat-button color="primary" [disabled]="acting || !selectedMarketId" (click)="supply()">
                @if (acting === 'supply') { Supplying… } @else { Supply }
              </button>
              <button mat-stroked-button [disabled]="acting || !selectedMarketId" (click)="withdraw()">
                @if (acting === 'withdraw') { Withdrawing… } @else { Withdraw }
              </button>
            </div>
          </mat-card-content>
        </mat-card>

        @if (positions.length > 0) {
          <h3 class="section-title">Your supply positions</h3>
          <div class="position-grid">
            @for (p of positions; track p.marketId) {
              <mat-card class="position-card">
                <mat-card-content>
                  <div class="position-market">{{ marketLabel(p.marketId) }}</div>
                  <div class="position-claim">{{ formatUnits(p.currentClaim) }}</div>
                  <div class="position-label">Current claim</div>
                </mat-card-content>
              </mat-card>
            }
          </div>
        }
      }
    </div>
  `,
  styles: [`
    .loading-row { display: flex; justify-content: center; padding: 48px 0; }
    .form-card { max-width: 480px; margin-bottom: 24px; }
    .full-width { width: 100%; }
    .action-row { display: flex; gap: 12px; margin-top: 8px; }
    .error-text { color: #dc2626; font-size: 12.5px; }
    .section-title { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
    .position-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; }
    .position-card { border-radius: 12px; }
    .position-market { font-size: 12px; color: var(--rw-text-secondary); margin-bottom: 4px; }
    .position-claim { font-size: 22px; font-weight: 700; color: var(--rw-text-primary); }
    .position-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px; color: var(--rw-text-secondary); margin-top: 2px; }
  `],
})
export class SupplyEarnComponent implements OnInit {
  private readonly lendingService = inject(LendingService);
  private readonly wallet = inject(WalletService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly snackBar = inject(MatSnackBar);

  loading = true;
  markets: LendingMarket[] = [];
  positions: LendingSupplyPosition[] = [];
  selectedMarketId: string | null = null;
  amount = 0;
  acting: 'supply' | 'withdraw' | null = null;
  actionError: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    forkJoin({
      markets: this.lendingService.listMarkets('ACTIVE').pipe(catchError(() => of<LendingMarket[]>([]))),
      positions: this.lendingService.supplyPositions().pipe(catchError(() => of<LendingSupplyPosition[]>([]))),
    }).subscribe(({ markets, positions }) => {
      this.markets = markets;
      this.positions = positions;
      this.selectedMarketId = markets[0]?.id ?? null;
      this.loading = false;
      this.cdr.markForCheck();
    });
  }

  marketLabel(marketId: string): string {
    const market = this.markets.find((m) => m.id === marketId);
    return market?.collateralAssetName ?? market?.marketAddress ?? marketId;
  }

  formatUnits(raw: string): string {
    return (Number(raw) / 1e6).toLocaleString(undefined, { maximumFractionDigits: 2 });
  }

  private shortHash(hash: string): string {
    return `${hash.slice(0, 10)}…${hash.slice(-6)}`;
  }

  async supply(): Promise<void> {
    const market = this.markets.find((m) => m.id === this.selectedMarketId);
    if (!market) return;
    this.acting = 'supply';
    this.actionError = null;
    this.cdr.markForCheck();

    try {
      if (!this.wallet.isConnected()) {
        await this.wallet.connect();
      }
      const marketAddress = market.marketAddress as Address;
      const loanToken = market.loanTokenAddress as Address;
      const amountUnits = BigInt(Math.trunc(this.amount * 1e6));

      const allowance = await this.wallet.readContract<bigint>({
        address: loanToken,
        abi: erc20Abi,
        functionName: 'allowance',
        args: [this.wallet.address(), marketAddress],
      });
      if (allowance < amountUnits) {
        const approveHash = await this.wallet.writeContract({
          address: loanToken,
          abi: erc20Abi,
          functionName: 'approve',
          args: [marketAddress, amountUnits],
        });
        await this.wallet.waitForTransaction(approveHash);
      }

      const hash = await this.wallet.writeContract({
        address: marketAddress,
        abi: repoMarketAbi,
        functionName: 'supply',
        args: [amountUnits],
      });
      await this.wallet.waitForTransaction(hash);
      this.snackBar.open(`Supplied ${this.amount}. Tx: ${this.shortHash(hash)}`, 'Dismiss', { duration: 6000 });
      this.load();
    } catch (err: unknown) {
      this.actionError = err instanceof Error ? err.message : 'Supply failed.';
    } finally {
      this.acting = null;
      this.cdr.markForCheck();
    }
  }

  async withdraw(): Promise<void> {
    const market = this.markets.find((m) => m.id === this.selectedMarketId);
    if (!market) return;
    this.acting = 'withdraw';
    this.actionError = null;
    this.cdr.markForCheck();

    try {
      if (!this.wallet.isConnected()) {
        await this.wallet.connect();
      }
      const amountUnits = BigInt(Math.trunc(this.amount * 1e6));
      const hash = await this.wallet.writeContract({
        address: market.marketAddress as Address,
        abi: repoMarketAbi,
        functionName: 'withdraw',
        args: [amountUnits],
      });
      await this.wallet.waitForTransaction(hash);
      this.snackBar.open(`Withdrew ${this.amount}. Tx: ${this.shortHash(hash)}`, 'Dismiss', { duration: 6000 });
      this.load();
    } catch (err: unknown) {
      this.actionError = err instanceof Error ? err.message : 'Withdraw failed.';
    } finally {
      this.acting = null;
      this.cdr.markForCheck();
    }
  }
}
