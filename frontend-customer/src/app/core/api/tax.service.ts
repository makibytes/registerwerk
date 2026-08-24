import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

/**
 * Tax certificate (Steuerbescheinigung) — wraps the existing, already-implemented
 * `corporateactions.web.SteuerbescheinigungController` (`GET /api/v1/me/tax-certificates/{year}`),
 * which had no frontend caller: the customer portal previously had no way to reach it despite
 * the backend already generating the PDF.
 */
@Injectable({ providedIn: 'root' })
export class TaxService {
  private readonly http = inject(HttpClient);

  downloadMyTaxCertificate(year: number): Observable<Blob> {
    return this.http.get(`${environment.apiUrl}/me/tax-certificates/${year}`, { responseType: 'blob' });
  }
}
