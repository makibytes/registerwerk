import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  KycComplianceResponse,
  KycDocument,
  KycJurisdictionApproval,
  Jurisdiction,
  JurisdictionRequirement,
} from '../models';

@Injectable({ providedIn: 'root' })
export class KycService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/entities`;
  private readonly publicBase = `${environment.apiUrl}/public`;

  listDocuments(entityId: string): Observable<KycDocument[]> {
    return this.http.get<KycDocument[]>(`${this.base}/${entityId}/kyc/documents`);
  }

  uploadDocument(
    entityId: string,
    file: File,
    documentType: string,
    jurisdiction?: Jurisdiction
  ): Observable<KycDocument> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    formData.append('documentType', documentType);
    if (jurisdiction) formData.append('jurisdiction', jurisdiction);
    return this.http.post<KycDocument>(`${this.base}/${entityId}/kyc/documents`, formData);
  }

  downloadDocument(entityId: string, docId: string): Observable<Blob> {
    return this.http.get(
      `${this.base}/${entityId}/kyc/documents/${docId}`,
      { responseType: 'blob' }
    );
  }

  deleteDocument(entityId: string, docId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${entityId}/kyc/documents/${docId}`);
  }

  approveKyc(entityId: string, expiryDate: string): Observable<unknown> {
    return this.http.post(`${this.base}/${entityId}/kyc/approve`, { expiryDate });
  }

  rejectKyc(entityId: string, reason: string): Observable<unknown> {
    return this.http.post(`${this.base}/${entityId}/kyc/reject`, { reason });
  }

  // ── Per-jurisdiction endpoints ─────────────────────────────────────────────

  getJurisdictionApprovals(entityId: string): Observable<KycJurisdictionApproval[]> {
    return this.http.get<KycJurisdictionApproval[]>(`${this.base}/${entityId}/kyc/jurisdictions`);
  }

  approveJurisdiction(
    entityId: string, jurisdiction: Jurisdiction, expiresAt?: string
  ): Observable<KycJurisdictionApproval> {
    return this.http.post<KycJurisdictionApproval>(
      `${this.base}/${entityId}/kyc/jurisdictions/${jurisdiction}/approve`,
      expiresAt ? { expiresAt } : {}
    );
  }

  rejectJurisdiction(
    entityId: string, jurisdiction: Jurisdiction, reason: string
  ): Observable<KycJurisdictionApproval> {
    return this.http.post<KycJurisdictionApproval>(
      `${this.base}/${entityId}/kyc/jurisdictions/${jurisdiction}/reject`,
      { reason }
    );
  }

  getCompliance(entityId: string, jurisdiction: Jurisdiction): Observable<KycComplianceResponse> {
    return this.http.get<KycComplianceResponse>(
      `${this.base}/${entityId}/kyc/compliance/${jurisdiction}`
    );
  }

  getJurisdictionRequirements(): Observable<JurisdictionRequirement[]> {
    return this.http.get<JurisdictionRequirement[]>(`${this.publicBase}/jurisdictions`);
  }
}
