import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { AuthService } from '../../core/auth/auth.service';
import { IssuanceService } from '../../core/api/issuance.service';
import { InvestmentService } from '../../core/api/investment.service';
import { DemoOnchainManifest, DemoOnchainService } from '../../core/api/demo-onchain.service';
import { Asset, InvestmentRecord } from '../../core/models';
import { StatusBadgeComponent, DonutChartComponent, DonutSlice, BarChartComponent, BarItem } from '@registerwerk/ui';



@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatChipsModule,
    StatusBadgeComponent,
    DonutChartComponent,
    BarChartComponent,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>Dashboard</h1>
        <p class="welcome">Welcome back, {{ userName || 'User' }}</p>
      </div>

      @if (loading) {
        <div class="loading-overlay"><mat-spinner diameter="48"></mat-spinner></div>
      } @else {

        @if (demoManifest) {
          <mat-card class="anvil-banner">
            <mat-card-content>
              <div class="anvil-copy">
                <span class="live-dot"></span>
                <div>
                  <strong>{{ demoManifest.network }}</strong>
                  <span>All seven EVM standards are live on-chain. Demo wallets include native gas.</span>
                </div>
              </div>
              <div class="standard-row">
                @for (standard of demoStandards; track standard.key) {
                  <span class="standard-chip" [title]="standard.address">{{ standard.label }}</span>
                }
              </div>
            </mat-card-content>
          </mat-card>
        }

        <!-- Load failures must be visible: silently rendering zeros would
             misrepresent the register state to the user. -->
        @if (loadError) {
          <mat-card class="error-banner">
            <mat-card-content>
              <mat-icon>error_outline</mat-icon>
              <span>Some dashboard data could not be loaded. The numbers below may be incomplete.</span>
              <button type="button" mat-stroked-button (click)="reload()">Retry</button>
            </mat-card-content>
          </mat-card>
        }

        <!-- ── ISSUER section ──────────────────────────────────────────────── -->
        @if (isIssuer && !issuerLoadError) {
          <section class="dashboard-section">
            <div class="section-header">
              <h2>
                <mat-icon>description</mat-icon>
                My Issuances
              </h2>
              <a mat-stroked-button routerLink="/issuances">View All</a>
            </div>

            <div class="card-grid">
              <mat-card class="stat-card">
                <mat-card-content>
                  <div class="stat-value">{{ draftCount }}</div>
                  <div class="stat-label">Draft</div>
                </mat-card-content>
              </mat-card>
              <mat-card class="stat-card">
                <mat-card-content>
                  <div class="stat-value">{{ pendingCount }}</div>
                  <div class="stat-label">Pending Approval</div>
                </mat-card-content>
              </mat-card>
              <mat-card class="stat-card">
                <mat-card-content>
                  <div class="stat-value">{{ issuedCount }}</div>
                  <div class="stat-label">Issued</div>
                </mat-card-content>
              </mat-card>
              <mat-card class="stat-card action-card">
                <mat-card-content>
                  <mat-icon class="action-icon">add_circle</mat-icon>
                  <div class="stat-label">New Issuance</div>
                </mat-card-content>
                <mat-card-actions>
                  <a mat-raised-button color="primary" routerLink="/issuances/new">Start</a>
                </mat-card-actions>
              </mat-card>
            </div>

            <!-- Issuance status chart -->
            @if (issuanceBarItems.length > 0) {
              <mat-card style="margin-bottom:16px">
                <mat-card-header>
                  <mat-card-title style="font-size:14px">Assets by Status</mat-card-title>
                </mat-card-header>
                <mat-card-content>
                  <app-bar-chart [items]="issuanceBarItems"></app-bar-chart>
                </mat-card-content>
              </mat-card>
            }

            <!-- Recent issuances -->
            @if (recentIssuances.length > 0) {
              <mat-card>
                <mat-card-header>
                  <mat-card-title>Recent Activity</mat-card-title>
                </mat-card-header>
                <mat-card-content>
                  @for (asset of recentIssuances; track asset.id) {
                    <div class="activity-row">
                      <div class="activity-info">
                        <span class="asset-name">{{ asset.name }}</span>
                        <span class="asset-number">{{ asset.assetNumber }}</span>
                      </div>
                      <app-status-badge [status]="asset.status"></app-status-badge>
                      <a mat-icon-button [routerLink]="['/issuances', asset.id]">
                        <mat-icon>arrow_forward</mat-icon>
                      </a>
                    </div>
                    <mat-divider></mat-divider>
                  }
                </mat-card-content>
              </mat-card>
            }
          </section>
        }

        <!-- ── INVESTOR section ───────────────────────────────────────────── -->
        @if (isInvestor && !investorLoadError) {
          <section class="dashboard-section">
            <div class="section-header">
              <h2>
                <mat-icon>savings</mat-icon>
                My Portfolio
              </h2>
              <a mat-stroked-button routerLink="/investments">View All</a>
            </div>

            <div class="card-grid">
              <mat-card class="stat-card">
                <mat-card-content>
                  <div class="stat-value">{{ totalHoldings }}</div>
                  <div class="stat-label">Holdings</div>
                </mat-card-content>
              </mat-card>
              <mat-card class="stat-card">
                <mat-card-content>
                  <div class="stat-value">{{ whitelistedCount }}</div>
                  <div class="stat-label">Whitelisted Wallets</div>
                </mat-card-content>
              </mat-card>
              <mat-card class="stat-card">
                <mat-card-content>
                  <div class="stat-value">{{ totalNominal | number:'1.0-0' }}</div>
                  <div class="stat-label">
                    Total Nominal{{ totalNominalCurrency ? ' (' + totalNominalCurrency + ')' : '' }}
                  </div>
                </mat-card-content>
              </mat-card>
            </div>

            @if (!investorLoadError && totalHoldings === 0) {
              <mat-card class="empty-state">
                <mat-card-content>
                  <mat-icon class="empty-icon">storefront</mat-icon>
                  <p>You don't hold any securities yet.</p>
                  <p class="empty-hint">Browse the marketplace to find available offerings.</p>
                </mat-card-content>
                <mat-card-actions class="empty-actions">
                  <a mat-raised-button color="primary" routerLink="/marketplace">Browse Marketplace</a>
                </mat-card-actions>
              </mat-card>
            }

            @if (portfolioSampled) {
              <p class="sample-note">
                Portfolio value and whitelist totals use the first {{ recentHoldingsSourceCount }} of {{ totalHoldings }} holdings.
              </p>
            }

            <!-- Portfolio distribution chart -->
            @if (portfolioDonutSlices.length > 0) {
              <mat-card style="margin-bottom:16px">
                <mat-card-header>
                  <mat-card-title style="font-size:14px">Portfolio Distribution</mat-card-title>
                </mat-card-header>
                <mat-card-content>
                  <app-donut-chart
                    [slices]="portfolioDonutSlices"
                    centerLabel="Holdings"
                    [centerValue]="totalHoldings.toString()">
                  </app-donut-chart>
                </mat-card-content>
              </mat-card>
            }

            <!-- Recent holdings -->
            @if (recentHoldings.length > 0) {
              <mat-card>
                <mat-card-header>
                  <mat-card-title>Recent Holdings</mat-card-title>
                </mat-card-header>
                <mat-card-content>
                  @for (h of recentHoldings; track h.id) {
                    <div class="activity-row">
                      <div class="activity-info">
                        <span class="asset-name">{{ h.assetName ?? h.assetId }}</span>
                        <span class="wallet-addr">{{ h.walletAddress | slice:0:16 }}…</span>
                      </div>
                      <span class="nominal-amount">{{ h.nominalAmount | number:'1.0-0' }} {{ h.currency ?? '' }}</span>
                      <app-status-badge [status]="h.whitelisted ? 'WHITELISTED' : 'NOT_WHITELISTED'"></app-status-badge>
                      <a mat-icon-button [routerLink]="['/investments', h.id]">
                        <mat-icon>arrow_forward</mat-icon>
                      </a>
                    </div>
                    <mat-divider></mat-divider>
                  }
                </mat-card-content>
              </mat-card>
            }
          </section>
        }

        @if (isTrader || isCompanyAdmin || isPublisher) {
          <section class="dashboard-section">
            <div class="section-header">
              <h2><mat-icon>apps</mat-icon>Workspace tools</h2>
            </div>
            <div class="quick-actions">
              @if (isTrader) {
                <a mat-stroked-button routerLink="/trading"><mat-icon>candlestick_chart</mat-icon>Trading Desk</a>
                <a mat-stroked-button routerLink="/positions"><mat-icon>account_balance_wallet</mat-icon>My Positions</a>
              }
              @if (isCompanyAdmin) {
                <a mat-stroked-button routerLink="/company-admin"><mat-icon>manage_accounts</mat-icon>Company Admin</a>
              }
              @if (isPublisher) {
                <a mat-stroked-button routerLink="/publisher"><mat-icon>widgets</mat-icon>My dApps</a>
              }
            </div>
          </section>
        }

        @if (!hasWorkspaceRole) {
          <mat-card class="empty-state">
            <mat-card-content>
              <mat-icon class="empty-icon">info_outline</mat-icon>
              <p>Your account does not have any active roles yet. Please contact your Company Admin.</p>
            </mat-card-content>
          </mat-card>
        }
      }
    </div>
  `,
  styles: [`
    .welcome { color: var(--rw-text-secondary); margin: 0 0 24px; }
    .error-banner { margin-bottom: 24px; border-left: 4px solid var(--rw-rejected-fg); }
    .error-banner mat-card-content { display: flex; align-items: center; gap: 12px; padding-top: 12px; }
    .error-banner mat-icon { color: var(--rw-rejected-fg); }
    .error-banner span { flex: 1; }
    .empty-hint { color: var(--rw-text-secondary); font-size: 13px; margin-top: 4px; }
    .sample-note { color: var(--rw-text-muted); font-size: 12px; margin: 10px 0 0; }
    .quick-actions { display: flex; flex-wrap: wrap; gap: 10px; }
    .empty-actions { display: flex; justify-content: center; padding-bottom: 16px; }
    .dashboard-section { margin-bottom: 40px; }
    .section-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 16px;
    }
    .section-header h2 {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      font-size: 16px;
      font-weight: 700;
      letter-spacing: -0.2px;
      color: var(--rw-text-primary);
    }
    .activity-row {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 0;
    }
    .activity-info {
      flex: 1;
      display: flex;
      flex-direction: column;
    }
    .asset-name { font-weight: 500; font-size: 14px; color: var(--rw-text-primary); }
    .asset-number, .wallet-addr { font-size: 12px; color: var(--rw-text-muted); }
    .nominal-amount { font-weight: 600; color: var(--rw-text-primary); }
    .action-card {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
    }
    .action-icon { font-size: 40px; width: 40px; height: 40px; color: var(--rw-accent); }
    .empty-state mat-card-content {
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 48px;
      text-align: center;
    }
    .empty-icon { font-size: 48px; width: 48px; height: 48px; color: var(--rw-text-muted); margin-bottom: 16px; }
    .anvil-banner { margin-bottom: 24px; border-left: 4px solid #10b981; }
    .anvil-banner mat-card-content { padding: 18px 20px; }
    .anvil-copy { display: flex; align-items: center; gap: 12px; }
    .anvil-copy div { display: flex; flex-direction: column; gap: 2px; }
    .anvil-copy span { color: var(--rw-text-muted); font-size: 13px; }
    .live-dot { width: 10px; height: 10px; flex: 0 0 10px; border-radius: 50%; background: #10b981; box-shadow: 0 0 0 5px rgba(16,185,129,.13); }
    .standard-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
    .standard-chip { padding: 4px 9px; border-radius: 999px; background: rgba(13,148,136,.1); color: #0f766e !important; font-weight: 600; font-size: 12px !important; }
    @media (max-width: 640px) {
      .activity-row { align-items: flex-start; flex-wrap: wrap; }
      .activity-info { min-width: 180px; }
      .error-banner mat-card-content { align-items: flex-start; flex-wrap: wrap; }
    }
  `]
})
export class DashboardComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auth = inject(AuthService);
  private readonly issuanceService = inject(IssuanceService);
  private readonly investmentService = inject(InvestmentService);
  private readonly demoOnchainService = inject(DemoOnchainService);

  loading = true;
  userName: string | null = null;
  demoManifest: DemoOnchainManifest | null = null;

  get demoStandards(): { key: string; label: string; address: string }[] {
    const products = [
      ['DEMO_ERC20_TOKEN', 'ERC-20'], ['DEMO_ERC721_TOKEN', 'ERC-721'],
      ['DEMO_ERC1155_TOKEN', 'ERC-1155'], ['DEMO_ERC3525_TOKEN', 'ERC-3525'],
      ['DEMO_ERC3643_TOKEN', 'ERC-3643'], ['DEMO_ERC4626_VAULT', 'ERC-4626'],
      ['DEMO_ERC7540_VAULT', 'ERC-7540'],
    ];
    return products
      .filter(([key]) => !!this.demoManifest?.contracts[key])
      .map(([key, label]) => ({ key, label, address: this.demoManifest!.contracts[key] }));
  }

  isIssuer = false;
  isInvestor = false;
  isTrader = false;
  isCompanyAdmin = false;
  isPublisher = false;
  loadError = false;
  issuerLoadError = false;
  investorLoadError = false;

  // Issuer stats
  draftCount = 0;
  pendingCount = 0;
  issuedCount = 0;
  recentIssuances: Asset[] = [];

  // Investor stats
  totalHoldings = 0;
  whitelistedCount = 0;
  totalNominal = 0;
  /** Null when holdings span more than one currency (or none is set) — the total is unitless then. */
  totalNominalCurrency: string | null = null;
  recentHoldings: InvestmentRecord[] = [];
  recentHoldingsSourceCount = 0;
  portfolioSampled = false;

  get hasWorkspaceRole(): boolean {
    return this.isIssuer || this.isInvestor || this.isTrader || this.isCompanyAdmin || this.isPublisher;
  }

  // Charts
  issuanceBarItems: BarItem[] = [];
  portfolioDonutSlices: DonutSlice[] = [];

  ngOnInit(): void {
    this.userName = this.auth.getUserName();
    this.isIssuer = this.auth.hasRole('ISSUER') || this.auth.hasRole('REGISTRY_ADMIN');
    this.isInvestor = this.auth.hasRole('INVESTOR') || this.auth.hasRole('REGISTRY_ADMIN');
    this.isTrader = this.auth.hasRole('TRADER') || this.auth.hasRole('REGISTRY_ADMIN');
    this.isCompanyAdmin = this.auth.hasRole('COMPANY_ADMIN') || this.auth.hasRole('REGISTRY_ADMIN');
    this.isPublisher = this.auth.hasRole('DAPP_PUBLISHER') || this.auth.hasRole('REGISTRY_ADMIN');
    this.demoOnchainService.getManifest().pipe(catchError(() => of(null)))
      .subscribe(manifest => { this.demoManifest = manifest; this.cdr.markForCheck(); });
    this.loadData();
  }

  reload(): void {
    this.loading = true;
    this.cdr.markForCheck();
    this.loadData();
  }

  private loadData(): void {
    this.loadError = false;
    this.issuerLoadError = false;
    this.investorLoadError = false;

    const issuances$ = this.isIssuer
      ? forkJoin({
          recent: this.issuanceService.getIssuances({ size: 5, sort: 'createdAt,desc' }),
          draft: this.issuanceService.getIssuances({ size: 1, status: 'DRAFT' }),
          pending: this.issuanceService.getIssuances({ size: 1, status: 'PENDING_APPROVAL' }),
          issued: this.issuanceService.getIssuances({ size: 1, status: 'ISSUED' }),
        }).pipe(
          map(result => ({
            recent: result.recent.content,
            draftCount: result.draft.totalElements,
            pendingCount: result.pending.totalElements,
            issuedCount: result.issued.totalElements,
          })),
          catchError(() => {
            this.loadError = true;
            this.issuerLoadError = true;
            return of(null);
          })
        )
      : of(null);

    const investments$ = this.isInvestor
      ? this.investmentService.getMyInvestments({ size: 200, sort: 'acquisitionDate,desc' }).pipe(
          catchError(() => {
            this.loadError = true;
            this.investorLoadError = true;
            return of(null);
          })
        )
      : of(null);

    forkJoin({ issuances: issuances$, investments: investments$ }).subscribe(({ issuances, investments }) => {
      if (issuances) {
        this.draftCount = issuances.draftCount;
        this.pendingCount = issuances.pendingCount;
        this.issuedCount = issuances.issuedCount;
        this.recentIssuances = issuances.recent;

        // Build issuance bar chart
        this.issuanceBarItems = [
          { label: 'Issued',           value: this.issuedCount,  color: '#10b981' },
          { label: 'Pending Approval', value: this.pendingCount, color: '#f59e0b' },
          { label: 'Draft',            value: this.draftCount,   color: '#6b7280' },
        ].filter(i => i.value > 0);
      }

      if (investments) {
        const holdings = investments.content;
        this.totalHoldings    = investments.totalElements;
        this.recentHoldingsSourceCount = holdings.length;
        this.portfolioSampled = investments.totalElements > holdings.length;
        this.whitelistedCount = holdings.filter(h => h.whitelisted).length;
        this.totalNominal     = holdings.reduce((sum, h) => sum + h.nominalAmount, 0);
        this.totalNominalCurrency = holdings.length > 0 && holdings.every(h => h.currency === holdings[0].currency)
          ? holdings[0].currency
          : null;
        this.recentHoldings   = holdings.slice(0, 5);

        // Build portfolio donut chart (by token standard)
        const byStandard = holdings.reduce((acc, h) => {
          const key = h.tokenStandard ?? 'Unknown';
          acc[key] = (acc[key] ?? 0) + 1;
          return acc;
        }, {} as Record<string, number>);
        const colors = ['#0d9488','#f59e0b','#6366f1','#10b981','#ef4444','#8b5cf6'];
        this.portfolioDonutSlices = Object.entries(byStandard).map(([label, value], i) => ({
          label, value, color: colors[i % colors.length],
        }));
      }

      this.loading = false;
      this.cdr.markForCheck();
    });
  }
}
