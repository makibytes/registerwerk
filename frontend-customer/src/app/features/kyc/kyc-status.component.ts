import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { KycService } from '../../core/api/kyc.service';
import { AuthService } from '../../core/auth/auth.service';
import { KycDocument } from '../../core/models';
import { StatusBadgeComponent } from '../../shared/components/status-badge/status-badge.component';

const DOCUMENT_TYPES = [
  'CERTIFICATE_OF_INCORPORATION',
  'ARTICLES_OF_ASSOCIATION',
  'PROOF_OF_ADDRESS',
  'BENEFICIAL_OWNER_REGISTER',
  'AUDIT_REPORT',
  'IDENTITY_DOCUMENT',
  'POWER_OF_ATTORNEY',
  'OTHER',
];

@Component({
  selector: 'app-kyc-status',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTableModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatChipsModule,
    MatTooltipModule,
    StatusBadgeComponent,
  ],
  template: `
    <div class="page-container">
      <h1 class="page-title">KYC Status & Documents</h1>
      <p class="page-subtitle">
        Upload supporting documents for your entity's KYC verification.
        The registry operator will review and approve them.
      </p>

      <!-- Status banner -->
      @if (entityId) {
        <mat-card class="status-card" [class.approved]="kycApproved" [class.rejected]="kycRejected">
          <mat-card-content style="display:flex;align-items:center;gap:12px">
            <mat-icon [style.color]="kycApproved ? '#388e3c' : kycRejected ? '#c62828' : '#f57c00'">
              {{ kycApproved ? 'verified_user' : kycRejected ? 'gpp_bad' : 'pending' }}
            </mat-icon>
            <div>
              <div style="font-weight:500">
                KYC Status: <strong>{{ kycStatus }}</strong>
              </div>
              <div style="font-size:13px;color:rgba(0,0,0,0.6)">Entity ID: {{ entityId }}</div>
            </div>
          </mat-card-content>
        </mat-card>
      }

      <!-- Upload section -->
      <mat-card class="upload-card">
        <mat-card-header>
          <mat-card-title>Upload Document</mat-card-title>
          <mat-card-subtitle>Accepted: PDF, JPG, PNG, XML (max 20 MB)</mat-card-subtitle>
        </mat-card-header>
        <mat-card-content>
          <div class="upload-form">
            <mat-form-field appearance="outline" style="min-width:240px">
              <mat-label>Document Type</mat-label>
              <mat-select [(ngModel)]="selectedDocType">
                @for (type of documentTypes; track type) {
                  <mat-option [value]="type">{{ formatDocType(type) }}</mat-option>
                }
              </mat-select>
            </mat-form-field>

            <label class="file-label" [class.has-file]="selectedFile">
              <mat-icon>attach_file</mat-icon>
              {{ selectedFile ? selectedFile.name : 'Choose file…' }}
              <input type="file" accept=".pdf,.jpg,.jpeg,.png,.xml" (change)="onFileSelected($event)" hidden />
            </label>

            <button mat-raised-button color="primary"
                    [disabled]="!selectedFile || !selectedDocType || uploading"
                    (click)="upload()">
              @if (uploading) { <mat-spinner diameter="18" style="margin-right:8px" /> }
              <mat-icon>cloud_upload</mat-icon> Upload
            </button>
          </div>
        </mat-card-content>
      </mat-card>

      <!-- Documents table -->
      <mat-card>
        <mat-card-header>
          <mat-card-title>Uploaded Documents ({{ documents.length }})</mat-card-title>
        </mat-card-header>
        <mat-card-content>
          @if (loading) {
            <div style="display:flex;justify-content:center;padding:40px">
              <mat-spinner diameter="40" />
            </div>
          } @else if (documents.length === 0) {
            <p style="text-align:center;color:rgba(0,0,0,0.54);padding:32px">
              No documents uploaded yet.
            </p>
          } @else {
            <table mat-table [dataSource]="documents" style="width:100%">
              <ng-container matColumnDef="type">
                <th mat-header-cell *matHeaderCellDef>Type</th>
                <td mat-cell *matCellDef="let d">{{ formatDocType(d.documentType) }}</td>
              </ng-container>
              <ng-container matColumnDef="fileName">
                <th mat-header-cell *matHeaderCellDef>File</th>
                <td mat-cell *matCellDef="let d">{{ d.fileName }}</td>
              </ng-container>
              <ng-container matColumnDef="size">
                <th mat-header-cell *matHeaderCellDef>Size</th>
                <td mat-cell *matCellDef="let d">{{ formatBytes(d.fileSize) }}</td>
              </ng-container>
              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef>Review Status</th>
                <td mat-cell *matCellDef="let d">
                  <app-status-badge [status]="d.status" />
                </td>
              </ng-container>
              <ng-container matColumnDef="uploadedAt">
                <th mat-header-cell *matHeaderCellDef>Uploaded</th>
                <td mat-cell *matCellDef="let d">{{ d.uploadedAt | date:'mediumDate' }}</td>
              </ng-container>
              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef></th>
                <td mat-cell *matCellDef="let d">
                  <button mat-icon-button [matTooltip]="'Download'" (click)="download(d)">
                    <mat-icon>download</mat-icon>
                  </button>
                  <button mat-icon-button color="warn" [matTooltip]="'Delete'" (click)="deleteDoc(d)">
                    <mat-icon>delete</mat-icon>
                  </button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="columns"></tr>
              <tr mat-row *matRowDef="let row; columns: columns;"></tr>
            </table>
          }
        </mat-card-content>
      </mat-card>
    </div>
  `,
  styles: [`
    .page-container { max-width: 900px; margin: 0 auto; padding: 16px; }
    .page-title { font-size: 22px; font-weight: 500; margin: 0 0 4px; }
    .page-subtitle { color: rgba(0,0,0,0.54); font-size: 14px; margin: 0 0 20px; }
    .status-card { margin-bottom: 16px; border-left: 4px solid #f57c00; }
    .status-card.approved { border-left-color: #388e3c; }
    .status-card.rejected { border-left-color: #c62828; }
    .upload-card { margin-bottom: 16px; }
    .upload-form { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding-top: 8px; }
    .file-label {
      display: flex; align-items: center; gap: 6px;
      padding: 10px 16px; border: 1px dashed #90a4ae; border-radius: 4px;
      cursor: pointer; font-size: 14px; color: rgba(0,0,0,0.6);
      transition: border-color 0.2s;
      &:hover { border-color: #1976d2; }
      &.has-file { border-color: #388e3c; color: rgba(0,0,0,0.87); }
    }
  `],
})
export class KycStatusComponent implements OnInit {
  private readonly kycService = inject(KycService);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);

  entityId: string | null = null;
  kycStatus = 'UNKNOWN';
  documents: KycDocument[] = [];
  loading = false;
  uploading = false;

  selectedFile: File | null = null;
  selectedDocType = '';
  readonly documentTypes = DOCUMENT_TYPES;
  readonly columns = ['type', 'fileName', 'size', 'status', 'uploadedAt', 'actions'];

  get kycApproved(): boolean { return this.kycStatus === 'APPROVED'; }
  get kycRejected(): boolean { return this.kycStatus === 'REJECTED'; }

  ngOnInit(): void {
    this.entityId = this.authService.getEntityId();
    if (this.entityId) {
      this.loadDocuments();
    }
  }

  loadDocuments(): void {
    if (!this.entityId) return;
    this.loading = true;
    this.kycService.listDocuments(this.entityId).subscribe({
      next: (docs) => {
        this.documents = docs;
        this.loading = false;
        // Derive KYC status from most recent doc review
        const approved = docs.some(d => d.status === 'APPROVED');
        const rejected = docs.some(d => d.status === 'REJECTED');
        if (approved && !rejected) this.kycStatus = 'APPROVED';
        else if (rejected) this.kycStatus = 'IN_REVIEW';
        else if (docs.length > 0) this.kycStatus = 'PENDING';
        else this.kycStatus = 'NOT_STARTED';
      },
      error: () => { this.loading = false; },
    });
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile = input.files?.[0] ?? null;
  }

  upload(): void {
    if (!this.entityId || !this.selectedFile || !this.selectedDocType) return;
    this.uploading = true;
    this.kycService.uploadDocument(this.entityId, this.selectedFile, this.selectedDocType).subscribe({
      next: (doc) => {
        this.documents = [...this.documents, doc];
        this.selectedFile = null;
        this.selectedDocType = '';
        this.uploading = false;
        this.snackBar.open('Document uploaded. Awaiting review.', 'OK', { duration: 4000 });
      },
      error: () => {
        this.uploading = false;
        this.snackBar.open('Upload failed. Please try again.', 'OK', { duration: 3000 });
      },
    });
  }

  download(doc: KycDocument): void {
    if (!this.entityId) return;
    this.kycService.downloadDocument(this.entityId, doc.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = doc.fileName;
        a.click();
        URL.revokeObjectURL(url);
      },
    });
  }

  deleteDoc(doc: KycDocument): void {
    if (!this.entityId || !confirm(`Delete ${doc.fileName}?`)) return;
    this.kycService.deleteDocument(this.entityId, doc.id).subscribe({
      next: () => {
        this.documents = this.documents.filter(d => d.id !== doc.id);
        this.snackBar.open('Document deleted.', 'OK', { duration: 2000 });
      },
    });
  }

  formatDocType(type: string): string {
    return type.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
  }

  formatBytes(bytes: number): string {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }
}
