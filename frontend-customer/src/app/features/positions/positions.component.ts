import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PageHeaderComponent, DataTableComponent, TableColumn, AsyncSectionStatus } from '@registerwerk/ui';
import { InvestmentService } from '../../core/api/investment.service';
import { TradingService } from '../../core/api/trading.service';
import { LendingService } from '../../core/api/lending.service';
import { StatementService } from '../../core/api/statement.service';
import { TaxService } from '../../core/api/tax.service';
import { RegisterDocumentService } from '../../core/api/register-document.service';
import { downloadBlob } from '../../core/utils/download.util';
import { FormsModule } from '@angular/forms';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { Chain, LendingMarket, RegisterDocumentMeta, SellableHolding } from '../../core/models';
import { ChainLendingCapability, lendingCapabilityFor } from '../../core/lending/chain-capabilities';
import { PlatformCapabilitiesService } from '../../core/feature/platform-capabilities';

interface PositionRow {
  holderId: string;
  assetId: string;
  assetName: string;
  isin: string | null;
  tokenStandard: string | null;
  nominalAmount: number;
  currency: string | null;
  whitelisted: boolean;
  chain: Chain | null;
  sellable: boolean;
  market: LendingMarket | null;
  marketValue: number | null;
  pricedVia: string | null;
  capability: ChainLendingCapability;
  registerDoc: RegisterDocumentMeta | null;
}

/**
 * Unifies the three previously-disconnected holding representations — trading
 * `sellable-holdings`, `InvestmentRecord`, and lending collateral — into a single "My
 * Positions" spine with per-row contextual actions (Sell / Pledge & borrow / View statement)
 * and a consolidated portfolio value, replacing three separate mental models of "what do I
 * own" with one.
 */
