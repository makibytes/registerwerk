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
import { formatUnits as formatTokenUnits, parseUnits, type Address } from 'viem';

/**
 * Lender side of the securities-backed lending facility — deliberately ungated (any stablecoin holder, no
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
      } @else if (loadError) {
        <mat-card class="form-card">
          <mat-card-content role="alert">
            <p class="error-text">{{ loadError }}</p>
            <button mat-stroked-button type="button" (click)="load()">Retry</button>
          </mat-card-content>
        </mat-card>
      } @else if (markets.length === 0) {
        <mat-card class="form-card">
          <mat-card-content>
            <p>No active supply markets are available yet.</p>
          </mat-card-content>
        </mat-card>
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
              <input matInput type="number" min="0.000001" step="0.000001" [(ngModel)]="amount" />
            </mat-form-field>

            @if (actionError) {
              <p class="error-text" role="alert">{{ actionError }}</p>
            }
            @if (positionsError) {
              <p class="error-text" role="alert">
                {{ positionsError }}
                <button mat-button type="button" (click)="load()">Retry</button>
              </p>
            }

            <div class="action-row">
              <button mat-flat-button color="primary" type="button" [disabled]="acting || !selectedMarketId || !isValidAmount()" (click)="supply()">
                @if (acting === 'supply') { Supplying… } @else { Supply }
              </button>
              <button mat-stroked-button type="button" [disabled]="acting || !selectedMarketId || !isValidAmount() || !!positionsError" (click)="withdraw()">
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
                  <div class="position-claim">{{ formatUnits(p.currentClaim, p.marketId) }}</div>
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
  loadError = '';
  positionsError = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = '';
    this.positionsError = '';
    let marketsFailed = false;
    let positionsFailed = false;
    forkJoin({
      markets: this.lendingService.listMarkets('ACTIVE').pipe(catchError(() => {
        marketsFailed = true;
        return of<LendingMarket[]>([]);
      })),
      positions: this.lendingService.supplyPositions().pipe(catchError(() => {
        positionsFailed = true;
        return of<LendingSupplyPosition[]>([]);
      })),
    }).subscribe(({ markets, positions }) => {
      this.markets = markets;
      this.positions = positions;
      if (marketsFailed) this.loadError = 'Supply markets could not be loaded.';
      if (positionsFailed) this.positionsError = 'Your existing supply positions could not be loaded.';
      if (!markets.some((market) => market.id === this.selectedMarketId)) {
        this.selectedMarketId = markets[0]?.id ?? null;
      }
      this.loading = false;
      this.cdr.markForCheck();
    });
  }

  marketLabel(marketId: string): string {
    const market = this.markets.find((m) => m.id === marketId);
    return market?.collateralAssetName ?? market?.marketAddress ?? marketId;
  }

  formatUnits(raw: string, marketId = this.selectedMarketId): string {
    const decimals = this.marketDecimals(marketId);
    return Number(formatTokenUnits(BigInt(raw), decimals))
      .toLocaleString(undefined, { maximumFractionDigits: decimals });
  }

  private shortHash(hash: string): string {
    return `${hash.slice(0, 10)}…${hash.slice(-6)}`;
  }

  async supply(): Promise<void> {
    const market = this.markets.find((m) => m.id === this.selectedMarketId);
    if (!market || this.acting || !this.isValidAmount()) return;
    this.acting = 'supply';
    this.actionError = null;
    this.cdr.markForCheck();

    try {
      if (!this.wallet.isConnected()) {
        await this.wallet.connect();
      }
      const marketAddress = market.marketAddress as Address;
      const loanToken = market.loanTokenAddress as Address;
      const amountUnits = this.amountUnits(market);

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
    if (!market || this.acting || !this.isValidAmount()) return;
    this.acting = 'withdraw';
    this.actionError = null;
    this.cdr.markForCheck();

    try {
      if (!this.wallet.isConnected()) {
        await this.wallet.connect();
      }
      const connectedWallet = this.wallet.address()?.toLowerCase();
      const position = this.positions.find((candidate) =>
        candidate.marketId === market.id && candidate.walletAddress.toLowerCase() === connectedWallet);
      const amountUnits = this.amountUnits(market);
      if (!position || amountUnits > BigInt(position.currentClaim)) {
        throw new Error('The withdrawal exceeds the connected wallet\'s current claim in this market.');
      }
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

  isValidAmount(): boolean {
    const market = this.markets.find((candidate) => candidate.id === this.selectedMarketId);
    if (!market || !Number.isFinite(this.amount) || this.amount <= 0) return false;
    try {
      return this.amountUnits(market) > 0n;
    } catch {
      return false;
    }
  }

  private marketDecimals(marketId: string | null): number {
    return this.markets.find((market) => market.id === marketId)?.loanTokenDecimals ?? 6;
  }

  private amountUnits(market: LendingMarket): bigint {
    return parseUnits(String(this.amount), market.loanTokenDecimals ?? 6);
  }
}
