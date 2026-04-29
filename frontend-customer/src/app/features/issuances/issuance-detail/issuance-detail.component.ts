import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatStepperModule } from '@angular/material/stepper';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { IssuanceService } from '../../../core/api/issuance.service';
import { TransactionService, TxRecord } from '../../../core/api/transaction.service';
import { AuthService } from '../../../core/auth/auth.service';
import { Erc3643Service, ComplianceStatus, IdentityRegistryEntry } from '../../../core/api/erc3643.service';
import { Asset, AssetDeployment, AssetDocument, AssetHolder, Chain, Network } from '../../../core/models';
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
import { AddHolderDialogComponent } from './add-holder-dialog.component';
import { HolderTableComponent } from '../../../shared/components/token-holders/holder-table.component';
import { HolderDistributionComponent } from '../../../shared/components/token-holders/holder-distribution.component';
import { TokenAdminPanelComponent } from '../../../shared/components/token-holders/token-admin-panel.component';
import { AddressComponent } from '../../../shared/components/address.component';
import type { LiveHolder, MintAction, BurnAction, ForceTransferAction, ForceApproveAction } from '../../../shared/components/token-holders/models';

@Component({
  selector: 'app-issuance-detail',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatStepperModule,
    MatTableModule,
    MatChipsModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    StatusBadgeComponent,
    ChainIconComponent,
    DataStatePillComponent,
    HolderTableComponent,
    HolderDistributionComponent,
    TokenAdminPanelComponent,
    AddressComponent,
  ],
  template: `
    <div class="page-container">
      <!-- Back nav -->
      <a mat-button routerLink="/issuances" class="back-link">
        <mat-icon>arrow_back</mat-icon>
        My Issuances
      </a>

      @if (loading) {
        <div class="loading-overlay"><mat-spinner diameter="48"></mat-spinner></div>
      } @else if (asset) {

        <!-- ── Asset header ──────────────────────────────────────────────── -->
        <mat-card class="header-card">
          <mat-card-content>
            <div class="asset-header">
              <div class="asset-title">
                <h1>{{ asset.name }}</h1>
                <div class="asset-meta">
                  <code class="asset-number">{{ asset.assetNumber }}</code>
                  @if (asset.isin) { <span class="isin">ISIN: {{ asset.isin }}</span> }
                  @if (asset.tokenStandard) { <mat-chip>{{ asset.tokenStandard }}</mat-chip> }
                  @if (asset.jurisdiction) {
                    <mat-chip style="background:rgba(99,102,241,0.12);color:#6366f1">
                      {{ jurisdictionLabel(asset.jurisdiction) }}
                    </mat-chip>
                  }
                  @if (asset.chain) {
                    <app-chain-icon [chain]="asset.chain"></app-chain-icon>
                  }
                </div>
              </div>
              <div class="asset-status">
                <app-status-badge [status]="asset.status"></app-status-badge>
              </div>
            </div>

            <!-- Action buttons -->
            <div class="action-bar">
              @if (asset.status === 'DRAFT') {
                <button
                  mat-raised-button
                  color="primary"
                  [disabled]="actionLoading"
                  (click)="submitForApproval()"
                >
                  @if (actionLoading) { <mat-spinner diameter="18"></mat-spinner> }
                  @else {
                    <ng-container>
                      <mat-icon>send</mat-icon>
                      Submit for Approval
                    </ng-container>
                  }
                </button>
              }
              @if (asset.status === 'APPROVED') {
                <button
                  mat-raised-button
                  color="accent"
                  [disabled]="actionLoading"
                  (click)="deploy()"
                >
                  @if (actionLoading) { <mat-spinner diameter="18"></mat-spinner> }
                  @else {
                    <ng-container>
                      <mat-icon>rocket_launch</mat-icon>
                      Deploy to Chain
                    </ng-container>
                  }
                </button>
              }
              @if (asset.status === 'ISSUED') {
                <button mat-raised-button color="primary" (click)="openAddHolder()">
                  <mat-icon>person_add</mat-icon>
                  Add Holder
                </button>
              }
            </div>
          </mat-card-content>
        </mat-card>

        <!-- ── Lifecycle timeline ─────────────────────────────────────────── -->
        <mat-card class="timeline-card">
          <mat-card-header>
            <mat-card-title>Lifecycle</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <mat-stepper [linear]="false" [selectedIndex]="lifecycleIndex" class="lifecycle-stepper">
              <mat-step label="Draft" [completed]="lifecycleIndex > 0" [editable]="false">
                <ng-template matStepLabel>Draft</ng-template>
              </mat-step>
              <mat-step label="Pending Approval" [completed]="lifecycleIndex > 1" [editable]="false">
                <ng-template matStepLabel>Pending Approval</ng-template>
              </mat-step>
              <mat-step label="Approved" [completed]="lifecycleIndex > 2" [editable]="false">
                <ng-template matStepLabel>Approved</ng-template>
              </mat-step>
              <mat-step label="Issued" [completed]="lifecycleIndex >= 3" [editable]="false">
                <ng-template matStepLabel>Issued</ng-template>
              </mat-step>
            </mat-stepper>
          </mat-card-content>
        </mat-card>

        <!-- ── Deployments ───────────────────────────────────────────────── -->
        <mat-card class="section-card">
            <mat-card-header>
              <mat-card-title>Chain Deployments</mat-card-title>
              <app-data-state-pill [status]="deploymentsState.status" />
            </mat-card-header>
            <mat-card-content>
            @if (deployments.length === 0) {
              <p class="empty-text">No deployments yet.</p>
            } @else {
              <table mat-table [dataSource]="deployments" class="mat-elevation-z0">
                <ng-container matColumnDef="chain">
                  <th mat-header-cell *matHeaderCellDef>Chain</th>
                  <td mat-cell *matCellDef="let d">
                    <app-chain-icon [chain]="d.chain"></app-chain-icon>
                  </td>
                </ng-container>
                <ng-container matColumnDef="network">
                  <th mat-header-cell *matHeaderCellDef>Network</th>
                  <td mat-cell *matCellDef="let d">{{ d.network }}</td>
                </ng-container>
                <ng-container matColumnDef="contract">
                  <th mat-header-cell *matHeaderCellDef>Contract Address</th>
                  <td mat-cell *matCellDef="let d">
                    @if (d.contractAddress) {
                      <code>{{ d.contractAddress }}</code>
                    } @else { — }
                  </td>
                </ng-container>
                <ng-container matColumnDef="status">
                  <th mat-header-cell *matHeaderCellDef>Status</th>
                  <td mat-cell *matCellDef="let d">
                    <app-status-badge [status]="d.deploymentStatus"></app-status-badge>
                  </td>
                </ng-container>
                <ng-container matColumnDef="deployedAt">
                  <th mat-header-cell *matHeaderCellDef>Deployed At</th>
                  <td mat-cell *matCellDef="let d">{{ d.deployedAt ? (d.deployedAt | date:'medium') : '—' }}</td>
                </ng-container>

                <tr mat-header-row *matHeaderRowDef="deploymentColumns"></tr>
                <tr mat-row *matRowDef="let r; columns: deploymentColumns;"></tr>
              </table>
            }
          </mat-card-content>
        </mat-card>

        <!-- ── Term Sheet ────────────────────────────────────────────────── -->
        <mat-card class="section-card">
          <mat-card-header>
            <mat-card-title>Term Sheet</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            @if (tsLoading) {
              <div style="padding:16px;text-align:center"><mat-spinner diameter="32"></mat-spinner></div>
            } @else if (termSheetDocs.length > 0) {
              @for (doc of termSheetDocs; track doc.id) {
                <div style="display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid var(--rw-border)">
                  <mat-icon style="color:var(--rw-text-muted)">description</mat-icon>
                  <div style="flex:1">
                    <div style="font-size:13px;font-weight:500">{{ doc.fileName ?? doc.documentType }}</div>
                    <div style="font-size:11px;color:var(--rw-text-muted)">
                      {{ doc.mimeType }} · {{ doc.source === 'UPLOAD' ? 'Uploaded' : 'On-chain' }}
                      @if (doc.sizeBytes) { · {{ (doc.sizeBytes / 1024 | number:'1.0-0') }} KB }
                    </div>
                  </div>
                  @if (doc.contentAvailable) {
                    <button mat-icon-button matTooltip="Download" (click)="downloadTermSheet(doc)">
                      <mat-icon>download</mat-icon>
                    </button>
                  }
                </div>
              }
            } @else if (isIssuer) {
              <div style="display:flex;align-items:center;gap:10px;padding:12px 0;color:var(--rw-text-secondary);font-size:13px">
                <mat-icon style="color:#F59E0B">warning_amber</mat-icon>
                <span style="flex:1">No term sheet uploaded. eWpG requires a term sheet for this security.</span>
                <label>
                  <input #tsFileInput type="file"
                    accept=".pdf,.html,.htm,.txt,.md,.json,.xml,.docx"
                    style="display:none"
                    (change)="onTermSheetFileSelected($event)" />
                  <button mat-stroked-button (click)="tsFileInput.click()" [disabled]="tsUploading">
                    <mat-icon>upload_file</mat-icon>
                    @if (tsUploading) { Uploading… } @else { Upload Term Sheet }
                  </button>
                </label>
              </div>
            } @else {
              <p style="color:var(--rw-text-muted);font-size:13px;padding:8px 0">
                Term sheet pending upload by the issuer.
              </p>
            }
          </mat-card-content>
        </mat-card>

        <!-- ── T-REX Compliance (ERC-3643 only) ────────────────────────── -->
        @if (isErc3643 && complianceStatus) {
          <mat-card class="section-card trex-card">
            <mat-card-header>
              <mat-card-title>
                <mat-icon class="trex-icon">verified_user</mat-icon>
                T-REX Compliance
              </mat-card-title>
              <app-data-state-pill [status]="identityRegistryState.status" />
            </mat-card-header>
            <mat-card-content>
              <div class="compliance-grid">
                <div class="compliance-stat">
                  <span class="stat-label">Registered Investors</span>
                  <span class="stat-value">
                    {{ complianceStatus.investorCount }}
                    @if (complianceStatus.maxInvestors) {
                      <span class="stat-max"> / {{ complianceStatus.maxInvestors }} max</span>
                    }
                  </span>
                </div>
                @if (complianceStatus.maxBalance) {
                  <div class="compliance-stat">
                    <span class="stat-label">Max Token Balance</span>
                    <span class="stat-value">{{ complianceStatus.maxBalance }}</span>
                  </div>
                }
                @if (complianceStatus.transferCooldown) {
                  <div class="compliance-stat">
                    <span class="stat-label">Transfer Cooldown</span>
                    <span class="stat-value">{{ complianceStatus.transferCooldown }}s</span>
                  </div>
                }
                @if (complianceStatus?.blockedCountries && complianceStatus.blockedCountries.length) {
                  <div class="compliance-stat">
                    <span class="stat-label">Blocked Countries</span>
                    <span class="stat-value">{{ complianceStatus.blockedCountries.join(', ') }}</span>
                  </div>
                }
              </div>
              @if (complianceStatus?.modules && complianceStatus.modules.length) {
                <div class="module-list">
                  <span class="module-list-label">Active Compliance Modules</span>
                  <div class="module-chips">
                    @for (m of complianceStatus.modules; track m.id) {
                      <mat-chip>{{ m.moduleType }}</mat-chip>
                    }
                  </div>
                </div>
              }
            </mat-card-content>
          </mat-card>
        }

        <!-- ── Holders ───────────────────────────────────────────────────── -->
        <mat-card class="section-card">
          <mat-card-header>
              <mat-card-title>Holders ({{ holders.length }})</mat-card-title>
              <app-data-state-pill [status]="holdersState.status" />
            </mat-card-header>
          <mat-card-content>
            @if (holders.length === 0) {
              <p class="empty-text">No holders recorded yet.</p>
            } @else {
              <table mat-table [dataSource]="holders" class="mat-elevation-z0">
                <ng-container matColumnDef="wallet">
                  <th mat-header-cell *matHeaderCellDef>Wallet Address</th>
                  <td mat-cell *matCellDef="let h">
                    <app-address [address]="h.walletAddress" />
                  </td>
                </ng-container>
                <ng-container matColumnDef="amount">
                  <th mat-header-cell *matHeaderCellDef>Nominal Amount</th>
                  <td mat-cell *matCellDef="let h">{{ h.nominalAmount | number:'1.0-2' }}</td>
                </ng-container>
                <ng-container matColumnDef="whitelisted">
                  <th mat-header-cell *matHeaderCellDef>Whitelisted</th>
                  <td mat-cell *matCellDef="let h">
                    <app-status-badge [status]="h.whitelisted ? 'WHITELISTED' : 'NOT_WHITELISTED'"></app-status-badge>
                  </td>
                </ng-container>
                <!-- ERC-3643 extra columns -->
                @if (isErc3643) {
                  <ng-container matColumnDef="onchainId">
                    <th mat-header-cell *matHeaderCellDef>ONCHAINID</th>
                    <td mat-cell *matCellDef="let h">
                      @let entry = identityFor(h.walletAddress);
                      @if (entry) {
                        <mat-icon [style.color]="entry.active ? '#388e3c' : '#e53935'" style="font-size:18px;vertical-align:middle">
                          {{ entry.active ? 'check_circle' : 'cancel' }}
                        </mat-icon>
                      } @else {
                        <mat-icon style="color:#9e9e9e;font-size:18px;vertical-align:middle">radio_button_unchecked</mat-icon>
                      }
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="kyc">
                    <th mat-header-cell *matHeaderCellDef>KYC</th>
                    <td mat-cell *matCellDef="let h">
                      @let kycEntry = identityFor(h.walletAddress);
                      @if (kycEntry) {
                        <mat-icon [style.color]="kycEntry.verified ? '#388e3c' : '#e53935'" style="font-size:18px;vertical-align:middle">
                          {{ kycEntry.verified ? 'verified' : 'gpp_bad' }}
                        </mat-icon>
                      } @else {
                        <mat-icon style="color:#9e9e9e;font-size:18px;vertical-align:middle">help_outline</mat-icon>
                      }
                    </td>
                  </ng-container>
                }

                <tr mat-header-row *matHeaderRowDef="activeHolderColumns"></tr>
                <tr mat-row *matRowDef="let r; columns: activeHolderColumns;"></tr>
              </table>
            }
          </mat-card-content>
        </mat-card>

        <!-- ── Token Holders (Live from Blockchain) ────────────────────────── -->
        <mat-card class="section-card">
          <mat-card-header>
            <div style="display:flex;justify-content:space-between;align-items:center;width:100%">
              <mat-card-title>Token Holders (Live from Blockchain)</mat-card-title>
              <app-data-state-pill [status]="liveHoldersState.status" />
              <button mat-icon-button (click)="refreshLiveHolders()" [disabled]="liveHoldersLoading" matTooltip="Refresh holder data">
                @if (liveHoldersLoading) {
                  <mat-spinner diameter="24"></mat-spinner>
                } @else {
                  <mat-icon>refresh</mat-icon>
                }
              </button>
            </div>
          </mat-card-header>
          <mat-card-content>
            @if (asset && asset.status === 'ISSUED' && deployments.length > 0) {
              <div style="display:grid;grid-template-columns:1fr 1fr;gap:24px;margin-bottom:24px">
                <!-- Left: Distribution -->
                <div>
                  <app-holder-distribution [holders]="liveHolders"></app-holder-distribution>
                </div>
                <!-- Right: Holder Table -->
                <div>
                  <app-holder-table [holders]="liveHolders"></app-holder-table>
                </div>
              </div>

              <!-- Token Admin Panel for Issuers -->
              @if (isIssuer && deployments.length > 0 && asset) {
                <div style="margin-top:24px;border-top:1px solid var(--rw-border);padding-top:24px">
                  <app-token-admin-panel
                    [assetId]="asset.id"
                    [deploymentId]="deployments[0].id"
                    (mint)="onMint($event)"
                    (burn)="onBurn($event)"
                    (forceTransfer)="onForceTransfer($event)"
                    (forceApprove)="onForceApprove($event)"
                  ></app-token-admin-panel>
                </div>
              }
            } @else {
              <p class="empty-text">
                @if (asset && asset.status !== 'ISSUED') {
                  Token holders will be displayed once the asset is issued.
                } @else {
                  No deployments available for this asset.
                }
              </p>
            }
          </mat-card-content>
        </mat-card>

      }
    </div>
  `,
  styles: [`
    .back-link { margin-bottom: 16px; display: inline-flex; }
    .header-card, .timeline-card, .section-card { margin-bottom: 16px; }
    .asset-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
    .asset-title h1 { margin: 0 0 8px; font-size: 22px; }
    .asset-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .asset-number { font-size: 13px; color: #546e7a; }
    .isin { font-size: 13px; color: #546e7a; }
    .action-bar { display: flex; gap: 12px; margin-top: 16px; padding-top: 16px; border-top: 1px solid #e0e0e0; }
    .empty-text { color: #9e9e9e; font-style: italic; }
    code { font-family: monospace; font-size: 13px; background: #f5f5f5; padding: 2px 4px; border-radius: 3px; }
    /* T-REX compliance card */
    .trex-card { border-left: 4px solid #00897b; }
    .trex-icon { vertical-align: middle; margin-right: 6px; color: #00897b; }
    .compliance-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 20px; margin-bottom: 16px; }
    .compliance-stat { display: flex; flex-direction: column; gap: 4px; }
    .stat-label { font-size: 11px; color: #78909c; text-transform: uppercase; letter-spacing: 0.5px; }
    .stat-value { font-size: 20px; font-weight: 600; color: #37474f; }
    .stat-max { font-size: 14px; font-weight: 400; color: #90a4ae; }
    .module-list { margin-top: 8px; }
    .module-list-label { font-size: 12px; color: #546e7a; display: block; margin-bottom: 6px; }
    .module-chips { display: flex; gap: 8px; flex-wrap: wrap; }
  `]
})
export class IssuanceDetailComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly route = inject(ActivatedRoute);
  private readonly issuanceService = inject(IssuanceService);
  private readonly auth = inject(AuthService);
  private readonly erc3643Service = inject(Erc3643Service);
  private readonly txService = inject(TransactionService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  txHistory: TxRecord[] = [];
  txHistoryLoading = false;

  asset: Asset | null = null;
  deployments: AssetDeployment[] = [];
  holders: AssetHolder[] = [];
  loading = true;
  actionLoading = false;
  deploymentsState: AsyncSection<null> = createAsyncSection<null>(null);
  holdersState: AsyncSection<null> = createAsyncSection<null>(null);
  identityRegistryState: AsyncSection<null> = createAsyncSection<null>(null);
  liveHoldersState: AsyncSection<null> = { data: null, status: 'ready', hasLoaded: true };

  // ── Term Sheet ────────────────────────────────────────────────────────────
  termSheetDocs: AssetDocument[] = [];
  tsLoading = false;
  tsUploading = false;

  get isIssuer(): boolean {
    return this.auth?.hasRole('ISSUER') || this.auth?.hasRole('REGISTRY_ADMIN') || false;
  }

  // ERC-3643 state
  complianceStatus: ComplianceStatus | null = null;
  identityRegistry: IdentityRegistryEntry[] = [];

  // Live token holders (blockchain state)
  liveHolders: LiveHolder[] = [];
  liveHoldersLoading = false;

  readonly deploymentColumns = ['chain', 'network', 'contract', 'status', 'deployedAt'];
  readonly baseHolderColumns  = ['wallet', 'amount', 'whitelisted'];
  readonly erc3643HolderColumns = ['wallet', 'amount', 'whitelisted', 'onchainId', 'kyc'];

  get isErc3643(): boolean {
    return this.asset?.tokenStandard === 'ERC3643' || this.asset?.tokenStandard === 'CONF_ERC3643';
  }

  get activeHolderColumns(): string[] {
    return this.isErc3643 ? this.erc3643HolderColumns : this.baseHolderColumns;
  }

  identityFor(walletAddress: string): IdentityRegistryEntry | undefined {
    return this.identityRegistry.find(e => e.walletAddress.toLowerCase() === walletAddress.toLowerCase());
  }

  get lifecycleIndex(): number {
    if (!this.asset) return 0;
    const map: Record<string, number> = {
      DRAFT: 0,
      PENDING_APPROVAL: 1,
      APPROVED: 2,
      ISSUED: 3,
      SUSPENDED: 3,
      REDEEMED: 4,
    };
    return map[this.asset.status] ?? 0;
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id')!;
    this.issuanceService.getIssuance(id).subscribe({
      next: (asset) => {
        this.asset = asset;
        this.loading = false;
        this.cdr.detectChanges();
        this.loadDeployments(asset.id);
        this.loadHolders(asset.id);
        this.loadTermSheetDocs(id);
      },
      error: () => {
        this.loading = false;
        this.cdr.detectChanges();
      },
    });
  }

  private loadDeployments(assetId: string): void {
    this.deploymentsState = beginAsyncSection(this.deploymentsState);
    this.issuanceService.getDeployments(assetId).subscribe({
      next: (deployments) => {
        this.deployments = deployments;
        this.deploymentsState = resolveAsyncSection(this.deploymentsState, null);
        this.cdr.detectChanges();
        if (this.isErc3643 && deployments.length > 0) {
          this.loadErc3643Data(assetId, deployments[0].id);
        }
      },
      error: () => {
        this.deploymentsState = failAsyncSection(this.deploymentsState);
        this.cdr.detectChanges();
      },
    });
  }

  private loadHolders(assetId: string): void {
    this.holdersState = beginAsyncSection(this.holdersState);
    this.issuanceService.getHolders(assetId, { size: 100 }).subscribe({
      next: (holders) => {
        this.holders = holders.content;
        this.holdersState = resolveAsyncSection(this.holdersState, null);
        this.cdr.detectChanges();
      },
      error: () => {
        this.holdersState = failAsyncSection(this.holdersState);
        this.cdr.detectChanges();
      },
    });
  }

  private loadErc3643Data(assetId: string, deploymentId: string): void {
    this.identityRegistryState = beginAsyncSection(this.identityRegistryState);
    this.erc3643Service.getComplianceStatus(assetId, deploymentId).subscribe({
      next: (compliance) => {
        this.complianceStatus = compliance;
        this.cdr.detectChanges();
      },
    });
    this.erc3643Service.getIdentityRegistry(assetId, deploymentId).subscribe({
      next: (registry) => {
        this.identityRegistry = registry;
        this.identityRegistryState = resolveAsyncSection(this.identityRegistryState, null);
        this.cdr.detectChanges();
      },
      error: () => {
        this.identityRegistryState = failAsyncSection(this.identityRegistryState);
        this.cdr.detectChanges();
      },
    });
  }

  submitForApproval(): void {
    if (!this.asset) return;
    this.actionLoading = true;

    this.issuanceService.submitIssuance(this.asset.id).subscribe({
      next: (updated) => {
        this.asset = updated;
        this.actionLoading = false;
        this.snackBar.open('Submitted for approval.', 'OK', { duration: 3000 });
        this.cdr.detectChanges();
      },
      error: () => {
        this.actionLoading = false;
        this.cdr.detectChanges();
      },
    });
  }

  deploy(): void {
    if (!this.asset?.chain || !this.asset?.network) return;
    this.actionLoading = true;

    this.issuanceService
      .deployIssuance(this.asset.id, this.asset.chain as Chain, this.asset.network as Network)
      .subscribe({
        next: (deployment) => {
          this.deployments = [...this.deployments, deployment];
          this.actionLoading = false;
          this.snackBar.open('Deployment initiated.', 'OK', { duration: 3000 });
          this.cdr.detectChanges();
        },
        error: () => {
          this.actionLoading = false;
          this.cdr.detectChanges();
        },
      });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  jurisdictionLabel(jur: string): string {
    const labels: Record<string, string> = {
      DE_EWPG: 'DE — eWpG',
      LU_CSSF: 'LU — CSSF',
      FR_AMF: 'FR — AMF',
      LI_TVTG: 'LI — TVTG',
    };
    return labels[jur] ?? jur;
  }

  // ── Term Sheet ────────────────────────────────────────────────────────────

  private loadTermSheetDocs(assetId: string): void {
    this.tsLoading = true;
    this.issuanceService.listDocuments(assetId).subscribe({
      next: docs => { this.termSheetDocs = docs; this.tsLoading = false; this.cdr.detectChanges(); },
      error: () => { this.tsLoading = false; this.cdr.detectChanges(); },
    });
  }

  onTermSheetFileSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file || !this.asset) return;
    this.tsUploading = true;
    this.issuanceService.uploadDocument(this.asset.id, file).subscribe({
      next: doc => {
        this.termSheetDocs = [doc, ...this.termSheetDocs];
        this.tsUploading = false;
        if (this.asset) this.asset.hasTermSheet = true;
        this.snackBar.open('Term sheet uploaded.', 'OK', { duration: 3000 });
        this.cdr.detectChanges();
      },
      error: () => {
        this.tsUploading = false;
        this.snackBar.open('Upload failed.', 'Close', { duration: 4000 });
        this.cdr.detectChanges();
      },
    });
  }

  downloadTermSheet(doc: AssetDocument): void {
    if (!this.asset) return;
    this.issuanceService.downloadDocument(this.asset.id, doc.id).subscribe(blob => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = doc.fileName ?? 'term_sheet';
      a.click();
      URL.revokeObjectURL(url);
    });
  }

  openAddHolder(): void {
    if (!this.asset) return;
    const ref = this.dialog.open(AddHolderDialogComponent, {
      width: '480px',
      data: { assetId: this.asset.id },
    });

    ref.afterClosed().subscribe((result: AssetHolder | undefined) => {
      if (result) {
        this.holders = [...this.holders, result];
        this.snackBar.open('Holder added successfully.', 'OK', { duration: 3000 });
      }
    });
  }

  // ── Live Token Holders ────────────────────────────────────────────────────

  refreshLiveHolders(): void {
    if (!this.asset?.id) return;
    this.liveHoldersLoading = true;
    this.liveHoldersState = beginAsyncSection(this.liveHoldersState);
    this.issuanceService.refreshHolders(this.asset.id).subscribe({
      next: (response) => {
        this.liveHoldersLoading = false;
        this.liveHoldersState = resolveAsyncSection(this.liveHoldersState, null);
        this.snackBar.open(response.message || 'Holder data refresh initiated', '', { duration: 3000 });
      },
      error: (error) => {
        this.liveHoldersLoading = false;
        this.liveHoldersState = failAsyncSection(this.liveHoldersState);
        const errorMsg = error?.error?.message || 'Failed to refresh holder data';
        this.snackBar.open(`Error: ${errorMsg}`, 'Close', { duration: 5000 });
      }
    });
  }

  onMint(action: MintAction): void {
    if (!this.asset?.id) return;
    // TODO: Call issuanceService to execute mint action
    this.snackBar.open(`Mint initiated: ${action.amount} tokens`, 'OK', { duration: 3000 });
  }

  onBurn(action: BurnAction): void {
    if (!this.asset?.id) return;
    // TODO: Call issuanceService to execute burn action
    this.snackBar.open(`Burn initiated: ${action.amount} tokens`, 'OK', { duration: 3000 });
  }

  onForceTransfer(action: ForceTransferAction): void {
    if (!this.asset?.id || this.deployments.length === 0) return;
    this.issuanceService.forceTransfer(this.asset.id, this.deployments[0].id, {
      from: action.fromWallet, to: action.toWallet,
      value: action.amount.toString(), legalBasis: '',
    }).subscribe({
      next: (r) => this.txService.track(r.txId, 'Forced transfer'),
      error: () => this.snackBar.open('Force transfer failed.', 'Close', { duration: 5000 }),
    });
  }

  onForceApprove(action: ForceApproveAction): void {
    if (!this.asset?.id || this.deployments.length === 0) return;
    this.issuanceService.forceApprove(this.asset.id, this.deployments[0].id, {
      owner: action.ownerWallet, spender: action.spenderWallet,
      value: action.amount.toString(), legalBasis: '',
    }).subscribe({
      next: (r) => this.txService.track(r.txId, 'Forced approve'),
      error: () => this.snackBar.open('Force approve failed.', 'Close', { duration: 5000 }),
    });
  }

  loadTxHistory(): void {
    if (!this.deployments[0]?.id) return;
    this.txHistoryLoading = true;
    this.txService.listTransactions(this.deployments[0].id).subscribe({
      next: (page) => { this.txHistory = page.content; this.txHistoryLoading = false; },
      error: () => { this.txHistoryLoading = false; },
    });
  }
}
