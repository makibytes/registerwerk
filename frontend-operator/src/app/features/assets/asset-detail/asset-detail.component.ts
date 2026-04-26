import { Component, OnInit, Input, inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar } from '@angular/material/snack-bar';
import { FormsModule } from '@angular/forms';
import { DatePipe, DecimalPipe } from '@angular/common';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { AssetService } from '../../../core/api/asset.service';
import {
  Erc3643Service, Erc3643Suite, IdentityRegistryEntry, ComplianceStatus,
  TrustedIssuer, ClaimTopic,
} from '../../../core/api/erc3643.service';
import { MintControlService, MintControlRule, RuleType } from '../../../core/api/mint-control.service';
import {
  Asset, AssetDeployment, AssetDocument, AssetHolder,
  KycComplianceResponse, DocumentStatus,
} from '../../../core/models';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';
import { ChainNamePipe } from '../../../shared/pipes/chain-name.pipe';
import { RegisterInvestorDialogComponent, RegisterInvestorData } from './register-investor-dialog.component';
import { AddIssuerDialogComponent, AddIssuerData } from './add-issuer-dialog.component';
import { AddClaimTopicDialogComponent, AddClaimTopicData } from './add-claim-topic-dialog.component';

@Component({
  selector: 'app-asset-detail',
  standalone: true,
  imports: [
    MatTabsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatTableModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatFormFieldModule,
    MatInputModule,
    MatTooltipModule,
    FormsModule,
    MatChipsModule,
    MatDialogModule,
    StatusBadgeComponent,
    ChainNamePipe,
    DatePipe,
    DecimalPipe,
  ],

  styles: [`
    .back-row { margin-bottom: 12px; }

    .asset-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 20px;

      .asset-title {
        h2 { margin: 0 0 4px; font-size: 22px; font-weight: 500; }
        .asset-number { color: rgba(0,0,0,0.54); font-size: 13px; font-family: monospace; }
      }

      .asset-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    }

    .field-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px 24px;
      @media (max-width: 600px) { grid-template-columns: 1fr; }
    }

    .field-item {
      padding: 12px 0;
      border-bottom: 1px solid #f5f5f5;

      .field-label {
        font-size: 12px;
        color: rgba(0,0,0,0.54);
        text-transform: uppercase;
        letter-spacing: 0.5px;
        margin-bottom: 4px;
      }
      .field-value { font-size: 14px; color: rgba(0,0,0,0.87); }
    }

    .tab-content { padding-top: 20px; }

    table { width: 100%; }

    .mint-form {
      max-width: 480px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      margin-bottom: 24px;

      .form-row {
        display: flex;
        gap: 12px;
        align-items: flex-start;
        mat-form-field { flex: 1; }
      }
    }

    .spinner-wrap { display: flex; justify-content: center; padding: 40px; }

    .jurisdiction-badge {
      font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 4px;
      background: rgba(99,102,241,0.12); color: #6366f1;
      border: 1px solid rgba(99,102,241,0.25); margin-left: 6px; vertical-align: middle;
    }

    .compliance-row {
      display: flex; align-items: center; gap: 8px;
      font-size: 12px; padding: 5px 0; border-bottom: 1px solid var(--rw-border);
      .comp-icon { font-size: 14px; flex-shrink: 0; }
      .comp-name { flex: 1; font-weight: 500; }
      .comp-note { color: var(--rw-text-muted); font-size: 11px; }
    }

    .termsheet-header {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-bottom: 16px;

      h3 { margin: 0; font-size: 15px; font-weight: 600; flex: 1; }
    }

    .termsheet-warning {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 12px 16px;
      border-radius: 6px;
      border-left: 3px solid #F59E0B;
      background: rgba(245,158,11,0.07);
      color: var(--rw-text-secondary);
      font-size: 13px;
      margin-bottom: 16px;

      mat-icon { color: #F59E0B; flex-shrink: 0; }
    }

    .source-badge {
      display: inline-block;
      font-size: 10px;
      font-weight: 700;
      letter-spacing: 0.6px;
      text-transform: uppercase;
      padding: 2px 7px;
      border-radius: 4px;

      &.upload { background: rgba(99,102,241,0.12); color: #6366f1; }
      &.onchain { background: rgba(16,185,129,0.12); color: #10b981; }
    }
  `],
  template: `
    <div class="back-row">
      <button mat-button (click)="goBack()">
        <mat-icon>arrow_back</mat-icon>
        Back to Assets
      </button>
    </div>

    @if (loading) {
      <div class="spinner-wrap"><mat-spinner diameter="40" /></div>
    } @else if (asset) {
      <div class="asset-header">
        <div class="asset-title">
          <h2>{{ asset.name }}</h2>
          <span class="asset-number">{{ asset.assetNumber }}</span>
          &nbsp;
          <app-status-badge [status]="asset.status" />
          @if (asset.jurisdiction) {
            <span class="jurisdiction-badge">{{ jurisdictionLabel(asset.jurisdiction) }}</span>
          }
        </div>
        <div class="asset-actions">
          @if (asset.status === 'PENDING_APPROVAL') {
            <button mat-raised-button color="primary" (click)="approve()">Approve</button>
          }
          @if (asset.status === 'APPROVED') {
            <button mat-raised-button color="primary" (click)="issue()">Issue</button>
          }
          @if (asset.status === 'ISSUED') {
            <button mat-stroked-button color="warn" (click)="suspend()">Suspend</button>
          }
          @if (asset.status === 'SUSPENDED') {
            <button mat-stroked-button color="primary" (click)="issue()">Reissue</button>
          }
          @if (asset.status !== 'REDEEMED') {
            <button mat-stroked-button color="warn" (click)="redeem()">Redeem</button>
          }
          <button mat-stroked-button (click)="edit()">
            <mat-icon>edit</mat-icon>
            Edit
          </button>
        </div>
      </div>

      <mat-tab-group animationDuration="200ms">
        <!-- Overview -->
        <mat-tab label="Overview">
          <div class="tab-content">
            <div class="field-grid">
              <div class="field-item">
                <div class="field-label">Asset ID</div>
                <div class="field-value"><code>{{ asset.id }}</code></div>
              </div>
              <div class="field-item">
                <div class="field-label">Issuer ID</div>
                <div class="field-value"><code>{{ asset.issuerId }}</code></div>
              </div>
              <div class="field-item">
                <div class="field-label">ISIN</div>
                <div class="field-value">{{ asset.isin ?? '—' }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">Token Standard</div>
                <div class="field-value">{{ asset.tokenStandard }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">On-chain Level</div>
                <div class="field-value">{{ asset.onchainLevel }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">Total Supply</div>
                <div class="field-value">{{ (asset.totalSupply ?? 0) | number }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">Decimals</div>
                <div class="field-value">{{ asset.decimals ?? '—' }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">Created At</div>
                <div class="field-value">{{ asset.createdAt | date:'medium' }}</div>
              </div>
            </div>
          </div>

          @if (asset.status === 'PENDING_APPROVAL' && asset.jurisdiction) {
            <div style="margin-top:20px">
              <div style="font-size:13px;font-weight:600;margin-bottom:10px;display:flex;align-items:center;gap:8px">
                <mat-icon style="font-size:16px">policy</mat-icon>
                KYC Compliance — {{ jurisdictionLabel(asset.jurisdiction) }}
                @if (kycCompliance?.fullyCompliant) {
                  <span style="font-size:11px;padding:2px 7px;border-radius:4px;background:rgba(16,185,129,0.12);color:#10b981">COMPLIANT</span>
                } @else if (kycCompliance) {
                  <span style="font-size:11px;padding:2px 7px;border-radius:4px;background:rgba(245,158,11,0.12);color:#f59e0b">INCOMPLETE</span>
                }
              </div>
              @if (kycComplianceLoading) {
                <div class="spinner-wrap"><mat-spinner diameter="24" /></div>
              } @else if (kycCompliance) {
                <div style="border:1px solid var(--rw-border);border-radius:6px;overflow:hidden;padding:4px 12px">
                  @for (doc of kycCompliance.documents; track doc.documentType) {
                    <div class="compliance-row">
                      @if (!doc.mandatory) {
                        <mat-icon class="comp-icon" style="color:var(--rw-text-muted)">radio_button_unchecked</mat-icon>
                      } @else if (doc.present && !doc.expired && !doc.tooOld) {
                        <mat-icon class="comp-icon" style="color:#10b981">check_circle</mat-icon>
                      } @else if (doc.tooOld) {
                        <mat-icon class="comp-icon" style="color:#f59e0b">schedule</mat-icon>
                      } @else {
                        <mat-icon class="comp-icon" style="color:#ef4444">cancel</mat-icon>
                      }
                      <span class="comp-name">{{ doc.localName }}</span>
                      <span class="comp-note">
                        @if (doc.tooOld) { Document too old (max 90 days) }
                        @else if (doc.expired) { Expired }
                        @else if (!doc.present && doc.mandatory) { Missing }
                        @else if (!doc.mandatory) { Recommended }
                      </span>
                    </div>
                  }
                </div>
                @if (!kycCompliance.fullyCompliant) {
                  <p style="font-size:12px;color:var(--rw-text-muted);margin-top:8px">
                    <mat-icon style="font-size:14px;vertical-align:middle">info</mat-icon>
                    Approving with an incomplete KYC checklist will be recorded in the audit trail.
                  </p>
                }
              }
            </div>
          }
        </mat-tab>

        <!-- Deployments -->
        <mat-tab label="Deployments">
          <div class="tab-content">
            @if (deploymentsLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else {
              <table mat-table [dataSource]="deployments">
                <ng-container matColumnDef="chain">
                  <th mat-header-cell *matHeaderCellDef>Chain</th>
                  <td mat-cell *matCellDef="let d">{{ d.chain | chainName }}</td>
                </ng-container>
                <ng-container matColumnDef="network">
                  <th mat-header-cell *matHeaderCellDef>Network</th>
                  <td mat-cell *matCellDef="let d">{{ d.network | chainName }}</td>
                </ng-container>
                <ng-container matColumnDef="contractAddress">
                  <th mat-header-cell *matHeaderCellDef>Contract Address</th>
                  <td mat-cell *matCellDef="let d">
                    @if (d.contractAddress) {
                      <code style="font-size:12px">{{ d.contractAddress }}</code>
                    } @else { — }
                  </td>
                </ng-container>
                <ng-container matColumnDef="deploymentStatus">
                  <th mat-header-cell *matHeaderCellDef>Status</th>
                  <td mat-cell *matCellDef="let d">
                    <app-status-badge [status]="d.deploymentStatus" />
                  </td>
                </ng-container>
                <ng-container matColumnDef="deployedAt">
                  <th mat-header-cell *matHeaderCellDef>Deployed At</th>
                  <td mat-cell *matCellDef="let d">{{ d.deployedAt ? (d.deployedAt | date:'medium') : '—' }}</td>
                </ng-container>
                <tr mat-header-row *matHeaderRowDef="deploymentColumns"></tr>
                <tr mat-row *matRowDef="let row; columns: deploymentColumns;"></tr>
              </table>
              @if (deployments.length === 0) {
                <p class="text-muted" style="text-align:center;padding:24px">No deployments yet.</p>
              }
            }
          </div>
        </mat-tab>

        <!-- Holders -->
        <mat-tab label="Holders">
          <div class="tab-content">
            @if (holdersLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else {
              <table mat-table [dataSource]="holders">
                <ng-container matColumnDef="address">
                  <th mat-header-cell *matHeaderCellDef>Address</th>
                  <td mat-cell *matCellDef="let h"><code style="font-size:12px">{{ h.address }}</code></td>
                </ng-container>
                <ng-container matColumnDef="balance">
                  <th mat-header-cell *matHeaderCellDef>Balance</th>
                  <td mat-cell *matCellDef="let h">{{ h.balance | number }}</td>
                </ng-container>
                <ng-container matColumnDef="percentage">
                  <th mat-header-cell *matHeaderCellDef>% of Supply</th>
                  <td mat-cell *matCellDef="let h">{{ h.percentage | number:'1.2-2' }}%</td>
                </ng-container>
                <tr mat-header-row *matHeaderRowDef="holderColumns"></tr>
                <tr mat-row *matRowDef="let row; columns: holderColumns;"></tr>
              </table>
              @if (holders.length === 0) {
                <p class="text-muted" style="text-align:center;padding:24px">No holder data available.</p>
              }
            }
          </div>
        </mat-tab>

        <!-- Term Sheet -->
        <mat-tab label="Term Sheet">
          <div class="tab-content">
            <div class="termsheet-header">
              <h3>Term Sheet Documents</h3>
              <label>
                <input #tsFileInput type="file"
                  accept=".pdf,.html,.htm,.txt,.md,.json,.xml,.docx"
                  style="display:none"
                  (change)="onTermSheetFileSelected($event)" />
                <button mat-stroked-button (click)="tsFileInput.click()" [disabled]="tsUploading">
                  <mat-icon>upload_file</mat-icon>
                  @if (tsUploading) { Uploading… } @else { Upload }
                </button>
              </label>
              @if (deployments.length > 0 && deployments[0].deploymentStatus === 'CONFIRMED') {
                <button mat-stroked-button (click)="syncTermSheetFromChain()" [disabled]="tsSyncing">
                  <mat-icon>cloud_download</mat-icon>
                  @if (tsSyncing) { Syncing… } @else { Sync from Chain }
                </button>
              }
            </div>

            @if (!asset.hasTermSheet && termSheetDocs.length === 0) {
              <div class="termsheet-warning">
                <mat-icon>warning_amber</mat-icon>
                No term sheet uploaded. eWpG requires a term sheet before this security can be approved.
              </div>
            }

            @if (tsLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else if (termSheetDocs.length === 0) {
              <p class="text-muted" style="text-align:center;padding:24px">
                No documents attached. Upload a PDF or sync from a deployed contract.
              </p>
            } @else {
              <table mat-table [dataSource]="termSheetDocs" style="width:100%">
                <ng-container matColumnDef="type">
                  <th mat-header-cell *matHeaderCellDef>Type</th>
                  <td mat-cell *matCellDef="let d">{{ d.documentType }}</td>
                </ng-container>
                <ng-container matColumnDef="source">
                  <th mat-header-cell *matHeaderCellDef>Source</th>
                  <td mat-cell *matCellDef="let d">
                    <span class="source-badge" [class.onchain]="d.source !== 'UPLOAD'" [class.upload]="d.source === 'UPLOAD'">
                      {{ d.source === 'UPLOAD' ? 'Uploaded' : 'On-chain' }}
                    </span>
                  </td>
                </ng-container>
                <ng-container matColumnDef="fileName">
                  <th mat-header-cell *matHeaderCellDef>File</th>
                  <td mat-cell *matCellDef="let d">{{ d.fileName ?? '—' }}</td>
                </ng-container>
                <ng-container matColumnDef="mimeType">
                  <th mat-header-cell *matHeaderCellDef>Format</th>
                  <td mat-cell *matCellDef="let d">{{ d.mimeType }}</td>
                </ng-container>
                <ng-container matColumnDef="sizeBytes">
                  <th mat-header-cell *matHeaderCellDef>Size</th>
                  <td mat-cell *matCellDef="let d">{{ d.sizeBytes ? (d.sizeBytes / 1024 | number:'1.0-0') + ' KB' : '—' }}</td>
                </ng-container>
                <ng-container matColumnDef="uploadedAt">
                  <th mat-header-cell *matHeaderCellDef>Date</th>
                  <td mat-cell *matCellDef="let d">{{ d.uploadedAt | date:'shortDate' }}</td>
                </ng-container>
                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef></th>
                  <td mat-cell *matCellDef="let d">
                    @if (d.contentAvailable) {
                      <button mat-icon-button matTooltip="Download" (click)="downloadTermSheet(d)">
                        <mat-icon>download</mat-icon>
                      </button>
                    }
                    <button mat-icon-button matTooltip="Delete" color="warn" (click)="deleteTermSheet(d)">
                      <mat-icon>delete_outline</mat-icon>
                    </button>
                  </td>
                </ng-container>
                <tr mat-header-row *matHeaderRowDef="termSheetColumns"></tr>
                <tr mat-row *matRowDef="let row; columns: termSheetColumns;"></tr>
              </table>
            }
          </div>
        </mat-tab>

        <!-- Mint Control (only if CONTROL level) -->
        @if (asset.onchainLevel === 'CONTROL') {
          <mat-tab label="Mint Control">
            <div class="tab-content">
              <h3 style="font-size:16px;font-weight:500;margin-bottom:16px">Mint Tokens</h3>
              <div class="mint-form">
                <mat-form-field appearance="outline">
                  <mat-label>Recipient Address</mat-label>
                  <input matInput [(ngModel)]="mintAddress" placeholder="0x..." />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Amount</mat-label>
                  <input matInput type="number" [(ngModel)]="mintAmount" min="1" />
                </mat-form-field>
                <div>
                  <button mat-raised-button color="primary" (click)="mintTokens()" [disabled]="!mintAddress || !mintAmount">
                    <mat-icon>add_circle</mat-icon>
                    Mint
                  </button>
                </div>
              </div>

              <mat-divider />

              <h3 style="font-size:16px;font-weight:500;margin:16px 0">Burn Tokens</h3>
              <div class="mint-form">
                <mat-form-field appearance="outline">
                  <mat-label>From Address</mat-label>
                  <input matInput [(ngModel)]="burnAddress" placeholder="0x..." />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Amount</mat-label>
                  <input matInput type="number" [(ngModel)]="burnAmount" min="1" />
                </mat-form-field>
                <div>
                  <button mat-raised-button color="warn" (click)="burnTokens()" [disabled]="!burnAddress || !burnAmount">
                    <mat-icon>remove_circle</mat-icon>
                    Burn
                  </button>
                </div>
              </div>

              <mat-divider />

              <!-- Mint Control Rules -->
              <div style="display:flex;justify-content:space-between;align-items:center;margin:16px 0 12px">
                <h3 style="font-size:16px;font-weight:500;margin:0">Allowance &amp; Auto-Approval Rules</h3>
                <button mat-stroked-button (click)="openAddRuleDialog()">
                  <mat-icon>add</mat-icon> Add Rule
                </button>
              </div>
              @if (rulesLoading) {
                <div class="spinner-wrap"><mat-spinner diameter="28" /></div>
              } @else {
                <table mat-table [dataSource]="mintRules">
                  <ng-container matColumnDef="target">
                    <th mat-header-cell *matHeaderCellDef>Target Address</th>
                    <td mat-cell *matCellDef="let r"><code style="font-size:11px">{{ r.targetAddress }}</code></td>
                  </ng-container>
                  <ng-container matColumnDef="type">
                    <th mat-header-cell *matHeaderCellDef>Type</th>
                    <td mat-cell *matCellDef="let r">{{ r.ruleType }}</td>
                  </ng-container>
                  <ng-container matColumnDef="maxAmount">
                    <th mat-header-cell *matHeaderCellDef>Max Amount</th>
                    <td mat-cell *matCellDef="let r">{{ r.maxAmount ?? '—' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="created">
                    <th mat-header-cell *matHeaderCellDef>Created</th>
                    <td mat-cell *matCellDef="let r">{{ r.createdAt | date:'mediumDate' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="actions">
                    <th mat-header-cell *matHeaderCellDef></th>
                    <td mat-cell *matCellDef="let r">
                      <button mat-icon-button color="warn" [matTooltip]="'Deactivate rule'" (click)="deleteRule(r)">
                        <mat-icon>delete</mat-icon>
                      </button>
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="ruleColumns"></tr>
                  <tr mat-row *matRowDef="let row; columns: ruleColumns;"></tr>
                </table>
                @if (mintRules.length === 0) {
                  <p style="text-align:center;padding:16px;color:rgba(0,0,0,0.54)">No mint control rules defined.</p>
                }
              }
            </div>
          </mat-tab>
        }

        <!-- ── ERC-3643 Mode: T-REX tabs (shown when tokenStandard is ERC3643 or CONF_ERC3643) ── -->
        @if (isErc3643) {
          <!-- T-REX Suite -->
          <mat-tab label="T-REX Suite">
            <div class="tab-content">
              @if (suiteLoading) {
                <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
              } @else if (suite) {
                <div style="display:flex;align-items:center;gap:8px;margin-bottom:16px">
                  <mat-icon style="color:#1a3a5c">verified_user</mat-icon>
                  <strong>T-REX Suite</strong>
                  @if (suite.isConfidential) {
                    <mat-chip color="accent" style="font-size:11px">Confidential (fhEVM)</mat-chip>
                  }
                </div>
                <div class="field-grid">
                  <div class="field-item"><div class="field-label">Token Contract</div><code class="field-value" style="font-size:12px">{{ suite.tokenAddress }}</code></div>
                  <div class="field-item"><div class="field-label">Identity Registry</div><code class="field-value" style="font-size:12px">{{ suite.identityRegistryAddress }}</code></div>
                  <div class="field-item"><div class="field-label">Identity Registry Storage</div><code class="field-value" style="font-size:12px">{{ suite.identityRegistryStorage }}</code></div>
                  <div class="field-item"><div class="field-label">Modular Compliance</div><code class="field-value" style="font-size:12px">{{ suite.complianceAddress }}</code></div>
                  <div class="field-item"><div class="field-label">Claim Topics Registry</div><code class="field-value" style="font-size:12px">{{ suite.claimTopicsRegistry }}</code></div>
                  <div class="field-item"><div class="field-label">Trusted Issuers Registry</div><code class="field-value" style="font-size:12px">{{ suite.trustedIssuersRegistry }}</code></div>
                  <div class="field-item"><div class="field-label">Deployed</div><div class="field-value">{{ suite.createdAt | date:'medium' }}</div></div>
                </div>
              } @else {
                <p style="text-align:center;padding:24px;color:rgba(0,0,0,0.54)">No T-REX suite found for this deployment.</p>
              }
            </div>
          </mat-tab>

          <!-- Identity Registry -->
          <mat-tab label="Identity Registry">
            <div class="tab-content">
              <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
                <strong>Registered Investors</strong>
                <button mat-raised-button color="primary" (click)="openRegisterInvestorDialog()">
                  <mat-icon>person_add</mat-icon> Register Investor
                </button>
              </div>
              @if (irLoading) {
                <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
              } @else {
                <table mat-table [dataSource]="identityRegistry">
                  <ng-container matColumnDef="wallet"><th mat-header-cell *matHeaderCellDef>Wallet</th><td mat-cell *matCellDef="let e"><code style="font-size:11px">{{ e.walletAddress }}</code></td></ng-container>
                  <ng-container matColumnDef="entity"><th mat-header-cell *matHeaderCellDef>Entity</th><td mat-cell *matCellDef="let e">{{ e.entityName || '—' }}</td></ng-container>
                  <ng-container matColumnDef="identity"><th mat-header-cell *matHeaderCellDef>ONCHAINID</th><td mat-cell *matCellDef="let e"><code style="font-size:11px">{{ e.identityAddress }}</code></td></ng-container>
                  <ng-container matColumnDef="country"><th mat-header-cell *matHeaderCellDef>Country</th><td mat-cell *matCellDef="let e">{{ e.countryCode || '—' }}</td></ng-container>
                  <ng-container matColumnDef="verified">
                    <th mat-header-cell *matHeaderCellDef>Verified</th>
                    <td mat-cell *matCellDef="let e">
                      <mat-icon [style.color]="e.verified ? 'green' : 'red'">{{ e.verified ? 'check_circle' : 'cancel' }}</mat-icon>
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="actions">
                    <th mat-header-cell *matHeaderCellDef></th>
                    <td mat-cell *matCellDef="let e">
                      <button mat-icon-button color="warn" (click)="removeInvestor(e)" [matTooltip]="'Remove from IdentityRegistry'">
                        <mat-icon>person_remove</mat-icon>
                      </button>
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="irColumns"></tr>
                  <tr mat-row *matRowDef="let row; columns: irColumns;"></tr>
                </table>
                @if (identityRegistry.length === 0) {
                  <p style="text-align:center;padding:24px;color:rgba(0,0,0,0.54)">No investors registered yet.</p>
                }
              }
            </div>
          </mat-tab>

          <!-- Compliance -->
          <mat-tab label="Compliance">
            <div class="tab-content">
              @if (complianceLoading) {
                <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
              } @else if (complianceStatus) {
                <div class="field-grid" style="margin-bottom:24px">
                  <div class="field-item">
                    <div class="field-label">Investors</div>
                    <div class="field-value">
                      {{ complianceStatus.investorCount }}
                      @if (complianceStatus.maxInvestors) { / {{ complianceStatus.maxInvestors }} max }
                      @else { (no limit) }
                    </div>
                  </div>
                  <div class="field-item">
                    <div class="field-label">Max Balance Per Investor</div>
                    <div class="field-value">{{ complianceStatus.maxBalance || 'No limit' }}</div>
                  </div>
                  <div class="field-item">
                    <div class="field-label">Transfer Cooldown</div>
                    <div class="field-value">{{ complianceStatus.transferCooldown ? (complianceStatus.transferCooldown + 's') : 'Disabled' }}</div>
                  </div>
                  <div class="field-item">
                    <div class="field-label">Blocked Countries</div>
                    <div class="field-value">
                      @if (complianceStatus.blockedCountries.length === 0) { None }
                      @else { {{ complianceStatus.blockedCountries.join(', ') }} }
                    </div>
                  </div>
                </div>
                <h4 style="font-size:14px;font-weight:500;margin-bottom:8px">Active Modules</h4>
                <table mat-table [dataSource]="complianceStatus.modules">
                  <ng-container matColumnDef="type"><th mat-header-cell *matHeaderCellDef>Type</th><td mat-cell *matCellDef="let m">{{ m.moduleType }}</td></ng-container>
                  <ng-container matColumnDef="address"><th mat-header-cell *matHeaderCellDef>Address</th><td mat-cell *matCellDef="let m"><code style="font-size:11px">{{ m.moduleAddress }}</code></td></ng-container>
                  <tr mat-header-row *matHeaderRowDef="['type','address']"></tr>
                  <tr mat-row *matRowDef="let row; columns: ['type','address'];"></tr>
                </table>
              }

              <!-- Admin Actions -->
              <mat-divider style="margin:24px 0" />
              <h4 style="font-size:14px;font-weight:500;margin-bottom:12px">Regulatory Actions</h4>
              <div style="display:flex;flex-direction:column;gap:16px;max-width:480px">
                <!-- Pause / Unpause -->
                <div style="display:flex;gap:8px">
                  <button mat-stroked-button color="warn" (click)="pauseToken()">
                    <mat-icon>pause_circle</mat-icon> Pause All Transfers
                  </button>
                  <button mat-stroked-button color="primary" (click)="unpauseToken()">
                    <mat-icon>play_circle</mat-icon> Unpause Transfers
                  </button>
                </div>
                <!-- Freeze -->
                <div class="mint-form">
                  <div class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>Address to Freeze/Unfreeze</mat-label>
                      <input matInput [(ngModel)]="freezeAddress" placeholder="0x..." />
                    </mat-form-field>
                    <button mat-stroked-button color="warn" [disabled]="!freezeAddress" (click)="freezeAddr()" style="height:56px">Freeze</button>
                    <button mat-stroked-button color="primary" [disabled]="!freezeAddress" (click)="unfreezeAddr()" style="height:56px">Unfreeze</button>
                  </div>
                </div>
                <!-- Forced Transfer -->
                <div class="mint-form">
                  <p style="font-size:12px;color:rgba(0,0,0,0.54);margin:0 0 4px">Forced Transfer (§ eWpG / MiCAR Art. 36)</p>
                  <mat-form-field appearance="outline">
                    <mat-label>From Address</mat-label>
                    <input matInput [(ngModel)]="forceFrom" placeholder="0x..." />
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>To Address</mat-label>
                    <input matInput [(ngModel)]="forceTo" placeholder="0x..." />
                  </mat-form-field>
                  <div class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>Amount</mat-label>
                      <input matInput [(ngModel)]="forceAmount" placeholder="0" />
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                      <mat-label>Legal Basis / Reason</mat-label>
                      <input matInput [(ngModel)]="forceReason" placeholder="AWG §17" />
                    </mat-form-field>
                  </div>
                  <div>
                    <button mat-raised-button color="warn"
                            [disabled]="!forceFrom || !forceTo || !forceAmount"
                            (click)="executeForceTransfer()">
                      <mat-icon>swap_horiz</mat-icon> Execute Forced Transfer
                    </button>
                  </div>
                </div>
                <!-- Force Burn -->
                <div class="mint-form">
                  <p style="font-size:12px;color:rgba(0,0,0,0.54);margin:0 0 4px">Force Burn (eWpG §26 / MiCAR Art. 94)</p>
                  <div class="form-row">
                    <mat-form-field appearance="outline">
                      <mat-label>From Address</mat-label>
                      <input matInput [(ngModel)]="forceBurnFrom" placeholder="0x..." />
                    </mat-form-field>
                    <mat-form-field appearance="outline">
                      <mat-label>Amount</mat-label>
                      <input matInput [(ngModel)]="forceBurnAmount" placeholder="0" />
                    </mat-form-field>
                  </div>
                  <mat-form-field appearance="outline">
                    <mat-label>Legal Basis</mat-label>
                    <input matInput [(ngModel)]="forceBurnLegalBasis" placeholder="eWpG §26" />
                  </mat-form-field>
                  <div>
                    <button mat-raised-button color="warn"
                            [disabled]="!forceBurnFrom || !forceBurnAmount"
                            (click)="executeForceBurn()">
                      <mat-icon>local_fire_department</mat-icon> Force Burn
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </mat-tab>

          <!-- Trusted Issuers -->
          <mat-tab label="Trusted Issuers">
            <div class="tab-content">
              <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
                <strong>Trusted Claim Issuers</strong>
                <button mat-raised-button color="primary" (click)="openAddIssuerDialog()">
                  <mat-icon>add</mat-icon> Add Issuer
                </button>
              </div>
              @if (issuersLoading) {
                <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
              } @else {
                <table mat-table [dataSource]="trustedIssuers">
                  <ng-container matColumnDef="address"><th mat-header-cell *matHeaderCellDef>Issuer Address</th><td mat-cell *matCellDef="let i"><code style="font-size:11px">{{ i.issuerAddress }}</code></td></ng-container>
                  <ng-container matColumnDef="topics"><th mat-header-cell *matHeaderCellDef>Claim Topics</th><td mat-cell *matCellDef="let i">{{ i.claimTopics.join(', ') }}</td></ng-container>
                  <ng-container matColumnDef="added"><th mat-header-cell *matHeaderCellDef>Added</th><td mat-cell *matCellDef="let i">{{ i.addedAt | date:'mediumDate' }}</td></ng-container>
                  <ng-container matColumnDef="actions"><th mat-header-cell *matHeaderCellDef></th><td mat-cell *matCellDef="let i"><button mat-icon-button color="warn" (click)="removeIssuer(i)"><mat-icon>delete</mat-icon></button></td></ng-container>
                  <tr mat-header-row *matHeaderRowDef="issuerColumns"></tr>
                  <tr mat-row *matRowDef="let row; columns: issuerColumns;"></tr>
                </table>
                @if (trustedIssuers.length === 0) {
                  <p style="text-align:center;padding:24px;color:rgba(0,0,0,0.54)">No trusted issuers registered.</p>
                }
              }
            </div>
          </mat-tab>

          <!-- Claim Topics -->
          <mat-tab label="Claim Topics">
            <div class="tab-content">
              <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
                <strong>Required Claim Topics</strong>
                <button mat-raised-button color="primary" (click)="openAddClaimTopicDialog()">
                  <mat-icon>add</mat-icon> Add Topic
                </button>
              </div>
              @if (topicsLoading) {
                <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
              } @else {
                <table mat-table [dataSource]="claimTopics">
                  <ng-container matColumnDef="topic"><th mat-header-cell *matHeaderCellDef>Topic #</th><td mat-cell *matCellDef="let t">{{ t.topic }}</td></ng-container>
                  <ng-container matColumnDef="label"><th mat-header-cell *matHeaderCellDef>Label</th><td mat-cell *matCellDef="let t">{{ t.label }}</td></ng-container>
                  <tr mat-header-row *matHeaderRowDef="['topic','label']"></tr>
                  <tr mat-row *matRowDef="let row; columns: ['topic','label'];"></tr>
                </table>
                @if (claimTopics.length === 0) {
                  <p style="text-align:center;padding:24px;color:rgba(0,0,0,0.54)">No claim topics required.</p>
                }
              }
            </div>
          </mat-tab>
        }
      </mat-tab-group>
    } @else {
      <p class="text-muted">Asset not found.</p>
    }
  `,
})
export class AssetDetailComponent implements OnInit {
  @Input() id!: string;

