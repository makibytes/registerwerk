import { ChangeDetectorRef, Component, OnInit, inject, Input } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe } from '@angular/common';
import { forkJoin } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { EntityService } from '../../../core/api/entity.service';
import { AdminUserService } from '../../../core/api/admin-user.service';
import { environment } from '../../../../environments/environment';
import { AddressComponent } from '../../../shared/components/address.component';
import { KycService } from '../../../core/api/kyc.service';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import {
  LegalEntity, KycDocument, LegalEntityNameHistory,
  KycJurisdictionApproval, KycComplianceResponse, Jurisdiction,
  JurisdictionRequirement, DocumentStatus, SyncStatus,
} from '../../../core/models';
import { DataStatePillComponent } from '../../../shared/components/data-state-pill/data-state-pill.component';
import { StatusBadgeComponent } from '../../../shared/components/status-badge/status-badge.component';

interface OnchainIdentityView {
  id: string;
  chainConfigId: string;
  chainIdentifier: string;
  identityAddress: string;
  deployedByTx?: string | null;
  syncStatus: SyncStatus;
  deployedAt: string;
  activeClaims: { topic: number; topicLabel: string; issuedAt: string; expiresAt: string | null; isRevoked: boolean }[];
}

@Component({
  selector: 'app-customer-detail',
  standalone: true,
  imports: [
    FormsModule,
    MatTabsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTableModule,
    MatProgressSpinnerModule,
    MatDividerModule,
    MatDialogModule,
    MatTooltipModule,
    DataStatePillComponent,
    StatusBadgeComponent,
    DatePipe,
    AddressComponent,
  ],
  styles: [`
    .back-row {
      margin-bottom: 12px;
    }

    .entity-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 20px;

      .entity-title {
        h2 { margin: 0 0 4px; font-size: 22px; font-weight: 500; }
        .entity-number { color: var(--rw-text-secondary); font-size: 13px; font-family: monospace; }
      }

      .entity-actions {
        display: flex;
        gap: 8px;
      }
    }

    .field-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px 24px;

      @media (max-width: 600px) { grid-template-columns: 1fr; }
    }

    .field-item {
      padding: 12px 0;
      border-bottom: 1px solid var(--rw-border);

      .field-label {
        font-size: 12px;
        color: var(--rw-text-muted);
        text-transform: uppercase;
        letter-spacing: 0.5px;
        margin-bottom: 4px;
      }

      .field-value {
        font-size: 14px;
        color: var(--rw-text-primary);
      }
    }

    .tab-content {
      padding-top: 20px;
    }

    .kyc-actions {
      display: flex;
      gap: 12px;
      margin-bottom: 20px;
      align-items: center;
    }

    .doc-table { width: 100%; }

    .history-item {
      padding: 12px 0;
      border-bottom: 1px solid var(--rw-border);
      display: flex;
      justify-content: space-between;
      align-items: center;

      .name { font-size: 14px; font-weight: 500; }
      .dates { font-size: 12px; color: var(--rw-text-secondary); }
    }

    .spinner-wrap {
      display: flex;
      justify-content: center;
      padding: 40px;
    }

    .reject-form {
      margin-top: 12px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      max-width: 480px;
    }
  `],
  template: `
    <div class="back-row">
      <button mat-button (click)="goBack()">
        <mat-icon>arrow_back</mat-icon>
        Back to Customers
      </button>
    </div>

    @if (loading) {
      <div class="spinner-wrap"><mat-spinner diameter="40" /></div>
    } @else if (entity) {
      <div class="entity-header">
        <div class="entity-title">
          <h2>{{ entity.currentName }}</h2>
          <span class="entity-number">{{ entity.entityNumber }}</span>
          &nbsp;
          <app-status-badge [status]="entity.status" />
        </div>
        <div class="entity-actions">
          @if (entity.status === 'ACTIVE') {
            <button mat-stroked-button color="warn" (click)="suspend()">Suspend</button>
          }
          @if (entity.status === 'SUSPENDED') {
            <button mat-stroked-button color="primary" (click)="reactivate()">Reactivate</button>
          }
          @if (entity.status !== 'DISSOLVED') {
            <button mat-stroked-button color="warn" (click)="dissolve()">Dissolve</button>
          }
          <button mat-raised-button color="primary" (click)="generateToken()">
            <mat-icon>key</mat-icon>
            Onboarding Token
          </button>
          <button mat-stroked-button (click)="openAsCompany()" matTooltip="Open this company in the customer portal as an admin">
            <mat-icon>open_in_new</mat-icon>
            Open as Company
          </button>
        </div>
      </div>

      <mat-tab-group animationDuration="200ms">
        <!-- Overview Tab -->
        <mat-tab label="Overview">
          <div class="tab-content">
            <div class="field-grid">
              <div class="field-item">
                <div class="field-label">Entity ID</div>
                <div class="field-value"><code>{{ entity.id }}</code></div>
              </div>
              <div class="field-item">
                <div class="field-label">Type</div>
                <div class="field-value">{{ entity.type }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">Legal Name</div>
                <div class="field-value">{{ entity.currentName }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">Registration Number</div>
                <div class="field-value">{{ entity.registrationNumber ?? '—' }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">Registration Country</div>
                <div class="field-value">{{ entity.registrationCountry ?? '—' }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">LEI Code</div>
                <div class="field-value">{{ entity.leiCode ?? '—' }}</div>
              </div>
              <div class="field-item">
                <div class="field-label">KYC Status</div>
                <div class="field-value">
                  <app-status-badge [status]="entity.kycStatus" />
                </div>
              </div>
              <div class="field-item">
                <div class="field-label">Created At</div>
                <div class="field-value">{{ entity.createdAt | date:'medium' }}</div>
              </div>
            </div>
          </div>
        </mat-tab>

        <!-- KYC Documents -->
        <mat-tab label="KYC Documents">
          <div class="tab-content">
            <div class="kyc-actions">
              <label>
                <input #fileInput type="file" style="display:none" (change)="onFileSelected($event)" />
                <button mat-raised-button color="primary" (click)="fileInput.click()">
                  <mat-icon>upload</mat-icon> Upload Document
                </button>
              </label>
            </div>

            @if (docsLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else {
              <table mat-table [dataSource]="documents" class="doc-table">
                <ng-container matColumnDef="documentType">
                  <th mat-header-cell *matHeaderCellDef>Type</th>
                  <td mat-cell *matCellDef="let doc">{{ formatDocType(doc.documentType) }}</td>
                </ng-container>
                <ng-container matColumnDef="jurisdiction">
                  <th mat-header-cell *matHeaderCellDef>Jurisdiction</th>
                  <td mat-cell *matCellDef="let doc">{{ doc.jurisdiction ?? '—' }}</td>
                </ng-container>
                <ng-container matColumnDef="fileName">
                  <th mat-header-cell *matHeaderCellDef>File</th>
                  <td mat-cell *matCellDef="let doc">{{ doc.fileName }}</td>
                </ng-container>
                <ng-container matColumnDef="sizeBytes">
                  <th mat-header-cell *matHeaderCellDef>Size</th>
                  <td mat-cell *matCellDef="let doc">{{ (doc.sizeBytes / 1024).toFixed(1) }} KB</td>
                </ng-container>
                <ng-container matColumnDef="uploadedAt">
                  <th mat-header-cell *matHeaderCellDef>Uploaded</th>
                  <td mat-cell *matCellDef="let doc">{{ doc.uploadedAt | date:'mediumDate' }}</td>
                </ng-container>
                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef></th>
                  <td mat-cell *matCellDef="let doc">
                    <button mat-icon-button (click)="downloadDoc(doc)" matTooltip="Download">
                      <mat-icon>download</mat-icon>
                    </button>
                  </td>
                </ng-container>
                <tr mat-header-row *matHeaderRowDef="docColumns"></tr>
                <tr mat-row *matRowDef="let row; columns: docColumns;"></tr>
              </table>
              @if (documents.length === 0) {
                <p class="text-muted" style="text-align:center;padding:24px">No documents uploaded.</p>
              }
            }
          </div>
        </mat-tab>

        <!-- KYC Jurisdiction Approvals -->
        <mat-tab label="Jurisdiction KYC">
          <div class="tab-content">
            <p style="font-size:13px;color:var(--rw-text-secondary);margin-bottom:16px">
              Approve or reject KYC for each regulatory jurisdiction. Each jurisdiction has its own
              document checklist. A security issued under a jurisdiction can only be approved when
              the issuer's KYC is complete for that jurisdiction.
            </p>

            @for (jur of allJurisdictions; track jur) {
              <mat-card style="margin-bottom:16px">
                <mat-card-header>
                  <mat-card-title style="font-size:14px;display:flex;align-items:center;gap:10px">
                    {{ jurisdictionLabel(jur) }}
                    @if (getJurisdictionStatus(jur) === 'APPROVED') {
                      <span style="font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;background:rgba(16,185,129,0.12);color:#10b981">APPROVED</span>
                    } @else if (getJurisdictionStatus(jur) === 'REJECTED') {
                      <span style="font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;background:rgba(239,68,68,0.12);color:#ef4444">REJECTED</span>
                    } @else {
                      <span style="font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;background:rgba(245,158,11,0.12);color:#f59e0b">PENDING</span>
                    }
                  </mat-card-title>
                </mat-card-header>
                <mat-card-content style="padding-top:12px">
                  @if (complianceByJurisdiction[jur]) {
                    @let cr = complianceByJurisdiction[jur];
                    <div style="display:flex;gap:16px;flex-wrap:wrap;margin-bottom:12px">
                      @if (cr.fullyCompliant) {
                        <span style="color:#10b981;font-size:13px"><mat-icon style="font-size:16px;vertical-align:middle">check_circle</mat-icon> All required documents present</span>
                      } @else {
                        @if (cr.missingCount > 0) {
                          <span style="color:#ef4444;font-size:13px"><mat-icon style="font-size:16px;vertical-align:middle">cancel</mat-icon> {{ cr.missingCount }} missing</span>
                        }
                        @if (cr.tooOldCount > 0) {
                          <span style="color:#f59e0b;font-size:13px"><mat-icon style="font-size:16px;vertical-align:middle">schedule</mat-icon> {{ cr.tooOldCount }} too old</span>
                        }
                        @if (cr.expiredCount > 0) {
                          <span style="color:#ef4444;font-size:13px"><mat-icon style="font-size:16px;vertical-align:middle">event_busy</mat-icon> {{ cr.expiredCount }} expired</span>
                        }
                      }
                    </div>
                    <div style="display:flex;flex-direction:column;gap:4px;margin-bottom:12px">
                      @for (doc of cr.documents; track doc.documentType) {
                        <div style="display:flex;align-items:center;gap:8px;font-size:12px;padding:4px 0">
                          @if (!doc.mandatory) {
                            <mat-icon style="font-size:14px;color:var(--rw-text-muted)">radio_button_unchecked</mat-icon>
                          } @else if (doc.present && !doc.expired && !doc.tooOld) {
                            <mat-icon style="font-size:14px;color:#10b981">check_circle</mat-icon>
                          } @else if (doc.tooOld) {
                            <mat-icon style="font-size:14px;color:#f59e0b">schedule</mat-icon>
                          } @else {
                            <mat-icon style="font-size:14px;color:#ef4444">cancel</mat-icon>
                          }
                          <span [style.font-weight]="doc.mandatory ? '600' : '400'">{{ doc.localName }}</span>
                          @if (!doc.mandatory) {
                            <span style="color:var(--rw-text-muted)">(recommended)</span>
                          }
                        </div>
                      }
                    </div>
                  } @else {
                    <p style="font-size:12px;color:var(--rw-text-muted);margin-bottom:12px">
                      <button mat-button (click)="loadCompliance(jur)">Load compliance checklist</button>
                    </p>
                  }

                  <div style="display:flex;gap:8px">
                    <button mat-raised-button color="primary" (click)="approveJurisdiction(jur)"
                            [disabled]="jurActionLoading[jur]">
                      <mat-icon>check_circle</mat-icon> Approve
                    </button>
                    <button mat-stroked-button color="warn" (click)="rejectJurisdiction(jur)"
                            [disabled]="jurActionLoading[jur]">
                      <mat-icon>cancel</mat-icon> Reject
                    </button>
                  </div>
                </mat-card-content>
              </mat-card>
            }
          </div>
        </mat-tab>

        <!-- Identities Tab (ONCHAINID) -->
        <mat-tab label="Identities">
          <div class="tab-content">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
              <strong>On-Chain Identities (ONCHAINID)</strong>
              <button mat-raised-button color="primary" (click)="deployIdentity()">
                <mat-icon>add</mat-icon> Deploy ONCHAINID
              </button>
            </div>
            @if (identitiesLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else {
              @for (identity of identities; track identity.id) {
                <mat-card style="margin-bottom:16px">
                    <mat-card-header>
                     <mat-card-title style="font-size:14px;display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                       <span>
                         <mat-icon style="vertical-align:middle;margin-right:4px">account_balance_wallet</mat-icon>
                         {{ identity.chainIdentifier }}
                       </span>
                       <app-data-state-pill [status]="asyncStatus(identity.syncStatus)" />
                     </mat-card-title>
                     <mat-card-subtitle><app-address [address]="identity.identityAddress" /></mat-card-subtitle>
                   </mat-card-header>
                   <mat-card-content style="padding-top:12px">
                     <div style="margin-bottom:8px;font-size:13px;color:var(--rw-text-secondary)">
                       Deployed: {{ identity.deployedAt | date:'mediumDate' }}
                     </div>
                     @if (identity.syncStatus !== 'READY') {
                       <div style="margin-bottom:12px;font-size:12px;color:var(--rw-text-secondary)">
                         {{ identity.syncStatus === 'PENDING' ? 'Deployment submitted and awaiting chain confirmation.' : 'Identity data is refreshing from the chain.' }}
                       </div>
                     }
                     <div style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px">
                       @for (claim of identity.activeClaims; track claim.topic) {
                         <span [style.background]="claim.isRevoked ? '#ffebee' : '#e8f5e9'"
                              style="padding:3px 10px;border-radius:12px;font-size:12px;font-weight:500">
                          {{ claim.topicLabel || ('Topic '+claim.topic) }}
                          {{ claim.isRevoked ? '✗' : '✓' }}
                          @if (claim.expiresAt) { (until {{ claim.expiresAt | date:'shortDate' }}) }
                        </span>
                      } @empty {
                        <span style="font-size:12px;color:var(--rw-text-secondary)">No claims issued</span>
                      }
                     </div>
                     <div style="display:flex;gap:8px">
                       <button mat-stroked-button color="primary" (click)="issueKycClaim(identity)" [disabled]="identity.syncStatus !== 'READY'">
                         <mat-icon>verified</mat-icon> Issue KYC Claim
                       </button>
                       <button mat-stroked-button (click)="issueAmlClaim(identity)" [disabled]="identity.syncStatus !== 'READY'">
                         Issue AML Claim
                       </button>
                     </div>
                   </mat-card-content>
                </mat-card>
              } @empty {
                <p style="text-align:center;padding:24px;color:var(--rw-text-secondary)">
                  No ONCHAINID deployed yet. Click "Deploy ONCHAINID" to create one on a supported chain.
                </p>
              }
            }
          </div>
        </mat-tab>

        <!-- History Tab -->
        <mat-tab label="Name History">
          <div class="tab-content">
            @if (historyLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else {
              @for (entry of nameHistory; track entry.id) {
                <div class="history-item">
                  <div>
                    <div class="name">{{ entry.name }}</div>
                    <div class="dates">
                      From {{ entry.effectiveFrom | date:'mediumDate' }}
                      @if (entry.effectiveTo) {
                        &nbsp;— {{ entry.effectiveTo | date:'mediumDate' }}
                      } @else {
                        &nbsp;— current
                      }
                    </div>
                  </div>
                </div>
              } @empty {
                <p class="text-muted" style="text-align:center;padding:24px">No name history.</p>
              }
            }
          </div>
        </mat-tab>
      </mat-tab-group>
    } @else {
      <p class="text-muted">Entity not found.</p>
    }
  `,
})
export class CustomerDetailComponent implements OnInit {
  @Input() id!: string;

