import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { BeneficialOwner, BeneficialOwnerRequest } from '../models';

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

  cease(entityId: string, beneficialOwnerId: string): Observable<BeneficialOwner> {
    return this.http.delete<BeneficialOwner>(`${this.base}/${entityId}/beneficial-owners/${beneficialOwnerId}`);
  }
}