  private readonly assetService = inject(AssetService);
  private readonly erc3643Service = inject(Erc3643Service);
  private readonly mintControlService = inject(MintControlService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  loading = true;
  deploymentsLoading = false;
  holdersLoading = false;

  asset: Asset | null = null;
  deployments: AssetDeployment[] = [];
  holders: AssetHolder[] = [];

  readonly deploymentColumns = ['chain', 'network', 'contractAddress', 'deploymentStatus', 'deployedAt'];
  readonly holderColumns = ['address', 'balance', 'percentage'];

  mintAddress = '';
  mintAmount: number | null = null;
  burnAddress = '';
  burnAmount: number | null = null;

  // ── KYC Compliance ────────────────────────────────────────────────────────
  kycCompliance: KycComplianceResponse | null = null;
  kycComplianceLoading = false;

  // ── Term Sheet ────────────────────────────────────────────────────────────
  tsLoading = false;
  tsUploading = false;
  tsSyncing = false;
  termSheetDocs: AssetDocument[] = [];
  readonly termSheetColumns = ['type', 'source', 'fileName', 'mimeType', 'sizeBytes', 'uploadedAt', 'actions'];

  // ── Mint Control Rules ────────────────────────────────────────────────────
  rulesLoading = false;
  mintRules: MintControlRule[] = [];
  newRuleAddress = '';
  newRuleType: RuleType = 'MINT_ALLOWANCE';
  newRuleMax = '';
  readonly ruleColumns = ['target', 'type', 'maxAmount', 'created', 'actions'];

  // ── ERC-3643 state ────────────────────────────────────────────────────────
  get isErc3643(): boolean {
    return this.asset?.tokenStandard === 'ERC3643' || this.asset?.tokenStandard === 'CONF_ERC3643';
  }

  private get primaryDeploymentId(): string | null {
    return this.deployments[0]?.id ?? null;
  }

  suiteLoading = false;
  irLoading = false;
  complianceLoading = false;
  issuersLoading = false;
  topicsLoading = false;

  suite: Erc3643Suite | null = null;
  identityRegistry: IdentityRegistryEntry[] = [];
  complianceStatus: ComplianceStatus | null = null;
  trustedIssuers: TrustedIssuer[] = [];
  claimTopics: ClaimTopic[] = [];

  readonly irColumns = ['wallet', 'entity', 'identity', 'country', 'verified', 'actions'];
  readonly issuerColumns = ['address', 'topics', 'added', 'actions'];

  // ── Admin / regulatory action fields ─────────────────────────────────────
  freezeAddress = '';
  forceFrom = '';
  forceTo = '';
  forceAmount = '';
  forceReason = '';
  forceBurnFrom = '';
  forceBurnAmount = '';
  forceBurnLegalBasis = '';

  ngOnInit(): void {
    this.loadAsset();
  }

  loadAsset(): void {
    this.loading = true;
    this.assetService.getAsset(this.id).subscribe({
      next: (asset) => {
        this.asset = asset;
        this.loading = false;
        this.loadDeployments();
        this.loadHolders();
        this.loadTermSheetDocs();
        if (asset.status === 'PENDING_APPROVAL' && asset.jurisdiction) {
          this.loadKycCompliance(asset.id);
        }
      },
      error: () => { this.loading = false; },
    });
  }

  loadDeployments(): void {
    this.deploymentsLoading = true;
    this.assetService.getDeployments(this.id).subscribe({
      next: (d) => {
        this.deployments = d;
        this.deploymentsLoading = false;
        if (d.length > 0) {
          if (this.asset?.onchainLevel === 'CONTROL') {
            this.loadMintRules(d[0].id);
          }
          if (this.isErc3643) {
            this.loadErc3643Data(d[0].id);
          }
        }
      },
      error: () => { this.deploymentsLoading = false; },
    });
  }

  // ── Mint Control Rules ────────────────────────────────────────────────────

  loadMintRules(deploymentId: string): void {
    this.rulesLoading = true;
    this.mintControlService.listRules(this.id, deploymentId).subscribe({
      next: (rules) => { this.mintRules = rules; this.rulesLoading = false; },
      error: () => { this.rulesLoading = false; },
    });
  }

  openAddRuleDialog(): void {
    const depId = this.primaryDeploymentId;
    if (!depId) return;
    const address = prompt('Target address (0x... wallet or contract):');
    if (!address) return;
    const typeInput = prompt('Rule type: MINT_ALLOWANCE, AUTO_APPROVE_TRANSFER, or AUTO_APPROVE_BURN');
    const ruleType = typeInput as RuleType;
    if (!['MINT_ALLOWANCE', 'AUTO_APPROVE_TRANSFER', 'AUTO_APPROVE_BURN'].includes(ruleType)) {
      this.snackBar.open('Invalid rule type.', 'OK', { duration: 3000 });
      return;
    }
    const maxAmountStr = prompt('Max amount (leave empty for unlimited):');
    this.mintControlService.createRule(this.id, depId, {
      targetAddress: address,
      ruleType,
      maxAmount: maxAmountStr || undefined,
    }).subscribe({
      next: (rule) => {
        this.mintRules = [...this.mintRules, rule];
        this.snackBar.open('Rule created.', 'OK', { duration: 2000 });
      },
    });
  }

  deleteRule(rule: MintControlRule): void {
    const depId = this.primaryDeploymentId;
    if (!depId || !confirm(`Deactivate rule for ${rule.targetAddress}?`)) return;
    this.mintControlService.deleteRule(this.id, depId, rule.id).subscribe({
      next: () => {
        this.mintRules = this.mintRules.filter(r => r.id !== rule.id);
        this.snackBar.open('Rule deactivated.', 'OK', { duration: 2000 });
      },
    });
  }

  // ── ERC-3643 loaders ──────────────────────────────────────────────────────

  loadErc3643Data(deploymentId: string): void {
    this.loadSuite(deploymentId);
    this.loadIdentityRegistry(deploymentId);
    this.loadComplianceStatus(deploymentId);
    this.loadTrustedIssuers(deploymentId);
    this.loadClaimTopics(deploymentId);
  }

  loadSuite(deploymentId: string): void {
    this.suiteLoading = true;
    this.erc3643Service.getSuite(this.id, deploymentId).subscribe({
      next: (s) => { this.suite = s; this.suiteLoading = false; },
      error: () => { this.suiteLoading = false; },
    });
  }

  loadIdentityRegistry(deploymentId: string): void {
    this.irLoading = true;
    this.erc3643Service.getIdentityRegistry(this.id, deploymentId).subscribe({
      next: (entries) => { this.identityRegistry = entries; this.irLoading = false; },
      error: () => { this.irLoading = false; },
    });
  }

  loadComplianceStatus(deploymentId: string): void {
    this.complianceLoading = true;
    this.erc3643Service.getComplianceStatus(this.id, deploymentId).subscribe({
      next: (s) => { this.complianceStatus = s; this.complianceLoading = false; },
      error: () => { this.complianceLoading = false; },
    });
  }

  loadTrustedIssuers(deploymentId: string): void {
    this.issuersLoading = true;
    this.erc3643Service.getTrustedIssuers(this.id, deploymentId).subscribe({
      next: (issuers) => { this.trustedIssuers = issuers; this.issuersLoading = false; },
      error: () => { this.issuersLoading = false; },
    });
  }

  loadClaimTopics(deploymentId: string): void {
    this.topicsLoading = true;
    this.erc3643Service.getClaimTopics(this.id, deploymentId).subscribe({
      next: (topics) => { this.claimTopics = topics; this.topicsLoading = false; },
      error: () => { this.topicsLoading = false; },
    });
  }

  // ── ERC-3643 actions ──────────────────────────────────────────────────────

  openRegisterInvestorDialog(): void {
    const depId = this.primaryDeploymentId;
    if (!depId) return;
    this.dialog.open(RegisterInvestorDialogComponent, { width: '440px' })
      .afterClosed()
      .subscribe((data: RegisterInvestorData | undefined) => {
        if (!data) return;
        this.erc3643Service.registerInvestor(this.id, depId, {
          walletAddress: data.walletAddress,
          legalEntityId: data.legalEntityId,
          chainConfigId: data.chainConfigId,
          countryCode: data.countryCode,
        }).subscribe({
          next: () => {
            this.loadIdentityRegistry(depId);
            this.snackBar.open('Investor registered.', 'OK', { duration: 2000 });
          },
        });
      });
  }

  removeInvestor(entry: IdentityRegistryEntry): void {
    const depId = this.primaryDeploymentId;
    if (!depId || !confirm(`Remove ${entry.walletAddress} from IdentityRegistry?`)) return;
    this.erc3643Service.removeInvestor(this.id, depId, entry.id).subscribe({
      next: () => {
        this.loadIdentityRegistry(depId);
        this.snackBar.open('Investor removed.', 'OK', { duration: 2000 });
      },
    });
  }

  openAddIssuerDialog(): void {
    const depId = this.primaryDeploymentId;
    if (!depId) return;
    this.dialog.open(AddIssuerDialogComponent, { width: '440px' })
      .afterClosed()
      .subscribe((data: AddIssuerData | undefined) => {
        if (!data) return;
        this.erc3643Service.addTrustedIssuer(this.id, depId, data).subscribe({
          next: () => {
            this.loadTrustedIssuers(depId);
            this.snackBar.open('Trusted issuer added.', 'OK', { duration: 2000 });
          },
        });
      });
  }

  removeIssuer(issuer: TrustedIssuer): void {
    const depId = this.primaryDeploymentId;
    if (!depId || !confirm(`Remove issuer ${issuer.issuerAddress}?`)) return;
    this.erc3643Service.removeTrustedIssuer(this.id, depId, issuer.id).subscribe({
      next: () => {
        this.trustedIssuers = this.trustedIssuers.filter(i => i.id !== issuer.id);
        this.snackBar.open('Trusted issuer removed.', 'OK', { duration: 2000 });
      },
    });
  }

  openAddClaimTopicDialog(): void {
    const depId = this.primaryDeploymentId;
    if (!depId) return;
    this.dialog.open(AddClaimTopicDialogComponent, { width: '360px' })
      .afterClosed()
      .subscribe((data: AddClaimTopicData | undefined) => {
        if (!data) return;
        this.erc3643Service.addClaimTopic(this.id, depId, data).subscribe({
          next: () => {
            this.loadClaimTopics(depId);
            this.snackBar.open('Claim topic added.', 'OK', { duration: 2000 });
          },
        });
      });
  }

  // ── Regulatory admin actions ──────────────────────────────────────────────

  pauseToken(): void {
    const depId = this.primaryDeploymentId;
    if (!depId || !confirm('Pause all token transfers?')) return;
    this.erc3643Service.pause(this.id, depId).subscribe({
      next: () => this.snackBar.open('Token transfers paused.', 'OK', { duration: 3000 }),
    });
  }

  unpauseToken(): void {
    const depId = this.primaryDeploymentId;
    if (!depId) return;
    this.erc3643Service.unpause(this.id, depId).subscribe({
      next: () => this.snackBar.open('Token transfers resumed.', 'OK', { duration: 3000 }),
    });
  }

  freezeAddr(): void {
    const depId = this.primaryDeploymentId;
    if (!depId || !this.freezeAddress) return;
    this.erc3643Service.freezeAddress(this.id, depId, this.freezeAddress).subscribe({
      next: () => {
        this.snackBar.open(`Address ${this.freezeAddress} frozen.`, 'OK', { duration: 3000 });
        this.freezeAddress = '';
      },
    });
  }

  unfreezeAddr(): void {
    const depId = this.primaryDeploymentId;
    if (!depId || !this.freezeAddress) return;
    this.erc3643Service.unfreezeAddress(this.id, depId, this.freezeAddress).subscribe({
      next: () => {
        this.snackBar.open(`Address ${this.freezeAddress} unfrozen.`, 'OK', { duration: 3000 });
        this.freezeAddress = '';
      },
    });
  }

  executeForceTransfer(): void {
    const depId = this.primaryDeploymentId;
    if (!depId || !this.forceFrom || !this.forceTo || !this.forceAmount) return;
    if (!confirm(`Execute forced transfer of ${this.forceAmount} from ${this.forceFrom} to ${this.forceTo}?`)) return;
    this.erc3643Service.forcedTransfer(this.id, depId, {
      from: this.forceFrom, to: this.forceTo,
      amount: this.forceAmount, reason: this.forceReason,
    }).subscribe({
      next: () => {
        this.snackBar.open('Forced transfer initiated.', 'OK', { duration: 3000 });
        this.forceFrom = ''; this.forceTo = ''; this.forceAmount = ''; this.forceReason = '';
        this.loadHolders();
      },
    });
  }

  executeForceBurn(): void {
    const depId = this.primaryDeploymentId;
    if (!depId || !this.forceBurnFrom || !this.forceBurnAmount) return;
    if (!confirm(`Force burn ${this.forceBurnAmount} from ${this.forceBurnFrom}?`)) return;
    this.erc3643Service.forceBurn(this.id, depId, {
      from: this.forceBurnFrom, amount: this.forceBurnAmount, legalBasis: this.forceBurnLegalBasis,
    }).subscribe({
      next: () => {
        this.snackBar.open('Force burn initiated.', 'OK', { duration: 3000 });
        this.forceBurnFrom = ''; this.forceBurnAmount = ''; this.forceBurnLegalBasis = '';
        this.loadHolders();
      },
    });
  }

  // ── Standard asset actions ────────────────────────────────────────────────

  loadHolders(): void {
    this.holdersLoading = true;
    this.assetService.getHolders(this.id).subscribe({
      next: (h) => { this.holders = h; this.holdersLoading = false; },
      error: () => { this.holdersLoading = false; },
    });
  }

  approve(): void {
    this.assetService.approveAsset(this.id).subscribe({ next: () => this.loadAsset() });
  }

  issue(): void {
    this.assetService.issueAsset(this.id).subscribe({ next: () => this.loadAsset() });
  }

  suspend(): void {
    this.assetService.suspendAsset(this.id).subscribe({ next: () => this.loadAsset() });
  }

  redeem(): void {
    if (!confirm('Redeem this asset? This is a final action.')) return;
    this.assetService.redeemAsset(this.id).subscribe({ next: () => this.loadAsset() });
  }

  mintTokens(): void {
    if (!this.mintAddress || !this.mintAmount) return;
    this.assetService.mint(this.id, { toAddress: this.mintAddress, amount: this.mintAmount }).subscribe({
      next: () => {
        this.mintAddress = '';
        this.mintAmount = null;
        this.loadHolders();
      },
    });
  }

  burnTokens(): void {
    if (!this.burnAddress || !this.burnAmount) return;
    this.assetService.burn(this.id, { fromAddress: this.burnAddress, amount: this.burnAmount }).subscribe({
      next: () => {
        this.burnAddress = '';
        this.burnAmount = null;
        this.loadHolders();
      },
    });
  }

  // ── KYC Compliance ────────────────────────────────────────────────────────

  loadKycCompliance(assetId: string): void {
    this.kycComplianceLoading = true;
    this.assetService.getKycCompliance(assetId).subscribe({
      next: (c) => { this.kycCompliance = c; this.kycComplianceLoading = false; },
      error: () => { this.kycComplianceLoading = false; },
    });
  }

  jurisdictionLabel(jur: string): string {
    const labels: Record<string, string> = {
      DE_EWPG: 'DE — eWpG / BaFin',
      LU_CSSF: 'LU — CSSF',
      FR_AMF: 'FR — AMF',
      LI_TVTG: 'LI — TVTG / FMA',
    };
    return labels[jur] ?? jur;
  }

  // ── Term Sheet ────────────────────────────────────────────────────────────

  loadTermSheetDocs(): void {
    this.tsLoading = true;
    this.assetService.listDocuments(this.id).subscribe({
      next: docs => { this.termSheetDocs = docs; this.tsLoading = false; },
      error: () => { this.tsLoading = false; },
    });
  }

  onTermSheetFileSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.tsUploading = true;
    this.assetService.uploadDocument(this.id, file).subscribe({
      next: doc => {
        this.termSheetDocs = [doc, ...this.termSheetDocs];
        this.tsUploading = false;
        if (this.asset) this.asset.hasTermSheet = true;
        this.snackBar.open('Term sheet uploaded.', 'OK', { duration: 3000 });
      },
      error: () => {
        this.tsUploading = false;
        this.snackBar.open('Upload failed.', 'Close', { duration: 4000 });
      },
    });
  }

