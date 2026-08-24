import { ChangeDetectorRef, Component, DestroyRef, OnInit, ViewChild, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { EMPTY } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { MatStepperModule, MatStepper } from '@angular/material/stepper';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { InvestmentService } from '../../../core/api/investment.service';
import { LendingService } from '../../../core/api/lending.service';
import { WalletService } from '../../../core/wallet/wallet.service';
import { erc20Abi, repoMarketAbi } from '../../../core/wallet/abi/repo-market.abi';
import { InvestmentRecord, LendingMarket, LendingQuote } from '../../../core/models';
import { LendingComplianceBannerComponent } from '../compliance-banner.component';
import { formatUnits as formatTokenUnits, parseUnits, type Address } from 'viem';

/**
 * Guided pledge-and-borrow flow — replaces the dead end in `investment-detail` that used to
 * hand the trader off to the marketplace listing page for `repo-facility` with an actual,
 * in-app, step-by-step transaction flow: review the holding and its matched market, connect a
 * wallet, size the loan against live terms, then approve + pledgeAndBorrow.
 */
@Component({
  selector: 'app-borrow-stepper',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    ReactiveFormsModule,
    MatStepperModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatProgressSpinnerModule,
    LendingComplianceBannerComponent,
  ],
  template: `
    <div class="page-container">
      <a mat-button routerLink="/positions">
        <mat-icon>arrow_back</mat-icon>
        My Positions
      </a>

      <header class="borrow-header">
        <h1>Borrow against this position</h1>
        <p>Review eligibility, choose terms, and confirm the collateral transaction.</p>
      </header>

      <app-lending-compliance-banner [market]="market" />

      @if (loading) {
        <div class="loading-row"><mat-spinner diameter="32"></mat-spinner></div>
      } @else if (loadError) {
        <mat-card class="section-card">
          <mat-card-content role="alert">
            <mat-icon class="warn-icon">cloud_off</mat-icon>
            <p>{{ loadError }}</p>
            @if (holderId) {
              <button mat-stroked-button type="button" (click)="load()">Retry</button>
            }
          </mat-card-content>
        </mat-card>
      } @else if (!market) {
        <mat-card class="section-card">
          <mat-card-content>
            <mat-icon class="warn-icon">info_outline</mat-icon>
            <p>This holding isn't eligible for repo/lending yet — no active market has been registered against it.</p>
            <a mat-stroked-button routerLink="/positions">Back to My Positions</a>
          </mat-card-content>
        </mat-card>
      } @else if (marketPaused) {
        <mat-card class="section-card">
          <mat-card-content>
            <mat-icon class="warn-icon">pause_circle_outline</mat-icon>
            <p>
              This market is temporarily paused for new borrowing by the registry operator.
              Existing loans are unaffected — you can still repay or manage a loan you already
              have from My Loans.
            </p>
            <a mat-stroked-button routerLink="/lending/loans">Go to My Loans</a>
          </mat-card-content>
        </mat-card>
      } @else {
        <mat-card>
          <mat-card-content>
            <mat-stepper [linear]="true" #stepper>

              <!-- ── Step 1: Review ──────────────────────────────────────────── -->
              <mat-step label="Review">
                <div class="step-body">
                  <h3>{{ investment?.assetName ?? investment?.assetNumber }}</h3>
                  <div class="detail-grid">
                    <div><span class="label">Nominal held</span><span class="value">{{ investment?.nominalAmount }}</span></div>
                    <div><span class="label">Maximum origination LTV</span><span class="value">{{ ((market.maxLtvBps ?? 0) / 100).toFixed(1) }}%</span></div>
                    <div><span class="label">Liquidation threshold</span><span class="value">{{ (market.lltvBps / 100).toFixed(1) }}%</span></div>
                    <div><span class="label">Liquidation bonus</span><span class="value">{{ (market.liquidationBonusBps / 100).toFixed(1) }}%</span></div>
                    <div><span class="label">Market</span><span class="value mono">{{ market.marketAddress | slice:0:10 }}…</span></div>
                  </div>
                  <p class="hint">
                    Pledging keeps this holding's coupon and redemption rights intact — you're borrowing against
                    it, not selling it. Repay any time to reclaim your full position.
                  </p>
                  <button mat-flat-button color="primary" type="button" (click)="stepper.next()">Continue</button>
                </div>
              </mat-step>

              <!-- ── Step 2: Connect wallet ───────────────────────────────────── -->
              <mat-step label="Connect wallet">
                <div class="step-body">
                  @if (wallet.isConnected() && walletMatchesHolding) {
                    <p class="success-text">
                      <mat-icon>check_circle</mat-icon>
                      Connected as <code>{{ wallet.address() }}</code>
                    </p>
                    <button mat-flat-button color="primary" type="button" (click)="onWalletConfirmed(stepper)">Continue</button>
                  } @else {
                    @if (wallet.isConnected() && !walletMatchesHolding) {
                      <p class="error-text">
                        Connected wallet <code>{{ wallet.address() }}</code> does not match this holding's
                        registered wallet <code>{{ investment?.walletAddress }}</code>. Switch accounts in your
                        wallet extension and reconnect.
                      </p>
                    }
                    <button mat-flat-button color="primary" type="button" [disabled]="wallet.connecting()" (click)="connect()">
                      @if (wallet.connecting()) {
                        <mat-spinner diameter="18"></mat-spinner>
                      } @else {
                        Connect wallet
                      }
                    </button>
                    @if (wallet.error()) {
                      <p class="error-text">{{ wallet.error() }}</p>
                    }
                  }
                </div>
              </mat-step>

              <!-- ── Step 3: Size the loan ────────────────────────────────────── -->
              <mat-step [stepControl]="amountsForm" label="Size the loan">
                <form [formGroup]="amountsForm" class="step-body">
                  <mat-form-field appearance="outline" class="full-width">
                    <mat-label>Collateral to pledge</mat-label>
                    <input matInput type="number" min="1" step="1" formControlName="collateralAmount" />
                    <mat-hint>Up to your full holding of {{ investment?.nominalAmount }} units</mat-hint>
                  </mat-form-field>

                  @if (quoting) {
                    <div class="quote-row"><mat-spinner diameter="18"></mat-spinner> Fetching live terms…</div>
                  } @else if (quoteError) {
                    <p class="error-text" role="alert">{{ quoteError }}</p>
                  } @else if (quote) {
                    @if (!quote.oracleReliable) {
                      <p class="error-text" role="alert">
                        The collateral price is missing or stale. This quote is informational only;
                        borrowing is disabled until the oracle is current.
                      </p>
                    }
                    <div class="quote-card">
                      <div><span class="label">Price / unit</span><span class="value">{{ formatLoanUnits(quote.pricePerUnit) }}</span></div>
                      <div><span class="label">Max borrow</span><span class="value">{{ formatLoanUnits(quote.maxBorrowAmount) }}</span></div>
                      <div><span class="label">Available liquidity</span><span class="value">{{ formatLoanUnits(quote.availableLiquidity) }}</span></div>
                      <div><span class="label">Origination LTV</span><span class="value">{{ (quote.maxLtvBps / 100).toFixed(1) }}%</span></div>
                      <div><span class="label">Utilization</span><span class="value">{{ formatPct(quote.utilizationWad) }}</span></div>
                      <div><span class="label">Borrow rate (APR)</span><span class="value">{{ formatPct(quote.borrowRateWad) }}</span></div>
                    </div>

                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Amount to borrow (stablecoin)</mat-label>
                      <input matInput type="number" min="0.000001" step="0.000001" formControlName="borrowAmount" />
                      <mat-hint>Max {{ formatLoanUnits(quote.maxBorrowAmount) }} at this collateral amount</mat-hint>
                      @if (amountsForm.get('borrowAmount')?.hasError('max')) {
                        <mat-error>Exceeds the max borrow for this collateral amount</mat-error>
                      }
                    </mat-form-field>
                  }

                  <button mat-flat-button color="primary" type="button" [disabled]="!quote?.oracleReliable || amountsForm.invalid" (click)="stepper.next()">
                    Continue
                  </button>
                </form>
              </mat-step>

              <!-- ── Step 4: Confirm & sign ───────────────────────────────────── -->
              <mat-step label="Confirm & sign">
                <div class="step-body">
                  @if (!executed) {
                    <div class="summary-card">
                      <div><span class="label">Pledging</span><span class="value">{{ amountsForm.value.collateralAmount }} units</span></div>
                      <div><span class="label">Borrowing</span><span class="value">{{ amountsForm.value.borrowAmount }} (stablecoin)</span></div>
                    </div>

                    <button mat-flat-button color="primary" type="button" [disabled]="executing || !quote?.oracleReliable" (click)="execute()">
                      @if (executing) {
                        <mat-spinner diameter="18"></mat-spinner>
                        {{ executionStage }}
                      } @else {
                        Pledge &amp; borrow
                      }
                    </button>
                    @if (executionError) {
                      <p class="error-text">{{ executionError }}</p>
                    }
                  } @else {
                    <p class="success-text">
                      <mat-icon>check_circle</mat-icon>
                      Borrow confirmed — tx <code>{{ txHash | slice:0:14 }}…</code>
                    </p>
                    <a mat-flat-button color="primary" routerLink="/lending/loans">View my loans</a>
                  }
                </div>
              </mat-step>
            </mat-stepper>
          </mat-card-content>
        </mat-card>
      }
    </div>
  `,
  styles: [`
    .borrow-header { margin: 18px 0 22px; }
    .borrow-header h1 { margin: 0; font-size: 24px; letter-spacing: -0.5px; }
    .borrow-header p { margin: 6px 0 0; color: var(--rw-text-secondary); font-size: 13px; }
    .loading-row { display: flex; justify-content: center; padding: 48px 0; }
    .section-card mat-card-content { display: flex; flex-direction: column; align-items: center; gap: 12px; padding: 32px !important; text-align: center; }
    .warn-icon { font-size: 32px; width: 32px; height: 32px; color: var(--rw-text-secondary); }
    .step-body { display: flex; flex-direction: column; gap: 14px; padding: 20px 4px; max-width: 480px; }
    .full-width { width: 100%; }
    .detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .detail-grid > div, .quote-card > div, .summary-card > div { display: flex; flex-direction: column; }
    .label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px; color: var(--rw-text-secondary); }
    .value { font-size: 15px; font-weight: 600; color: var(--rw-text-primary); }
    .value.mono { font-family: 'IBM Plex Mono', monospace; font-size: 13px; }
    .hint { font-size: 12.5px; color: var(--rw-text-secondary); }
    .quote-row { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--rw-text-secondary); }
    .quote-card { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; padding: 12px; border: 1px solid var(--rw-border); border-radius: 8px; }
    .summary-card { display: flex; flex-direction: column; gap: 8px; padding: 12px; border: 1px solid var(--rw-border); border-radius: 8px; }
    .sponsor-toggle { display: flex; align-items: center; gap: 6px; }
    .info-icon { font-size: 15px; width: 15px; height: 15px; opacity: 0.6; cursor: help; }
    .success-text { display: flex; align-items: center; gap: 6px; color: #059669; }
    .success-text code, .error-text code { font-family: 'IBM Plex Mono', monospace; font-size: 11px; }
    .error-text { color: #dc2626; font-size: 13px; }
  `],
})
export class BorrowStepperComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly investmentService = inject(InvestmentService);
  private readonly lendingService = inject(LendingService);
  private readonly fb = inject(FormBuilder);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);

  readonly wallet = inject(WalletService);

  @ViewChild('stepper') stepperRef?: MatStepper;

  loading = true;
  holderId = '';
  loadError = '';
  investment: InvestmentRecord | null = null;
  market: LendingMarket | null = null;

  quoting = false;
  quote: LendingQuote | null = null;
  quoteError = '';

  executing = false;
  executionStage = '';
  executionError: string | null = null;
  executed = false;
  txHash: string | null = null;

  amountsForm = this.fb.group({
    collateralAmount: [0, [Validators.required, Validators.min(1)]],
    borrowAmount: [0, [Validators.required, Validators.min(1)]],
  });

  get walletMatchesHolding(): boolean {
    const connected = this.wallet.address();
    const target = this.investment?.walletAddress;
    return !!connected && !!target && connected.toLowerCase() === target.toLowerCase();
  }

  /**
   * `market.status` reflects the on-chain `EwpgRepoMarket.borrowPaused` flag live (see
   * `LendingMarketService.resolveEffectiveStatus` backend-side) even though this component
   * fetched it via `listMarkets('ACTIVE')` — the DB-persisted filter and the returned status
   * are deliberately independent, so a market can be found here yet still read PAUSED.
   */
  get marketPaused(): boolean {
    return this.market?.status === 'PAUSED';
  }

  ngOnInit(): void {
    // debounced/switchMap so rapid typing doesn't fire a quote request per keystroke, and a
    // stale in-flight request can't overwrite a newer one's result.
    this.amountsForm.get('collateralAmount')!.valueChanges.pipe(
      debounceTime(350),
      distinctUntilChanged(),
      switchMap((amount) => {
        if (!this.market || !amount || amount <= 0) {
          this.quote = null;
          this.quoteError = '';
          return EMPTY;
        }
        this.quoting = true;
        this.quoteError = '';
        this.cdr.markForCheck();
        return this.lendingService.quote(this.market.id, String(amount)).pipe(
          catchError(() => {
            this.quote = null;
            this.quoting = false;
            this.quoteError = 'A live quote could not be loaded. Change the amount or try again.';
            this.cdr.markForCheck();
            return EMPTY;
          }),
        );
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe({
      next: (quote) => this.onQuoteLoaded(quote),
    });

    this.holderId = this.route.snapshot.paramMap.get('holderId')?.trim() ?? '';
    if (!this.holderId) {
      this.loading = false;
      this.loadError = 'The holding address is incomplete.';
      return;
    }
    this.load();
  }

  load(): void {
    if (!this.holderId) return;
    this.loading = true;
    this.loadError = '';
    this.investment = null;
    this.market = null;
    this.investmentService.getInvestment(this.holderId).subscribe({
      next: (investment) => {
        this.investment = investment;
        this.amountsForm.get('collateralAmount')?.setValidators([
          Validators.required,
          Validators.min(1),
          Validators.max(investment.nominalAmount),
        ]);
        // Programmatic initial value — emitEvent:false so it doesn't itself go through the
        // debounced valueChanges pipeline above; fetched explicitly below once market is known.
        this.amountsForm.patchValue({ collateralAmount: investment.nominalAmount }, { emitEvent: false });
        this.lendingService.listMarkets('ACTIVE').subscribe({
          next: (markets) => {
            this.market = markets.find((m) => m.collateralAssetId === investment.assetId) ?? null;
            this.loading = false;
            this.cdr.markForCheck();
            if (this.market) {
              this.amountsForm.get('collateralAmount')?.setValue(investment.nominalAmount);
            }
          },
          error: () => {
            this.loadError = 'Active lending markets could not be loaded.';
            this.loading = false;
            this.cdr.markForCheck();
          },
        });
      },
      error: (err) => {
        this.loadError = err?.error?.message ?? 'The holding could not be loaded.';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  async connect(): Promise<void> {
    try {
      await this.wallet.connect();
    } catch {
      // wallet.error() signal already carries the message for the template.
    }
    this.cdr.markForCheck();
  }

  onWalletConfirmed(stepper: MatStepper): void {
    stepper.next();
  }

  private onQuoteLoaded(quote: LendingQuote): void {
    this.quote = quote;
    this.quoting = false;
    const maxBorrowHuman = Number(formatTokenUnits(BigInt(quote.maxBorrowAmount), this.loanTokenDecimals));
    this.amountsForm.get('borrowAmount')?.setValidators([
      Validators.required,
      Validators.min(1),
      Validators.max(maxBorrowHuman),
    ]);
    this.amountsForm.get('borrowAmount')?.updateValueAndValidity();
    this.cdr.markForCheck();
  }

  get loanTokenDecimals(): number {
    return this.market?.loanTokenDecimals ?? 6;
  }

  formatLoanUnits(raw: string): string {
    const value = formatTokenUnits(BigInt(raw), this.loanTokenDecimals);
    return Number(value).toLocaleString(undefined, { maximumFractionDigits: this.loanTokenDecimals });
  }

  formatPct(wad: string): string {
    return `${(Number(wad) / 1e16).toFixed(2)}%`;
  }

  async execute(): Promise<void> {
    const collateralHuman = this.amountsForm.value.collateralAmount ?? 0;
    const borrowAmountHuman = this.amountsForm.value.borrowAmount ?? 0;
    if (!this.market || this.executing || this.amountsForm.invalid || !this.quote?.oracleReliable
        || !this.walletMatchesHolding || !Number.isSafeInteger(collateralHuman)
        || !Number.isFinite(borrowAmountHuman)) return;
    this.executing = true;
    this.executionError = null;
    this.cdr.markForCheck();

    try {
      const collateralAmount = BigInt(collateralHuman);
      const borrowAmount = parseUnits(String(borrowAmountHuman), this.loanTokenDecimals);
      const marketAddress = this.market.marketAddress as Address;
      const collateralToken = this.market.collateralTokenAddress as Address;

      this.executionStage = 'Checking allowance…';
      this.cdr.markForCheck();
      const allowance = await this.wallet.readContract<bigint>({
        address: collateralToken,
        abi: erc20Abi,
        functionName: 'allowance',
        args: [this.wallet.address(), marketAddress],
      });

      if (allowance < collateralAmount) {
        this.executionStage = 'Approving collateral…';
        this.cdr.markForCheck();
        const approveHash = await this.wallet.writeContract({
          address: collateralToken,
          abi: erc20Abi,
          functionName: 'approve',
          args: [marketAddress, collateralAmount],
        });
        await this.wallet.waitForTransaction(approveHash);
      }

      // Lending-market borrowing uses the connected wallet's direct transaction path.
      const hash = await this.wallet.writeContract({
        address: marketAddress,
        abi: repoMarketAbi,
        functionName: 'pledgeAndBorrow',
        args: [collateralAmount, borrowAmount],
      });
      await this.wallet.waitForTransaction(hash);
      this.txHash = hash;

      this.executed = true;
    } catch (err: unknown) {
      this.executionError = err instanceof Error ? err.message : 'Transaction failed.';
    } finally {
      this.executing = false;
      this.cdr.markForCheck();
    }
  }
}
