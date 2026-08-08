import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { PageHeaderComponent } from '@registerwerk/ui';
import { LendingService } from '../../../core/api/lending.service';
import { WalletService } from '../../../core/wallet/wallet.service';
import { LendingMarket, LendingPosition, LendingSupplyPosition } from '../../../core/models';
import { LendingComplianceBannerComponent } from '../compliance-banner.component';

/**
 * "Liquidity" home for the Trader workspace — the guided entry point that used to not exist at
 * all (the only prior liquidity affordance was a single text card in `investment-detail`
 * pointing at the marketplace listing page). Surfaces next-step guidance instead of requiring
 * the trader to already know that pledging is even possible.
 */
@Component({
  selector: 'app-liquidity-overview',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    PageHeaderComponent,
    LendingComplianceBannerComponent,
  ],
  template: `
    <div class="page-container">
      <app-page-header title="Liquidity" subtitle="Borrow against your holdings without selling them, or supply stablecoin to earn yield." />

      <app-lending-compliance-banner />

      @if (loading) {
        <div class="loading-row"><mat-spinner diameter="32"></mat-spinner></div>
      } @else {
        @if (loadWarnings.length > 0) {
          <mat-card class="warning-card">
            <mat-card-content role="alert">
              <mat-icon>cloud_off</mat-icon>
              <div>
                @for (warning of loadWarnings; track warning) { <p>{{ warning }}</p> }
              </div>
              <button mat-button type="button" (click)="load()">Retry</button>
            </mat-card-content>
          </mat-card>
        }
        <div class="stat-grid">
          <mat-card class="stat-card">
            <mat-card-content>
              <div class="stat-value">{{ openLoans.length }}</div>
              <div class="stat-label">Open loans</div>
            </mat-card-content>
          </mat-card>
          <mat-card class="stat-card">
            <mat-card-content>
              <div class="stat-value">{{ supplyPositions.length }}</div>
              <div class="stat-label">Supply positions</div>
            </mat-card-content>
          </mat-card>
          <mat-card class="stat-card">
            <mat-card-content>
              <div class="stat-value">{{ activeMarkets.length }}</div>
              <div class="stat-label">Markets available</div>
            </mat-card-content>
          </mat-card>
        </div>

        <div class="next-steps">
          @if (!walletConnected) {
            <mat-card class="step-card">
              <mat-card-content>
                <mat-icon class="step-icon">account_balance_wallet</mat-icon>
                <div class="step-body">
                  <strong>① Connect your wallet</strong>
                  <span>Needed to sign a pledge, repay, or supply transaction.</span>
                </div>
                <button mat-flat-button color="primary" type="button" [disabled]="wallet.connecting()" (click)="connectWallet()">
                  {{ wallet.connecting() ? 'Connecting…' : 'Connect' }}
                </button>
              </mat-card-content>
            </mat-card>
            @if (wallet.error()) {
              <p class="wallet-error" role="alert">{{ wallet.error() }}</p>
            }
          }

          @if (openLoans.length > 0) {
            <mat-card class="step-card">
              <mat-card-content>
                <mat-icon class="step-icon">monitoring</mat-icon>
                <div class="step-body">
                  <strong>You have {{ openLoans.length }} open loan(s)</strong>
                  <span>Check your health factor and manage repayments.</span>
                </div>
                <a mat-stroked-button routerLink="/lending/loans">View loans</a>
              </mat-card-content>
            </mat-card>
          } @else {
            <mat-card class="step-card">
              <mat-card-content>
                <mat-icon class="step-icon">water_drop</mat-icon>
                <div class="step-body">
                  <strong>Free up cash without selling</strong>
                  <span>Pledge an eligible holding as collateral and borrow a stablecoin against it.</span>
                </div>
                <a mat-flat-button color="primary" routerLink="/positions">Choose a holding</a>
              </mat-card-content>
            </mat-card>
          }

          <mat-card class="step-card">
            <mat-card-content>
              <mat-icon class="step-icon">savings</mat-icon>
              <div class="step-body">
                <strong>Supply &amp; earn</strong>
                <span>Deposit stablecoin into any market — no KYC needed — and earn utilization-based yield.</span>
              </div>
              <a mat-stroked-button routerLink="/lending/supply">Supply</a>
            </mat-card-content>
          </mat-card>
        </div>
      }
    </div>
  `,
  styles: [`
    .loading-row { display: flex; justify-content: center; padding: 48px 0; }
    .stat-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: 16px;
      margin-bottom: 20px;
    }
    .stat-card { border-radius: 12px; }
    .stat-value { font-size: 26px; font-weight: 700; color: var(--rw-text-primary); }
    .stat-label { font-size: 12px; text-transform: uppercase; letter-spacing: 0.4px; color: var(--rw-text-secondary); margin-top: 4px; }
    .next-steps { display: flex; flex-direction: column; gap: 12px; }
    .step-card mat-card-content { display: flex; align-items: center; gap: 14px; padding: 16px !important; }
    .step-icon { font-size: 28px; width: 28px; height: 28px; color: var(--rw-accent); flex-shrink: 0; }
    .step-body { display: flex; flex-direction: column; gap: 2px; flex: 1; }
    .step-body span { font-size: 12.5px; color: var(--rw-text-secondary); }
    .warning-card { margin-bottom: 16px; }
    .warning-card mat-card-content { display: flex; align-items: center; gap: 12px; }
    .warning-card p { margin: 0; color: var(--rw-text-danger); font-size: 12px; }
    .wallet-error { color: var(--rw-text-danger); font-size: 12px; }
  `],
})
export class LiquidityOverviewComponent implements OnInit {
  private readonly lendingService = inject(LendingService);
  readonly wallet = inject(WalletService);
  private readonly cdr = inject(ChangeDetectorRef);

  loading = true;
  openLoans: LendingPosition[] = [];
  supplyPositions: LendingSupplyPosition[] = [];
  activeMarkets: LendingMarket[] = [];
  loadWarnings: string[] = [];

  get walletConnected(): boolean {
    return this.wallet.isConnected();
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadWarnings = [];
    let positionsFailed = false;
    let supplyFailed = false;
    let marketsFailed = false;
    forkJoin({
      positions: this.lendingService.myPositions().pipe(catchError(() => {
        positionsFailed = true;
        return of<LendingPosition[]>([]);
      })),
      supply: this.lendingService.supplyPositions().pipe(catchError(() => {
        supplyFailed = true;
        return of<LendingSupplyPosition[]>([]);
      })),
      markets: this.lendingService.listMarkets('ACTIVE').pipe(catchError(() => {
        marketsFailed = true;
        return of<LendingMarket[]>([]);
      })),
    }).subscribe(({ positions, supply, markets }) => {
      this.openLoans = positions.filter((p) => p.status === 'OPEN');
      this.supplyPositions = supply;
      this.activeMarkets = markets;
      if (positionsFailed) this.loadWarnings.push('Open loans could not be loaded.');
      if (supplyFailed) this.loadWarnings.push('Supply positions could not be loaded.');
      if (marketsFailed) this.loadWarnings.push('Available markets could not be loaded.');
      this.loading = false;
      this.cdr.markForCheck();
    });
  }

  async connectWallet(): Promise<void> {
    if (this.wallet.connecting()) return;
    try {
      await this.wallet.connect();
    } catch {
      // WalletService already surfaces its own error() signal; nothing further to do here.
    }
    this.cdr.markForCheck();
  }
}