@Component({
  selector: 'app-positions',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatTooltipModule,
    MatSelectModule,
    FormsModule,
    MatSnackBarModule,
    PageHeaderComponent,
    DataTableComponent,
  ],
  template: `
    <div class="page-container">
      <app-page-header title="My Positions" subtitle="Every holding across trading, investment, and collateral in one place.">
        <button type="button" mat-stroked-button [disabled]="downloadingStatement" (click)="downloadStatement()">
          <mat-icon>picture_as_pdf</mat-icon>
          @if (downloadingStatement) { Preparing… } @else { Download Depotauszug }
        </button>
        <mat-select class="tax-year-select" [(ngModel)]="taxCertificateYear" [disabled]="downloadingTaxCertificate">
          @for (y of taxCertificateYears; track y) {
            <mat-option [value]="y">{{ y }}</mat-option>
          }
        </mat-select>
        <button type="button" mat-stroked-button [disabled]="downloadingTaxCertificate" (click)="downloadTaxCertificate()">
          <mat-icon>receipt_long</mat-icon>
          @if (downloadingTaxCertificate) { Preparing… } @else { Download Tax Certificate }
        </button>
      </app-page-header>

      @if (state === 'ready') {
      <div class="summary-row">
        <mat-card class="summary-card">
          <mat-card-content>
            <div class="summary-value">
              {{ portfolioValue | number:'1.0-2' }}
              @if (portfolioCurrency) { <span class="summary-currency">{{ portfolioCurrency }}</span> }
            </div>
            <div class="summary-label">
              Portfolio value
              <mat-icon
                class="info-icon"
                [matTooltip]="portfolioCurrency
                  ? 'Sum of nominal amount for every holding; priced live via its repo/lending market where one exists, nominal otherwise.'
                  : 'Sum of nominal amount for every holding — shown without a currency because holdings span more than one currency (or none is set on the underlying asset yet), so this total cannot be added meaningfully. Priced live via repo/lending market where one exists, nominal otherwise.'">
                info_outline
              </mat-icon>
            </div>
          </mat-card-content>
        </mat-card>
        <mat-card class="summary-card">
          <mat-card-content>
            <div class="summary-value">{{ rows.length }}</div>
            <div class="summary-label">Holdings</div>
          </mat-card-content>
        </mat-card>
        <mat-card class="summary-card">
          <mat-card-content>
            <div class="summary-value">{{ pledgeableCount }}</div>
            <div class="summary-label">Pledgeable for liquidity</div>
          </mat-card-content>
        </mat-card>
      </div>
      @if (partialDataWarning) {
        <p class="partial-warning" role="status">
          <mat-icon>info_outline</mat-icon>
          Some trading, lending, or register-document details are temporarily unavailable.
        </p>
      }
      }

      <rw-data-table
        [columns]="columns"
        [rows]="rows"
        [state]="state"
        filterPlaceholder="Filter positions…"
        emptyMessage="No holdings yet."
        [actionsTemplate]="actions"
        (retry)="load()">
      </rw-data-table>

      <ng-template #actions let-row>
        <a mat-stroked-button [routerLink]="['/investments', row.holderId]">
          <mat-icon>description</mat-icon>
          Statement
        </a>
        @if (row.registerDoc) {
          <button
            mat-stroked-button
            type="button"
            [disabled]="downloadingRegisterDocFor.has(row.assetId)"
            [matTooltip]="row.registerDoc.statutory
              ? row.registerDoc.title + ' (§19 eWpG statutory register statement)'
              : row.registerDoc.title + ' — a holding confirmation, not a statutory register statement'"
            (click)="downloadRegisterDocument(row)">
            <mat-icon>gavel</mat-icon>
            @if (downloadingRegisterDocFor.has(row.assetId)) { Preparing… } @else { {{ row.registerDoc.title }} }
          </button>
        }
        @if (row.sellable) {
          <a mat-stroked-button routerLink="/trading">
            <mat-icon>sell</mat-icon>
            Sell
          </a>
        }
        @if (lendingEnabled && row.market) {
          <a mat-flat-button color="primary" [routerLink]="['/lending', 'borrow', row.holderId]">
            <mat-icon>water_drop</mat-icon>
            Pledge &amp; borrow
          </a>
        } @else if (!row.capability.lendingSupported) {
          <span class="capability-note" [matTooltip]="row.capability.note">
            <mat-icon>info_outline</mat-icon>
            No lending on {{ row.chain ?? 'this chain' }}
          </span>
        }
      </ng-template>
    </div>
  `,
  styles: [`
    .tax-year-select {
      width: 90px;
      margin: 0 4px;
    }
    .summary-row {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 16px;
      margin-bottom: 20px;
    }
    .summary-card { border-radius: 12px; }
    .summary-value { font-size: 26px; font-weight: 700; color: var(--rw-text-primary); line-height: 1.2; }
    .summary-currency { font-size: 14px; font-weight: 600; color: var(--rw-text-secondary); margin-left: 4px; }
    .summary-label {
      display: flex;
      align-items: center;
      gap: 4px;
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: 0.4px;
      color: var(--rw-text-secondary);
      margin-top: 4px;
    }
    .info-icon { font-size: 14px; width: 14px; height: 14px; opacity: 0.6; cursor: help; }
    .capability-note {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 11.5px;
      color: var(--rw-text-secondary);
      cursor: help;

      mat-icon { font-size: 14px; width: 14px; height: 14px; }
    }
    .partial-warning { display: flex; align-items: center; gap: 7px; color: var(--rw-text-warning); font-size: 12px; }
    .partial-warning mat-icon { font-size: 16px; height: 16px; width: 16px; }
  `],
})
export class PositionsComponent implements OnInit {
  private readonly capabilities = inject(PlatformCapabilitiesService);
  private readonly investmentService = inject(InvestmentService);
  private readonly tradingService = inject(TradingService);
  private readonly lendingService = inject(LendingService);
  private readonly statementService = inject(StatementService);
  private readonly taxService = inject(TaxService);
  private readonly registerDocumentService = inject(RegisterDocumentService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly snackBar = inject(MatSnackBar);

  get lendingEnabled(): boolean {
    return this.capabilities.lendingEnabled();
  }

  @ViewChild('actions', { static: true }) actions!: TemplateRef<{ $implicit: PositionRow }>;

  state: AsyncSectionStatus = 'pending';
  rows: PositionRow[] = [];
  portfolioValue = 0;
  /** Null when holdings span more than one currency (or none is set) — see the tooltip. */
  portfolioCurrency: string | null = null;
  pledgeableCount = 0;
  partialDataWarning = false;
  downloadingStatement = false;
  downloadingRegisterDocFor = new Set<string>();
  downloadingTaxCertificate = false;
  readonly taxCertificateYears = Array.from(
    { length: new Date().getFullYear() - 2019 },
    (_, i) => new Date().getFullYear() - i,
  );
  taxCertificateYear = this.taxCertificateYears[0];

  readonly columns: TableColumn[] = [
    { key: 'assetName', header: 'Asset', cell: (r: PositionRow) => r.assetName },
    { key: 'isin', header: 'ISIN', cell: (r: PositionRow) => r.isin, type: 'mono' },
    { key: 'tokenStandard', header: 'Standard', cell: (r: PositionRow) => r.tokenStandard },
    {
      key: 'nominalAmount',
      header: 'Nominal',
      cell: (r: PositionRow) => r.currency ? `${r.nominalAmount} ${r.currency}` : String(r.nominalAmount),
      type: 'number',
    },
    {
      key: 'marketValue',
      header: 'Market value',
      cell: (r: PositionRow) => {
        const ccy = r.currency ? ` ${r.currency}` : '';
        return r.marketValue !== null
          ? `${r.marketValue.toLocaleString()}${ccy} (${r.pricedVia})`
          : `${r.nominalAmount}${ccy} (nominal only)`;
      },
    },
    {
      key: 'whitelisted',
      header: 'Status',
      cell: (r: PositionRow) => (r.whitelisted ? 'WHITELISTED' : 'NOT_WHITELISTED'),
      type: 'badge',
    },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state = 'pending';
    this.rows = [];
    this.partialDataWarning = false;
    forkJoin({
      investments: this.investmentService.getMyInvestments({ page: 0, size: 200 }).pipe(
        map((page) => page.content),
      ),
      sellable: this.tradingService.listSellableHoldings().pipe(
        catchError(() => { this.partialDataWarning = true; return of<SellableHolding[]>([]); }),
      ),
      markets: this.lendingEnabled
        ? this.lendingService.listMarkets('ACTIVE').pipe(
            catchError(() => { this.partialDataWarning = true; return of<LendingMarket[]>([]); })
          )
        : of<LendingMarket[]>([]),
      registerDocs: this.registerDocumentService.listAvailable().pipe(
        catchError(() => { this.partialDataWarning = true; return of<RegisterDocumentMeta[]>([]); }),
      ),
    }).subscribe({
      next: ({ investments, sellable, markets, registerDocs }) => {
        const sellableHolderIds = new Set(sellable.map((s) => s.holderId));
        const marketByAssetId = new Map(markets.filter((m) => m.collateralAssetId).map((m) => [m.collateralAssetId as string, m]));
        const registerDocByAssetId = new Map(registerDocs.map((d) => [d.assetId, d]));

        this.rows = investments.map((inv) => {
          const market = marketByAssetId.get(inv.assetId) ?? null;
          return {
            holderId: inv.id,
            assetId: inv.assetId,
            assetName: inv.assetName ?? inv.assetNumber ?? inv.assetId,
            isin: inv.isin,
            tokenStandard: inv.tokenStandard,
            nominalAmount: inv.nominalAmount,
            currency: inv.currency,
            whitelisted: inv.whitelisted,
            chain: inv.chain,
            sellable: sellableHolderIds.has(inv.id),
            market,
            marketValue: null,
            pricedVia: market?.collateralAssetName ?? null,
            capability: lendingCapabilityFor(inv.chain),
            registerDoc: registerDocByAssetId.get(inv.assetId) ?? null,
          } satisfies PositionRow;
        });

        this.pledgeableCount = this.rows.filter((r) => r.market).length;
        this.portfolioValue = this.rows.reduce((sum, r) => sum + (r.marketValue ?? r.nominalAmount), 0);
        this.portfolioCurrency = this.uniformCurrency();
        this.state = 'ready';
        this.cdr.markForCheck();
        this.priceMatchedRows();
      },
      error: () => {
        this.rows = [];
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  /** The shared currency across every row, or null if holdings span more than one (or none is set). */
  private uniformCurrency(): string | null {
    if (this.rows.length === 0) return null;
    const first = this.rows[0].currency;
    return first !== null && this.rows.every((r) => r.currency === first) ? first : null;
  }

  /** Best-effort live price for rows with a matching active lending market — see the header tooltip. */
  private priceMatchedRows(): void {
    const priced = this.rows.filter((r) => r.market);
    if (priced.length === 0) return;

    priced.forEach((row) => {
      this.lendingService.quote(row.market!.id, String(row.nominalAmount)).subscribe({
        next: (quote) => {
          const pricePerUnit = Number(quote.pricePerUnit) / 1e6; // stablecoin legs are 6-decimal in the demo catalog
          row.marketValue = row.nominalAmount * pricePerUnit;
          row.pricedVia = row.market!.collateralAssetName ?? 'lending market';
          this.rows = [...this.rows];
          this.portfolioValue = this.rows.reduce((sum, r) => sum + (r.marketValue ?? r.nominalAmount), 0);
          this.cdr.markForCheck();
        },
        error: () => {
          // Unpriced collateral (PriceNotSet) — leave nominal-only, already the default.
        },
      });
    });
  }

  downloadRegisterDocument(row: PositionRow): void {
    this.downloadingRegisterDocFor.add(row.assetId);
    this.registerDocumentService.download(row.assetId).subscribe({
      next: (pdf) => {
        downloadBlob(pdf, `registerauszug-${row.assetId}.pdf`);
        this.downloadingRegisterDocFor.delete(row.assetId);
        this.cdr.markForCheck();
      },
      error: () => {
        this.downloadingRegisterDocFor.delete(row.assetId);
        this.cdr.markForCheck();
        this.snackBar.open('Register document could not be downloaded.', 'Dismiss', { duration: 5000 });
      },
    });
  }

  downloadStatement(): void {
    this.downloadingStatement = true;
    this.statementService.downloadMyStatement().subscribe({
      next: (pdf) => {
        downloadBlob(pdf, `depotauszug-${new Date().toISOString().slice(0, 10)}.pdf`);
        this.downloadingStatement = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.downloadingStatement = false;
        this.cdr.markForCheck();
        this.snackBar.open('The portfolio statement could not be downloaded.', 'Dismiss', { duration: 5000 });
      },
    });
  }

  downloadTaxCertificate(): void {
    this.downloadingTaxCertificate = true;
    this.taxService.downloadMyTaxCertificate(this.taxCertificateYear).subscribe({
      next: (pdf) => {
        downloadBlob(pdf, `steuerbescheinigung-${this.taxCertificateYear}.pdf`);
        this.downloadingTaxCertificate = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.downloadingTaxCertificate = false;
        this.cdr.markForCheck();
        this.snackBar.open('The tax certificate could not be downloaded.', 'Dismiss', { duration: 5000 });
      },
    });
  }
}
