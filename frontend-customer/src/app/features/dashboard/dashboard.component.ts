import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { AuthService } from '../../core/auth/auth.service';
import { IssuanceService } from '../../core/api/issuance.service';
import { InvestmentService } from '../../core/api/investment.service';
import { Asset, InvestmentRecord } from '../../core/models';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';

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

        <!-- ── ISSUER section ──────────────────────────────────────────────── -->
        @if (isIssuer) {
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
        @if (isInvestor) {
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
                  <div class="stat-label">Total Nominal (€)</div>
                </mat-card-content>
              </mat-card>
            </div>

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
                      <span class="nominal-amount">€ {{ h.nominalAmount | number:'1.0-0' }}</span>
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

        @if (!isIssuer && !isInvestor) {
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
  `]
})
export class DashboardComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly auth = inject(AuthService);
  private readonly issuanceService = inject(IssuanceService);
  private readonly investmentService = inject(InvestmentService);

  loading = true;
  userName: string | null = null;

  isIssuer = false;
  isInvestor = false;

  // Issuer stats
  draftCount = 0;
  pendingCount = 0;
  issuedCount = 0;
  recentIssuances: Asset[] = [];

  // Investor stats
  totalHoldings = 0;
  whitelistedCount = 0;
  totalNominal = 0;
  recentHoldings: InvestmentRecord[] = [];

  ngOnInit(): void {
    this.userName = this.auth.getUserName();
    this.isIssuer = this.auth.hasRole('ISSUER') || this.auth.hasRole('REGISTRY_ADMIN');
    this.isInvestor = this.auth.hasRole('INVESTOR') || this.auth.hasRole('REGISTRY_ADMIN');

    const issuances$ = this.isIssuer
      ? this.issuanceService.getIssuances({ size: 20 }).pipe(catchError(() => of(null)))
      : of(null);

    const investments$ = this.isInvestor
      ? this.investmentService.getMyInvestments({ size: 20 }).pipe(catchError(() => of(null)))
      : of(null);

    forkJoin({ issuances: issuances$, investments: investments$ }).subscribe(({ issuances, investments }) => {
      if (issuances) {
        const items = issuances.content;
        this.draftCount   = items.filter(a => a.status === 'DRAFT').length;
        this.pendingCount = items.filter(a => a.status === 'PENDING_APPROVAL').length;
        this.issuedCount  = items.filter(a => a.status === 'ISSUED').length;
        this.recentIssuances = items.slice(0, 5);
      }

      if (investments) {
        const holdings = investments.content;
        this.totalHoldings    = investments.totalElements;
        this.whitelistedCount = holdings.filter(h => h.whitelisted).length;
        this.totalNominal     = holdings.reduce((sum, h) => sum + h.nominalAmount, 0);
        this.recentHoldings   = holdings.slice(0, 5);
      }

      this.loading = false;
      this.cdr.detectChanges();
    });
  }
}
