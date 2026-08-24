import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { InspectionLegalBasis, RegisterInspectionRequest } from '../models';

/**
 * §10 eWpG register inspection — wraps `registertransfer.web.RegisterInspectionController`'s
 * `POST /api/v1/register-inspections`, which had no frontend caller: the request side of this
 * statutory workflow was curl-only despite being open to any authenticated participant.
 */
@Injectable({ providedIn: 'root' })
export class RegisterInspectionService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/register-inspections`;

  submit(
    assetId: string,
    requesterName: string,
    requesterEmail: string,
    legalBasis: InspectionLegalBasis,
    statedInterest?: string,
    requesterEntityId?: string,
  ): Observable<RegisterInspectionRequest> {
    return this.http.post<RegisterInspectionRequest>(this.base, {
      assetId, requesterEntityId, requesterName, requesterEmail, legalBasis, statedInterest,
    });
  }
}
