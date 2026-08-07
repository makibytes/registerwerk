import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { BeneficialOwner } from '../models';

/**
 * Read-only view of `kyc.web.BeneficialOwnerController`
 * (`GET /api/v1/entities/{entityId}/beneficial-owners`), which had no customer frontend
 * caller: an entity could see the UBO screening applied to it (nightly
 * `BeneficialOwnerScreeningJob`) only via an operator asking them directly. Write stays
 * operator/compliance-only.
 */
@Injectable({ providedIn: 'root' })
export class BeneficialOwnerService {
  private readonly http = inject(HttpClient);

  list(entityId: string): Observable<BeneficialOwner[]> {
    return this.http.get<BeneficialOwner[]>(`${environment.apiUrl}/entities/${entityId}/beneficial-owners`);
  }
}
