import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { BeneficialOwner, BeneficialOwnerRequest, OwnershipSummary } from '../models';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

/**
 * Wraps `kyc.web.BeneficialOwnerController` (`/api/v1/entities/{entityId}/beneficial-owners`),
 * which had no operator frontend caller: UBO data (GwG §3, AMLR Art. 42) was screened nightly
 * by `BeneficialOwnerScreeningJob` but operators had no way to view or maintain it.
 */
@Injectable({ providedIn: 'root' })
export class BeneficialOwnerService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/entities`;

  list(entityId: string): Observable<BeneficialOwner[]> {
    return this.http.get<BeneficialOwner[]>(`${this.base}/${entityId}/beneficial-owners`);
  }

  add(entityId: string, request: BeneficialOwnerRequest): Observable<BeneficialOwner> {
    return this.http.post<BeneficialOwner>(`${this.base}/${entityId}/beneficial-owners`, request);
  }

  summary(entityId: string): Observable<OwnershipSummary> {
    return this.http.get<OwnershipSummary>(`${this.base}/${entityId}/beneficial-owners/summary`);
  }

  /**
   * Ceasing needs a JSON body (`reason` mandatory, optional evidencing register-extract `documentId`),
   * step-up and a second approver (`BENEFICIAL_OWNER_CEASE`); 409 while the person's screening is unresolved.
   */
  cease(entityId: string, beneficialOwnerId: string, body: { reason: string; documentId?: string },
        tokens: DualControlTokens): Observable<BeneficialOwner> {
    return this.http.delete<BeneficialOwner>(
      `${this.base}/${entityId}/beneficial-owners/${beneficialOwnerId}`,
      { body, headers: dualControlHeaders(tokens) });
  }

  /** Verifies a beneficial owner against a stored KYC document (counts towards the identified share). */
  verify(entityId: string, beneficialOwnerId: string, documentId: string): Observable<BeneficialOwner> {
    return this.http.post<BeneficialOwner>(
      `${this.base}/${entityId}/beneficial-owners/${beneficialOwnerId}/verify`, { documentId });
  }

  /** EDD approval of a confirmed PEP (`PEP_EDD_APPROVE`): step-up + second approver; review due at most 6 months out. */
  approveEdd(entityId: string, beneficialOwnerId: string, body: { note: string; reviewDueDate?: string },
             tokens: DualControlTokens): Observable<unknown> {
    return this.http.post(
      `${this.base}/${entityId}/beneficial-owners/${beneficialOwnerId}/edd-approvals`, body,
      { headers: dualControlHeaders(tokens) });
  }
}
