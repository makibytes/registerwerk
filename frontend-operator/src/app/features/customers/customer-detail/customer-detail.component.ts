import { ChangeDetectorRef, Component, ElementRef, OnInit, inject, Input, ViewChild } from '@angular/core';
import { switchMap } from 'rxjs/operators';
import { downloadBlob } from '../../../core/utils/download.util';
import { showActionError } from '../../../shared/utils/action-error';
import { Router, RouterLink } from '@angular/router';
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
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { EntityService } from '../../../core/api/entity.service';
import { AdminUserService } from '../../../core/api/admin-user.service';
import { ScreeningService } from '../../../core/api/screening.service';
import { HolderBlockService } from '../../../core/api/holder-block.service';
import { BeneficialOwnerService } from '../../../core/api/beneficial-owner.service';
import { PortfolioMigrationComponent } from '../wizards/portfolio-migration/portfolio-migration.component';
import { MifidClassificationComponent } from '../mifid-classification/mifid-classification.component';
import { CorporateActionsService } from '../../../core/api/corporate-actions.service';
import { environment } from '../../../../environments/environment';
import { AddressComponent } from '../../../shared/components/address.component';
import { KycService } from '../../../core/api/kyc.service';
import { GasSponsorshipService, GasSponsorshipPolicy, GasSponsor } from '../../../core/api/gas-sponsorship.service';
import { DualControlTokens } from '../../../core/api/dual-control-headers';
import { ImpersonateDialogComponent, ImpersonateDialogResult } from './impersonate-dialog.component';
import { openStepUp } from '../../../shared/components/step-up/open-step-up';
import { withDualControl } from '../../../shared/components/step-up/with-dual-control';
import { KycRejectDialogComponent, KycRejectDialogResult } from './kyc-reject-dialog.component';
import { AsyncSectionStatus } from '../../../core/async/async-section';
import {
  LegalEntity, KycDocument, LegalEntityNameHistory, EntityMergeRecordView,
  KycJurisdictionApproval, KycComplianceResponse, Jurisdiction,
  SyncStatus, ScreeningRun, HolderBlock,
  BeneficialOwner,
} from '../../../core/models';

