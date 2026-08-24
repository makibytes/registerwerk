import { ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject } from '@angular/core';
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
import { firstValueFrom } from 'rxjs';
import { numberToHex } from 'viem';
import { IssuanceService } from '../../../core/api/issuance.service';
import { TransactionService } from '../../../core/api/transaction.service';
import { RegisterDocumentService } from '../../../core/api/register-document.service';
import { BondTermsService } from '../../../core/api/bond-terms.service';
import { CorporateActionsService, ProposeCorporateActionRequest } from '../../../core/api/corporate-actions.service';
import { AuthService } from '../../../core/auth/auth.service';
import { Erc3643Service, ComplianceStatus, IdentityRegistryEntry } from '../../../core/api/erc3643.service';
import { Asset, AssetBondTerms, AssetDeployment, AssetDocument, AssetHolder, Chain, CorporateActionType, CorporateActionView, Network } from '../../../core/models';
import { WalletService } from '../../../core/wallet/wallet.service';
import { FheClientService } from '../../../core/fhe/fhe-client.service';
import { downloadBlob } from '../../../core/utils/download.util';

/** Minimal ABI fragment for reading a confidential balance handle — see
 *  `investment-detail.component.ts`'s identical fragment for the fuller rationale. */
const CONFIDENTIAL_BALANCE_ABI = [
  {
    name: 'confidentialBalanceOf', type: 'function', stateMutability: 'view',
    inputs: [{ name: 'account', type: 'address' }],
    outputs: [{ name: '', type: 'uint256' }],
  },
] as const;
import {
  AsyncSection,
  beginAsyncSection,
  createAsyncSection,
  failAsyncSection,
  resolveAsyncSection,
} from '../../../core/async/async-section';
import { StatusBadgeComponent, DataStatePillComponent } from '@registerwerk/ui';
import { ChainIconComponent } from '../../../shared/components/chain-icon/chain-icon.component';

