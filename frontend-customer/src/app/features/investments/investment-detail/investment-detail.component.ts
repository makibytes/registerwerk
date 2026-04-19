import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { forkJoin } from 'rxjs';
import { InvestmentService } from '../../../core/api/investment.service';
import { IssuanceService } from '../../../core/api/issuance.service';
import { Erc3643Service, IdentityRegistryEntry } from '../../../core/api/erc3643.service';
import { AssetDeployment, InvestmentRecord } from '../../../core/models';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { ChainIconComponent } from '../../../shared/components/chain-icon/chain-icon.component';

@Component({
  selector: 'app-investment-detail',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatTableModule,
    MatChipsModule,
    StatusBadgeComponent,
    ChainIconComponent,
  ],
  template: `
    <div class="page-container">
      <a mat-button routerLink="/investments" class="back-link">
        <mat-icon>arrow_back</mat-icon>
        My Investments
      </a>

      @if (loading) {
        <div class="loading-overlay"><mat-spinner diameter="48"></mat-spinner></div>
      } @else if (record) {

        <!-- ── Header ──────────────────────────────────────────────────────── -->
        <mat-card class="header-card">
          <mat-card-content>
            <div class="holding-header">
              <div>
                <h1>{{ record.assetName ?? record.assetId }}</h1>
                @if (record.isin) {
                  <span class="isin">ISIN: {{ record.isin }}</span>
                }
              </div>
              <app-status-badge [status]="record.whitelisted ? 'WHITELISTED' : 'NOT_WHITELISTED'"></app-status-badge>
            </div>

            <mat-divider style="margin: 16px 0"></mat-divider>

            <div class="detail-grid">
              <div class="detail-item">
                <span class="detail-label">Nominal Amount</span>
                <span class="detail-value large">{{ record.nominalAmount | number:'1.0-2' }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">Wallet Address</span>
                <code class="detail-value">{{ record.walletAddress }}</code>
              </div>
              <div class="detail-item">
                <span class="detail-label">Acquisition Date</span>
                <span class="detail-value">
                  {{ record.acquisitionDate ? (record.acquisitionDate | date:'mediumDate') : '—' }}
                </span>
              </div>
              <div class="detail-item">
                <span class="detail-label">Holding Since</span>
                <span class="detail-value">{{ record.createdAt | date:'mediumDate' }}</span>
              </div>
              @if (record.tokenStandard) {
                <div class="detail-item">
                  <span class="detail-label">Token Standard</span>
                  <span class="detail-value"><mat-chip>{{ record.tokenStandard }}</mat-chip></span>
                </div>
              }
              @if (record.assetStatus) {
                <div class="detail-item">
                  <span class="detail-label">Asset Status</span>
                  <app-status-badge [status]="record.assetStatus"></app-status-badge>
                </div>
              }
              @if (record.assetNumber) {
                <div class="detail-item">
                  <span class="detail-label">Asset Number</span>
                  <code class="detail-value">{{ record.assetNumber }}</code>
                </div>
              }
            </div>
          </mat-card-content>
        </mat-card>

        <!-- ── ERC-3643 Identity Status ────────────────────────────────────── -->
        @if (isErc3643) {
          <mat-card class="section-card identity-card">
            <mat-card-header>
              <mat-card-title>
                <mat-icon class="identity-icon">fingerprint</mat-icon>
                Identity Status
              </mat-card-title>
            </mat-card-header>
            <mat-card-content>
              @if (identityEntry) {
                <div class="identity-row">
                  <mat-icon style="color:#388e3c">check_circle</mat-icon>
                  <div>
                    <span class="identity-label">ONCHAINID Registered</span>
                    @if (identityEntry.identityAddress) {
                      <code class="identity-address">{{ identityEntry.identityAddress }}</code>
                    }
                  </div>
                </div>
                <div class="claim-status-grid">
                  <div class="claim-item" [class.claim-ok]="identityEntry.verified" [class.claim-fail]="!identityEntry.verified">
                    <mat-icon>{{ identityEntry.verified ? 'verified' : 'gpp_bad' }}</mat-icon>
                    <div>
                      <span class="claim-label">KYC (Topic 1)</span>
                      <span class="claim-status">{{ identityEntry.verified ? 'Valid' : 'Missing or expired' }}</span>
                    </div>
                  </div>
                </div>
              } @else if (identityLoading) {
                <mat-spinner diameter="32"></mat-spinner>
              } @else {
                <div class="identity-row not-deployed">
                  <mat-icon style="color:#e53935">cancel</mat-icon>
                  <div>
                    <span class="identity-label">ONCHAINID not deployed</span>
                    <span class="identity-sub">Your wallet is not yet registered in the Identity Registry for this token.</span>
                  </div>
                </div>
              }
            </mat-card-content>
          </mat-card>
        }

        <!-- ── Chain Deployments ───────────────────────────────────────────── -->
        @if (deployments.length > 0) {
          <mat-card class="section-card">
            <mat-card-header>
              <mat-card-title>Chain Deployments</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              <table mat-table [dataSource]="deployments" class="mat-elevation-z0">
                <ng-container matColumnDef="chain">
                  <th mat-header-cell *matHeaderCellDef>Chain</th>
                  <td mat-cell *matCellDef="let d"><app-chain-icon [chain]="d.chain"></app-chain-icon></td>
                </ng-container>
                <ng-container matColumnDef="network">
                  <th mat-header-cell *matHeaderCellDef>Network</th>
                  <td mat-cell *matCellDef="let d">{{ d.network }}</td>
                </ng-container>
                <ng-container matColumnDef="contract">
                  <th mat-header-cell *matHeaderCellDef>Contract Address</th>
                  <td mat-cell *matCellDef="let d">
                    @if (d.contractAddress) { <code>{{ d.contractAddress }}</code> }
                    @else { — }
                  </td>
                </ng-container>
                <ng-container matColumnDef="status">
                  <th mat-header-cell *matHeaderCellDef>Status</th>
                  <td mat-cell *matCellDef="let d">
                    <app-status-badge [status]="d.deploymentStatus"></app-status-badge>
                  </td>
                </ng-container>

                <tr mat-header-row *matHeaderRowDef="deploymentColumns"></tr>
                <tr mat-row *matRowDef="let r; columns: deploymentColumns;"></tr>
              </table>
            </mat-card-content>
          </mat-card>
        }

      }
    </div>
  `,
  styles: [`
    .back-link { margin-bottom: 16px; display: inline-flex; }
    .header-card, .section-card { margin-bottom: 16px; }
    .holding-header { display: flex; align-items: flex-start; justify-content: space-between; }
    .holding-header h1 { margin: 0 0 4px; font-size: 22px; }
    .isin { font-size: 13px; color: #546e7a; }
    .detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 20px; }
    .detail-item { display: flex; flex-direction: column; gap: 4px; }
    .detail-label { font-size: 11px; color: #78909c; text-transform: uppercase; letter-spacing: 0.5px; }
    .detail-value { font-size: 15px; color: #37474f; }
    .detail-value.large { font-size: 28px; font-weight: 700; color: #00695c; }
    code { font-family: monospace; font-size: 12px; background: #f5f5f5; padding: 2px 4px; border-radius: 3px; word-break: break-all; }
    .identity-card { border-left: 4px solid #5e35b1; }
    .identity-icon { vertical-align: middle; margin-right: 6px; color: #5e35b1; }
    .identity-row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
    .identity-row.not-deployed mat-icon { margin-top: 2px; }
    .identity-label { display: block; font-weight: 500; color: #37474f; }
    .identity-sub { display: block; font-size: 13px; color: #90a4ae; margin-top: 2px; }
    .identity-address { font-family: monospace; font-size: 12px; background: #f5f5f5; padding: 2px 6px; border-radius: 3px; display: block; margin-top: 4px; word-break: break-all; }
    .claim-status-grid { display: flex; gap: 16px; flex-wrap: wrap; }
    .claim-item { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: 8px; background: #fafafa; border: 1px solid #e0e0e0; }
    .claim-item.claim-ok { border-color: #a5d6a7; background: #f1f8e9; }
    .claim-item.claim-ok mat-icon { color: #388e3c; }
    .claim-item.claim-fail { border-color: #ef9a9a; background: #fce4ec; }
    .claim-item.claim-fail mat-icon { color: #e53935; }
    .claim-label { display: block; font-size: 12px; color: #546e7a; font-weight: 500; }
    .claim-status { display: block; font-size: 14px; color: #37474f; }
  `],
})
export class InvestmentDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly investmentService = inject(InvestmentService);
  private readonly issuanceService = inject(IssuanceService);
  private readonly erc3643Service = inject(Erc3643Service);

  record: InvestmentRecord | null = null;
  deployments: AssetDeployment[] = [];
  loading = true;
  identityLoading = false;
  identityEntry: IdentityRegistryEntry | null = null;

  readonly deploymentColumns = ['chain', 'network', 'contract', 'status'];

  get isErc3643(): boolean {
    return this.record?.tokenStandard === 'ERC3643' || this.record?.tokenStandard === 'CONF_ERC3643';
  }

  ngOnInit(): void {
    const holderId = this.route.snapshot.paramMap.get('holderId')!;
    this.investmentService.getInvestment(holderId).subscribe({
      next: (rec) => {
        this.record = rec;
        this.issuanceService.getDeployments(rec.assetId).subscribe({
          next: (deployments) => {
            this.deployments = deployments;
            this.loading = false;
            if (this.isErc3643 && deployments.length > 0 && rec.walletAddress) {
              this.loadIdentityStatus(rec.assetId, deployments[0].id, rec.walletAddress);
            }
          },
          error: () => (this.loading = false),
        });
      },
      error: () => (this.loading = false),
    });
  }

  private loadIdentityStatus(assetId: string, deploymentId: string, walletAddress: string): void {
    this.identityLoading = true;
    this.erc3643Service.getIdentityRegistry(assetId, deploymentId).subscribe({
      next: (entries) => {
        this.identityEntry = entries.find(
          e => e.walletAddress.toLowerCase() === walletAddress.toLowerCase()
        ) ?? null;
        this.identityLoading = false;
      },
      error: () => (this.identityLoading = false),
    });
  }
}
