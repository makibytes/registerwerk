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
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

/** Fixed customer-facing category of a KYC rejection (the customer never sees the internal reason). */
export type KycRejectionCategory =
  | 'INFORMATION_INCOMPLETE' | 'DOCUMENTS_UNREADABLE' | 'INFORMATION_INCONSISTENT' | 'CONTACT_SUPPORT';

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

  /** Needs step-up and a second approver (`KYC_APPROVE`). */
  approveKyc(entityId: string, expiryDate: string, tokens?: DualControlTokens): Observable<unknown> {
    return this.http.post(
      `${this.base}/${entityId}/kyc/approve`, { expiryDate }, tokens ? { headers: dualControlHeaders(tokens) } : {},
    );
  }

  /**
   * Rejects the entity's KYC. `reason` is the INTERNAL reason (audit trail and operator UI only, never sent
   * to the customer); `customerReasonCode` is the fixed category the customer sees. Needs step-up and a
   * second approver (`KYC_REJECT`); pass the tokens from `StepUpDialogComponent`.
   */
  rejectKyc(
    entityId: string, reason: string, customerReasonCode: KycRejectionCategory, tokens?: DualControlTokens
  ): Observable<unknown> {
    return this.http.post(
      `${this.base}/${entityId}/kyc/reject`,
      { reason, customerReasonCode },
      tokens ? { headers: dualControlHeaders(tokens) } : {},
    );
  }

  // ── Per-jurisdiction endpoints ─────────────────────────────────────────────

  getJurisdictionApprovals(entityId: string): Observable<KycJurisdictionApproval[]> {
    return this.http.get<KycJurisdictionApproval[]>(`${this.base}/${entityId}/kyc/jurisdictions`);
  }

  /** Needs step-up and a second approver (`KYC_JURISDICTION_APPROVE`). */
  approveJurisdiction(
    entityId: string, jurisdiction: Jurisdiction, expiresAt?: string, tokens?: DualControlTokens
  ): Observable<KycJurisdictionApproval> {
    return this.http.post<KycJurisdictionApproval>(
      `${this.base}/${entityId}/kyc/jurisdictions/${jurisdiction}/approve`,
      expiresAt ? { expiresAt } : {},
      tokens ? { headers: dualControlHeaders(tokens) } : {},
    );
  }

  /**
   * Needs step-up and a second approver (`KYC_JURISDICTION_REJECT`). Note: the backend stores `reason` as
   * the jurisdiction's rejection reason, which the customer's KYC status page displays.
   */
  rejectJurisdiction(
    entityId: string, jurisdiction: Jurisdiction, reason: string,
    customerReasonCode?: KycRejectionCategory, tokens?: DualControlTokens
  ): Observable<KycJurisdictionApproval> {
    return this.http.post<KycJurisdictionApproval>(
      `${this.base}/${entityId}/kyc/jurisdictions/${jurisdiction}/reject`,
      customerReasonCode ? { reason, customerReasonCode } : { reason },
      tokens ? { headers: dualControlHeaders(tokens) } : {},
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
