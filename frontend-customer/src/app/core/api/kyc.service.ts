import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  KycComplianceResponse,
  KycDocument,
  KycEntityStatus,
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
    formData.append('file', file);
    formData.append('documentType', documentType);
    if (jurisdiction) formData.append('jurisdiction', jurisdiction);
    return this.http.post<KycDocument>(`${this.base}/${entityId}/kyc/documents`, formData);
  }

  downloadDocument(entityId: string, docId: string): Observable<Blob> {
    return this.http.get(`${this.base}/${entityId}/kyc/documents/${docId}`, { responseType: 'blob' });
  }

  deleteDocument(entityId: string, docId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${entityId}/kyc/documents/${docId}`);
  }

  getCompliance(entityId: string, jurisdiction: Jurisdiction): Observable<KycComplianceResponse> {
    return this.http.get<KycComplianceResponse>(
      `${this.base}/${entityId}/kyc/compliance/${jurisdiction}`
    );
  }

  getJurisdictionRequirements(): Observable<JurisdictionRequirement[]> {
    return this.http.get<JurisdictionRequirement[]>(`${this.publicBase}/jurisdictions`);
  }

  /**
   * Wraps `KycController.getKycStatus` (`GET /entities/{entityId}/kyc/status`), which
   * previously had no frontend caller: the customer portal had no way for a client to see
   * their own KYC decision despite the backend already tracking it.
   */
  getKycStatus(entityId: string): Observable<{ kycStatus: KycEntityStatus }> {
    return this.http.get<{ kycStatus: KycEntityStatus }>(`${this.base}/${entityId}/kyc/status`);
  }

  /**
   * Wraps `KycController.listJurisdictionApprovals` (`GET /entities/{entityId}/kyc/jurisdictions`),
   * which includes `rejectionReason` — also previously uncalled from the customer portal, so a
   * rejected client had no way to see why.
   */
  getJurisdictionApprovals(entityId: string): Observable<KycJurisdictionApproval[]> {
    return this.http.get<KycJurisdictionApproval[]>(`${this.base}/${entityId}/kyc/jurisdictions`);
  }
}
