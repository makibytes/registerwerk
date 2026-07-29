import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Position statement (Depotauszug) — wraps the existing, already-implemented
 * `corporateactions.web.PositionStatementController` (`GET /api/v1/me/statements`), which had
 * no frontend caller at all: the customer portal previously had no way to reach a Depotauszug,
 * coupon, or corporate-action view despite the backend already generating them.
 */
@Injectable({ providedIn: 'root' })
export class StatementService {
  private readonly http = inject(HttpClient);

  downloadMyStatement(): Observable<Blob> {
    return this.http.get(`${environment.apiUrl}/me/statements`, { responseType: 'blob' });
  }
}