import { DataStatePillComponent, StatusBadgeComponent } from '@registerwerk/ui';
import { AuthService } from '../../../core/auth/auth.service';

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
    RouterLink,
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
    PortfolioMigrationComponent,
    MifidClassificationComponent,
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
        flex-wrap: wrap;
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

    .request-error {
      display: grid;
      justify-items: center;
      gap: 12px;
      padding: 40px 20px;
      color: var(--rw-text-danger);
      text-align: center;
    }

    .reject-form {
      margin-top: 12px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      max-width: 480px;
    }

    @media (max-width: 720px) {
      .entity-header { gap: 16px; flex-direction: column; }
      .kyc-actions { align-items: flex-start; flex-direction: column; }
    }
  `],
  template: `
    <div class="back-row">
      <button type="button" mat-button (click)="goBack()">
        <mat-icon>arrow_back</mat-icon>
        Back to Customers
      </button>
    </div>
    <h1 class="sr-only">Customer details</h1>

    @if (loading) {
      <div class="spinner-wrap"><mat-spinner diameter="40" /></div>
    } @else if (loadError) {
      <div class="request-error" role="alert">
        <mat-icon>cloud_off</mat-icon>
        <span>The customer could not be loaded.</span>
        <button mat-stroked-button type="button" (click)="loadEntity()">Retry</button>
      </div>
    } @else if (entity) {
      <div class="entity-header">
        <div class="entity-title">
          <h2>{{ entity.currentName }}</h2>
          <span class="entity-number">{{ entity.entityNumber }}</span>
          &nbsp;
          <app-status-badge [status]="entity.status" />
        </div>
        <div class="entity-actions">
          @if (canMutate) {
          @if (entity.status === 'ACTIVE') {
            <button type="button" mat-stroked-button color="warn" (click)="suspend()">Suspend</button>
          }
          @if (entity.status === 'SUSPENDED') {
            <button type="button" mat-stroked-button color="primary" (click)="reactivate()">Reactivate</button>
          }
          @if (entity.status === 'CLOSED' || entity.status === 'DISSOLVED') {
            <button type="button" mat-stroked-button color="primary" (click)="reinstate()"
                    matTooltip="Start reinstatement: moves to PENDING_REACTIVATION (never straight to ACTIVE). Needs a legal reference, step-up and a second approver; re-screening and a fresh KYC approval are required before the customer is active again.">
              Reinstate
            </button>
          }
          @if (entity.status === 'PENDING_REACTIVATION') {
            <span class="entity-number" matTooltip="Reinstatement pending: approve KYC afresh to reactivate. Trading, settlement and issuance stay blocked.">Reinstatement pending - fresh KYC approval required</span>
          }
          @if (entity.status !== 'CLOSED' && entity.status !== 'DISSOLVED') {
            <button type="button" mat-stroked-button color="warn" (click)="terminate()" matTooltip="End the customer relationship: disables users, cancels open listings, revokes admin grants, moves to CLOSED. Requires step-up + a second approver.">
              Terminate
            </button>
          }
          @if (entity.kycStatus !== 'APPROVED') {
            <button type="button" mat-stroked-button (click)="approveKyc()"
                    matTooltip="Approve this customer's KYC (the server sets the expiry, at most 12 months). Requires step-up + a second approver.">
              <mat-icon>verified</mat-icon>
              Approve KYC
            </button>
            <button type="button" mat-stroked-button (click)="approveKyc(true)"
                    matTooltip="Approve despite a failed gate (registry administrator only): needs a documented override note, step-up and a second approver.">
              <mat-icon>rule</mat-icon>
              Approve with override
            </button>
          }
          @if (entity.kycStatus !== 'REJECTED') {
            <button type="button" mat-stroked-button color="warn" (click)="rejectKyc()"
                    matTooltip="Reject this customer's KYC: internal reason plus a category the customer sees. Requires step-up + a second approver.">
              <mat-icon>cancel</mat-icon>
              Reject KYC
            </button>
          }
          <button type="button" mat-raised-button color="primary" (click)="generateToken()">
            <mat-icon>key</mat-icon>
            Onboarding Token
          </button>
          <button type="button" mat-stroked-button (click)="openAsCompany()" matTooltip="Open this company in the customer portal as an admin">
            <mat-icon>open_in_new</mat-icon>
            Open as Company
          </button>
          }
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
              <div class="field-item">
                <div class="field-label">Relationship Manager</div>
                <div class="field-value">
                  @if (canMutate && editingRm) {
                    <div style="display:flex;gap:8px;align-items:center">
                      <input matInput [(ngModel)]="rmIdInput" placeholder="Staff user ID (blank to clear)"
                             style="border:1px solid var(--rw-border);border-radius:4px;padding:4px 8px;font-size:13px;width:220px" />
                      <button type="button" mat-icon-button color="primary" (click)="saveRelationshipManager()" matTooltip="Save">
                        <mat-icon>check</mat-icon>
                      </button>
                      <button type="button" mat-icon-button (click)="editingRm = false" matTooltip="Cancel">
                        <mat-icon>close</mat-icon>
                      </button>
                    </div>
                  } @else {
                    <code>{{ entity.assignedRelationshipManagerId ?? 'Unassigned' }}</code>
                    @if (canMutate) {
                    <button type="button" mat-icon-button (click)="startEditRelationshipManager()" matTooltip="Assign relationship manager">
                      <mat-icon>edit</mat-icon>
                    </button>
                    }
                  }
                </div>
              </div>
            </div>
          </div>
        </mat-tab>

        <!-- KYC Documents -->
        <mat-tab label="KYC Documents">
          <div class="tab-content">
            @if (canMutate) {
            <div class="kyc-actions">
              <label>
                <input #fileInput type="file" style="display:none" (change)="onFileSelected($event)" />
                <button type="button" mat-raised-button color="primary" (click)="fileInput.click()">
                  <mat-icon>upload</mat-icon> Upload Document
                </button>
              </label>
            </div>
            }

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
                    <button type="button" mat-icon-button (click)="downloadDoc(doc)" matTooltip="Download">
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
                      <span style="font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;background:var(--rw-approved-bg);color:var(--rw-approved-fg)">APPROVED</span>
                    } @else if (getJurisdictionStatus(jur) === 'REJECTED') {
                      <span style="font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;background:var(--rw-rejected-bg);color:var(--rw-rejected-fg)">REJECTED</span>
                    } @else if (getJurisdictionStatus(jur) === 'UNKNOWN') {
                      <span style="font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;background:var(--rw-surface-soft, var(--rw-surface));color:var(--rw-text-muted);border:1px solid var(--rw-border)">Unknown (could not be loaded)</span>
                    } @else {
                      <span style="font-size:11px;font-weight:700;padding:2px 8px;border-radius:4px;background:var(--rw-pending-bg);color:var(--rw-pending-fg)">PENDING</span>
                    }
                  </mat-card-title>
                </mat-card-header>
                <mat-card-content style="padding-top:12px">
                  @if (complianceByJurisdiction[jur]) {
                    @let cr = complianceByJurisdiction[jur];
                    <div style="display:flex;gap:16px;flex-wrap:wrap;margin-bottom:12px">
                      @if (cr.fullyCompliant) {
                        <span style="color:var(--rw-text-success);font-size:13px"><mat-icon style="font-size:16px;vertical-align:middle">check_circle</mat-icon> All required documents present</span>
                      } @else {
                        @if (cr.missingCount > 0) {
                          <span style="color:var(--rw-text-danger);font-size:13px"><mat-icon style="font-size:16px;vertical-align:middle">cancel</mat-icon> {{ cr.missingCount }} missing</span>
                        }
                        @if (cr.tooOldCount > 0) {
                          <span style="color:var(--rw-text-warning);font-size:13px"><mat-icon style="font-size:16px;vertical-align:middle">schedule</mat-icon> {{ cr.tooOldCount }} too old</span>
                        }
                        @if (cr.expiredCount > 0) {
                          <span style="color:var(--rw-text-danger);font-size:13px"><mat-icon style="font-size:16px;vertical-align:middle">event_busy</mat-icon> {{ cr.expiredCount }} expired</span>
                        }
                      }
                    </div>
                    <div style="display:flex;flex-direction:column;gap:4px;margin-bottom:12px">
                      @for (doc of cr.documents; track doc.documentType) {
                        <div style="display:flex;align-items:center;gap:8px;font-size:12px;padding:4px 0">
                          @if (!doc.mandatory) {
                            <mat-icon style="font-size:14px;color:var(--rw-text-muted)">radio_button_unchecked</mat-icon>
                          } @else if (doc.present && !doc.expired && !doc.tooOld) {
                            <mat-icon style="font-size:14px;color:var(--rw-text-success)">check_circle</mat-icon>
                          } @else if (doc.tooOld) {
                            <mat-icon style="font-size:14px;color:var(--rw-text-warning)">schedule</mat-icon>
                          } @else {
                            <mat-icon style="font-size:14px;color:var(--rw-text-danger)">cancel</mat-icon>
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
                      <button type="button" mat-button (click)="loadCompliance(jur)">Load compliance checklist</button>
                    </p>
                  }

                  @if (canMutate) {
                  <div style="display:flex;gap:8px">
                    <button type="button" mat-raised-button color="primary" (click)="approveJurisdiction(jur)"
                            [disabled]="jurActionLoading[jur]">
                      <mat-icon>check_circle</mat-icon> Approve
                    </button>
                    <button type="button" mat-stroked-button color="warn" (click)="rejectJurisdiction(jur)"
                            [disabled]="jurActionLoading[jur]">
                      <mat-icon>cancel</mat-icon> Reject
                    </button>
                  </div>
                  }
                </mat-card-content>
              </mat-card>
            }
          </div>
        </mat-tab>

        <!-- Beneficial Owners (UBO) -->
        <mat-tab label="Beneficial Owners">
          <div class="tab-content">
            <p style="font-size:13px;color:var(--rw-text-secondary);margin-bottom:16px">
              Beneficial owners (GwG §3, AMLR Art. 42). Adding an owner immediately triggers a
              sanctions/PEP screening; <code>BeneficialOwnerScreeningJob</code> re-screens active
              owners nightly.
            </p>

            @if (ubosLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else {
              <table mat-table [dataSource]="beneficialOwners" style="width:100%;margin-bottom:24px">
                <ng-container matColumnDef="name">
                  <th mat-header-cell *matHeaderCellDef>Name</th>
                  <td mat-cell *matCellDef="let bo">{{ bo.givenName }} {{ bo.familyName }}</td>
                </ng-container>
                <ng-container matColumnDef="country">
                  <th mat-header-cell *matHeaderCellDef>Country</th>
                  <td mat-cell *matCellDef="let bo">{{ bo.country ?? '—' }}</td>
                </ng-container>
                <ng-container matColumnDef="pepStatus">
                  <th mat-header-cell *matHeaderCellDef>PEP Status</th>
                  <td mat-cell *matCellDef="let bo">
                    <span [style.color]="bo.pepStatus === 'NOT_PEP' || bo.pepStatus === 'UNKNOWN' ? 'var(--rw-text-secondary)' : 'var(--rw-text-danger)'">
                      {{ bo.pepStatus.replace('_',' ') }}
                    </span>
                  </td>
                </ng-container>
                <ng-container matColumnDef="ownershipPct">
                  <th mat-header-cell *matHeaderCellDef>Ownership</th>
                  <td mat-cell *matCellDef="let bo">{{ bo.ownershipPct != null ? (bo.ownershipPct + '%') : '—' }}</td>
                </ng-container>
                <ng-container matColumnDef="controlType">
                  <th mat-header-cell *matHeaderCellDef>Control Type</th>
                  <td mat-cell *matCellDef="let bo">{{ bo.controlType.replace('_',' ') }}</td>
                </ng-container>
                <ng-container matColumnDef="registeredAt">
                  <th mat-header-cell *matHeaderCellDef>Registered</th>
                  <td mat-cell *matCellDef="let bo">{{ bo.registeredAt | date:'mediumDate' }}</td>
                </ng-container>
                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef></th>
                  <td mat-cell *matCellDef="let bo">
                    @if (canMutate) {
                    @if (!bo.verifiedAt) {
                      <button type="button" mat-icon-button matTooltip="Verify against a KYC document (needed for the identified share)"
                              (click)="verifyBeneficialOwner(bo)">
                        <mat-icon style="font-size:18px">fact_check</mat-icon>
                      </button>
                    }
                    @if (bo.pepStatus === 'CONFIRMED_PEP') {
                      <button type="button" mat-icon-button matTooltip="Approve enhanced due diligence (step-up + second approver)"
                              (click)="approveEdd(bo)">
                        <mat-icon style="font-size:18px">policy</mat-icon>
                      </button>
                    }
                    <button type="button" mat-icon-button color="warn" matTooltip="Cease (mark no longer a beneficial owner)"
                            (click)="ceaseBeneficialOwner(bo)">
                      <mat-icon style="font-size:18px">person_remove</mat-icon>
                    </button>
                    }
                  </td>
                </ng-container>
                <tr mat-header-row *matHeaderRowDef="uboColumns"></tr>
                <tr mat-row *matRowDef="let row; columns: uboColumns;"></tr>
              </table>
              @if (beneficialOwners.length === 0) {
                <p style="text-align:center;padding:24px;color:var(--rw-text-secondary);font-size:13px">
                  No beneficial owners registered for this entity.
                </p>
              }
            }

            @if (canMutate) {
            <mat-divider style="margin-bottom:20px"></mat-divider>
            <h4 style="margin:0 0 12px">Register a Beneficial Owner</h4>
            <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end">
              <mat-form-field appearance="outline">
                <mat-label>Given name</mat-label>
                <input matInput [(ngModel)]="uboForm.givenName" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Family name</mat-label>
                <input matInput [(ngModel)]="uboForm.familyName" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Date of birth</mat-label>
                <input matInput type="date" [(ngModel)]="uboForm.dateOfBirth" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Nationality</mat-label>
                <input matInput [(ngModel)]="uboForm.nationality" placeholder="DE" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Country of residence</mat-label>
                <input matInput [(ngModel)]="uboForm.countryOfResidence" placeholder="DE" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Ownership %</mat-label>
                <input matInput type="number" min="0" max="100" step="0.01" [(ngModel)]="uboForm.ownershipPct" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Control type</mat-label>
                <mat-select [(ngModel)]="uboForm.controlType">
                  <mat-option value="DIRECT_OWNERSHIP">Direct ownership</mat-option>
                  <mat-option value="INDIRECT_OWNERSHIP">Indirect ownership</mat-option>
                  <mat-option value="OTHER_CONTROL">Other control</mat-option>
                  <mat-option value="LEGAL_REPRESENTATIVE">Legal representative</mat-option>
                  <mat-option value="TRUSTEE">Trustee</mat-option>
                  <mat-option value="SENIOR_MANAGING_OFFICIAL">Senior managing official (fallback)</mat-option>
                </mat-select>
              </mat-form-field>
              @if (uboForm.controlType === 'SENIOR_MANAGING_OFFICIAL') {
                <mat-form-field appearance="outline">
                  <mat-label>Why no beneficial owner could be identified</mat-label>
                  <input matInput [(ngModel)]="uboForm.fallbackReason" />
                </mat-form-field>
              }
              <mat-form-field appearance="outline">
                <mat-label>Source</mat-label>
                <input matInput [(ngModel)]="uboForm.source" placeholder="e.g. Commercial register extract" />
              </mat-form-field>
              <button type="button" mat-raised-button color="primary"
                      [disabled]="!uboForm.givenName || !uboForm.familyName || uboSaving || (uboForm.controlType === 'SENIOR_MANAGING_OFFICIAL' && !uboForm.fallbackReason.trim())"
                      (click)="addBeneficialOwner()">
                <mat-icon>person_add</mat-icon>
                Register
              </button>
            </div>
            }
          </div>
        </mat-tab>

        <!-- Portfolio Migration (investors only) -->
        @if (canMutate && entity.type === 'INVESTOR') {
          <mat-tab label="Portfolio Migration">
            <app-portfolio-migration [investorEntityId]="id" />
          </mat-tab>
        }

        <!-- MiFID II Classification & Suitability -->
        @if (canMutate) {
          <mat-tab label="MiFID Classification">
            <app-mifid-classification [entityId]="id" />
          </mat-tab>
        }

        <!-- Identities Tab (ONCHAINID) -->
        <mat-tab label="Identities">
          <div class="tab-content">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
              <strong>On-Chain Identities (ONCHAINID)</strong>
              @if (canMutate) {
              <button type="button" mat-raised-button color="primary" (click)="deployIdentity()">
                <mat-icon>add</mat-icon> Deploy ONCHAINID
              </button>
              }
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
                         <span [style.background]="claim.isRevoked ? 'var(--rw-rejected-bg)' : 'var(--rw-approved-bg)'"
                              [style.color]="claim.isRevoked ? 'var(--rw-rejected-fg)' : 'var(--rw-approved-fg)'"
                              style="padding:3px 10px;border-radius:12px;font-size:12px;font-weight:500">
                          {{ claim.topicLabel || ('Topic '+claim.topic) }}
                          {{ claim.isRevoked ? '✗' : '✓' }}
                          @if (claim.expiresAt) { (until {{ claim.expiresAt | date:'shortDate' }}) }
                        </span>
                      } @empty {
                        <span style="font-size:12px;color:var(--rw-text-secondary)">No claims issued</span>
                      }
                     </div>
                     @if (canMutate) {
                     <div style="display:flex;gap:8px">
                       <button type="button" mat-stroked-button color="primary" (click)="issueKycClaim(identity)" [disabled]="identity.syncStatus !== 'READY'">
                         <mat-icon>verified</mat-icon> Issue KYC Claim
                       </button>
                       <button type="button" mat-stroked-button (click)="issueAmlClaim(identity)" [disabled]="identity.syncStatus !== 'READY'">
                         Issue AML Claim
                       </button>
                     </div>
                     }
                   </mat-card-content>
                </mat-card>
              } @empty {
                <p style="text-align:center;padding:24px;color:var(--rw-text-secondary)">
                  {{ canMutate ? 'No ONCHAINID deployed yet. Click "Deploy ONCHAINID" to create one on a supported chain.' : 'No ONCHAINID identities are deployed.' }}
                </p>
              }
            }
          </div>
        </mat-tab>

        <!-- History Tab -->
        <mat-tab label="History">
          <div class="tab-content">
            @if (historyLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else {
              <h4 style="margin:0 0 8px">Name Changes</h4>
              @for (entry of nameHistory; track entry.id) {
                <div class="history-item">
                  <div>
                    <div class="name">{{ entry.previousName }} → {{ entry.newName }}</div>
                    <div class="dates">
                      Effective {{ entry.effectiveDate | date:'mediumDate' }}
                      &nbsp;— {{ entry.changeType.replace('_', ' ') }}
                    </div>
                  </div>
                </div>
              } @empty {
                <p class="text-muted" style="text-align:center;padding:16px">No name history.</p>
              }

              <mat-divider style="margin:16px 0" />

              <h4 style="margin:0 0 8px">Mergers &amp; Acquisitions</h4>
              @for (record of mergeRecords; track record.id) {
                <div class="history-item">
                  <div>
                    <div class="name">
                      {{ record.sourceEntityId === id ? 'Absorbed into' : 'Absorbed' }}
                      {{ record.sourceEntityId === id ? record.targetEntityId : record.sourceEntityId }}
                    </div>
                    <div class="dates">
                      Effective {{ record.effectiveDate | date:'mediumDate' }}
                      &nbsp;— {{ record.mergeType }}
                      @if (record.notes) { &nbsp;— {{ record.notes }} }
                    </div>
                  </div>
                </div>
              } @empty {
                <p class="text-muted" style="text-align:center;padding:16px">No merge records.</p>
              }

              @if (canMutate && entity && entity.status !== 'DISSOLVED') {
                <mat-divider style="margin:16px 0" />
                <h4 style="margin:0 0 8px">Record a Merger</h4>
                <p class="hint-text" style="margin:0 0 8px;font-size:12px;color:var(--rw-text-secondary)">
                  Records this entity as absorbed into another (M&amp;A event) and marks it dissolved (needs step-up and a second approver) —
                  German commercial law requires this history to be retained, not deleted.
                </p>
                <div class="row-2" style="display:grid;grid-template-columns:1fr 1fr;gap:12px;max-width:640px">
                  <mat-form-field appearance="outline">
                    <mat-label>Target entity ID (surviving entity)</mat-label>
                    <input matInput [(ngModel)]="mergeForm.targetEntityId" placeholder="xxxxxxxx-…" />
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Merge type</mat-label>
                    <mat-select [(ngModel)]="mergeForm.mergeType">
                      <mat-option value="ABSORPTION">Absorption</mat-option>
                      <mat-option value="CONSOLIDATION">Consolidation</mat-option>
                    </mat-select>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Effective date</mat-label>
                    <input matInput type="date" [(ngModel)]="mergeForm.effectiveDate" />
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Notes</mat-label>
                    <input matInput [(ngModel)]="mergeForm.notes" />
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Reason (required, audited)</mat-label>
                    <input matInput [(ngModel)]="mergeForm.reason" />
                  </mat-form-field>
                </div>
                <button type="button" mat-raised-button color="warn"
                        [disabled]="!mergeForm.targetEntityId || !mergeForm.effectiveDate || !mergeForm.reason.trim()"
                        (click)="recordMerger()">
                  <mat-icon>call_merge</mat-icon>
                  Record Merger
                </button>
              }
            }
          </div>
        </mat-tab>

        <!-- Holder Blocks Tab -->
        <mat-tab label="Holder Blocks">
          <div class="tab-content">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
              <div>
                <strong style="font-size:14px">Sperrvermerk (§16 eWpG)</strong>
                <div style="font-size:12px;color:var(--rw-text-secondary);margin-top:2px">
                  Active legal blocks on this entity's wallets
                </div>
              </div>
              @if (canMutate) {
                <button type="button" mat-stroked-button color="warn"
                        [routerLink]="'/compliance/holder-blocks'">
                  <mat-icon>gavel</mat-icon>
                  Manage Blocks
                </button>
              }
            </div>

            @if (blocksLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else {
              <table mat-table [dataSource]="holderBlocks" style="width:100%">
                <ng-container matColumnDef="blockType">
                  <th mat-header-cell *matHeaderCellDef>Type</th>
                  <td mat-cell *matCellDef="let b">{{ b.blockType.replace('_',' ') }}</td>
                </ng-container>
                <ng-container matColumnDef="walletAddress">
                  <th mat-header-cell *matHeaderCellDef>Wallet</th>
                  <td mat-cell *matCellDef="let b" style="font-family:monospace;font-size:12px">{{ b.walletAddress }}</td>
                </ng-container>
                <ng-container matColumnDef="legalBasis">
                  <th mat-header-cell *matHeaderCellDef>Legal Basis</th>
                  <td mat-cell *matCellDef="let b">{{ b.legalBasis }}</td>
                </ng-container>
                <ng-container matColumnDef="startsAt">
                  <th mat-header-cell *matHeaderCellDef>Active Since</th>
                  <td mat-cell *matCellDef="let b">{{ b.startsAt | date:'mediumDate' }}</td>
                </ng-container>
                <ng-container matColumnDef="expiresAt">
                  <th mat-header-cell *matHeaderCellDef>Expires</th>
                  <td mat-cell *matCellDef="let b">{{ b.expiresAt ? (b.expiresAt | date:'mediumDate') : '—' }}</td>
                </ng-container>
                <tr mat-header-row *matHeaderRowDef="holderBlockColumns"></tr>
                <tr mat-row *matRowDef="let row; columns: holderBlockColumns;"></tr>
              </table>
              @if (holderBlocks.length === 0) {
                <p style="text-align:center;padding:24px;color:var(--rw-text-secondary);font-size:13px">
                  No active holder blocks for this entity.
                </p>
              }
            }
          </div>
        </mat-tab>

        <!-- Gas Sponsorship Default (issuers only) -->
        @if (entity.type === 'ISSUER') {
          <mat-tab label="Gas Sponsorship">
            <div class="tab-content">
              <p style="font-size:13px;color:var(--rw-text-secondary);margin:0 0 16px;max-width:640px">
                This issuer's default gas-sponsorship policy, inherited by every future asset
                deployment unless a specific deployment overrides it (set on the asset's
                deployment detail page). Sponsored transactions run through the on-chain
                <code>EwpgPaymaster</code>. If no default and no override exists, investors
                pay their own gas.
              </p>

              @if (canMutate) {
              <div style="display:flex;gap:12px;align-items:flex-end;margin-bottom:20px;flex-wrap:wrap">
                <mat-form-field appearance="outline">
                  <mat-label>Sponsor</mat-label>
                  <mat-select [(ngModel)]="gasSponsor">
                    <mat-option value="ISSUER">Issuer</mat-option>
                    <mat-option value="OPERATOR">Operator</mat-option>
                  </mat-select>
                </mat-form-field>
                <mat-form-field appearance="outline">
                  <mat-label>Monthly cap (ETH)</mat-label>
                  <input matInput type="number" min="0" step="0.01" [(ngModel)]="gasMonthlyCapEth" />
                </mat-form-field>
                <button type="button" mat-raised-button color="primary" (click)="saveGasSponsorshipDefault()">
                  <mat-icon>save</mat-icon>
                  Set default
                </button>
              </div>
              }

              @if (gasPoliciesLoading) {
                <div class="spinner-wrap"><mat-spinner diameter="28" /></div>
              } @else {
                <table mat-table [dataSource]="gasPolicies" style="width:100%">
                  <ng-container matColumnDef="scope">
                    <th mat-header-cell *matHeaderCellDef>Scope</th>
                    <td mat-cell *matCellDef="let p">{{ p.assetDeploymentId ? 'Deployment override' : 'Issuer default' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="sponsor">
                    <th mat-header-cell *matHeaderCellDef>Sponsor</th>
                    <td mat-cell *matCellDef="let p">{{ p.sponsor }}</td>
                  </ng-container>
                  <ng-container matColumnDef="monthlyCapEth">
                    <th mat-header-cell *matHeaderCellDef>Monthly cap (ETH)</th>
                    <td mat-cell *matCellDef="let p">{{ p.monthlyCapEth ?? 'Unlimited' }}</td>
                  </ng-container>
                  <ng-container matColumnDef="active">
                    <th mat-header-cell *matHeaderCellDef>Active</th>
                    <td mat-cell *matCellDef="let p">
                      <mat-icon [style.color]="p.active ? 'green' : 'var(--rw-text-muted)'">{{ p.active ? 'check_circle' : 'cancel' }}</mat-icon>
                    </td>
                  </ng-container>
                  <ng-container matColumnDef="createdAt">
                    <th mat-header-cell *matHeaderCellDef>Created</th>
                    <td mat-cell *matCellDef="let p">
                      {{ p.createdAt | date:'mediumDate' }}
                      @if (p.active && canMutate) {
                        <button type="button" mat-icon-button color="warn" matTooltip="Deactivate" (click)="deactivateGasPolicy(p)">
                          <mat-icon style="font-size:18px">delete</mat-icon>
                        </button>
                      }
                    </td>
                  </ng-container>
                  <tr mat-header-row *matHeaderRowDef="gasPolicyColumns"></tr>
                  <tr mat-row *matRowDef="let row; columns: gasPolicyColumns;"></tr>
                </table>
                @if (gasPolicies.length === 0) {
                  <p style="text-align:center;padding:24px;color:var(--rw-text-secondary);font-size:13px">
                    No gas sponsorship policies configured for this issuer yet.
                  </p>
                }
              }
            </div>
          </mat-tab>
        }

        <!-- Corporate Actions Tab -->
        <mat-tab label="Documents &amp; Certificates">
          <div class="tab-content">
            <div style="display:flex;flex-direction:column;gap:16px">
              <mat-card>
                <mat-card-header>
                  <mat-card-title style="font-size:14px">Position Statement (Depotauszug)</mat-card-title>
                </mat-card-header>
                <mat-card-content style="padding-top:8px">
                  <p style="font-size:12px;color:var(--rw-text-secondary)">
                    Current portfolio statement including all token holdings, balances and valuations.
                  </p>
                  <button type="button" mat-stroked-button color="primary" (click)="downloadStatement()">
                    <mat-icon>download</mat-icon>
                    Download Statement (PDF)
                  </button>
                </mat-card-content>
              </mat-card>

              <mat-card>
                <mat-card-header>
                  <mat-card-title style="font-size:14px">Income statement (Ertragsübersicht)</mat-card-title>
                </mat-card-header>
                <mat-card-content style="padding-top:8px;display:flex;align-items:center;gap:12px;flex-wrap:wrap">
                  <p style="font-size:12px;color:var(--rw-text-secondary);margin:0;flex-basis:100%">
                    Annual statement of settled coupon, interest and dividend income, per currency. Informational only. This document is not a tax certificate; Registerwerk does not withhold or compute taxes. Principal repayments are excluded.
                  </p>
                  <mat-form-field appearance="outline" subscriptSizing="dynamic" style="width:100px">
                    <mat-label>Tax year</mat-label>
                    <input matInput type="number" [(ngModel)]="taxCertYear"
                           [min]="2020" [max]="currentYear" />
                  </mat-form-field>
                  <button type="button" mat-stroked-button color="primary" (click)="downloadTaxCert()">
                    <mat-icon>download</mat-icon>
                    Download statement (PDF)
                  </button>
                </mat-card-content>
              </mat-card>
            </div>
          </div>
        </mat-tab>

        <!-- Screening Tab -->
        <mat-tab label="Screening">
          <div class="tab-content">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
              <div>
                <strong style="font-size:14px">Sanctions &amp; PEP Screening History</strong>
                <div style="font-size:12px;color:var(--rw-text-secondary);margin-top:2px">
                  GwG §10 ongoing monitoring — last {{ screeningRuns.length }} run(s)
                </div>
              </div>
              @if (canMutate) {
                <button type="button" mat-stroked-button color="primary"
                        (click)="reScreenEntity()"
                        [disabled]="screeningLoading">
                  <mat-icon>search</mat-icon>
                  Re-screen Now
                </button>
              }
            </div>

            @if (screeningLoading) {
              <div class="spinner-wrap"><mat-spinner diameter="32" /></div>
            } @else {
              <table mat-table [dataSource]="screeningRuns" style="width:100%">
                <ng-container matColumnDef="status">
                  <th mat-header-cell *matHeaderCellDef>Status</th>
                  <td mat-cell *matCellDef="let run">
                    <app-status-badge [status]="run.status" />
                  </td>
                </ng-container>
                <ng-container matColumnDef="trigger">
                  <th mat-header-cell *matHeaderCellDef>Trigger</th>
                  <td mat-cell *matCellDef="let run">{{ run.triggerType?.replace('_', ' ') }}</td>
                </ng-container>
                <ng-container matColumnDef="provider">
                  <th mat-header-cell *matHeaderCellDef>Provider</th>
                  <td mat-cell *matCellDef="let run">{{ run.provider }}</td>
                </ng-container>
                <ng-container matColumnDef="startedAt">
                  <th mat-header-cell *matHeaderCellDef>Started</th>
                  <td mat-cell *matCellDef="let run">{{ run.startedAt | date:'mediumDate' }}</td>
                </ng-container>
                <ng-container matColumnDef="actions">
                  <th mat-header-cell *matHeaderCellDef></th>
                  <td mat-cell *matCellDef="let run">
                    @if (run.status === 'HIT') {
                      <button type="button" mat-stroked-button color="warn" style="font-size:12px"
                              (click)="viewScreeningRun(run)">
                        <mat-icon style="font-size:16px;width:16px;height:16px">warning</mat-icon>
                        Review Hits
                      </button>
                    } @else {
                      <button type="button" mat-icon-button (click)="viewScreeningRun(run)"
                              matTooltip="View run details">
                        <mat-icon>open_in_new</mat-icon>
                      </button>
                    }
                  </td>
                </ng-container>
                <tr mat-header-row *matHeaderRowDef="screeningRunColumns"></tr>
                <tr mat-row *matRowDef="let row; columns: screeningRunColumns;"></tr>
              </table>
              @if (screeningRuns.length === 0) {
                <p style="text-align:center;padding:24px;color:var(--rw-text-secondary);font-size:13px">
                  No screening runs found. Click "Re-screen Now" to run the first check.
                </p>
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
  private readonly screeningService = inject(ScreeningService);
  private readonly holderBlockService = inject(HolderBlockService);
  private readonly beneficialOwnerService = inject(BeneficialOwnerService);
  private readonly corporateActionsService = inject(CorporateActionsService);
  private readonly gasSponsorshipService = inject(GasSponsorshipService);
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly auth = inject(AuthService);

  readonly canMutate = this.auth.hasRole('REGISTRY_ADMIN');

  loading = true;
  loadError = false;
  docsLoading = false;
  historyLoading = false;
  identitiesLoading = false;
  screeningLoading = false;
  blocksLoading = false;

  entity: LegalEntity | null = null;
  editingRm = false;
  rmIdInput = '';
  documents: KycDocument[] = [];
  nameHistory: LegalEntityNameHistory[] = [];
  mergeRecords: EntityMergeRecordView[] = [];
  mergeForm: { targetEntityId: string; mergeType: 'ABSORPTION' | 'CONSOLIDATION'; effectiveDate: string; notes: string; reason: string } =
    { targetEntityId: '', mergeType: 'ABSORPTION', effectiveDate: '', notes: '', reason: '' };
  identities: OnchainIdentityView[] = [];
  screeningRuns: ScreeningRun[] = [];
  holderBlocks: HolderBlock[] = [];
  beneficialOwners: BeneficialOwner[] = [];
  ubosLoading = false;
  uboSaving = false;
  uboForm: {
    givenName: string; familyName: string; dateOfBirth: string; nationality: string;
    countryOfResidence: string; ownershipPct: number | null;
    controlType: BeneficialOwner['controlType']; source: string; fallbackReason: string;
  } = {
    givenName: '', familyName: '', dateOfBirth: '', nationality: '',
    countryOfResidence: '', ownershipPct: null, controlType: 'DIRECT_OWNERSHIP', source: '', fallbackReason: '',
  };

  readonly currentYear = new Date().getFullYear();
  taxCertYear = this.currentYear - 1;

  docColumns = ['documentType', 'jurisdiction', 'fileName', 'sizeBytes', 'uploadedAt', 'actions'];
  screeningRunColumns = ['status', 'trigger', 'provider', 'startedAt', 'actions'];
  holderBlockColumns = ['blockType', 'walletAddress', 'legalBasis', 'startsAt', 'expiresAt'];
  uboColumns = ['name', 'country', 'pepStatus', 'ownershipPct', 'controlType', 'registeredAt', 'actions'];

  // ── Jurisdiction KYC ──────────────────────────────────────────────────────
  readonly allJurisdictions: Jurisdiction[] = ['DE_EWPG', 'LU_CSSF', 'FR_AMF', 'LI_TVTG'];
  jurisdictionApprovals: KycJurisdictionApproval[] = [];
  /** The approvals could not be loaded: statuses read "Unknown", not a misleading PENDING. */
  jurisdictionApprovalsFailed = false;
  complianceByJurisdiction: Partial<Record<Jurisdiction, KycComplianceResponse>> = {};
  jurActionLoading: Partial<Record<Jurisdiction, boolean>> = {};

  // ── Gas Sponsorship (issuer default) ─────────────────────────────────────
  gasPoliciesLoading = false;
  gasPolicies: GasSponsorshipPolicy[] = [];
  gasSponsor: GasSponsor = 'ISSUER';
  gasMonthlyCapEth: number | null = 0.5;
  readonly gasPolicyColumns = ['scope', 'sponsor', 'monthlyCapEth', 'active', 'createdAt'];

  @ViewChild('fileInput') private fileInput?: ElementRef<HTMLInputElement>;

  ngOnInit(): void {
    this.loadEntity();
  }

  loadEntity(): void {
    this.loading = true;
    this.loadError = false;
    this.entityService.getEntity(this.id).subscribe({
      next: (entity) => {
        this.entity = entity;
        this.loading = false;
        this.loadError = false;
        this.cdr.markForCheck();
        this.loadDocuments();
        this.loadHistory();
        this.loadIdentities();
        this.loadJurisdictionApprovals();
        this.loadScreeningRuns();
        this.loadHolderBlocks();
        this.loadBeneficialOwners();
        if (entity.type === 'ISSUER') {
          this.loadGasPolicies();
        }
      },
      error: () => {
        this.loading = false;
        this.loadError = true;
        this.cdr.markForCheck();
      },
    });
  }

  // ── Gas Sponsorship (issuer default) ─────────────────────────────────────

  loadGasPolicies(): void {
    this.gasPoliciesLoading = true;
    this.gasSponsorshipService.listForIssuer(this.id).subscribe({
      next: (policies) => {
        this.gasPolicies = policies;
        this.gasPoliciesLoading = false;
        this.cdr.markForCheck();
      },
      error: () => { this.gasPoliciesLoading = false; this.cdr.markForCheck(); },
    });
  }

  saveGasSponsorshipDefault(): void {
    this.gasSponsorshipService.createIssuerDefault(this.id, {
      sponsor: this.gasSponsor,
      monthlyCapEth: this.gasMonthlyCapEth ?? undefined,
    }).subscribe({
      next: () => this.loadGasPolicies(),
      error: (err) => this.showActionError('Failed to save gas sponsorship policy.', err),
    });
  }

  deactivateGasPolicy(policy: GasSponsorshipPolicy): void {
    if (!confirm('Deactivate this gas sponsorship policy?')) return;
    this.gasSponsorshipService.deactivate(policy.id).subscribe({
      next: () => this.loadGasPolicies(),
      error: (err) => this.showActionError('Failed to deactivate gas sponsorship policy.', err),
    });
  }

  loadScreeningRuns(): void {
    this.screeningLoading = true;
    this.cdr.markForCheck();
    this.screeningService.listRunsByEntity(this.id).subscribe({
      next: (runs) => {
        this.screeningRuns = runs;
        this.screeningLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.screeningLoading = false;
        this.cdr.markForCheck();
      },
    });
  }

  reScreenEntity(): void {
    if (!this.entity) return;
    this.screeningService.screenEntity(this.id, { name: this.entity.currentName }).subscribe({
      next: (run) => {
        this.screeningRuns = [run, ...this.screeningRuns];
        this.cdr.markForCheck();
      },
      error: (err) => this.showActionError('Failed to start screening.', err),
    });
  }

  viewScreeningRun(run: ScreeningRun): void {
    this.router.navigate(['/compliance/screening/runs', run.id]);
  }

  loadHolderBlocks(): void {
    this.blocksLoading = true;
    this.cdr.markForCheck();
    this.holderBlockService.listByEntity(this.id).subscribe({
      next: (blocks) => {
        this.holderBlocks = blocks;
        this.blocksLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.blocksLoading = false;
        this.cdr.markForCheck();
      },
    });
  }

  loadBeneficialOwners(): void {
    this.ubosLoading = true;
    this.cdr.markForCheck();
    this.beneficialOwnerService.list(this.id).subscribe({
      next: (owners) => {
        this.beneficialOwners = owners;
        this.ubosLoading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.ubosLoading = false;
        this.cdr.markForCheck();
      },
    });
  }

  addBeneficialOwner(): void {
    if (!this.uboForm.givenName || !this.uboForm.familyName) return;
    this.uboSaving = true;
    this.cdr.markForCheck();

    this.beneficialOwnerService.add(this.id, {
      person: {
        givenName: this.uboForm.givenName,
        familyName: this.uboForm.familyName,
        dateOfBirth: this.uboForm.dateOfBirth || undefined,
        nationality: this.uboForm.nationality || undefined,
        countryOfResidence: this.uboForm.countryOfResidence || undefined,
        country: this.uboForm.countryOfResidence || undefined,
      },
      ownershipPct: this.uboForm.controlType === 'SENIOR_MANAGING_OFFICIAL' ? undefined : (this.uboForm.ownershipPct ?? undefined),
      controlType: this.uboForm.controlType,
      source: this.uboForm.source || undefined,
      fallbackReason: this.uboForm.controlType === 'SENIOR_MANAGING_OFFICIAL' ? this.uboForm.fallbackReason.trim() : undefined,
    }).subscribe({
      next: (owner) => {
        this.beneficialOwners = [owner, ...this.beneficialOwners];
        this.uboForm = {
          givenName: '', familyName: '', dateOfBirth: '', nationality: '',
          countryOfResidence: '', ownershipPct: null, controlType: 'DIRECT_OWNERSHIP', source: '', fallbackReason: '',
        };
        this.uboSaving = false;
        this.cdr.markForCheck();
        this.snackBar.open('Beneficial owner registered. Sanctions/PEP screening triggered.', 'Dismiss', { duration: 5000 });
      },
      error: (err) => {
        this.uboSaving = false;
        this.cdr.markForCheck();
        this.snackBar.open(err?.error?.message ?? 'Failed to register beneficial owner.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  ceaseBeneficialOwner(bo: BeneficialOwner): void {
    const reason = prompt(`Reason for ceasing ${bo.givenName} ${bo.familyName} as beneficial owner (required, audited):`);
    if (!reason || !reason.trim()) return;
    this.withDualControl('BENEFICIAL_OWNER_CEASE', `Cease beneficial owner ${bo.givenName} ${bo.familyName}`,
      `DELETE /api/v1/entities/${this.id}/beneficial-owners/${bo.id}`, { reason: reason.trim() }, tokens => {
      this.beneficialOwnerService.cease(this.id, bo.id, { reason: reason.trim() }, tokens).subscribe({
        next: (updated) => {
          this.beneficialOwners = this.beneficialOwners.map(o => o.id === updated.id ? updated : o)
            .filter(o => !o.ceasedAt);
          this.cdr.markForCheck();
          this.snackBar.open('Beneficial owner ceased.', 'Dismiss', { duration: 4000 });
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to cease beneficial owner.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  /** Picks one of the entity's stored KYC documents by number; verification links the owner to it. */
  verifyBeneficialOwner(bo: BeneficialOwner): void {
    if (this.documents.length === 0) {
      this.snackBar.open('Upload a register extract or identity document first.', 'Dismiss', { duration: 5000 });
      return;
    }
    const list = this.documents.map((d, i) => `${i + 1}) ${d.fileName} (${d.documentType})`).join('\n');
    const answer = prompt(`Verify ${bo.givenName} ${bo.familyName} against which document?\n${list}`, '1');
    const doc = this.documents[Number(answer) - 1];
    if (!doc) return;
    this.beneficialOwnerService.verify(this.id, bo.id, doc.id).subscribe({
      next: (updated) => {
        this.beneficialOwners = this.beneficialOwners.map(o => o.id === updated.id ? updated : o);
        this.cdr.markForCheck();
        this.snackBar.open('Beneficial owner verified.', 'Dismiss', { duration: 4000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to verify beneficial owner.', 'Dismiss', { duration: 6000 }),
    });
  }

  approveEdd(bo: BeneficialOwner): void {
    const note = prompt(`Enhanced due diligence note for ${bo.givenName} ${bo.familyName} (required, audited):`);
    if (!note || !note.trim()) return;
    this.withDualControl('PEP_EDD_APPROVE', `Approve EDD for ${bo.givenName} ${bo.familyName}`,
      `POST /api/v1/entities/${this.id}/beneficial-owners/${bo.id}/edd-approvals`, { note: note.trim() }, tokens => {
      this.beneficialOwnerService.approveEdd(this.id, bo.id, { note: note.trim() }, tokens).subscribe({
        next: () => {
          this.snackBar.open('EDD approved; review due within 6 months.', 'Dismiss', { duration: 5000 });
          this.loadBeneficialOwners();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to approve EDD.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  downloadStatement(): void {
    this.corporateActionsService.downloadPositionStatement(this.id).subscribe({
      next: (blob) => triggerBlobDownload(blob, `depotauszug-${this.id}-${new Date().toISOString().split('T')[0]}.pdf`),
      error: (err) => this.showActionError('Failed to generate position statement.', err),
    });
  }

  downloadTaxCert(): void {
    this.corporateActionsService.downloadTaxCertificate(this.id, this.taxCertYear).subscribe({
      next: (blob) => triggerBlobDownload(blob, `Ertragsuebersicht-${this.taxCertYear}.pdf`),
      error: (err) => this.showActionError('Failed to generate income statement.', err),
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
      next: (approvals) => { this.jurisdictionApprovals = approvals; this.jurisdictionApprovalsFailed = false; this.cdr.markForCheck(); },
      error: () => { this.jurisdictionApprovalsFailed = true; this.cdr.markForCheck(); },
    });
  }

  loadCompliance(jurisdiction: Jurisdiction): void {
    this.kycService.getCompliance(this.id, jurisdiction).subscribe({
      next: (result) => { this.complianceByJurisdiction = { ...this.complianceByJurisdiction, [jurisdiction]: result }; this.cdr.markForCheck(); },
      error: (err) => this.showActionError(`Compliance for ${jurisdiction} could not be loaded.`, err),
    });
  }

  getJurisdictionStatus(jur: Jurisdiction): string {
    if (this.jurisdictionApprovalsFailed) return 'UNKNOWN';
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

  /** Step-up plus second approver for a KYC decision (shared with the compliance officer's KYC review). */
  private withDualControl(action: string, reason: string, target: string, targetBody: unknown,
                          then: (tokens: DualControlTokens) => void): void {
    withDualControl(this.dialog, { action, reason, target, targetBody }, then);
  }

  approveJurisdiction(jur: Jurisdiction): void {
    this.withDualControl('KYC_JURISDICTION_APPROVE', `Approve KYC for ${this.jurisdictionLabel(jur)}`,
      `POST /api/v1/entities/${this.id}/kyc/jurisdictions/${jur}/approve`, {}, tokens => {
      this.jurActionLoading = { ...this.jurActionLoading, [jur]: true };
      this.kycService.approveJurisdiction(this.id, jur, undefined, tokens).subscribe({
        next: () => {
          this.jurActionLoading = { ...this.jurActionLoading, [jur]: false };
          this.snackBar.open('Jurisdiction KYC approved.', 'Dismiss', { duration: 4000 });
          this.cdr.markForCheck();
          this.loadJurisdictionApprovals();
          this.loadCompliance(jur);
        },
        error: (err) => {
          this.jurActionLoading = { ...this.jurActionLoading, [jur]: false };
          this.showActionError('Failed to approve jurisdiction KYC.', err);
          this.cdr.markForCheck();
        },
      });
    });
  }

  rejectJurisdiction(jur: Jurisdiction): void {
    this.dialog.open(KycRejectDialogComponent, {
      width: '520px', maxWidth: '95vw',
      data: { title: `Reject KYC for ${this.jurisdictionLabel(jur)}`, reasonVisibleToCustomer: true },
    }).afterClosed().subscribe((decision: KycRejectDialogResult | undefined) => {
      if (!decision) return;
      this.withDualControl('KYC_JURISDICTION_REJECT', `Reject KYC for ${this.jurisdictionLabel(jur)}`,
        `POST /api/v1/entities/${this.id}/kyc/jurisdictions/${jur}/reject`,
        { reason: decision.reason, customerReasonCode: decision.customerReasonCode }, tokens => {
        this.jurActionLoading = { ...this.jurActionLoading, [jur]: true };
        this.kycService.rejectJurisdiction(this.id, jur, decision.reason, decision.customerReasonCode, tokens).subscribe({
          next: () => {
            this.jurActionLoading = { ...this.jurActionLoading, [jur]: false };
            this.snackBar.open('Jurisdiction KYC rejected.', 'Dismiss', { duration: 4000 });
            this.cdr.markForCheck();
            this.loadJurisdictionApprovals();
          },
          error: (err) => {
            this.jurActionLoading = { ...this.jurActionLoading, [jur]: false };
            this.showActionError('Failed to reject jurisdiction KYC.', err);
            this.cdr.markForCheck();
          },
        });
      });
    });
  }

  formatDocType(type: string): string {
    return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
  }

  loadHistory(): void {
    this.historyLoading = true;
    this.entityService.getEntityHistory(this.id).subscribe({
      next: (history) => {
        this.nameHistory = history.nameHistory;
        this.mergeRecords = history.mergeRecords;
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
    this.fileInput?.nativeElement.click();
  }

  onFileSelected(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.kycService.uploadDocument(this.id, file, 'GENERAL').subscribe({
      next: () => this.loadDocuments(),
      error: (err) => this.showActionError('Failed to upload KYC document.', err),
    });
    (event.target as HTMLInputElement).value = '';
  }

  downloadDoc(doc: KycDocument): void {
    this.kycService.downloadDocument(this.id, doc.id).subscribe({
      next: (blob) => triggerBlobDownload(blob, doc.fileName),
      error: (err) => this.showActionError('Failed to download KYC document.', err),
    });
  }

  /**
   * The server defaults the expiry (12 months, shortened for PEPs) and refuses without a beneficial-owner
   * register or sufficient identification; read `kycExpiryDate` back instead of choosing one here.
   * `withOverride` records an administrator's documented override of a failed gate.
   */
  approveKyc(withOverride = false): void {
    let overrideNote: string | undefined;
    if (withOverride) {
      const note = prompt('Override note (required, kept in the audit trail). Only a registry administrator may override a failed KYC gate:');
      if (!note || !note.trim()) return;
      overrideNote = note.trim();
    }
    this.withDualControl('KYC_APPROVE', 'Approve KYC for this customer',
      `POST /api/v1/entities/${this.id}/kyc/approve`, { overrideNote }, tokens => {
      this.kycService.approveKyc(this.id, { overrideNote }, tokens).subscribe({
        next: () => {
          this.loadEntity();
          // Prompt operator to also issue on-chain KYC claim
          if (this.identities.length > 0 && confirm(
            `KYC approved. Issue KYC claim on-chain for all deployed ONCHAINID identities?`)) {
            this.identities.forEach(identity => this.issueKycClaim(identity));
          }
        },
        error: (err) => this.showActionError('Failed to approve KYC.', err),
      });
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
      next: (identity) => { this.identities = [...this.identities, identity]; this.cdr.markForCheck(); },
      error: (err) => this.showActionError('Failed to deploy on-chain identity.', err),
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
      error: (err) => this.showActionError('Failed to issue KYC claim.', err),
    });
  }

  issueAmlClaim(identity: OnchainIdentityView): void {
    this.http.post(
      `${environment.apiUrl}/entities/${this.id}/onchain-identity/${identity.id}/claims/aml`,
      {}
    ).subscribe({
      next: () => this.loadIdentities(),
      error: (err) => this.showActionError('Failed to issue AML claim.', err),
    });
  }

  rejectKyc(): void {
    this.dialog.open(KycRejectDialogComponent, { width: '520px', maxWidth: '95vw' })
      .afterClosed().subscribe((decision: KycRejectDialogResult | undefined) => {
        if (!decision) return;
        this.withDualControl('KYC_REJECT', 'Reject KYC for this customer',
          `POST /api/v1/entities/${this.id}/kyc/reject`,
          { reason: decision.reason, customerReasonCode: decision.customerReasonCode }, tokens => {
          this.kycService.rejectKyc(this.id, decision.reason, decision.customerReasonCode, tokens).subscribe({
            next: () => {
              this.snackBar.open('KYC rejected. The customer is told the selected category only.', 'Dismiss', { duration: 6000 });
              this.loadEntity();
            },
            error: (err) => this.showActionError('Failed to reject KYC.', err),
          });
        });
      });
  }

  suspend(): void { this.lifecycleChange('suspend', 'ENTITY_SUSPEND', 'Suspend customer', 'Failed to suspend customer.'); }

  reactivate(): void { this.lifecycleChange('reactivate', 'ENTITY_REACTIVATE', 'Reactivate customer', 'Failed to reactivate customer.'); }

  /**
   * Reinstatement of a CLOSED/DISSOLVED customer (T6-12): legal reference + reason, step-up and a second
   * approver bound to the request body. Result is PENDING_REACTIVATION; KYC must be approved afresh.
   */
  reinstate(): void {
    const legalReference = prompt('Legal reference for the reinstatement (court decision, registry entry, ticket id; required):');
    if (!legalReference || !legalReference.trim()) return;
    const reason = prompt('Reason for the reinstatement (required, written to the audit trail):');
    if (!reason || !reason.trim()) return;
    const body = { reason: reason.trim(), legalReference: legalReference.trim() };
    this.withDualControl('ENTITY_REINSTATE', `Reinstate ${this.entity?.currentName ?? 'customer'}`.trim(),
      `POST /api/v1/entities/${this.id}/reinstate`, body, tokens => {
      this.entityService.reinstateEntity(this.id, body.reason, body.legalReference, tokens).subscribe({
        next: () => {
          this.snackBar.open('Reinstatement requested: PENDING_REACTIVATION. Re-screening started; approve KYC afresh to reactivate.', 'Dismiss', { duration: 8000 });
          this.loadEntity();
        },
        error: (err) => this.showActionError('Failed to request reinstatement.', err),
      });
    });
  }

  /** Suspend/reactivate: a reason, step-up and a second approver; reactivation can be refused by compliance gates (409 message shown). */
  private lifecycleChange(path: 'suspend' | 'reactivate', action: string, label: string, failure: string): void {
    const reason = prompt(`Reason for this action (required, written to the audit trail):`);
    if (!reason || !reason.trim()) return;
    this.withDualControl(action, `${label} ${this.entity?.currentName ?? ''}`.trim(),
      `POST /api/v1/entities/${this.id}/${path}`, { reason: reason.trim() }, tokens => {
      const call = path === 'suspend'
        ? this.entityService.suspendEntity(this.id, reason.trim(), tokens)
        : this.entityService.reactivateEntity(this.id, reason.trim(), tokens);
      call.subscribe({
        next: () => this.loadEntity(),
        error: (err) => this.showActionError(failure, err),
      });
    });
  }

  startEditRelationshipManager(): void {
    this.rmIdInput = this.entity?.assignedRelationshipManagerId ?? '';
    this.editingRm = true;
  }

  saveRelationshipManager(): void {
    const value = this.rmIdInput.trim() || null;
    this.entityService.assignRelationshipManager(this.id, value).subscribe({
      next: (updated) => {
        this.entity = updated;
        this.editingRm = false;
        this.snackBar.open('Relationship manager updated.', 'Dismiss', { duration: 4000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Failed to update relationship manager.', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  terminate(): void {
    const reason = prompt(
      'Reason for terminating this customer relationship (required for audit trail):'
    );
    if (!reason || !reason.trim()) return;

    // Open obligations (tokens outstanding, trades, repos, loans, Sperrvermerk ...) must be acknowledged one by one.
    this.entityService.getOffboardingObligations(this.id).subscribe({
      next: (obligations) => {
        let acknowledged: { obligationId: string; reason: string }[] = [];
        if (obligations.length > 0) {
          const list = obligations.map(o => `- ${o.kind}: ${o.description}`).join('\n');
          if (!confirm(`${obligations.length} open obligation(s) remain:\n${list}\n\nTerminate anyway? Each will be acknowledged with your reason and becomes an operator task.`)) return;
          acknowledged = obligations.map(o => ({ obligationId: o.obligationId, reason: reason.trim() }));
        }
        this.openTerminateStepUp(reason.trim(), acknowledged);
      },
      error: (err) => this.showActionError('Could not load the open obligations.', err),
    });
  }

  private openTerminateStepUp(reason: string, acknowledged: { obligationId: string; reason: string }[]): void {
    this.withDualControl('CUSTOMER_OFFBOARDING',
      `Terminate customer relationship for ${this.entity?.currentName} (offboarding)`,
      `POST /api/v1/entities/${this.id}/terminate`, { reason, acknowledgedObligations: acknowledged }, tokens => {
      this.entityService.terminateEntity(this.id, reason, acknowledged, tokens).subscribe({
        next: () => {
          this.snackBar.open('Customer relationship terminated. Audit event recorded.', 'Dismiss', { duration: 5000 });
          this.loadEntity();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to terminate customer.', 'Dismiss', { duration: 8000 }),
      });
    });
  }

  recordMerger(): void {
    if (!this.mergeForm.reason.trim()) return;
    if (!confirm('This will mark the current entity as dissolved (absorbed). Continue?')) return;
    const mergeBody = {
      targetEntityId: this.mergeForm.targetEntityId,
      mergeType: this.mergeForm.mergeType,
      effectiveDate: this.mergeForm.effectiveDate,
      notes: this.mergeForm.notes || undefined,
      reason: this.mergeForm.reason.trim(),
    };
    this.withDualControl('ENTITY_MERGE', `Record merger of ${this.entity?.currentName ?? 'this entity'}`,
      `POST /api/v1/entities/${this.id}/merge`, mergeBody, tokens => {
      this.entityService.mergeEntity(this.id, mergeBody, tokens).subscribe({
        next: () => {
          this.mergeForm = { targetEntityId: '', mergeType: 'ABSORPTION', effectiveDate: '', notes: '', reason: '' };
          this.loadEntity();
          this.loadHistory();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to record merger.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  generateToken(): void {
    this.router.navigate(['/onboarding/token', this.id]);
  }

  openAsCompany(): void {
    if (!this.entity) return;
    const entity = this.entity;
    this.adminUserService.isProductionMode().pipe(
      switchMap(productionMode => this.dialog.open(ImpersonateDialogComponent, {
        width: '520px', maxWidth: '95vw', data: { entityName: entity.currentName, productionMode },
      }).afterClosed()),
    ).subscribe((choice: ImpersonateDialogResult | undefined) => {
      if (!choice) return;
      const onBehalf = choice.mode === 'ACT_ON_BEHALF';
      openStepUp(this.dialog, {
        requireDualControl: onBehalf,
        reason: `${onBehalf ? 'Act on behalf of' : 'Open a read-only support session for'} ${entity.currentName}`,
        action: 'ADMIN_IMPERSONATION_ACT_ON_BEHALF',
        target: 'POST /api/v1/impersonation/act-on-behalf',
        targetBody: { entityId: entity.id, reason: choice.reason, ticket: choice.ticket || undefined },
      }).subscribe(tokens => {
        if (!tokens) return;
        // The tab must be opened inside the click gesture chain; the handoff URL (one-time code) is navigated to afterwards.
        const handoffTab = window.open('', '_blank');
        if (handoffTab) {
          handoffTab.opener = null;
          handoffTab.document.title = 'Opening customer portal…';
        }
        const request$ = onBehalf
          ? this.adminUserService.impersonateActOnBehalf(entity.id, choice.reason, {
              stepUpToken: tokens.stepUpToken, dualControlToken: tokens.dualControlToken }, choice.ticket)
          : this.adminUserService.impersonate(entity.id, choice.reason, tokens.stepUpToken, choice.ticket);
        request$.subscribe({
          next: (res) => {
            let url: URL;
            try {
              url = new URL(res.handoffUrl, window.location.origin);
              if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Unsupported protocol');
            } catch {
              handoffTab?.close();
              this.snackBar.open('The server returned an invalid customer-portal URL.', 'Dismiss', { duration: 6000 });
              return;
            }
            if (handoffTab) {
              handoffTab.location.replace(url.href);
            } else {
              this.snackBar.open('The customer portal was blocked. Allow pop-ups and try again.', 'Dismiss', { duration: 6000 });
            }
          },
          error: (err) => {
            handoffTab?.close();
            this.snackBar.open(err?.error?.message ?? 'Impersonation failed', 'Dismiss', { duration: 6000 });
          },
        });
      });
    });
  }

  goBack(): void {
    this.router.navigate(['/customers']);
  }

  private showActionError(fallback: string, error: unknown): void {
    showActionError(this.snackBar, fallback, error);
    this.cdr.markForCheck();
  }
}

function triggerBlobDownload(blob: Blob, filename: string): void {
  downloadBlob(blob, filename);
}
