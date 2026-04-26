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
import { InvestmentService } from '../../../core/api/investment.service';
import { IssuanceService } from '../../../core/api/issuance.service';
import { Erc3643Service, IdentityRegistryEntry } from '../../../core/api/erc3643.service';
import { AssetDeployment, InvestmentRecord } from '../../../core/models';
import {
  AsyncSection,
  beginAsyncSection,
  createAsyncSection,
  failAsyncSection,
  resolveAsyncSection,
} from '../../../core/async/async-section';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { ChainIconComponent } from '../../../shared/components/chain-icon/chain-icon.component';
import { DataStatePillComponent } from '../../../shared/components/data-state-pill/data-state-pill.component';

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
    DataStatePillComponent,
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

        @if (isErc3643) {
          <mat-card class="section-card identity-card">
            <mat-card-header>
              <mat-card-title>
                <mat-icon class="identity-icon">fingerprint</mat-icon>
                Identity Status
              </mat-card-title>
              <app-data-state-pill [status]="identitySection.status" />
            </mat-card-header>
            <mat-card-content>
              @if (identitySection.data) {
                <div class="identity-row">
                  <mat-icon [style.color]="identitySection.data.verified ? '#388e3c' : '#f59e0b'">
                    {{ identitySection.data.syncStatus === 'PENDING' ? 'hourglass_top' : 'check_circle' }}
                  </mat-icon>
                  <div>
                    <span class="identity-label">
                      {{ identitySection.data.syncStatus === 'PENDING' ? 'Identity registration pending' : 'ONCHAINID registered' }}
                    </span>
                    @if (identitySection.data.identityAddress) {
                      <code class="identity-address">{{ identitySection.data.identityAddress }}</code>
                    }
                  </div>
                </div>
                <div class="claim-status-grid">
                  <div class="claim-item" [class.claim-ok]="identitySection.data.verified" [class.claim-fail]="!identitySection.data.verified">
                    <mat-icon>{{ identitySection.data.verified ? 'verified' : 'gpp_bad' }}</mat-icon>
                    <div>
                      <span class="claim-label">KYC (Topic 1)</span>
                      <span class="claim-status">
                        @if (identitySection.data.syncStatus === 'PENDING') { Waiting for chain confirmation }
                        @else if (identitySection.data.verified) { Valid }
                        @else { Missing or expired }
                      </span>
                    </div>
                  </div>
                </div>
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

        <mat-card class="section-card">
          <mat-card-header>
            <mat-card-title>Chain Deployments</mat-card-title>
            <app-data-state-pill [status]="deploymentsSection.status" />
          </mat-card-header>
          <mat-card-content>
            @if (deploymentsSection.data.length > 0) {
              <table mat-table [dataSource]="deploymentsSection.data" class="mat-elevation-z0">
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
            } @else {
              <p class="empty-text">
                @if (deploymentsSection.status === 'pending') { Deployment data is still loading. }
                @else { No deployments recorded yet. }
              </p>
            }
          </mat-card-content>
        </mat-card>
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
    .empty-text { color: var(--rw-text-muted); }
  `],
})
export class InvestmentDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly investmentService = inject(InvestmentService);
  private readonly issuanceService = inject(IssuanceService);
  private readonly erc3643Service = inject(Erc3643Service);

  record: InvestmentRecord | null = null;
  loading = true;
  deploymentsSection: AsyncSection<AssetDeployment[]> = createAsyncSection<AssetDeployment[]>([]);
  identitySection: AsyncSection<IdentityRegistryEntry | null> = createAsyncSection<IdentityRegistryEntry | null>(null);

  readonly deploymentColumns = ['chain', 'network', 'contract', 'status'];

  get isErc3643(): boolean {
    return this.record?.tokenStandard === 'ERC3643' || this.record?.tokenStandard === 'CONF_ERC3643';
  }

  ngOnInit(): void {
    const holderId = this.route.snapshot.paramMap.get('holderId')!;
    this.investmentService.getInvestment(holderId).subscribe({
      next: (record) => {
        this.record = record;
        this.loading = false;
        this.loadDeployments(record.assetId, record.walletAddress);
      },
      error: () => {
        this.loading = false;
      },
    });
  }

  private loadDeployments(assetId: string, walletAddress: string): void {
    this.deploymentsSection = beginAsyncSection(this.deploymentsSection);
    this.issuanceService.getDeployments(assetId).subscribe({
      next: (deployments) => {
        this.deploymentsSection = resolveAsyncSection(this.deploymentsSection, deployments);
        if (this.isErc3643 && deployments.length > 0) {
          this.loadIdentityStatus(assetId, deployments[0].id, walletAddress);
        }
      },
      error: () => {
        this.deploymentsSection = failAsyncSection(this.deploymentsSection);
      },
    });
  }

  private loadIdentityStatus(assetId: string, deploymentId: string, walletAddress: string): void {
    this.identitySection = beginAsyncSection(this.identitySection);
    this.erc3643Service.getIdentityRegistry(assetId, deploymentId).subscribe({
      next: (entries) => {
        const match = entries.find(
          entry => entry.walletAddress.toLowerCase() === walletAddress.toLowerCase()
        ) ?? null;
        this.identitySection = resolveAsyncSection(this.identitySection, match);
      },
      error: () => {
        this.identitySection = failAsyncSection(this.identitySection);
      },
    });
  }
}
