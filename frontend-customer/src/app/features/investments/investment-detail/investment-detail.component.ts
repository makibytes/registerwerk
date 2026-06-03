import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { InvestmentService } from '../../../core/api/investment.service';
import { IssuanceService } from '../../../core/api/issuance.service';
import { Erc3643Service, IdentityRegistryEntry } from '../../../core/api/erc3643.service';
import { IdentityRequestService } from '../../../core/api/identity-request.service';
import { AssetDeployment, InvestmentRecord } from '../../../core/models';
import {
  AsyncSection,
  beginAsyncSection,
  createAsyncSection,
  failAsyncSection,
  resolveAsyncSection,
} from '../../../core/async/async-section';
import { StatusBadgeComponent, DataStatePillComponent } from '@registerwerk/ui';
import { ChainIconComponent } from '../../../shared/components/chain-icon/chain-icon.component';

import { AddressComponent } from '../../../shared/components/address.component';
import { ExternalIdEditorComponent } from '../../../shared/components/external-id-editor.component';

@Component({
  selector: 'app-investment-detail',
  standalone: true,
  imports: [
    CommonModule,
    RouterLink,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatTableModule,
    MatChipsModule,
    MatFormFieldModule,
    MatInputModule,
    StatusBadgeComponent,
    ChainIconComponent,
    DataStatePillComponent,
    AddressComponent,
    ExternalIdEditorComponent,
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
                <app-address [address]="record.walletAddress" />
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
              <div class="detail-item external-id-item">
                <span class="detail-label">External ID</span>
                <app-external-id-editor
                  [subjectType]="'ASSET_HOLDER'"
                  [subjectId]="record.id"
                  [value]="record.externalId"
                  label="Investment external ID"
                  placeholder="Your internal holding ID"
                  (valueChange)="record.externalId = $event"
                />
              </div>
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
                <div class="identity-external-id">
                  <app-external-id-editor
                    [subjectType]="'ERC3643_IDENTITY_REGISTRY_ENTRY'"
                    [subjectId]="identitySection.data.id"
                    [value]="identitySection.data.externalId"
                    label="Identity registry external ID"
                    placeholder="Your internal identity entry ID"
                    (valueChange)="identitySection.data!.externalId = $event"
                  />
                </div>

              } @else if (identitySection.status === 'ready') {
                <!-- Dead-end replaced with actionable self-service flow -->
                <div class="identity-not-registered">
                  <div class="idr-header">
                    <mat-icon class="idr-icon warn">assignment_late</mat-icon>
                    <div>
                      <div class="identity-label">Wallet not in Identity Registry</div>
                      <div class="identity-sub">
                        This is a permissioned security token (ERC-3643). Your wallet must be
                        registered in the on-chain Identity Registry before transfers are possible.
                        Follow the steps below to request registration.
                      </div>
                    </div>
                  </div>

                  <div class="idr-steps">
                    <div class="idr-step">
                      <span class="step-number">1</span>
                      <div class="step-body">
                        <strong>Complete your KYC</strong>
                        <span>Your KYC documents must be approved by the registry operator.</span>
                        <a mat-stroked-button routerLink="/kyc" style="margin-top:8px;width:fit-content">
                          <mat-icon>how_to_reg</mat-icon>
                          Check KYC Status
                        </a>
                      </div>
                    </div>
                    <div class="idr-step">
                      <span class="step-number">2</span>
                      <div class="step-body">
                        <strong>Request Identity Registration</strong>
                        <span>Once KYC is approved, submit a registration request to the operator.</span>

                        @if (registrationRequested) {
                          <div class="request-sent">
                            <mat-icon>check_circle</mat-icon>
                            Request submitted. The registry operator will register your wallet.
                          </div>
                        } @else {
                          @if (showRegistrationForm) {
                            <div class="reg-form">
                              <mat-form-field appearance="outline" style="width:100%">
                                <mat-label>Optional note to operator</mat-label>
                                <textarea matInput [(ngModel)]="registrationNote" rows="2"
                                          placeholder="e.g. Requested at onboarding meeting 2025-01-10"></textarea>
                              </mat-form-field>
                              <div style="display:flex;gap:8px">
                                <button mat-raised-button color="primary"
                                        (click)="submitRegistrationRequest()"
                                        [disabled]="registrationSubmitting">
                                  <mat-icon>send</mat-icon>
                                  {{ registrationSubmitting ? 'Submitting…' : 'Submit Request' }}
                                </button>
                                <button mat-stroked-button (click)="showRegistrationForm = false">Cancel</button>
                              </div>
                            </div>
                          } @else {
                            <button mat-stroked-button color="primary"
                                    (click)="showRegistrationForm = true"
                                    style="margin-top:8px;width:fit-content"
                                    [disabled]="deploymentsSection.data.length === 0">
                              <mat-icon>how_to_reg</mat-icon>
                              Request Registration
                            </button>
                          }
                        }
                      </div>
                    </div>
                    <div class="idr-step">
                      <span class="step-number">3</span>
                      <div class="step-body">
                        <strong>Operator deploys your identity</strong>
                        <span>The operator will deploy your ONCHAINID contract and add you to the registry. This page will update automatically.</span>
                      </div>
                    </div>
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
    .isin { font-size: 13px; color: var(--rw-text-secondary); }
    .detail-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 20px; }
    .detail-item { display: flex; flex-direction: column; gap: 4px; }
    .external-id-item { grid-column: 1 / -1; }
    .detail-label { font-size: 11px; color: var(--rw-text-muted); text-transform: uppercase; letter-spacing: 0.5px; }
    .detail-value { font-size: 15px; color: var(--rw-text-primary); }
    .detail-value.large { font-size: 28px; font-weight: 700; color: var(--rw-accent); }
    code { word-break: break-all; }
    .identity-card { border-left: 4px solid #5e35b1; }
    .identity-icon { vertical-align: middle; margin-right: 6px; color: #5e35b1; }
    .identity-row { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
    .identity-label { display: block; font-weight: 500; color: var(--rw-text-primary); }
    .identity-sub { display: block; font-size: 13px; color: var(--rw-text-muted); margin-top: 2px; }
    .identity-address { display: block; margin-top: 4px; word-break: break-all; }
    .claim-status-grid { display: flex; gap: 16px; flex-wrap: wrap; }
    .claim-item { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-radius: 8px; background: var(--rw-surface-soft); border: 1px solid var(--rw-border); }
    .claim-item.claim-ok { border-color: color-mix(in srgb, var(--rw-text-success) 32%, var(--rw-border)); background: color-mix(in srgb, var(--rw-text-success) 10%, var(--rw-surface)); }
    .claim-item.claim-ok mat-icon { color: var(--rw-text-success); }
    .claim-item.claim-fail { border-color: color-mix(in srgb, var(--rw-text-danger) 32%, var(--rw-border)); background: color-mix(in srgb, var(--rw-text-danger) 10%, var(--rw-surface)); }
    .claim-item.claim-fail mat-icon { color: var(--rw-text-danger); }
    .claim-label { display: block; font-size: 12px; color: var(--rw-text-secondary); font-weight: 500; }
    .claim-status { display: block; font-size: 14px; color: var(--rw-text-primary); }
    .identity-external-id { margin-top: 16px; }
    .empty-text { color: var(--rw-text-muted); }

    /* ── Identity not registered: actionable flow ──────────────────────────── */
    .identity-not-registered { display: flex; flex-direction: column; gap: 20px; }
    .idr-header { display: flex; gap: 12px; align-items: flex-start; }
    .idr-icon { font-size: 28px; width: 28px; height: 28px; flex-shrink: 0; margin-top: 2px; }
    .idr-icon.warn { color: #f59e0b; }
    .idr-steps { display: flex; flex-direction: column; gap: 16px; border-left: 2px solid var(--rw-border); padding-left: 20px; margin-left: 8px; }
    .idr-step { display: flex; gap: 14px; }
    .step-number {
      width: 24px; height: 24px; border-radius: 50%;
      background: var(--rw-accent); color: #07091A;
      font-size: 12px; font-weight: 700;
      display: flex; align-items: center; justify-content: center;
      flex-shrink: 0; margin-top: 2px;
    }
    .step-body { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
    .step-body strong { color: var(--rw-text-primary); font-size: 14px; }
    .step-body span { color: var(--rw-text-secondary); }
    .reg-form { display: flex; flex-direction: column; gap: 10px; margin-top: 8px; max-width: 460px; }
    .request-sent {
      display: flex; align-items: center; gap: 8px;
      font-size: 13px; color: #10b981; font-weight: 500;
      margin-top: 8px; padding: 8px 12px;
      background: rgba(16,185,129,0.08); border-radius: 6px;
      border: 1px solid rgba(16,185,129,0.2);

      mat-icon { font-size: 18px; width: 18px; height: 18px; }
    }
  `],
})
export class InvestmentDetailComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly route = inject(ActivatedRoute);
  private readonly investmentService = inject(InvestmentService);
  private readonly issuanceService = inject(IssuanceService);
  private readonly erc3643Service = inject(Erc3643Service);
  private readonly identityRequestService = inject(IdentityRequestService);
  private readonly snackBar = inject(MatSnackBar);

  record: InvestmentRecord | null = null;
  loading = true;
  deploymentsSection: AsyncSection<AssetDeployment[]> = createAsyncSection<AssetDeployment[]>([]);
  identitySection: AsyncSection<IdentityRegistryEntry | null> = createAsyncSection<IdentityRegistryEntry | null>(null);

  showRegistrationForm = false;
  registrationNote = '';
  registrationSubmitting = false;
  registrationRequested = false;

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
        this.cdr.detectChanges();
        this.loadDeployments(record.assetId, record.walletAddress);
      },
      error: () => {
        this.loading = false;
        this.cdr.detectChanges();
      },
    });
  }

  submitRegistrationRequest(): void {
    if (!this.record || this.deploymentsSection.data.length === 0) return;
    this.registrationSubmitting = true;
    this.cdr.detectChanges();

    const deployment = this.deploymentsSection.data[0];
    this.identityRequestService.requestIdentityRegistration({
      assetId: this.record.assetId,
      deploymentId: deployment.id,
      walletAddress: this.record.walletAddress,
      note: this.registrationNote || undefined,
    }).subscribe({
      next: () => {
        this.registrationRequested = true;
        this.registrationSubmitting = false;
        this.showRegistrationForm = false;
        this.cdr.detectChanges();
        this.snackBar.open(
          'Registration request submitted. The registry operator will contact you.',
          'Dismiss',
          { duration: 6000 },
        );
      },
      error: (err) => {
        this.registrationSubmitting = false;
        this.cdr.detectChanges();
        this.snackBar.open(
          err?.error?.message ?? 'Failed to submit request. Please try again.',
          'Dismiss',
          { duration: 5000 },
        );
      },
    });
  }

  private loadDeployments(assetId: string, walletAddress: string): void {
    this.deploymentsSection = beginAsyncSection(this.deploymentsSection);
    this.issuanceService.getDeployments(assetId).subscribe({
      next: (deployments) => {
        this.deploymentsSection = resolveAsyncSection(this.deploymentsSection, deployments);
        this.cdr.detectChanges();
        if (this.isErc3643 && deployments.length > 0) {
          this.loadIdentityStatus(assetId, deployments[0].id, walletAddress);
        }
      },
      error: () => {
        this.deploymentsSection = failAsyncSection(this.deploymentsSection);
        this.cdr.detectChanges();
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
        this.cdr.detectChanges();
      },
      error: () => {
        this.identitySection = failAsyncSection(this.identitySection);
        this.cdr.detectChanges();
      },
    });
  }
}