import { AddHolderDialogComponent } from './add-holder-dialog.component';
import { HolderTableComponent } from '../../../shared/components/token-holders/holder-table.component';
import { HolderDistributionComponent } from '../../../shared/components/token-holders/holder-distribution.component';
import { TokenAdminPanelComponent } from '../../../shared/components/token-holders/token-admin-panel.component';
import { AddressComponent } from '../../../shared/components/address.component';
import { ExternalIdEditorComponent } from '../../../shared/components/external-id-editor.component';
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
    ExternalIdEditorComponent,
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
                <button type="button"
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
                <button type="button"
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
                <button type="button" mat-raised-button color="primary" (click)="openAddHolder()">
                  <mat-icon>person_add</mat-icon>
                  Add Holder
                </button>
              }
              @if (isIssuer) {
                <button type="button" mat-stroked-button [disabled]="downloadingRegisterExtract" (click)="downloadRegisterExtract()"
                        matTooltip="§ 10 eWpG register extract for this asset's full holder list">
                  <mat-icon>gavel</mat-icon>
                  @if (downloadingRegisterExtract) { Preparing… } @else { Register extract (§10) }
                </button>
              }
            </div>

            <div class="external-id-panel">
              <app-external-id-editor
                [subjectType]="'ASSET'"
                [subjectId]="asset.id"
                [value]="asset.externalId"
                label="Issuance external ID"
                placeholder="Your internal issuance ID"
                (valueChange)="asset.externalId = $event"
              />
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
            @if (deploymentsState.status === 'error') {
              <p class="confidential-error" role="alert">
                Deployments could not be loaded.
                <button mat-button type="button" (click)="retryDeployments()">Retry</button>
              </p>
            } @else if (deployments.length === 0) {
              <p class="empty-text">No deployments yet.</p>
            } @else {
              <div class="table-wrap">
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
                      <app-address [address]="d.contractAddress" />
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
              </div>
            }
          </mat-card-content>
        </mat-card>

        <!-- ── Bond Terms ───────────────────────────────────────────────── -->
        @if (bondTerms) {
          <mat-card class="section-card">
            <mat-card-header>
              <mat-card-title>Bond Terms</mat-card-title>
            </mat-card-header>
            <mat-card-content>
              <div class="bond-terms-grid">
                <div><span class="bt-label">Face value</span><span class="bt-value">{{ bondTerms.faceValue | number }} {{ bondTerms.currencyIso }}</span></div>
                <div><span class="bt-label">Issue date</span><span class="bt-value">{{ bondTerms.issueDate }}</span></div>
                <div><span class="bt-label">Maturity date</span><span class="bt-value">{{ bondTerms.maturityDate }}</span></div>
                @if (bondTerms.couponRate !== null) {
                  <div><span class="bt-label">Coupon rate</span><span class="bt-value">{{ bondTerms.couponRate | percent:'1.2-4' }}</span></div>
                }
                @if (bondTerms.referenceRate) {
                  <div><span class="bt-label">Reference rate</span><span class="bt-value">{{ bondTerms.referenceRate }}{{ bondTerms.spread !== null ? ' + ' + (bondTerms.spread | percent:'1.2-4') : '' }}</span></div>
                }
                <div><span class="bt-label">Payment frequency</span><span class="bt-value">{{ bondTerms.paymentFrequency.replace('_', ' ') }}</span></div>
                <div><span class="bt-label">Day count</span><span class="bt-value">{{ formatEnum(bondTerms.dayCount) }}</span></div>
                <div><span class="bt-label">Issue price</span><span class="bt-value">{{ bondTerms.issuePrice | percent:'1.0-2' }} of face value</span></div>
                <div><span class="bt-label">Callable</span><span class="bt-value">{{ bondTerms.callable ? 'Yes' : 'No' }}</span></div>
                <div><span class="bt-label">Status</span><span class="bt-value">{{ bondTerms.bondStatus }}</span></div>
              </div>
              @if (bondTerms.callable && bondTerms.callSchedule && bondTerms.callSchedule.length > 0) {
                <div class="call-schedule">
                  <span class="bt-label">Call schedule</span>
                  <ul class="call-schedule-list">
                    @for (entry of bondTerms.callSchedule; track $index) {
                      <li>{{ entry.callDate }} — {{ entry.callPrice | number:'1.0-4' }}% of face value</li>
                    }
                  </ul>
                </div>
              }
            </mat-card-content>
          </mat-card>
        }

        <!-- ── Corporate Actions ────────────────────────────────────────────── -->
        <mat-card class="section-card">
          <mat-card-header>
            <mat-card-title>Corporate Actions</mat-card-title>
            <app-data-state-pill [status]="corporateActionsState.status" />
          </mat-card-header>
          <mat-card-content>
            <button mat-stroked-button type="button" color="primary" style="margin-bottom:12px"
                    (click)="openProposeDialog()">
              <mat-icon>add_circle_outline</mat-icon>
              Propose a corporate action
            </button>
            @if (corporateActionsState.status === 'error') {
              <p class="confidential-error" role="alert">
                Corporate actions could not be loaded.
                <button mat-button type="button" (click)="loadCorporateActions()">Retry</button>
              </p>
            } @else if (corporateActionsState.data.length > 0) {
              <div class="table-wrap">
                <table mat-table [dataSource]="corporateActionsState.data" class="mat-elevation-z0">
                  <ng-container matColumnDef="type">
                    <th mat-header-cell *matHeaderCellDef>Type</th>
                    <td mat-cell *matCellDef="let a">{{ formatEnum(a.actionType) }}</td>
                  </ng-container>
                  <ng-container matColumnDef="status">
                    <th mat-header-cell *matHeaderCellDef>Status</th>
                    <td mat-cell *matCellDef="let a">{{ formatEnum(a.status) }}</td>
                  </ng-container>
                  <ng-container matColumnDef="paymentDate">
                    <th mat-header-cell *matHeaderCellDef>Payment date</th>
                    <td mat-cell *matCellDef="let a">{{ a.paymentDate || '—' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="progress">
                    <th mat-header-cell *matHeaderCellDef>Settlement progress</th>
                    <td mat-cell *matCellDef="let a">{{ corporateActionProgress(a) }}</td>
                  </ng-container>
                  <ng-container matColumnDef="actions">
                    <th mat-header-cell *matHeaderCellDef></th>
                    <td mat-cell *matCellDef="let a">
                      @if (a.status === 'PROPOSED') {
                        <button mat-button type="button" (click)="withdrawProposal(a)">Withdraw</button>
                      }
                      @if (isPreSettlement(a) && !a.issuerAttestedAt) {
                        <button mat-button type="button" color="primary" (click)="openAttestDialog(a)">
                          Attest settlement
                        </button>
                      }
                    </td>
                  </ng-container>

                  <tr mat-header-row *matHeaderRowDef="corporateActionColumns"></tr>
                  <tr mat-row *matRowDef="let r; columns: corporateActionColumns;"></tr>
                </table>
              </div>
            } @else {
              <p class="empty-text">
                @if (corporateActionsState.status === 'pending') { Corporate actions are still loading. }
                @else { No corporate actions for this asset yet. }
              </p>
            }
          </mat-card-content>
        </mat-card>

        <ng-template #proposeDialogTpl>
          <h2 mat-dialog-title>Propose a Corporate Action</h2>
          <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
            <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
              Your proposal starts as a draft an operator must review before it joins the register.
            </p>
            <mat-form-field appearance="outline">
              <mat-label>Type</mat-label>
              <mat-select [(ngModel)]="proposeForm.actionType">
                <mat-option value="DIVIDEND">Dividend</mat-option>
                <mat-option value="SPLIT">Split</mat-option>
                @if (bondTerms?.callable) {
                  <mat-option value="CALL">Call</mat-option>
                }
              </mat-select>
            </mat-form-field>

            @if (proposeForm.actionType === 'DIVIDEND') {
              <mat-form-field appearance="outline">
                <mat-label>Amount per unit</mat-label>
                <input matInput type="number" step="0.01" [(ngModel)]="proposeForm.amountPerUnit" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Currency</mat-label>
                <input matInput maxlength="3" [(ngModel)]="proposeForm.currency" placeholder="EUR" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Record date</mat-label>
                <input matInput type="date" [(ngModel)]="proposeForm.recordDate" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Payment date</mat-label>
                <input matInput type="date" [(ngModel)]="proposeForm.paymentDate" />
              </mat-form-field>
            }

            @if (proposeForm.actionType === 'SPLIT') {
              <mat-form-field appearance="outline">
                <mat-label>Ratio numerator</mat-label>
                <input matInput type="number" step="1" [(ngModel)]="proposeForm.ratioNumerator" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Ratio denominator</mat-label>
                <input matInput type="number" step="1" [(ngModel)]="proposeForm.ratioDenominator" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Record date</mat-label>
                <input matInput type="date" [(ngModel)]="proposeForm.recordDate" />
              </mat-form-field>
            }

            @if (proposeForm.actionType === 'CALL') {
              @if (bondTerms?.callSchedule && bondTerms!.callSchedule!.length > 0) {
                <mat-form-field appearance="outline">
                  <mat-label>Scheduled call</mat-label>
                  <mat-select [(ngModel)]="proposeForm.callScheduleIndex">
                    <mat-option [value]="null">Custom (not on the schedule)</mat-option>
                    @for (entry of bondTerms!.callSchedule; track $index) {
                      <mat-option [value]="$index">{{ entry.callDate }} — {{ entry.callPrice }}%</mat-option>
                    }
                  </mat-select>
                </mat-form-field>
              }
              @if (proposeForm.callScheduleIndex === null) {
                <mat-form-field appearance="outline">
                  <mat-label>Call date</mat-label>
                  <input matInput type="date" [(ngModel)]="proposeForm.paymentDate" />
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Call price (% of face value)</mat-label>
                  <input matInput type="number" step="0.01" [(ngModel)]="proposeForm.amountPerUnit" />
                </mat-form-field>
              }
            }

            <mat-form-field appearance="outline">
              <mat-label>Notes (optional)</mat-label>
              <textarea matInput rows="2" maxlength="2000" [(ngModel)]="proposeForm.notes"></textarea>
            </mat-form-field>
          </mat-dialog-content>
          <mat-dialog-actions style="justify-content:flex-end;gap:8px">
            <button mat-stroked-button type="button" mat-dialog-close>Cancel</button>
            <button mat-raised-button color="primary" type="button"
                    [disabled]="submittingProposal" (click)="submitPropose()">
              <mat-icon>send</mat-icon>
              Submit proposal
            </button>
          </mat-dialog-actions>
        </ng-template>

        <ng-template #attestDialogTpl>
          <h2 mat-dialog-title>Attest Settlement Readiness</h2>
          <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
            <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
              Confirm the underlying obligation/cash-leg for this corporate action is ready. An
              operator confirmation still follows before settlement executes.
            </p>
            <mat-form-field appearance="outline">
              <mat-label>Attestation reference</mat-label>
              <input matInput maxlength="255" [(ngModel)]="attestForm.attestationReference"
                     placeholder="Payment instruction id, bank reference, …" />
            </mat-form-field>
          </mat-dialog-content>
          <mat-dialog-actions style="justify-content:flex-end;gap:8px">
            <button mat-stroked-button type="button" mat-dialog-close>Cancel</button>
            <button mat-raised-button color="primary" type="button"
                    [disabled]="submittingAttestation || !attestForm.attestationReference.trim()"
                    (click)="submitAttest()">
              <mat-icon>verified</mat-icon>
              Attest
            </button>
          </mat-dialog-actions>
        </ng-template>

        <!-- ── Term Sheet ────────────────────────────────────────────────── -->
        <mat-card class="section-card">
          <mat-card-header>
            <mat-card-title>Term Sheet</mat-card-title>
          </mat-card-header>
          <mat-card-content>
            @if (tsLoading) {
              <div style="padding:16px;text-align:center"><mat-spinner diameter="32"></mat-spinner></div>
            } @else if (termSheetError) {
              <p class="confidential-error" role="alert">
                {{ termSheetError }}
                <button mat-button type="button" (click)="retryTermSheets()">Retry</button>
              </p>
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
                    <button mat-icon-button type="button" matTooltip="Download" (click)="downloadTermSheet(doc)">
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
                  <button mat-stroked-button type="button" (click)="tsFileInput.click()" [disabled]="tsUploading">
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
        @if (isErc3643 && (complianceStatus || complianceError || identityRegistryState.status === 'error')) {
          <mat-card class="section-card trex-card">
            <mat-card-header>
              <mat-card-title>
                <mat-icon class="trex-icon">verified_user</mat-icon>
                T-REX Compliance
              </mat-card-title>
              <app-data-state-pill [status]="identityRegistryState.status" />
            </mat-card-header>
            <mat-card-content>
              @if (complianceError) {
                <p class="confidential-error" role="alert">{{ complianceError }}</p>
              }
              @if (complianceStatus) {
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
                @if (complianceStatus.blockedCountries && complianceStatus.blockedCountries.length) {
                  <div class="compliance-stat">
                    <span class="stat-label">Blocked Countries</span>
                    <span class="stat-value">{{ complianceStatus.blockedCountries.join(', ') }}</span>
                  </div>
                }
              </div>
              @if (complianceStatus.modules && complianceStatus.modules.length) {
                <div class="module-list">
                  <span class="module-list-label">Active Compliance Modules</span>
                  <div class="module-chips">
                    @for (m of complianceStatus.modules; track m.id) {
                      <mat-chip>{{ m.moduleType }}</mat-chip>
                    }
                  </div>
                </div>
              }
              }

              @if (identityRegistryState.status === 'error') {
                <p class="confidential-error" role="alert">
                  Identity registry mappings could not be loaded.
                  <button mat-button type="button" (click)="retryErc3643Data()">Retry</button>
                </p>
              } @else if (identityRegistry.length > 0) {
                <div class="identity-table-wrap">
                  <span class="module-list-label">Identity Registry Mappings</span>
                  <div class="table-wrap">
                  <table mat-table [dataSource]="identityRegistry" class="mat-elevation-z0">
                    <ng-container matColumnDef="wallet">
                      <th mat-header-cell *matHeaderCellDef>Wallet</th>
                      <td mat-cell *matCellDef="let entry">
                        <app-address [address]="entry.walletAddress"></app-address>
                      </td>
                    </ng-container>

                    <ng-container matColumnDef="entity">
                      <th mat-header-cell *matHeaderCellDef>Entity</th>
                      <td mat-cell *matCellDef="let entry">
                        {{ entry.entityName || entry.legalEntityId || '—' }}
                      </td>
                    </ng-container>

                    <ng-container matColumnDef="legalEntityExternalId">
                      <th mat-header-cell *matHeaderCellDef>Entity external ID</th>
                      <td mat-cell *matCellDef="let entry">
                        @if (entry.legalEntityId) {
                          <app-external-id-editor
                            [subjectType]="'LEGAL_ENTITY'"
                            [subjectId]="entry.legalEntityId"
                            [value]="entry.legalEntityExternalId"
                            label="Entity external ID"
                            placeholder="Your customer ID"
                            (valueChange)="entry.legalEntityExternalId = $event"
                          />
                        } @else {
                          —
                        }
                      </td>
                    </ng-container>

                    <ng-container matColumnDef="registryExternalId">
                      <th mat-header-cell *matHeaderCellDef>Registry external ID</th>
                      <td mat-cell *matCellDef="let entry">
                        <app-external-id-editor
                          [subjectType]="'ERC3643_IDENTITY_REGISTRY_ENTRY'"
                          [subjectId]="entry.id"
                          [value]="entry.externalId"
                          label="Identity entry external ID"
                          placeholder="Your identity entry ID"
                          (valueChange)="entry.externalId = $event"
                        />
                      </td>
                    </ng-container>

                    <ng-container matColumnDef="status">
                      <th mat-header-cell *matHeaderCellDef>Status</th>
                      <td mat-cell *matCellDef="let entry">
                        <app-status-badge [status]="entry.verified ? 'APPROVED' : 'PENDING'"></app-status-badge>
                      </td>
                    </ng-container>

                    <tr mat-header-row *matHeaderRowDef="identityRegistryColumns"></tr>
                    <tr mat-row *matRowDef="let row; columns: identityRegistryColumns;"></tr>
                  </table>
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
            @if (holdersState.status === 'error') {
              <p class="confidential-error" role="alert">
                Register holders could not be loaded.
                <button mat-button type="button" (click)="retryHolders()">Retry</button>
              </p>
            } @else if (holders.length === 0) {
              <p class="empty-text">No holders recorded yet.</p>
            } @else {
              <div class="table-wrap">
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
                <ng-container matColumnDef="externalId">
                  <th mat-header-cell *matHeaderCellDef>External ID</th>
                  <td mat-cell *matCellDef="let h">
                    <app-external-id-editor
                      [subjectType]="'ASSET_HOLDER'"
                      [subjectId]="h.id"
                      [value]="h.externalId"
                      label="Holder external ID"
                      placeholder="Your holding ID"
                      (valueChange)="h.externalId = $event"
                    />
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
                        <mat-icon style="color:var(--rw-text-muted);font-size:18px;vertical-align:middle">radio_button_unchecked</mat-icon>
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
                        <mat-icon style="color:var(--rw-text-muted);font-size:18px;vertical-align:middle">help_outline</mat-icon>
                      }
                    </td>
                  </ng-container>
                }

                <tr mat-header-row *matHeaderRowDef="activeHolderColumns"></tr>
                <tr mat-row *matRowDef="let r; columns: activeHolderColumns;"></tr>
              </table>
              </div>
            }
          </mat-card-content>
        </mat-card>

        @if (isConfidential) {
          <mat-card class="section-card confidential-card">
            <mat-card-header>
              <mat-card-title>
                <mat-icon class="confidential-icon">lock</mat-icon>
                Confidential Balances
              </mat-card-title>
            </mat-card-header>
            <mat-card-content>
              <p class="confidential-intro">
                Balances/transfer amounts are encrypted on-chain (Zama fhEVM). As the issuer you're
                a registered viewer — connect your wallet to reveal any holder's on-chain balance
                directly against Zama's relayer and compare it with the register's own nominal
                amount above. Confidential minting is encrypted server-side (there's no browser
                signature needed for issuance).
              </p>

              @if (!walletService.isConnected()) {
                <button mat-stroked-button color="primary" type="button" (click)="connectIssuerWallet()" [disabled]="connectingWallet">
                  <mat-icon>account_balance_wallet</mat-icon>
                  {{ connectingWallet ? 'Connecting…' : 'Connect Viewer Wallet' }}
                </button>
              } @else if (holders.length > 0) {
                <div class="table-wrap">
                <table mat-table [dataSource]="holders" class="mat-elevation-z0">
                  <ng-container matColumnDef="wallet">
                    <th mat-header-cell *matHeaderCellDef>Wallet Address</th>
                    <td mat-cell *matCellDef="let h"><app-address [address]="h.walletAddress" /></td>
                  </ng-container>
                  <ng-container matColumnDef="register">
                    <th mat-header-cell *matHeaderCellDef>Register Amount</th>
                    <td mat-cell *matCellDef="let h">{{ h.nominalAmount | number:'1.0-2' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="onchain">
                    <th mat-header-cell *matHeaderCellDef>On-Chain (decrypted)</th>
                    <td mat-cell *matCellDef="let h">
                      @if (revealedBalances[h.walletAddress]) {
                        <span class="revealed-balance">{{ revealedBalances[h.walletAddress] }}</span>
                      } @else {
                        <button mat-stroked-button type="button" (click)="revealHolderBalance(h.walletAddress)" [disabled]="revealingWallet !== null">
                          {{ revealingWallet === h.walletAddress ? 'Decrypting…' : 'Reveal' }}
                        </button>
                      }
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="confidentialHolderColumns"></tr>
                  <tr mat-row *matRowDef="let r; columns: confidentialHolderColumns;"></tr>
                </table>
                </div>
                @if (revealError) {
                  <p class="confidential-error">{{ revealError }}</p>
                }
              } @else {
                <p class="empty-text">No holders recorded yet.</p>
              }

              @if (isIssuer && deployments.length > 0) {
                <mat-divider style="margin: 20px 0"></mat-divider>
                <h3 class="confidential-mint-title">Confidential Mint</h3>
                <div class="confidential-mint-form">
                  <mat-form-field appearance="outline">
                    <mat-label>Recipient address</mat-label>
                    <input matInput [(ngModel)]="mintToAddress" placeholder="0x…" autocomplete="off" />
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Amount</mat-label>
                    <input matInput type="number" [(ngModel)]="mintAmount" min="1" step="1" />
                  </mat-form-field>
                  <button type="button" mat-flat-button color="primary"
                          (click)="submitConfidentialMint()"
                          [disabled]="minting || !isValidWalletAddress(mintToAddress) || !isValidMintAmount()">
                    <mat-icon>add_circle</mat-icon>
                    {{ minting ? 'Encrypting & submitting…' : 'Mint' }}
                  </button>
                </div>
              }
            </mat-card-content>
          </mat-card>
        }

        <!-- ── Token Holders (Live from Blockchain) ────────────────────────── -->
        <mat-card class="section-card">
          <mat-card-header>
            <div style="display:flex;justify-content:space-between;align-items:center;width:100%">
              <mat-card-title>Token Holders (Live from Blockchain)</mat-card-title>
              <app-data-state-pill [status]="liveHoldersState.status" />
              <button mat-icon-button type="button" (click)="refreshLiveHolders()" [disabled]="liveHoldersLoading" matTooltip="Refresh holder data">
                @if (liveHoldersLoading) {
                  <mat-spinner diameter="24"></mat-spinner>
                } @else {
                  <mat-icon>refresh</mat-icon>
                }
              </button>
            </div>
          </mat-card-header>
          <mat-card-content>
            @if (liveHoldersState.status === 'error') {
              <p class="confidential-error" role="alert">
                Live holder data could not be loaded.
                <button mat-button type="button" (click)="loadLiveHolders()">Retry</button>
              </p>
            } @else if (asset && asset.status === 'ISSUED' && deployments.length > 0) {
              <div class="live-holder-grid">
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
                  @if (supportsGenericTokenAdmin) {
                    <app-token-admin-panel
                      [assetId]="asset.id"
                      [deploymentId]="deployments[0].id"
                      [busy]="tokenActionInProgress"
                      (mint)="onMint($event)"
                      (burn)="onBurn($event)"
                      (forceTransfer)="onForceTransfer($event)"
                      (forceApprove)="onForceApprove($event)"
                    ></app-token-admin-panel>
                  } @else if (tokenAdminMessage) {
                    <div class="token-admin-note">
                      <mat-icon>info</mat-icon>
                      <span>{{ tokenAdminMessage }}</span>
                    </div>
                  }
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

      } @else {
        <mat-card>
          <mat-card-content class="load-error" role="alert">
            <h1 class="sr-only">Issuance details</h1>
            <mat-icon>cloud_off</mat-icon>
            <p>{{ loadError || 'Issuance not found.' }}</p>
            @if (assetId) {
              <button mat-stroked-button type="button" (click)="load()">Retry</button>
            }
          </mat-card-content>
        </mat-card>
      }
    </div>
  `,
  styles: [`
    .back-link { margin-bottom: 16px; display: inline-flex; }
    .header-card, .timeline-card, .section-card { margin-bottom: 16px; }
    .bond-terms-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px 24px;
    }
    .bond-terms-grid > div { display: flex; flex-direction: column; gap: 2px; }
    .bt-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px; color: var(--rw-text-secondary); }
    .bt-value { font-size: 14px; color: var(--rw-text-primary); font-weight: 600; }
    .call-schedule { margin-top: 16px; display: flex; flex-direction: column; gap: 6px; }
    .call-schedule-list { margin: 0; padding-left: 18px; font-size: 13px; color: var(--rw-text-primary); }
    .asset-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
    .asset-title h1 { margin: 0 0 8px; font-size: 22px; }
    .asset-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .asset-number { font-size: 13px; color: var(--rw-text-secondary); }
    .isin { font-size: 13px; color: var(--rw-text-secondary); }
    .action-bar { display: flex; gap: 12px; margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--rw-border); }
    .external-id-panel { margin-top: 16px; max-width: 520px; }
    .empty-text { color: var(--rw-text-muted); font-style: italic; }
    code { font-size: 13px; }
    /* T-REX compliance card */
    .trex-card { border-left: 4px solid var(--rw-accent); }
    .trex-icon { vertical-align: middle; margin-right: 6px; color: var(--rw-accent); }
    .compliance-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 20px; margin-bottom: 16px; }
    .compliance-stat { display: flex; flex-direction: column; gap: 4px; }
    .stat-label { font-size: 11px; color: var(--rw-text-muted); text-transform: uppercase; letter-spacing: 0.5px; }
    .stat-value { font-size: 20px; font-weight: 600; color: var(--rw-text-primary); }
    .stat-max { font-size: 14px; font-weight: 400; color: var(--rw-text-secondary); }
    .module-list { margin-top: 8px; }
     .module-list-label { font-size: 12px; color: var(--rw-text-secondary); display: block; margin-bottom: 6px; }
     .module-chips { display: flex; gap: 8px; flex-wrap: wrap; }
     .identity-table-wrap { margin-top: 20px; }
     .token-admin-note {
       display: flex;
       align-items: flex-start;
       gap: 8px;
       padding: 12px 14px;
       border-radius: 8px;
       background: var(--rw-surface-soft);
       border: 1px solid var(--rw-border);
       color: var(--rw-text-secondary);
       font-size: 13px;
     }
     .token-admin-note mat-icon {
       color: var(--rw-accent);
       font-size: 18px;
       width: 18px;
       height: 18px;
       margin-top: 1px;
     }
     .confidential-card { border-left: 4px solid var(--rw-accent); }
     .confidential-icon { vertical-align: middle; margin-right: 6px; color: var(--rw-accent); }
     .confidential-intro { color: var(--rw-text-secondary); font-size: 13px; margin: 0 0 16px; max-width: 720px; }
     .revealed-balance { font-weight: 700; color: var(--rw-accent); }
     .confidential-error { color: var(--rw-text-danger); font-size: 13px; margin-top: 8px; }
     .confidential-mint-title { font-size: 14px; margin: 0 0 12px; color: var(--rw-text-primary); }
     .confidential-mint-form { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
     .confidential-mint-form mat-form-field { flex: 1; min-width: 220px; }
     .table-wrap { overflow-x: auto; }
     .live-holder-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-bottom: 24px; }
     .load-error { text-align: center; padding: 48px 16px; color: var(--rw-text-secondary); }
     @media (max-width: 800px) { .live-holder-grid { grid-template-columns: 1fr; } }
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
  private readonly registerDocumentService = inject(RegisterDocumentService);
  private readonly bondTermsService = inject(BondTermsService);
  private readonly corporateActionsService = inject(CorporateActionsService);
  protected readonly walletService = inject(WalletService);
  private readonly fheService = inject(FheClientService);

  asset: Asset | null = null;
  downloadingRegisterExtract = false;
  deployments: AssetDeployment[] = [];
  holders: AssetHolder[] = [];
  loading = true;
  assetId = '';
  loadError = '';
  actionLoading = false;
  deploymentsState: AsyncSection<null> = createAsyncSection<null>(null);
  holdersState: AsyncSection<null> = createAsyncSection<null>(null);

  // ── Confidential balances (issuer reveal-all + confidential mint) ────────
  connectingWallet = false;
  revealedBalances: Record<string, string> = {};
  revealingWallet: string | null = null;
  revealError: string | null = null;
  mintToAddress = '';
  mintAmount: number | null = null;
  minting = false;
  identityRegistryState: AsyncSection<null> = createAsyncSection<null>(null);
  liveHoldersState: AsyncSection<null> = { data: null, status: 'ready', hasLoaded: true };

  bondTerms: AssetBondTerms | null = null;

  // ── Corporate actions ────────────────────────────────────────────────────
  @ViewChild('proposeDialogTpl') proposeDialogTpl!: TemplateRef<unknown>;
  @ViewChild('attestDialogTpl') attestDialogTpl!: TemplateRef<unknown>;
  corporateActionsState: AsyncSection<CorporateActionView[]> = createAsyncSection<CorporateActionView[]>([]);
  readonly corporateActionColumns = ['type', 'status', 'paymentDate', 'progress', 'actions'];
  proposeForm: {
    actionType: CorporateActionType;
    amountPerUnit: number | null;
    currency: string;
    recordDate: string;
    paymentDate: string;
    ratioNumerator: number | null;
    ratioDenominator: number | null;
    callScheduleIndex: number | null;
    notes: string;
  } = this.emptyProposeForm();
  submittingProposal = false;
  attestForm: { corporateActionId: string; attestationReference: string } = { corporateActionId: '', attestationReference: '' };
  submittingAttestation = false;

  // ── Term Sheet ────────────────────────────────────────────────────────────
  termSheetDocs: AssetDocument[] = [];
  tsLoading = false;
  tsUploading = false;
  termSheetError = '';

  get isIssuer(): boolean {
    return this.auth?.hasRole('ISSUER') || this.auth?.hasRole('REGISTRY_ADMIN') || false;
  }

  // ERC-3643 state
  complianceStatus: ComplianceStatus | null = null;
  complianceError = '';
  identityRegistry: IdentityRegistryEntry[] = [];

  // Live token holders (blockchain state)
  liveHolders: LiveHolder[] = [];
  liveHoldersLoading = false;
  tokenActionInProgress = false;

  readonly deploymentColumns = ['chain', 'network', 'contract', 'status', 'deployedAt'];
  readonly baseHolderColumns  = ['wallet', 'amount', 'whitelisted', 'externalId'];
  readonly erc3643HolderColumns = ['wallet', 'amount', 'whitelisted', 'externalId', 'onchainId', 'kyc'];
  readonly confidentialHolderColumns = ['wallet', 'register', 'onchain'];
  readonly identityRegistryColumns = ['wallet', 'entity', 'legalEntityExternalId', 'registryExternalId', 'status'];

  get isErc3643(): boolean {
    return this.asset?.tokenStandard === 'ERC3643' || this.asset?.tokenStandard === 'CONF_ERC3643';
  }

  get isConfidential(): boolean {
    return this.asset?.tokenStandard === 'CONF_ERC20' || this.asset?.tokenStandard === 'CONF_ERC3643';
  }

  get activeHolderColumns(): string[] {
    return this.isErc3643 ? this.erc3643HolderColumns : this.baseHolderColumns;
  }

  get supportsGenericTokenAdmin(): boolean {
    return this.asset?.tokenStandard === 'ERC20'
      || this.asset?.tokenStandard === 'ERC721'
      || this.asset?.tokenStandard === 'ERC1155';
  }

  get tokenAdminMessage(): string | null {
    switch (this.asset?.tokenStandard) {
      case 'ERC3643':
      case 'CONF_ERC3643':
        return 'ERC-3643 issuances use registry and compliance controls instead of the generic issuer admin panel.';
      case 'CONF_ERC20':
        return 'Confidential token admin actions are intentionally hidden here until encrypted-input operations are exposed in the customer portal.';
      case 'SPL':
      case 'SPL_2022':
        return 'Solana issuer admin actions are not yet exposed in this panel. Deployment and holder views remain available.';
      default:
        return null;
    }
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
    this.assetId = this.route.snapshot.paramMap.get('id')?.trim() ?? '';
    if (!this.assetId) {
      this.loading = false;
      this.loadError = 'The issuance address is incomplete.';
      return;
    }
    this.load();
  }

  load(): void {
    if (!this.assetId) return;
    this.loading = true;
    this.loadError = '';
    this.asset = null;
    this.issuanceService.getIssuance(this.assetId).subscribe({
      next: (asset) => {
        this.asset = asset;
        this.loading = false;
        this.cdr.detectChanges();
        this.loadDeployments(asset.id);
        this.loadHolders(asset.id);
        this.loadTermSheetDocs(asset.id);
        this.loadBondTerms(asset.id);
        this.loadCorporateActions(asset.id);
      },
      error: (err) => {
        this.loadError = err?.error?.message ?? 'Failed to load the issuance.';
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
        if (this.asset?.status === 'ISSUED' && deployments.length > 0) {
          this.loadLiveHolders();
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
    this.complianceError = '';
    this.erc3643Service.getComplianceStatus(assetId, deploymentId).subscribe({
      next: (compliance) => {
        this.complianceStatus = compliance;
        this.cdr.detectChanges();
      },
      error: () => {
        this.complianceError = 'Compliance configuration could not be loaded.';
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

  retryDeployments(): void {
    if (this.asset) this.loadDeployments(this.asset.id);
  }

  retryHolders(): void {
    if (this.asset) this.loadHolders(this.asset.id);
  }

  retryErc3643Data(): void {
    const deployment = this.deployments[0];
    if (this.asset && deployment) this.loadErc3643Data(this.asset.id, deployment.id);
  }

  submitForApproval(): void {
    if (!this.asset || this.actionLoading || this.asset.status !== 'DRAFT') return;
    this.actionLoading = true;

    this.issuanceService.submitIssuance(this.asset.id).subscribe({
      next: () => {
        if (this.asset) {
          this.asset = { ...this.asset, status: 'PENDING_APPROVAL' };
        }
        this.actionLoading = false;
        this.snackBar.open('Submitted for approval.', 'OK', { duration: 3000 });
        this.cdr.detectChanges();
      },
      error: () => {
        this.actionLoading = false;
        this.snackBar.open('Could not submit the issuance for approval.', 'Close', { duration: 5000 });
        this.cdr.detectChanges();
      },
    });
  }

  deploy(): void {
    if (!this.asset?.chain || !this.asset?.network || this.actionLoading || this.asset.status !== 'APPROVED') return;
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
          this.snackBar.open('Could not start the deployment.', 'Close', { duration: 5000 });
          this.cdr.detectChanges();
        },
      });
  }

  downloadRegisterExtract(): void {
    if (!this.asset || this.downloadingRegisterExtract) return;
    this.downloadingRegisterExtract = true;
    this.registerDocumentService.downloadIssuerRegisterExtract(this.asset.id).subscribe({
      next: (pdf) => {
        downloadBlob(pdf, `registereinsicht-${this.asset!.assetNumber}.pdf`);
        this.downloadingRegisterExtract = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.downloadingRegisterExtract = false;
        this.snackBar.open('Failed to generate the register extract. Please try again.', 'Close', { duration: 5000 });
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
    this.termSheetError = '';
    this.issuanceService.listDocuments(assetId).subscribe({
      next: docs => { this.termSheetDocs = docs; this.tsLoading = false; this.cdr.detectChanges(); },
      error: () => {
        this.termSheetError = 'Term-sheet documents could not be loaded.';
        this.tsLoading = false;
        this.cdr.detectChanges();
      },
    });
  }

  retryTermSheets(): void {
    if (this.asset) this.loadTermSheetDocs(this.asset.id);
  }

  private loadBondTerms(assetId: string): void {
    this.bondTermsService.getBondTerms(assetId).subscribe({
      next: (terms) => { this.bondTerms = terms; this.cdr.detectChanges(); },
      error: () => {
        // 404 for the (common) case of a non-bond asset — no terms to show, not an error.
      },
    });
  }

  formatEnum(value: string): string {
    return value.split('_').join(' ');
  }

  private emptyProposeForm() {
    return {
      actionType: 'DIVIDEND' as CorporateActionType,
      amountPerUnit: null as number | null,
      currency: '',
      recordDate: '',
      paymentDate: '',
      ratioNumerator: null as number | null,
      ratioDenominator: null as number | null,
      callScheduleIndex: null as number | null,
      notes: '',
    };
  }

  /** Non-terminal, past the proposal stage — where the two-party settlement control applies. */
  isPreSettlement(a: CorporateActionView): boolean {
    return a.status === 'ANNOUNCED' || a.status === 'RECORD_DATE_SET' || a.status === 'COMPUTED';
  }

  corporateActionProgress(a: CorporateActionView): string {
    switch (a.status) {
      case 'PROPOSED': return 'Awaiting operator review';
      case 'REJECTED': return 'Rejected — submit a fresh proposal';
      case 'CANCELLED': return 'Cancelled';
      case 'SETTLED':
      case 'CLOSED':
        return a.settlementTxHash ? `${a.settlementTxHash.slice(0, 10)}…` : 'Settled off-chain';
      default:
        if (!a.issuerAttestedAt) return 'Awaiting your attestation';
        if (!a.dualControlApprovedAt) return 'Attested — awaiting operator confirmation';
        return 'Confirmed — awaiting settlement dispatch';
    }
  }

  loadCorporateActions(assetId: string = this.assetId): void {
    if (!assetId) return;
    this.corporateActionsState = beginAsyncSection(this.corporateActionsState);
    this.corporateActionsService.listForAsset(assetId).subscribe({
      next: (actions) => {
        this.corporateActionsState = resolveAsyncSection(this.corporateActionsState, actions);
        this.cdr.detectChanges();
      },
      error: () => {
        this.corporateActionsState = failAsyncSection(this.corporateActionsState);
        this.cdr.detectChanges();
      },
    });
  }

  openProposeDialog(): void {
    this.proposeForm = this.emptyProposeForm();
    this.dialog.open(this.proposeDialogTpl, { width: '480px', maxWidth: '95vw' });
  }

  submitPropose(): void {
    if (!this.assetId || this.submittingProposal) return;
    const f = this.proposeForm;
    const request: ProposeCorporateActionRequest = {
      actionType: f.actionType,
      notes: f.notes.trim() || undefined,
    };
    if (f.actionType === 'DIVIDEND') {
      request.amountPerUnit = f.amountPerUnit ?? undefined;
      request.currency = f.currency.trim() || undefined;
      request.recordDate = f.recordDate || undefined;
      request.paymentDate = f.paymentDate || undefined;
    } else if (f.actionType === 'SPLIT') {
      request.ratioNumerator = f.ratioNumerator ?? undefined;
      request.ratioDenominator = f.ratioDenominator ?? undefined;
      request.recordDate = f.recordDate || undefined;
    } else if (f.actionType === 'CALL') {
      if (f.callScheduleIndex !== null) {
        request.callScheduleIndex = f.callScheduleIndex;
      } else {
        request.paymentDate = f.paymentDate || undefined;
        request.amountPerUnit = f.amountPerUnit ?? undefined;
      }
    }

    this.submittingProposal = true;
    this.cdr.detectChanges();
    this.corporateActionsService.propose(this.assetId, request).subscribe({
      next: () => {
        this.dialog.closeAll();
        this.submittingProposal = false;
        this.cdr.detectChanges();
        this.snackBar.open('Proposal submitted. An operator will review it.', 'Dismiss', { duration: 5000 });
        this.loadCorporateActions();
      },
      error: (err) => {
        this.submittingProposal = false;
        this.cdr.detectChanges();
        this.snackBar.open(err?.error?.message ?? 'Failed to submit the proposal.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  withdrawProposal(a: CorporateActionView): void {
    if (!this.assetId) return;
    this.corporateActionsService.withdraw(this.assetId, a.id).subscribe({
      next: () => {
        this.snackBar.open('Proposal withdrawn.', 'Dismiss', { duration: 4000 });
        this.loadCorporateActions();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to withdraw the proposal.', 'Dismiss', { duration: 6000 }),
    });
  }

  openAttestDialog(a: CorporateActionView): void {
    this.attestForm = { corporateActionId: a.id, attestationReference: '' };
    this.dialog.open(this.attestDialogTpl, { width: '440px', maxWidth: '95vw' });
  }

  submitAttest(): void {
    if (!this.assetId || this.submittingAttestation || !this.attestForm.attestationReference.trim()) return;
    this.submittingAttestation = true;
    this.cdr.detectChanges();
    this.corporateActionsService.attestSettlement(
      this.assetId, this.attestForm.corporateActionId, this.attestForm.attestationReference.trim(),
    ).subscribe({
      next: () => {
        this.dialog.closeAll();
        this.submittingAttestation = false;
        this.cdr.detectChanges();
        this.snackBar.open('Attested. Awaiting operator confirmation.', 'Dismiss', { duration: 5000 });
        this.loadCorporateActions();
      },
      error: (err) => {
        this.submittingAttestation = false;
        this.cdr.detectChanges();
        this.snackBar.open(err?.error?.message ?? 'Failed to attest.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  onTermSheetFileSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file || !this.asset || this.tsUploading) return;
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
    this.issuanceService.downloadDocument(this.asset.id, doc.id).subscribe({
      next: (blob) => {
        downloadBlob(blob, doc.fileName ?? 'term_sheet');
      },
      error: () => this.snackBar.open('Term-sheet download failed.', 'Close', { duration: 5000 }),
    });
  }

  openAddHolder(): void {
    if (!this.asset) return;
    const ref = this.dialog.open(AddHolderDialogComponent, {
      width: '480px',
      maxWidth: '95vw',
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
    if (!this.asset?.id || this.liveHoldersLoading) return;
    this.liveHoldersLoading = true;
    this.liveHoldersState = beginAsyncSection(this.liveHoldersState);
    this.issuanceService.refreshHolders(this.asset.id).subscribe({
      next: (response) => {
        this.snackBar.open(response.message || 'Holder data refresh initiated', '', { duration: 3000 });
        this.loadLiveHolders();
      },
      error: (error) => {
        this.liveHoldersLoading = false;
        this.liveHoldersState = failAsyncSection(this.liveHoldersState);
        const errorMsg = error?.error?.message || 'Failed to refresh holder data';
        this.snackBar.open(`Error: ${errorMsg}`, 'Close', { duration: 5000 });
      }
    });
  }

  loadLiveHolders(): void {
    const deployment = this.deployments[0];
    if (!this.asset || !deployment) return;
    this.liveHoldersLoading = true;
    this.liveHoldersState = beginAsyncSection(this.liveHoldersState);
    this.issuanceService.getLiveHolders(this.asset.id, deployment.id).subscribe({
      next: (holders) => {
        this.liveHolders = holders;
        this.liveHoldersLoading = false;
        this.liveHoldersState = resolveAsyncSection(this.liveHoldersState, null);
        this.cdr.detectChanges();
      },
      error: () => {
        this.liveHoldersLoading = false;
        this.liveHoldersState = failAsyncSection(this.liveHoldersState);
        this.cdr.detectChanges();
      },
    });
  }

  onMint(action: MintAction): void {
    if (!this.asset?.id || this.deployments.length === 0 || this.tokenActionInProgress) return;
    this.tokenActionInProgress = true;
    this.issuanceService.mint(this.asset.id, this.deployments[0].id, {
      toAddress: action.recipient,
      amount: action.amount.toString(),
    }).subscribe({
      next: (r) => {
        this.tokenActionInProgress = false;
        this.txService.track(r.txId, `Mint ${action.amount} tokens`);
      },
      error: () => {
        this.tokenActionInProgress = false;
        this.snackBar.open('Mint failed.', 'Close', { duration: 5000 });
      },
    });
  }

  onBurn(action: BurnAction): void {
    if (!this.asset?.id || this.deployments.length === 0 || this.tokenActionInProgress) return;
    this.tokenActionInProgress = true;
    this.issuanceService.burn(this.asset.id, this.deployments[0].id, {
      fromAddress: action.fromWallet ?? '',
      amount: action.amount.toString(),
    }).subscribe({
      next: (r) => {
        this.tokenActionInProgress = false;
        this.txService.track(r.txId, `Burn ${action.amount} tokens`);
      },
      error: () => {
        this.tokenActionInProgress = false;
        this.snackBar.open('Burn failed.', 'Close', { duration: 5000 });
      },
    });
  }

  onForceTransfer(action: ForceTransferAction): void {
    if (!this.asset?.id || this.deployments.length === 0 || this.tokenActionInProgress) return;
    this.tokenActionInProgress = true;
    this.issuanceService.forceTransfer(this.asset.id, this.deployments[0].id, {
      from: action.fromWallet, to: action.toWallet,
      value: action.amount.toString(), legalBasis: action.legalBasis,
    }).subscribe({
      next: (r) => {
        this.tokenActionInProgress = false;
        this.txService.track(r.txId, 'Forced transfer');
      },
      error: () => {
        this.tokenActionInProgress = false;
        this.snackBar.open('Force transfer failed.', 'Close', { duration: 5000 });
      },
    });
  }

  onForceApprove(action: ForceApproveAction): void {
    if (!this.asset?.id || this.deployments.length === 0 || this.tokenActionInProgress) return;
    this.tokenActionInProgress = true;
    this.issuanceService.forceApprove(this.asset.id, this.deployments[0].id, {
      owner: action.ownerWallet, spender: action.spenderWallet,
      value: action.amount.toString(), legalBasis: action.legalBasis,
    }).subscribe({
      next: (r) => {
        this.tokenActionInProgress = false;
        this.txService.track(r.txId, 'Forced approve');
      },
      error: () => {
        this.tokenActionInProgress = false;
        this.snackBar.open('Force approve failed.', 'Close', { duration: 5000 });
      },
    });
  }

  // ── Confidential balances: issuer reveal-all + confidential mint ─────────

  async connectIssuerWallet(): Promise<void> {
    if (this.connectingWallet) return;
    this.connectingWallet = true;
    this.cdr.detectChanges();
    try {
      await this.walletService.connect();
    } catch (err: unknown) {
      this.snackBar.open((err as Error)?.message ?? 'Wallet connection failed.', 'Dismiss', { duration: 5000 });
    } finally {
      this.connectingWallet = false;
      this.cdr.detectChanges();
    }
  }

  async revealHolderBalance(walletAddress: string): Promise<void> {
    if (!this.asset || this.deployments.length === 0 || this.revealingWallet !== null
        || !this.isValidWalletAddress(walletAddress)) return;
    this.revealingWallet = walletAddress;
    this.revealError = null;
    this.cdr.detectChanges();
    try {
      const dep = this.deployments[0];
      const ctx = await firstValueFrom(this.issuanceService.getConfidentialContext(this.asset.id, dep.id));
      const handleValue = await this.walletService.readContract<bigint>({
        address: ctx.contractAddress as `0x${string}`,
        abi: CONFIDENTIAL_BALANCE_ABI,
        functionName: 'confidentialBalanceOf',
        args: [walletAddress as `0x${string}`],
      });
      const handle = numberToHex(handleValue, { size: 32 });
      const cleartext = await this.fheService.userDecrypt(handle, ctx.contractAddress as `0x${string}`, ctx.chainId);
      this.revealedBalances = { ...this.revealedBalances, [walletAddress]: cleartext.toString() };
    } catch (err: unknown) {
      this.revealError = (err as Error)?.message ?? 'Failed to reveal balance — is your wallet a registered viewer for this token?';
    } finally {
      this.revealingWallet = null;
      this.cdr.detectChanges();
    }
  }

  submitConfidentialMint(): void {
    if (!this.asset?.id || this.deployments.length === 0 || this.minting
        || !this.isValidWalletAddress(this.mintToAddress) || !this.isValidMintAmount()) return;
    const amount = this.mintAmount!;
    this.minting = true;
    this.cdr.detectChanges();
    this.issuanceService.mintConfidential(this.asset.id, this.deployments[0].id, {
      toAddress: this.mintToAddress.trim(),
      amount: amount.toString(),
    }).subscribe({
      next: (r) => {
        this.txService.track(r.txId, 'Confidential mint');
        this.mintToAddress = '';
        this.mintAmount = null;
        this.minting = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.snackBar.open('Confidential mint failed.', 'Close', { duration: 5000 });
        this.minting = false;
        this.cdr.detectChanges();
      },
    });
  }

  isValidWalletAddress(address: string): boolean {
    return /^0x[0-9a-fA-F]{40}$/.test(address.trim());
  }

  isValidMintAmount(): boolean {
    return this.mintAmount !== null
      && Number.isSafeInteger(this.mintAmount)
      && this.mintAmount > 0;
  }
}
