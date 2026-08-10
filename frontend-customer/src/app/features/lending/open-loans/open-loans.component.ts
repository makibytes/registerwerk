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
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { PageHeaderComponent, DataTableComponent, TableColumn, AsyncSectionStatus } from '@registerwerk/ui';
import { LendingService } from '../../../core/api/lending.service';
import { WalletService } from '../../../core/wallet/wallet.service';
import { erc20Abi, repoMarketAbi } from '../../../core/wallet/abi/repo-market.abi';
import { LendingMarket, LendingPosition } from '../../../core/models';
import { formatUnits as formatTokenUnits, parseUnits, type Address } from 'viem';

interface LoanRow extends LendingPosition {
  marketLabel: string;
  healthFactorDisplay: string;
  healthFactorSeverity: 'ok' | 'warn' | 'danger' | 'none';
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
        }
      </ng-template>

      <ng-template #repayDialog let-data>
        <h2 mat-dialog-title>Repay loan</h2>
        <mat-dialog-content>
          <p>Outstanding debt: {{ formatDebt(data.row) }}</p>
          <mat-form-field appearance="outline" class="full-width">
            <mat-label>Amount to repay</mat-label>
            <input matInput type="number" [min]="minimumAmount(data.row)" [step]="minimumAmount(data.row)"
                   [max]="outstandingAmount(data.row)" [(ngModel)]="repayAmount" />
          </mat-form-field>
          @if (repayError) {
            <p class="error-text">{{ repayError }}</p>
          }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
          <button mat-button type="button" [mat-dialog-close]="null" [disabled]="repaying">Cancel</button>
          <button mat-flat-button color="primary" type="button" [disabled]="repaying || repayAmount <= 0" (click)="confirmRepay(data.row)">
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
    .error-text { color: #dc2626; font-size: 12.5px; }
    .warning-text { display: flex; align-items: center; gap: 7px; color: var(--rw-text-warning); font-size: 12px; }
    .warning-text mat-icon { font-size: 16px; height: 16px; width: 16px; }
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
  repayAmount = 0;
  repaying = false;
  repayError: string | null = null;
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
      healthFactorDisplay: position.healthFactorReliable === false
        ? 'Unavailable (stale price)'
        : hfRaw !== null ? hfRaw.toFixed(2) : '—',
      healthFactorSeverity: severity,
    };
  }

  private loanTokenDecimals(row: LoanRow): number {
    return this.marketsById.get(row.marketId)?.loanTokenDecimals ?? 6;
  }

  formatDebt(row: LoanRow): string {
    const decimals = this.loanTokenDecimals(row);
    return Number(formatTokenUnits(BigInt(row.currentDebt), decimals))
      .toLocaleString(undefined, { maximumFractionDigits: decimals });
  }

  outstandingAmount(row: LoanRow): number {
    return Number(formatTokenUnits(BigInt(row.currentDebt), this.loanTokenDecimals(row)));
  }

  minimumAmount(row: LoanRow): number {
    return 10 ** -this.loanTokenDecimals(row);
  }

  openRepay(row: LoanRow, template: TemplateRef<{ $implicit: LoanRow }>): void {
    this.repayAmount = this.outstandingAmount(row);
    this.repayError = null;
    this.dialog.open(template, { width: '420px', data: { row } });
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
      const marketAddress = market.marketAddress as Address;
      const amount = BigInt(this.collateralAmount);
      if (this.collateralAction === 'add') {
        const collateralToken = market.collateralTokenAddress as Address;
        const allowance = await this.wallet.readContract<bigint>({
          address: collateralToken,
          abi: erc20Abi,
          functionName: 'allowance',
          args: [walletAddress, marketAddress],
        });
        if (allowance < amount) {
          const approval = await this.wallet.writeContract({
            address: collateralToken,
            abi: erc20Abi,
            functionName: 'approve',
            args: [marketAddress, amount],
          });
          await this.wallet.waitForTransaction(approval);
        }
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
    const outstanding = this.outstandingAmount(row);
    if (!market) {
      this.repayError = this.marketsLoadFailed ? 'Market details are unavailable. Reload the page and try again.' : 'Market not found.';
      return;
    }
    if (!Number.isFinite(this.repayAmount) || this.repayAmount <= 0 || this.repayAmount > outstanding) {
      this.repayError = `Enter an amount between 0 and ${outstanding.toLocaleString()}.`;
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
      const repayAmountUnits = parseUnits(String(this.repayAmount), this.loanTokenDecimals(row));
      const hash = await this.wallet.writeContract({
        address: market.marketAddress as Address,
        abi: repoMarketAbi,
        functionName: 'repay',
        args: [repayAmountUnits],
      });
      await this.wallet.waitForTransaction(hash);
      this.dialog.closeAll();
      this.snackBar.open(`Repaid ${this.repayAmount}. Tx: ${hash.slice(0, 10)}…${hash.slice(-6)}`, 'Dismiss', { duration: 6000 });
      this.load();
    } catch (err: unknown) {
      this.repayError = err instanceof Error ? err.message : 'Repay failed.';
    } finally {
      this.repaying = false;
      this.cdr.markForCheck();
    }
  }
}
