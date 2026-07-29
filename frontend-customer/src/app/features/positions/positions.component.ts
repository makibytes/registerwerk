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
import { RegisterDocumentService } from '../../core/api/register-document.service';
import { Chain, InvestmentRecord, LendingMarket, RegisterDocumentMeta, SellableHolding } from '../../core/models';
import { ChainLendingCapability, lendingCapabilityFor } from '../../core/lending/chain-capabilities';
import { environment } from '../../../environments/environment';

interface PositionRow {
  holderId: string;
  assetId: string;
  assetName: string;
  isin: string | null;
  tokenStandard: string | null;
  nominalAmount: number;
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
    PageHeaderComponent,
    DataTableComponent,
  ],
  template: `
    <div class="page-container">
      <app-page-header title="My Positions" subtitle="Every holding across trading, investment, and collateral in one place.">
        <button mat-stroked-button [disabled]="downloadingStatement" (click)="downloadStatement()">
          <mat-icon>picture_as_pdf</mat-icon>
          @if (downloadingStatement) { Preparing… } @else { Download Depotauszug }
        </button>
      </app-page-header>

      <div class="summary-row">
        <mat-card class="summary-card">
          <mat-card-content>
            <div class="summary-value">{{ portfolioValue | number:'1.0-2' }}</div>
            <div class="summary-label">
              Portfolio value
              <mat-icon
                class="info-icon"
                matTooltip="Sum of nominal amount for every holding; priced live via its repo/lending market where one exists, nominal otherwise.">
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

      <rw-data-table
        [columns]="columns"
        [rows]="rows"
        [state]="state"
        filterPlaceholder="Filter positions…"
        emptyMessage="No holdings yet."
        [actionsTemplate]="actions">
      </rw-data-table>

      <ng-template #actions let-row>
        <a mat-stroked-button [routerLink]="['/investments', row.holderId]">
          <mat-icon>description</mat-icon>
          Statement
        </a>
        @if (row.registerDoc) {
          <button
            mat-stroked-button
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
    .summary-row {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 16px;
      margin-bottom: 20px;
    }
    .summary-card { border-radius: 12px; }
    .summary-value { font-size: 26px; font-weight: 700; color: var(--rw-text-primary); line-height: 1.2; }
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
  `],
})
export class PositionsComponent implements OnInit {
  readonly lendingEnabled = environment.lendingEnabled;
  private readonly investmentService = inject(InvestmentService);
  private readonly tradingService = inject(TradingService);
  private readonly lendingService = inject(LendingService);
  private readonly statementService = inject(StatementService);
  private readonly registerDocumentService = inject(RegisterDocumentService);
  private readonly cdr = inject(ChangeDetectorRef);

  @ViewChild('actions', { static: true }) actions!: TemplateRef<{ $implicit: PositionRow }>;

  state: AsyncSectionStatus = 'pending';
  rows: PositionRow[] = [];
  portfolioValue = 0;
  pledgeableCount = 0;
  downloadingStatement = false;
  downloadingRegisterDocFor = new Set<string>();

  readonly columns: TableColumn[] = [
    { key: 'assetName', header: 'Asset', cell: (r: PositionRow) => r.assetName },
    { key: 'isin', header: 'ISIN', cell: (r: PositionRow) => r.isin, type: 'mono' },
    { key: 'tokenStandard', header: 'Standard', cell: (r: PositionRow) => r.tokenStandard },
    { key: 'nominalAmount', header: 'Nominal', cell: (r: PositionRow) => String(r.nominalAmount), type: 'number' },
    {
      key: 'marketValue',
      header: 'Market value',
      cell: (r: PositionRow) =>
        r.marketValue !== null ? `${r.marketValue.toLocaleString()} (${r.pricedVia})` : `${r.nominalAmount} (nominal only)`,
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

  private load(): void {
    this.state = 'pending';
    forkJoin({
      investments: this.investmentService.getMyInvestments({ page: 0, size: 200 }).pipe(
        map((page) => page.content),
        catchError(() => of<InvestmentRecord[]>([])),
      ),
      sellable: this.tradingService.listSellableHoldings().pipe(
        catchError(() => of<SellableHolding[]>([])),
      ),
      markets: environment.lendingEnabled
        ? this.lendingService.listMarkets('ACTIVE').pipe(catchError(() => of<LendingMarket[]>([])))
        : of<LendingMarket[]>([]),
      registerDocs: this.registerDocumentService.listAvailable().pipe(
        catchError(() => of<RegisterDocumentMeta[]>([])),
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
        this.state = 'ready';
        this.cdr.markForCheck();
        this.priceMatchedRows();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
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
        const url = URL.createObjectURL(pdf);
        const link = document.createElement('a');
        link.href = url;
        link.download = `registerauszug-${row.assetId}.pdf`;
        link.click();
        URL.revokeObjectURL(url);
        this.downloadingRegisterDocFor.delete(row.assetId);
        this.cdr.markForCheck();
      },
      error: () => {
        this.downloadingRegisterDocFor.delete(row.assetId);
        this.cdr.markForCheck();
      },
    });
  }

  downloadStatement(): void {
    this.downloadingStatement = true;
    this.statementService.downloadMyStatement().subscribe({
      next: (pdf) => {
        const url = URL.createObjectURL(pdf);
        const link = document.createElement('a');
        link.href = url;
        link.download = `depotauszug-${new Date().toISOString().slice(0, 10)}.pdf`;
        link.click();
        URL.revokeObjectURL(url);
        this.downloadingStatement = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.downloadingStatement = false;
        this.cdr.markForCheck();
      },
    });
  }
}
