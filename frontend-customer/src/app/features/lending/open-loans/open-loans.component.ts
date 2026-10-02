import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { PageHeaderComponent, DataTableComponent, TableColumn, AsyncSectionStatus } from '@registerwerk/ui';
import { LendingService } from '../../../core/api/lending.service';
import { WalletService } from '../../../core/wallet/wallet.service';
import {
  repoMarketAbi,
  trexEligibilityAbi,
  trexIdentityRegistryAbi,
} from '../../../core/wallet/abi/repo-market.abi';
import { LendingMarket, LendingPosition } from '../../../core/models';
import { formatUnits as formatTokenUnits, type Address } from 'viem';
import { fullRepayRequest } from '../../../core/lending/token-amount.util';
import { executeRepay } from '../../../core/lending/repay.flow';
import { formatTokenAmount, formatTokenAmountGrouped, parseTokenAmount } from '../../../core/lending/token-amount.util';

interface LoanRow extends LendingPosition {
  marketLabel: string;
  surplusDisplay: string;
  healthFactorDisplay: string;
  healthFactorSeverity: 'ok' | 'warn' | 'danger' | 'none';
  dataNotes: string;
}

@Component({
  selector: 'app-open-loans',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatCheckboxModule,
    MatDialogModule,
    MatSnackBarModule,
    PageHeaderComponent,
    DataTableComponent,
  ],
  template: `
    <div class="page-container">
      <app-page-header title="My Loans" subtitle="Repay any time to reclaim your collateral — a health factor below 1.0 risks liquidation.">
        <a mat-stroked-button routerLink="/lending">
          <mat-icon>arrow_back</mat-icon>
          Liquidity
        </a>
      </app-page-header>

      @if (state === 'ready' && marketsLoadFailed) {
        <p class="warning-text" role="status">
          <mat-icon>info_outline</mat-icon>
          Market details are temporarily unavailable; repayment is disabled until you retry.
        </p>
      }

      @if (hasStaleOrUnverified) {
        <p class="warning-text" role="status">
          <mat-icon>info_outline</mat-icon>
          Some figures are not confirmed: "Balance may be out of date" means the chain could not be read at the
          last refresh; "Collateral unverified" means the market holds less collateral than recorded (for example
          after a forced transfer) and the operator is reconciling it. Amounts may overstate what can be returned.
        </p>
      }

      @if (claimError) {
        <p class="error-text" role="alert">{{ claimError }}</p>
      }

      @if (hasAnySurplus) {
        <p class="hint-text" role="status">
          A liquidation sold your collateral in whole units for more than the debt it closed. The
          difference is yours: use "Claim … surplus" to receive it in your wallet.
          @if (hasStaleSurplus) { The surplus shown may be out of date; check again after the next refresh. }
        </p>
      }

      <rw-data-table
        [columns]="columns"
        [rows]="rows"
        [state]="state"
        filterPlaceholder="Filter loans…"
        emptyMessage="No open or past loans yet."
        [actionsTemplate]="actions"
        (retry)="load()">
      </rw-data-table>

      <ng-template #actions let-row>
        @if (row.status === 'OPEN') {
          <button mat-stroked-button type="button" (click)="openRepay(row, repayDialog)">
            <mat-icon>payments</mat-icon>
            Repay
          </button>
          <button mat-stroked-button type="button" (click)="openCollateral(row, collateralDialog, 'add')">
            <mat-icon>add_circle_outline</mat-icon>
            Add collateral
          </button>
          <button mat-stroked-button type="button" (click)="openCollateral(row, collateralDialog, 'withdraw')">
            <mat-icon>move_up</mat-icon>
            Withdraw excess
          </button>
        } @else if (hasClaimableCollateral(row)) {
          <button mat-stroked-button type="button" [disabled]="claimingKey === rowKey(row)" (click)="claimCollateral(row)">
            <mat-icon>move_down</mat-icon>
            @if (claimingKey === rowKey(row)) { Claiming… } @else { Claim collateral }
          </button>
        }
        @if (hasClaimableSurplus(row)) {
          <button mat-stroked-button type="button" [disabled]="claimingSurplusKey === rowKey(row)" (click)="claimSurplus(row)">
            <mat-icon>savings</mat-icon>
            @if (claimingSurplusKey === rowKey(row)) { Claiming… } @else { Claim {{ row.surplusDisplay }} surplus }
          </button>
        }
      </ng-template>

      <ng-template #repayDialog let-data>
        <h2 mat-dialog-title>Repay loan</h2>
        <mat-dialog-content>
          <p>Outstanding debt: {{ formatDebt(data.row) }}</p>
          <mat-checkbox [(ngModel)]="repayFull" [disabled]="repaying">Repay in full</mat-checkbox>
          @if (repayFull) {
            <p class="hint-text">
              You will pay at most {{ fullRepayCap(data.row) }} (the current debt plus a safety margin for interest
              accruing until the transaction is mined). Only the actual debt is taken; the unused allowance is reset
              to 0 afterwards.
            </p>
          } @else {
            <mat-form-field appearance="outline" class="full-width">
              <mat-label>Amount to repay</mat-label>
              <input matInput type="text" inputmode="decimal" autocomplete="off" [(ngModel)]="repayAmountText" />
            </mat-form-field>
          }
          @if (collateralReleaseBlocker) {
            <p class="warning-text" role="status">
              <mat-icon>info_outline</mat-icon>
              {{ collateralReleaseBlocker }}
            </p>
            <mat-checkbox [(ngModel)]="repayKeepCollateral" [disabled]="repaying">
              Repay without withdrawing collateral
            </mat-checkbox>
            @if (repayKeepCollateral) {
              <p class="hint-text">
                Your collateral stays pledged. Once the loan is fully repaid and the collateral token
                accepts your wallet again, use "Claim collateral" to take it back.
              </p>
            }
          }
          @if (repayError) {
            <p class="error-text">{{ repayError }}</p>
          }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
          <button mat-button type="button" [mat-dialog-close]="null" [disabled]="repaying">Cancel</button>
          <button mat-flat-button color="primary" type="button" [disabled]="repaying || (!repayFull && !repayAmountText)" (click)="confirmRepay(data.row)">
            @if (repaying) { Repaying… } @else { Repay }
          </button>
        </mat-dialog-actions>
      </ng-template>

      <ng-template #collateralDialog let-data>
        <h2 mat-dialog-title>{{ collateralAction === 'add' ? 'Add collateral' : 'Withdraw excess collateral' }}</h2>
        <mat-dialog-content>
          <p>
            Current collateral: {{ data.row.collateralAmount }} units.
            @if (collateralAction === 'withdraw') {
              The transaction will only succeed if the remaining position stays within the market's
              origination limit at the current oracle price.
            }
          </p>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Collateral units</mat-label>
            <input matInput type="number" min="1" step="1" [(ngModel)]="collateralAmount" />
          </mat-form-field>
          @if (collateralError) {
            <p class="error-text" role="alert">{{ collateralError }}</p>
          }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
          <button mat-button type="button" [mat-dialog-close]="null" [disabled]="managingCollateral">Cancel</button>
          <button mat-flat-button color="primary" type="button"
                  [disabled]="managingCollateral || !isValidCollateralAmount()"
                  (click)="confirmCollateralChange(data.row)">
            @if (managingCollateral) { Confirming… } @else { Confirm }
          </button>
        </mat-dialog-actions>
      </ng-template>
    </div>
  `,
  styles: [`
    .full-width { width: 100%; margin-top: 8px; }
    .error-text { color: var(--rw-text-danger); font-size: 12.5px; }
    .warning-text { display: flex; align-items: center; gap: 7px; color: var(--rw-text-warning); font-size: 12px; }
    .warning-text mat-icon { font-size: 16px; height: 16px; width: 16px; flex-shrink: 0; }
    .hint-text { color: var(--rw-text-secondary); font-size: 12px; }
  `],
})
export class OpenLoansComponent implements OnInit {
  private readonly lendingService = inject(LendingService);
  private readonly wallet = inject(WalletService);
  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly snackBar = inject(MatSnackBar);