  private readonly entityService = inject(EntityService);
  private readonly kycService = inject(KycService);
  private readonly adminUserService = inject(AdminUserService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  loading = true;
  docsLoading = false;
  historyLoading = false;
  identitiesLoading = false;

  entity: LegalEntity | null = null;
  documents: KycDocument[] = [];
  nameHistory: LegalEntityNameHistory[] = [];
  identities: OnchainIdentityView[] = [];

  docColumns = ['documentType', 'jurisdiction', 'fileName', 'sizeBytes', 'uploadedAt', 'actions'];
  showRejectForm = false;
  rejectReason = '';

  // ── Jurisdiction KYC ──────────────────────────────────────────────────────
  readonly allJurisdictions: Jurisdiction[] = ['DE_EWPG', 'LU_CSSF', 'FR_AMF', 'LI_TVTG'];
  jurisdictionApprovals: KycJurisdictionApproval[] = [];
  complianceByJurisdiction: Partial<Record<Jurisdiction, KycComplianceResponse>> = {};
  jurActionLoading: Partial<Record<Jurisdiction, boolean>> = {};

  ngOnInit(): void {
    this.loadEntity();
  }

  loadEntity(): void {
    this.loading = true;
    this.entityService.getEntity(this.id).subscribe({
      next: (entity) => {
        this.entity = entity;
        this.loading = false;
        this.cdr.markForCheck();
        this.loadDocuments();
        this.loadHistory();
        this.loadIdentities();
        this.loadJurisdictionApprovals();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  loadDocuments(): void {
    this.docsLoading = true;
    this.kycService.listDocuments(this.id).subscribe({
      next: (docs) => { this.documents = docs; this.docsLoading = false; this.cdr.markForCheck(); },
      error: () => { this.docsLoading = false; this.cdr.markForCheck(); },
    });
  }

  loadJurisdictionApprovals(): void {
    this.kycService.getJurisdictionApprovals(this.id).subscribe({
      next: (approvals) => { this.jurisdictionApprovals = approvals; this.cdr.markForCheck(); },
    });
  }

  loadCompliance(jurisdiction: Jurisdiction): void {
    this.kycService.getCompliance(this.id, jurisdiction).subscribe({
      next: (result) => { this.complianceByJurisdiction = { ...this.complianceByJurisdiction, [jurisdiction]: result }; this.cdr.markForCheck(); },
    });
  }

  getJurisdictionStatus(jur: Jurisdiction): string {
    return this.jurisdictionApprovals.find(a => a.jurisdiction === jur)?.status ?? 'PENDING';
  }

  jurisdictionLabel(jur: Jurisdiction): string {
    const labels: Record<Jurisdiction, string> = {
      DE_EWPG: 'Germany — eWpG / BaFin',
      LU_CSSF: 'Luxembourg — CSSF',
      FR_AMF: 'France — AMF',
      LI_TVTG: 'Liechtenstein — TVTG / FMA',
    };
    return labels[jur] ?? jur;
  }

  approveJurisdiction(jur: Jurisdiction): void {
    this.jurActionLoading = { ...this.jurActionLoading, [jur]: true };
    this.kycService.approveJurisdiction(this.id, jur).subscribe({
      next: () => {
        this.jurActionLoading = { ...this.jurActionLoading, [jur]: false };
        this.cdr.markForCheck();
        this.loadJurisdictionApprovals();
        this.loadCompliance(jur);
      },
      error: () => { this.jurActionLoading = { ...this.jurActionLoading, [jur]: false }; this.cdr.markForCheck(); },
    });
  }

  rejectJurisdiction(jur: Jurisdiction): void {
    const reason = prompt('Rejection reason (required):');
    if (!reason) return;
    this.jurActionLoading = { ...this.jurActionLoading, [jur]: true };
    this.kycService.rejectJurisdiction(this.id, jur, reason).subscribe({
      next: () => {
        this.jurActionLoading = { ...this.jurActionLoading, [jur]: false };
        this.cdr.markForCheck();
        this.loadJurisdictionApprovals();
      },
      error: () => { this.jurActionLoading = { ...this.jurActionLoading, [jur]: false }; this.cdr.markForCheck(); },
    });
  }

  formatDocType(type: string): string {
    return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
  }

  loadHistory(): void {
    this.historyLoading = true;
    this.entityService.getEntityHistory(this.id).subscribe({
      next: (history) => {
        this.nameHistory = history;
        this.historyLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.historyLoading = false;
        this.cdr.markForCheck();
      },
    });
  }

  uploadDoc(): void {
    const input = document.querySelector<HTMLInputElement>('input[type="file"]');
    input?.click();
  }

  onFileSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.kycService.uploadDocument(this.id, file, 'GENERAL').subscribe({
      next: () => this.loadDocuments(),
    });
  }

  downloadDoc(doc: KycDocument): void {
    this.kycService.downloadDocument(this.id, doc.id).subscribe((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = doc.fileName;
      a.click();
      URL.revokeObjectURL(url);
    });
  }

  approveKyc(): void {
    const expiry = new Date();
    expiry.setFullYear(expiry.getFullYear() + 1);
    this.kycService.approveKyc(this.id, expiry.toISOString().split('T')[0]).subscribe({
      next: () => {
        this.loadEntity();
        // Prompt operator to also issue on-chain KYC claim
        if (this.identities.length > 0 && confirm(
          `KYC approved. Issue KYC claim on-chain for all deployed ONCHAINID identities?`)) {
          this.identities.forEach(identity => this.issueKycClaim(identity));
        }
      },
    });
  }

  loadIdentities(): void {
    this.identitiesLoading = true;
    this.http.get<OnchainIdentityView[]>(
      `${environment.apiUrl}/entities/${this.id}/onchain-identity`
    ).subscribe({
      next: (ids) => { this.identities = ids; this.identitiesLoading = false; this.cdr.markForCheck(); },
      error: () => { this.identitiesLoading = false; this.cdr.markForCheck(); },
    });
  }

  asyncStatus(syncStatus: SyncStatus): AsyncSectionStatus {
    switch (syncStatus) {
      case 'PENDING':
        return 'pending';
      case 'UPDATING':
        return 'updating';
      default:
        return 'ready';
    }
  }

  deployIdentity(): void {
    const chainConfigId = prompt('Chain config UUID to deploy ONCHAINID on:');
    if (!chainConfigId) return;
    this.http.post<OnchainIdentityView>(
      `${environment.apiUrl}/entities/${this.id}/onchain-identity`,
      { chainConfigId }
    ).subscribe({
      next: (identity) => { this.identities = [...this.identities, identity]; },
    });
  }

  issueKycClaim(identity: OnchainIdentityView): void {
    const expiresAt = new Date();
    expiresAt.setFullYear(expiresAt.getFullYear() + 1);
    this.http.post(
      `${environment.apiUrl}/entities/${this.id}/onchain-identity/${identity.id}/claims/kyc`,
      { expiresAt: expiresAt.toISOString() }
    ).subscribe({
      next: () => this.loadIdentities(),
    });
  }

  issueAmlClaim(identity: OnchainIdentityView): void {
    this.http.post(
      `${environment.apiUrl}/entities/${this.id}/onchain-identity/${identity.id}/claims/aml`,
      {}
    ).subscribe({
      next: () => this.loadIdentities(),
    });
  }

  rejectKyc(): void {
    this.kycService.rejectKyc(this.id, this.rejectReason).subscribe({
      next: () => {
        this.showRejectForm = false;
        this.rejectReason = '';
        this.loadEntity();
      },
    });
  }

  suspend(): void {
    this.entityService.suspendEntity(this.id).subscribe({ next: () => this.loadEntity() });
  }

  reactivate(): void {
    this.entityService.reactivateEntity(this.id).subscribe({ next: () => this.loadEntity() });
  }

  dissolve(): void {
    if (!confirm('Are you sure you want to dissolve this entity? This action cannot be undone.')) return;
    this.entityService.dissolveEntity(this.id).subscribe({ next: () => this.loadEntity() });
  }

  generateToken(): void {
    this.router.navigate(['/onboarding/token', this.id]);
  }

  openAsCompany(): void {
    if (!this.entity) return;
    this.adminUserService.impersonate(this.entity.id).subscribe({
      next: (res) => {
        window.open(res.handoffUrl, '_blank');
      },
      error: (err) => {
        alert(err?.error?.message ?? 'Impersonation failed');
      },
    });
  }

  goBack(): void {
    this.router.navigate(['/customers']);
  }
}
