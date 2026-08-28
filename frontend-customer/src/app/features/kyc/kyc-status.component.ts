import { ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { KycService } from '../../core/api/kyc.service';
import { AuthService } from '../../core/auth/auth.service';
import { downloadBlob } from '../../core/utils/download.util';
import {
  KycDocument,
  KycComplianceResponse,
  KycEntityStatus,
  KycJurisdictionApproval,
  Jurisdiction,
} from '../../core/models';

const ALL_JURISDICTIONS: { value: Jurisdiction; label: string }[] = [
  { value: 'DE_EWPG', label: 'Germany — eWpG / BaFin' },
  { value: 'LU_CSSF', label: 'Luxembourg — CSSF' },
  { value: 'FR_AMF',  label: 'France — AMF' },
  { value: 'LI_TVTG', label: 'Liechtenstein — TVTG / FMA' },
];

const ALL_DOC_TYPES = [
  'PASSPORT', 'COMMERCIAL_REGISTER', 'COMMERCIAL_REGISTER_EXTRACT', 'ANNUAL_REPORT',
  'CERTIFICATE_OF_INCORPORATION', 'UBO_DECLARATION', 'BENEFICIAL_OWNER_REGISTER_EXTRACT',
  'OWNERSHIP_STRUCTURE_CHART', 'OWNERSHIP_CHART', 'SHAREHOLDER_REGISTER', 'BOARD_RESOLUTION',
  'POWER_OF_ATTORNEY', 'IDENTITY_DOCUMENT', 'PROOF_OF_RESIDENCE', 'BANK_STATEMENT',
  'SOURCE_OF_FUNDS', 'LEI_CERTIFICATE', 'REGULATORY_LICENSE', 'TOKEN_WHITEPAPER',
  'SMART_CONTRACT_AUDIT', 'PEP_SANCTIONS_SCREENING', 'AML_QUESTIONNAIRE', 'OTHER',
];

@Component({
  selector: 'app-kyc-status',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTabsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatFormFieldModule,
    MatSelectModule,
    MatTooltipModule,
    MatDialogModule,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>KYC & Compliance Documents</h1>
        @if (entityKycStatus) {
          <span class="status-chip" [class]="entityStatusClass(entityKycStatus)">{{ entityKycStatus.replace('_', ' ') }}</span>
        }
      </div>
      <p class="page-sub">
        Upload the required documents for each regulatory jurisdiction. Documents marked
        as universal (no jurisdiction selected) count towards any jurisdiction's requirements.
      </p>

      <!-- Jurisdiction tabs -->
      <mat-tab-group animationDuration="200ms">

        @for (jur of allJurisdictions; track jur.value) {
          <mat-tab [label]="jur.label">
            <div style="padding-top:20px">

              <!-- Jurisdiction approval decision + rejection reason -->
              @if (jurisdictionApprovals[jur.value]; as approval) {
                @if (approval.status === 'REJECTED') {
                  <mat-card class="decision-card rejected">
                    <mat-card-content>
                      <div class="decision-row">
                        <mat-icon class="ci err">cancel</mat-icon>
                        <span class="decision-label">KYC rejected for {{ jur.label }}</span>
                      </div>
                      @if (approval.rejectionReason) {
                        <p class="decision-reason">{{ approval.rejectionReason }}</p>
                      }
                      <p class="decision-hint">Address the issue above and re-upload the affected document(s) below.</p>
                    </mat-card-content>
                  </mat-card>
                } @else if (approval.status === 'APPROVED') {
                  <mat-card class="decision-card approved">
                    <mat-card-content>
                      <div class="decision-row">
                        <mat-icon class="ci ok">check_circle</mat-icon>
                        <span class="decision-label">KYC approved for {{ jur.label }}</span>
                        @if (approval.expiresAt) {
                          <span class="decision-expiry">valid until {{ approval.expiresAt | date:'mediumDate' }}</span>
                        }
                      </div>
                    </mat-card-content>
                  </mat-card>
                } @else if (approval.status === 'EXPIRED') {
                  <mat-card class="decision-card rejected">
                    <mat-card-content>
                      <div class="decision-row">
                        <mat-icon class="ci warn">schedule</mat-icon>
                        <span class="decision-label">KYC expired for {{ jur.label }}</span>
                      </div>
                      <p class="decision-hint">Re-upload the expired document(s) below to restart review.</p>
                    </mat-card-content>
                  </mat-card>
                }
              }

              <!-- Compliance checklist card -->
              <mat-card style="margin-bottom:16px">
                <mat-card-header>
                  <mat-card-title style="font-size:14px;display:flex;align-items:center;gap:10px">
                    Document Checklist
                    @if (compliance[jur.value]?.fullyCompliant) {
                      <span class="status-chip compliant">COMPLIANT</span>
                    } @else if (compliance[jur.value]) {
                      <span class="status-chip incomplete">INCOMPLETE</span>
                    }
                  </mat-card-title>
                </mat-card-header>
                <mat-card-content>
                  @if (!compliance[jur.value]) {
                    <button mat-stroked-button type="button" (click)="loadCompliance(jur.value)" [disabled]="complianceLoading[jur.value]">
                      @if (complianceLoading[jur.value]) { <mat-spinner diameter="16" /> }
                      @else { <mat-icon>refresh</mat-icon> }
                      Load checklist
                    </button>
                    @if (complianceError[jur.value]) {
                      <p class="inline-error" role="alert">Checklist could not be loaded. Try again.</p>
                    }
                  } @else {
                    @let cr = compliance[jur.value]!;
                    @if (!cr.fullyCompliant) {
                      <div class="compliance-summary">
                        @if (cr.missingCount > 0) { <span class="chip missing">{{ cr.missingCount }} missing</span> }
                        @if (cr.tooOldCount > 0) { <span class="chip too-old">{{ cr.tooOldCount }} too old</span> }
                        @if (cr.expiredCount > 0) { <span class="chip expired">{{ cr.expiredCount }} expired</span> }
                      </div>
                    }
                    <div class="checklist">
                      @for (doc of cr.documents; track doc.documentType) {
                        <div class="check-row">
                          @if (!doc.mandatory) {
                            <mat-icon class="ci muted">radio_button_unchecked</mat-icon>
                          } @else if (doc.present && !doc.expired && !doc.tooOld) {
                            <mat-icon class="ci ok">check_circle</mat-icon>
                          } @else if (doc.tooOld) {
                            <mat-icon class="ci warn">schedule</mat-icon>
                          } @else {
                            <mat-icon class="ci err">cancel</mat-icon>
                          }
                          <span class="check-name" [style.font-weight]="doc.mandatory ? '600' : '400'">{{ doc.localName }}</span>
                          @if (!doc.mandatory) {
                            <span class="check-note">recommended</span>
                          } @else if (doc.tooOld) {
                            <span class="check-note warn-text">too old (max 90 days)</span>
                          } @else if (doc.expired) {
                            <span class="check-note err-text">expired</span>
                          } @else if (!doc.present) {
                            <span class="check-note err-text">missing</span>
                          }
                          @if (doc.mandatory && (!doc.present || doc.expired || doc.tooOld)) {
                            <button mat-button color="primary" class="quick-upload-btn" type="button"
                                    (click)="quickUpload(jur.value, doc.documentType)">
                              <mat-icon>upload</mat-icon> Upload
                            </button>
                          }
                        </div>
                      }
                    </div>
                  }
                </mat-card-content>
              </mat-card>

              <!-- Upload section -->
              <mat-card style="margin-bottom:16px">
                <mat-card-header>
                  <mat-card-title style="font-size:14px">Upload Document for {{ jur.label }}</mat-card-title>
                </mat-card-header>
                <mat-card-content style="padding-top:8px">
                  <div class="upload-row" [id]="'upload-' + jur.value">
                    <mat-form-field appearance="outline" style="min-width:240px">
                      <mat-label>Document Type</mat-label>
                      <mat-select [(ngModel)]="uploadDocType[jur.value]">
                        @for (type of allDocTypes; track type) {
                          <mat-option [value]="type">{{ formatDocType(type) }}</mat-option>
                        }
                      </mat-select>
                    </mat-form-field>

                    <label class="file-label" [class.has-file]="uploadFiles[jur.value]">
                      <mat-icon>attach_file</mat-icon>
                      {{ uploadFiles[jur.value]?.name ?? 'Choose file…' }}
                      <input type="file" accept=".pdf,.jpg,.jpeg,.png,.xml,.docx,.html,.txt,.md,.json"
                        (change)="onFileSelected($event, jur.value)" hidden />
                    </label>

                    <button mat-raised-button color="primary" type="button"
                            [disabled]="!uploadFiles[jur.value] || !uploadDocType[jur.value] || uploadLoading[jur.value]"
                            (click)="upload(jur.value)">
                      @if (uploadLoading[jur.value]) { <mat-spinner diameter="18" style="margin-right:8px" /> }
                      <mat-icon>cloud_upload</mat-icon> Upload
                    </button>
                  </div>
                </mat-card-content>
              </mat-card>
            </div>
          </mat-tab>
        }

        <!-- All documents tab -->
        <mat-tab label="All Documents">
          <div style="padding-top:20px">
            <mat-card>
              <mat-card-header>
                <mat-card-title style="font-size:14px">All Uploaded Documents ({{ documents.length }})</mat-card-title>
              </mat-card-header>
              <mat-card-content>
                @if (loading) {
                  <div style="display:flex;justify-content:center;padding:40px"><mat-spinner diameter="40" /></div>
                } @else if (documentsLoadError) {
                  <div class="document-error" role="alert">
                    <mat-icon>error_outline</mat-icon>
                    <span>Documents could not be loaded.</span>
                    <button mat-stroked-button type="button" (click)="loadDocuments()">Retry</button>
                  </div>
                } @else if (documents.length === 0) {
                  <p style="text-align:center;color:var(--rw-text-muted);padding:32px">No documents uploaded yet.</p>
                } @else {
                  <div class="table-wrap"><table mat-table [dataSource]="documents" style="width:100%">
                    <ng-container matColumnDef="type">
                      <th mat-header-cell *matHeaderCellDef>Type</th>
                      <td mat-cell *matCellDef="let d">{{ formatDocType(d.documentType) }}</td>
                    </ng-container>
                    <ng-container matColumnDef="jurisdiction">
                      <th mat-header-cell *matHeaderCellDef>Jurisdiction</th>
                      <td mat-cell *matCellDef="let d">{{ d.jurisdiction ?? 'Universal' }}</td>
                    </ng-container>
                    <ng-container matColumnDef="fileName">
                      <th mat-header-cell *matHeaderCellDef>File</th>
                      <td mat-cell *matCellDef="let d">{{ d.fileName }}</td>
                    </ng-container>
                    <ng-container matColumnDef="size">
                      <th mat-header-cell *matHeaderCellDef>Size</th>
                      <td mat-cell *matCellDef="let d">{{ formatBytes(d.sizeBytes ?? 0) }}</td>
                    </ng-container>
                    <ng-container matColumnDef="uploadedAt">
                      <th mat-header-cell *matHeaderCellDef>Uploaded</th>
                      <td mat-cell *matCellDef="let d">{{ d.uploadedAt | date:'mediumDate' }}</td>
                    </ng-container>
                    <ng-container matColumnDef="actions">
                      <th mat-header-cell *matHeaderCellDef></th>
                      <td mat-cell *matCellDef="let d">
                        <button mat-icon-button type="button" matTooltip="Download" (click)="download(d)">
                          <mat-icon>download</mat-icon>
                        </button>
                        <button mat-icon-button color="warn" type="button"
                                [matTooltip]="d.status === 'APPROVED' ? 'Approved documents are part of the compliance record and cannot be deleted here' : 'Delete'"
                                [disabled]="d.status === 'APPROVED'"
                                (click)="deleteDoc(d)">
                          <mat-icon>delete_outline</mat-icon>
                        </button>
                      </td>
                    </ng-container>
                    <tr mat-header-row *matHeaderRowDef="docColumns"></tr>
                    <tr mat-row *matRowDef="let row; columns: docColumns;"></tr>
                  </table></div>
                }
              </mat-card-content>
            </mat-card>
          </div>
        </mat-tab>

      </mat-tab-group>
    </div>

    <ng-template #deleteConfirmDialog let-doc>
      <h2 mat-dialog-title>Delete document</h2>
      <mat-dialog-content>
        <p>Delete <strong>{{ doc.fileName }}</strong>? This cannot be undone.</p>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button mat-flat-button color="warn" type="button" [mat-dialog-close]="true">Delete</button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .page-container { max-width: 960px; margin: 0 auto; padding: 16px; }
    .page-sub { color: var(--rw-text-secondary); font-size: 13px; margin: 0 0 20px; }

    .page-header { display: flex; align-items: center; gap: 10px; }

    .status-chip {
      font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 4px;
      text-transform: uppercase; letter-spacing: 0.3px;
      &.compliant, &.status-approved { background: rgba(16,185,129,0.12); color: #10b981; }
      &.incomplete, &.status-in-progress, &.status-not-started { background: rgba(245,158,11,0.12); color: #f59e0b; }
      &.status-rejected, &.status-expired { background: rgba(239,68,68,0.12); color: #ef4444; }
    }

    .decision-card {
      margin-bottom: 16px; border-left: 3px solid transparent;
      &.rejected { border-left-color: #ef4444; }
      &.approved { border-left-color: #10b981; }
    }
    .decision-row { display: flex; align-items: center; gap: 8px; }
    .decision-label { font-weight: 600; font-size: 13px; }
    .decision-expiry { margin-left: auto; font-size: 11px; color: var(--rw-text-muted); }
    .decision-reason { margin: 8px 0 0 22px; font-size: 12.5px; color: var(--rw-text-secondary); }
    .decision-hint { margin: 4px 0 0 22px; font-size: 11.5px; color: var(--rw-text-muted); }

    .compliance-summary { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
    .chip {
      font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 4px;
      &.missing { background: rgba(239,68,68,0.12); color: #ef4444; }
      &.too-old { background: rgba(245,158,11,0.12); color: #f59e0b; }
      &.expired { background: rgba(239,68,68,0.12); color: #ef4444; }
    }

    .checklist { display: flex; flex-direction: column; gap: 2px; }
    .check-row {
      display: flex; align-items: center; gap: 8px;
      font-size: 12px; padding: 5px 0; border-bottom: 1px solid var(--rw-border);
    }
    .ci { font-size: 14px !important; flex-shrink: 0; }
    .ci.ok { color: #10b981; }
    .ci.warn { color: #f59e0b; }
    .ci.err { color: #ef4444; }
    .ci.muted { color: var(--rw-text-muted); }
    .check-name { flex: 1; }
    .check-note { font-size: 11px; color: var(--rw-text-muted); }
    .warn-text { color: #f59e0b !important; }
    .err-text { color: #ef4444 !important; }

    .quick-upload-btn { margin-left: auto; font-size: 11px; height: 24px; line-height: 24px; padding: 0 8px; }
    .upload-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding-top: 4px; }
    .file-label {
      display: flex; align-items: center; gap: 6px;
      padding: 10px 16px; border: 1px dashed var(--rw-border); border-radius: 4px;
      cursor: pointer; font-size: 13px; color: var(--rw-text-muted);
      &:hover { border-color: var(--rw-accent); }
      &.has-file { border-color: #10b981; color: var(--rw-text-primary); }
    }
    .inline-error { color: var(--rw-text-danger); font-size: 12px; margin: 8px 0 0; }
    .document-error { display: grid; justify-items: center; gap: 10px; padding: 40px 16px; color: var(--rw-text-secondary); text-align: center; }
    .document-error mat-icon { color: var(--rw-text-danger); }
    .table-wrap { overflow-x: auto; }
    .table-wrap table { min-width: 760px; }
  `],
})
export class KycStatusComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly kycService = inject(KycService);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  @ViewChild('deleteConfirmDialog') deleteConfirmDialogTpl!: TemplateRef<{ $implicit: KycDocument }>;

  entityId: string | null = null;
  documents: KycDocument[] = [];
  loading = false;
  documentsLoadError = false;

  readonly allJurisdictions = ALL_JURISDICTIONS;
  readonly allDocTypes = ALL_DOC_TYPES;
  readonly docColumns = ['type', 'jurisdiction', 'fileName', 'size', 'uploadedAt', 'actions'];

  entityKycStatus: KycEntityStatus | null = null;
  jurisdictionApprovals: Partial<Record<Jurisdiction, KycJurisdictionApproval>> = {};

  compliance: Partial<Record<Jurisdiction, KycComplianceResponse>> = {};
  complianceLoading: Partial<Record<Jurisdiction, boolean>> = {};
  complianceError: Partial<Record<Jurisdiction, boolean>> = {};
  uploadFiles: Partial<Record<Jurisdiction, File>> = {};
  uploadDocType: Partial<Record<Jurisdiction, string>> = {};
  uploadLoading: Partial<Record<Jurisdiction, boolean>> = {};

  ngOnInit(): void {
    this.entityId = this.authService.getEntityId();
    if (this.entityId) {
      this.loadDocuments();
      this.loadKycStatus();
      this.loadJurisdictionApprovals();
    }
  }

  loadKycStatus(): void {
    if (!this.entityId) return;
    this.kycService.getKycStatus(this.entityId).subscribe({
      next: (res) => { this.entityKycStatus = res.kycStatus; this.cdr.markForCheck(); },
      error: () => { this.entityKycStatus = null; this.cdr.markForCheck(); },
    });
  }

  loadJurisdictionApprovals(): void {
    if (!this.entityId) return;
    this.kycService.getJurisdictionApprovals(this.entityId).subscribe({
      next: (approvals) => {
        this.jurisdictionApprovals = Object.fromEntries(
          approvals.map(a => [a.jurisdiction, a])
        ) as Partial<Record<Jurisdiction, KycJurisdictionApproval>>;
        this.cdr.markForCheck();
      },
      error: () => {
        this.jurisdictionApprovals = {};
        this.cdr.markForCheck();
      },
    });
  }

  entityStatusClass(status: KycEntityStatus): string {
    return 'status-' + status.toLowerCase().replace(/_/g, '-');
  }

  loadDocuments(): void {
    if (!this.entityId) return;
    this.loading = true;
    this.documentsLoadError = false;
    this.kycService.listDocuments(this.entityId).subscribe({
      next: (docs) => { this.documents = docs; this.loading = false; this.cdr.markForCheck(); },
      error: () => {
        this.documents = [];
        this.loading = false;
        this.documentsLoadError = true;
        this.cdr.markForCheck();
      },
    });
  }

  loadCompliance(jur: Jurisdiction): void {
    if (!this.entityId) return;
    this.complianceLoading = { ...this.complianceLoading, [jur]: true };
    this.complianceError = { ...this.complianceError, [jur]: false };
    this.kycService.getCompliance(this.entityId, jur).subscribe({
      next: (result) => {
        this.compliance = { ...this.compliance, [jur]: result };
        this.complianceLoading = { ...this.complianceLoading, [jur]: false };
        this.complianceError = { ...this.complianceError, [jur]: false };
        this.cdr.markForCheck();
      },
      error: () => {
        this.complianceLoading = { ...this.complianceLoading, [jur]: false };
        this.complianceError = { ...this.complianceError, [jur]: true };
        this.cdr.markForCheck();
      },
    });
  }

  onFileSelected(event: Event, jur: Jurisdiction): void {
    const file = (event.target as HTMLInputElement).files?.[0] ?? null;
    if (file) this.uploadFiles = { ...this.uploadFiles, [jur]: file };
  }

  quickUpload(jur: Jurisdiction, docType: string): void {
    this.uploadDocType = { ...this.uploadDocType, [jur]: docType };
    // Scroll to the upload section in this tab — it sits after the checklist
    setTimeout(() => {
      const uploadSection = document.getElementById(`upload-${jur}`);
      uploadSection?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 50);
  }

  upload(jur: Jurisdiction): void {
    const file = this.uploadFiles[jur];
    const docType = this.uploadDocType[jur];
    if (!this.entityId || !file || !docType) return;

    this.uploadLoading = { ...this.uploadLoading, [jur]: true };
    this.kycService.uploadDocument(this.entityId, file, docType, jur).subscribe({
      next: (doc) => {
        this.documents = [...this.documents, doc];
        this.uploadFiles = { ...this.uploadFiles, [jur]: undefined };
        this.uploadDocType = { ...this.uploadDocType, [jur]: '' };
        this.uploadLoading = { ...this.uploadLoading, [jur]: false };
        this.cdr.markForCheck();
        // Refresh compliance checklist
        this.loadCompliance(jur);
        this.snackBar.open('Document uploaded. Awaiting review.', 'OK', { duration: 4000 });
      },
      error: () => {
        this.uploadLoading = { ...this.uploadLoading, [jur]: false };
        this.cdr.markForCheck();
        this.snackBar.open('Upload failed. Please try again.', 'OK', { duration: 3000 });
      },
    });
  }

  download(doc: KycDocument): void {
    if (!this.entityId) return;
    this.kycService.downloadDocument(this.entityId, doc.id).subscribe({
      next: (blob) => {
        downloadBlob(blob, doc.fileName);
      },
      error: () => this.snackBar.open('Document could not be downloaded.', 'Dismiss', { duration: 5000 }),
    });
  }

  deleteDoc(doc: KycDocument): void {
    if (!this.entityId || doc.status === 'APPROVED') return;

    this.dialog
      .open(this.deleteConfirmDialogTpl, { width: '420px', data: doc })
      .afterClosed()
      .subscribe((confirmed: boolean | undefined) => {
        if (!confirmed || !this.entityId) return;
        this.kycService.deleteDocument(this.entityId, doc.id).subscribe({
          next: () => {
            this.documents = this.documents.filter(d => d.id !== doc.id);
            this.cdr.markForCheck();
            this.snackBar.open('Document deleted.', 'OK', { duration: 2000 });
          },
          error: () => this.snackBar.open('Document could not be deleted.', 'Dismiss', { duration: 5000 }),
        });
      });
  }

  formatDocType(type: string): string {
    return type.replace(/_/g, ' ').toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }
}