  @ViewChild('actions', { static: true }) actionsTpl!: TemplateRef<{ $implicit: LoanRow }>;

  state: AsyncSectionStatus = 'pending';
  rows: LoanRow[] = [];
  repayAmountText = '';
  repayFull = true;
  repaying = false;
  repayError: string | null = null;
  /** Why the collateral token would refuse to return collateral to this wallet, if it would. */
  collateralReleaseBlocker: string | null = null;
  repayKeepCollateral = false;
  claimingKey: string | null = null;
  claimingSurplusKey: string | null = null;
  claimError: string | null = null;
  collateralAction: 'add' | 'withdraw' = 'add';
  collateralAmount = 0;
  managingCollateral = false;
  collateralError: string | null = null;
  marketsLoadFailed = false;

  private marketsById = new Map<string, LendingMarket>();

  readonly columns: TableColumn[] = [
    { key: 'marketLabel', header: 'Market', cell: (r: LoanRow) => r.marketLabel },
    { key: 'walletAddress', header: 'Wallet', cell: (r: LoanRow) => r.walletAddress, type: 'mono' },
    { key: 'collateralAmount', header: 'Collateral', cell: (r: LoanRow) => r.collateralAmount, type: 'number' },
    { key: 'currentDebt', header: 'Debt', cell: (r: LoanRow) => this.formatDebt(r) },
    { key: 'healthFactorDisplay', header: 'Health factor', cell: (r: LoanRow) => r.healthFactorDisplay },
    { key: 'surplusDisplay', header: 'Claimable surplus', cell: (r: LoanRow) => r.surplusDisplay },
    { key: 'dataNotes', header: 'Data quality', cell: (r: LoanRow) => r.dataNotes },
    { key: 'status', header: 'Status', cell: (r: LoanRow) => r.status, type: 'badge' },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.rows = [];
    this.marketsLoadFailed = false;
    forkJoin({
      positions: this.lendingService.myPositions(),
      markets: this.lendingService.listMarkets().pipe(
        map((markets) => {
          this.marketsById = new Map(markets.map((m) => [m.id, m]));
          return markets;
        }),
        catchError(() => {
          this.marketsLoadFailed = true;
          return of<LendingMarket[]>([]);
        }),
      ),
    }).subscribe({
      next: ({ positions }) => {
        this.rows = positions.map((p) => this.toRow(p));
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.rows = [];
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  private toRow(position: LendingPosition): LoanRow {
    const market = this.marketsById.get(position.marketId);
    const hfRaw = position.healthFactorWad && position.healthFactorReliable !== false
      ? Number(formatTokenUnits(BigInt(position.healthFactorWad), 18))
      : null;
    let severity: LoanRow['healthFactorSeverity'] = 'none';
    if (hfRaw !== null) {
      severity = hfRaw < 1 ? 'danger' : hfRaw < 1.2 ? 'warn' : 'ok';
    }
    return {
      ...position,
      marketLabel: market?.collateralAssetName ?? market?.marketAddress ?? position.marketId,
      surplusDisplay: BigInt(position.liquidationSurplus ?? '0') > 0n
        ? Number(formatTokenUnits(BigInt(position.liquidationSurplus), market?.loanTokenDecimals ?? 6))
          .toLocaleString(undefined, { maximumFractionDigits: market?.loanTokenDecimals ?? 6 })
        : '—',
      healthFactorDisplay: position.healthFactorReliable === false
        ? 'Unavailable (stale price)'
        : hfRaw !== null ? hfRaw.toFixed(2) : '—',
      healthFactorSeverity: severity,
      dataNotes: [
        position.stale ? 'Balance may be out of date' : '',
        position.collateralUnverified ? 'Collateral unverified' : '',
      ].filter(Boolean).join(' · ') || '—',
    };
  }

  private loanTokenDecimals(row: LoanRow): number {
    return this.marketsById.get(row.marketId)?.loanTokenDecimals ?? 6;
  }

  /** Exact (bigint-based) display; never goes through Number. */
  formatDebt(row: LoanRow): string {
    return formatTokenAmountGrouped(BigInt(row.currentDebt), this.loanTokenDecimals(row));
  }

  outstandingUnits(row: LoanRow): bigint {
    return BigInt(row.currentDebt);
  }

  /** Upper bound shown for a full repayment: what the transaction will request/approve. */
  fullRepayCap(row: LoanRow): string {
    const market = this.marketsById.get(row.marketId);
    const decimals = this.loanTokenDecimals(row);
    const cap = fullRepayRequest(this.outstandingUnits(row), market ?? {});
    return formatTokenAmountGrouped(cap, decimals);
  }

  openRepay(row: LoanRow, template: TemplateRef<{ $implicit: LoanRow }>): void {
    this.repayFull = true;
    this.repayAmountText = formatTokenAmount(this.outstandingUnits(row), this.loanTokenDecimals(row));
    this.repayError = null;
    this.collateralReleaseBlocker = null;
    this.repayKeepCollateral = false;
    this.dialog.open(template, { width: '420px', data: { row } });
    // Only possible with a connected wallet; otherwise confirmRepay() checks after connecting.
    if (this.wallet.isConnected()) {
      void this.detectCollateralReleaseBlocker(row);
    }
  }

  rowKey(row: LoanRow): string {
    return `${row.marketId}:${row.walletAddress.toLowerCase()}`;
  }

  /** A liquidation credited loan-token cash to this wallet (`surplusOf`). */
  hasClaimableSurplus(row: LoanRow): boolean {
    return BigInt(row.liquidationSurplus ?? '0') > 0n;
  }

  get hasStaleSurplus(): boolean {
    return this.rows.some((row) => row.stale && this.hasClaimableSurplus(row));
  }

  get hasStaleOrUnverified(): boolean {
    return this.rows.some((row) => row.stale || row.collateralUnverified);
  }

  get hasAnySurplus(): boolean {
    return this.rows.some((row) => this.hasClaimableSurplus(row));
  }

  async claimSurplus(row: LoanRow): Promise<void> {
    const market = this.marketsById.get(row.marketId);
    if (!market || this.claimingSurplusKey) {
      if (!market) this.claimError = 'Market details are unavailable. Reload the page and try again.';
      return;
    }
    this.claimingSurplusKey = this.rowKey(row);
    this.claimError = null;
    this.cdr.markForCheck();

    try {
      if (!this.wallet.isConnected()) await this.wallet.connect();
      if (this.wallet.address()?.toLowerCase() !== row.walletAddress.toLowerCase()) {
        throw new Error(`Connect the wallet that owns this position (${row.walletAddress}) before claiming.`);
      }
      await this.wallet.ensureChain(market.chainId, market.chainName);
      const hash = await this.wallet.writeContract({
        address: market.marketAddress as Address,
        abi: repoMarketAbi,
        functionName: 'claimLiquidationSurplus',
        args: [],
      });
      await this.wallet.waitForTransaction(hash);
      this.snackBar.open(
        `Claimed ${row.surplusDisplay} liquidation surplus. Tx: ${hash.slice(0, 10)}…${hash.slice(-6)}`,
        'Dismiss',
        { duration: 6000 },
      );
      this.load();
    } catch (err: unknown) {
      this.claimError = err instanceof Error ? err.message : 'Claiming the liquidation surplus failed.';
    } finally {
      this.claimingSurplusKey = null;
      this.cdr.markForCheck();
    }
  }

  /** Debt is closed but collateral is still held — after a debt-only repayment or a liquidation. */
  hasClaimableCollateral(row: LoanRow): boolean {
    return BigInt(row.currentDebt) === 0n && BigInt(row.collateralAmount) > 0n;
  }

  /**
   * Checks whether the collateral token would refuse to deliver collateral to the loan's wallet
   * (paused token, frozen wallet, or wallet no longer verified — T-REX rules). If so, offers the
   * debt-only repayment and selects it. Returns true when a blocker was found. Tokens that do not
   * expose these reads (non-T-REX collateral) are treated as unblocked.
   */
  private async detectCollateralReleaseBlocker(row: LoanRow): Promise<boolean> {
    const market = this.marketsById.get(row.marketId);
    if (!market) return false;
    const blocker = await this.collateralReleaseBlockerFor(
      market.collateralTokenAddress as Address,
      row.walletAddress as Address,
    );
    this.collateralReleaseBlocker = blocker;
    if (blocker) this.repayKeepCollateral = true;
    this.cdr.markForCheck();
    return blocker !== null;
  }

  private async collateralReleaseBlockerFor(token: Address, holder: Address): Promise<string | null> {
    try {
      const [paused, frozen, registry] = await Promise.all([
        this.wallet.readContract<boolean>({ address: token, abi: trexEligibilityAbi, functionName: 'paused' }),
        this.wallet.readContract<boolean>({
          address: token, abi: trexEligibilityAbi, functionName: 'isFrozen', args: [holder],
        }),
        this.wallet.readContract<Address>({ address: token, abi: trexEligibilityAbi, functionName: 'identityRegistry' }),
      ]);
      if (paused) {
        return 'The collateral token is paused, so the market cannot return collateral to you right now.';
      }
      if (frozen) {
        return 'Your wallet is frozen on the collateral token, so it cannot receive the collateral back.';
      }
      const verified = await this.wallet.readContract<boolean>({
        address: registry, abi: trexIdentityRegistryAbi, functionName: 'isVerified', args: [holder],
      });
      if (!verified) {
        return 'Your wallet is no longer a verified holder of the collateral token (for example, expired KYC), '
          + 'so it cannot receive the collateral back.';
      }
      return null;
    } catch {
      return null;
    }
  }

  async claimCollateral(row: LoanRow): Promise<void> {
    const market = this.marketsById.get(row.marketId);
    if (!market || this.claimingKey) {
      if (!market) this.claimError = 'Market details are unavailable. Reload the page and try again.';
      return;
    }
    this.claimingKey = this.rowKey(row);
    this.claimError = null;
    this.cdr.markForCheck();

    try {
      if (!this.wallet.isConnected()) await this.wallet.connect();
      if (this.wallet.address()?.toLowerCase() !== row.walletAddress.toLowerCase()) {
        throw new Error(`Connect the wallet that owns this position (${row.walletAddress}) before claiming.`);
      }
      await this.wallet.ensureChain(market.chainId, market.chainName);
      const blocker = await this.collateralReleaseBlockerFor(
        market.collateralTokenAddress as Address,
        row.walletAddress as Address,
      );
      if (blocker) throw new Error(`${blocker} Try again once this is resolved.`);
      const hash = await this.wallet.writeContract({
        address: market.marketAddress as Address,
        abi: repoMarketAbi,
        functionName: 'claimCollateral',
        args: [],
      });
      await this.wallet.waitForTransaction(hash);
      this.snackBar.open(
        `Claimed ${row.collateralAmount} collateral units. Tx: ${hash.slice(0, 10)}…${hash.slice(-6)}`,
        'Dismiss',
        { duration: 6000 },
      );
      this.load();
    } catch (err: unknown) {
      this.claimError = err instanceof Error ? err.message : 'Claiming collateral failed.';
    } finally {
      this.claimingKey = null;
      this.cdr.markForCheck();
    }
  }

  openCollateral(
    row: LoanRow,
    template: TemplateRef<{ $implicit: LoanRow }>,
    action: 'add' | 'withdraw',
  ): void {
    this.collateralAction = action;
    this.collateralAmount = action === 'withdraw' ? 1 : 0;
    this.collateralError = null;
    this.dialog.open(template, { width: '460px', data: { row } });
  }

  isValidCollateralAmount(): boolean {
    return Number.isSafeInteger(this.collateralAmount) && this.collateralAmount > 0;
  }

  async confirmCollateralChange(row: LoanRow): Promise<void> {
    const market = this.marketsById.get(row.marketId);
    if (!market || !this.isValidCollateralAmount() || this.managingCollateral) return;
    if (this.collateralAction === 'withdraw' && BigInt(this.collateralAmount) > BigInt(row.collateralAmount)) {
      this.collateralError = 'The amount exceeds the collateral currently pledged.';
      return;
    }
    this.managingCollateral = true;
    this.collateralError = null;
    this.cdr.markForCheck();

    try {
      if (!this.wallet.isConnected()) await this.wallet.connect();
      const walletAddress = this.wallet.address();
      if (walletAddress?.toLowerCase() !== row.walletAddress.toLowerCase()) {
        throw new Error(`Connect the wallet that owns this loan (${row.walletAddress}).`);
      }
      await this.wallet.ensureChain(market.chainId, market.chainName);
      const marketAddress = market.marketAddress as Address;
      const amount = BigInt(this.collateralAmount);
      if (this.collateralAction === 'add') {
        const collateralToken = market.collateralTokenAddress as Address;
        await this.wallet.ensureAllowance(collateralToken, marketAddress, amount);
      }
      const hash = await this.wallet.writeContract({
        address: marketAddress,
        abi: repoMarketAbi,
        functionName: this.collateralAction === 'add' ? 'addCollateral' : 'withdrawCollateral',
        args: [amount],
      });
      await this.wallet.waitForTransaction(hash);
      this.dialog.closeAll();
      this.snackBar.open(
        `${this.collateralAction === 'add' ? 'Added' : 'Withdrew'} ${this.collateralAmount} collateral units. Tx: ${hash.slice(0, 10)}…${hash.slice(-6)}`,
        'Dismiss',
        { duration: 6000 },
      );
      this.load();
    } catch (err: unknown) {
      this.collateralError = err instanceof Error ? err.message : 'Collateral update failed.';
    } finally {
      this.managingCollateral = false;
      this.cdr.markForCheck();
    }
  }

  async confirmRepay(row: LoanRow): Promise<void> {
    const market = this.marketsById.get(row.marketId);
    if (!market) {
      this.repayError = this.marketsLoadFailed ? 'Market details are unavailable. Reload the page and try again.' : 'Market not found.';
      return;
    }
    const outstanding = this.outstandingUnits(row);
    const decimals = this.loanTokenDecimals(row);
    const amountUnits = this.repayFull ? null : parseTokenAmount(this.repayAmountText, decimals);
    if (!this.repayFull && (amountUnits === null || amountUnits <= 0n || amountUnits > outstanding)) {
      this.repayError = `Enter an amount between 0 and ${formatTokenAmountGrouped(outstanding, decimals)} (at most ${decimals} decimals).`;
      return;
    }
    this.repaying = true;
    this.repayError = null;
    this.cdr.markForCheck();

    try {
      if (!this.wallet.isConnected()) {
        await this.wallet.connect();
      }
      if (this.wallet.address()?.toLowerCase() !== row.walletAddress.toLowerCase()) {
        throw new Error(`Connect the wallet that owns this loan (${row.walletAddress}) before repaying.`);
      }
      await this.wallet.ensureChain(market.chainId, market.chainName);
      // The wallet may only have been connected just now, so the dialog could not check yet.
      // Stop and let the user confirm the debt-only option instead of sending a doomed repay.
      if (!this.collateralReleaseBlocker && await this.detectCollateralReleaseBlocker(row)) {
        return;
      }
      const debtOnly = this.collateralReleaseBlocker !== null && this.repayKeepCollateral;
      const result = await executeRepay(this.wallet, {
        market,
        borrower: row.walletAddress as Address,
        snapshotDebt: outstanding,
        full: this.repayFull,
        amountUnits,
        debtOnly,
      });
      const hash = result.hash;
      this.dialog.closeAll();
      const tx = `Tx: ${hash.slice(0, 10)}…${hash.slice(-6)}`;
      if (this.repayFull && result.residualDebt !== null && result.residualDebt > 0n) {
        this.snackBar.open(
          `Repayment sent, but residual debt of ${formatTokenAmountGrouped(result.residualDebt, decimals)} remains and the collateral is still pledged. Repay again to close the loan. ${tx}`,
          'Dismiss',
          { duration: 12000 },
        );
      } else {
        const paid = this.repayFull ? 'in full' : this.repayAmountText;
        this.snackBar.open(`Repaid ${paid}. ${tx}`, 'Dismiss', { duration: 6000 });
      }
      this.load();
    } catch (err: unknown) {
      this.repayError = err instanceof Error ? err.message : 'Repay failed.';
    } finally {
      this.repaying = false;
      this.cdr.markForCheck();
    }
  }
}