  syncTermSheetFromChain(): void {
    const depId = this.primaryDeploymentId;
    if (!depId) return;
    this.tsSyncing = true;
    this.assetService.syncFromChain(this.id, depId).subscribe({
      next: doc => {
        this.termSheetDocs = [doc, ...this.termSheetDocs];
        this.tsSyncing = false;
        if (this.asset) this.asset.hasTermSheet = true;
        this.snackBar.open('Term sheet synced from chain.', 'OK', { duration: 3000 });
      },
      error: () => {
        this.tsSyncing = false;
        this.snackBar.open('Sync failed.', 'Close', { duration: 4000 });
      },
    });
  }

  downloadTermSheet(doc: AssetDocument): void {
    this.assetService.downloadDocument(this.id, doc.id).subscribe(blob => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = doc.fileName ?? 'term_sheet';
      a.click();
      URL.revokeObjectURL(url);
    });
  }

  deleteTermSheet(doc: AssetDocument): void {
    if (!confirm('Delete this document?')) return;
    this.assetService.deleteDocument(this.id, doc.id).subscribe(() => {
      this.termSheetDocs = this.termSheetDocs.filter(d => d.id !== doc.id);
      if (this.termSheetDocs.length === 0 && this.asset) this.asset.hasTermSheet = false;
      this.snackBar.open('Document deleted.', 'OK', { duration: 2000 });
    });
  }

  edit(): void {
    this.router.navigate(['/assets', this.id, 'edit']);
  }

  goBack(): void {
    this.router.navigate(['/assets']);
  }
}
