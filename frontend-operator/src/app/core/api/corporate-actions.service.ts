import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class CorporateActionsService {
  private readonly http = inject(HttpClient);

  downloadPositionStatement(entityId: string): Observable<Blob> {
    return this.http.get(
      `${environment.apiUrl}/customers/${entityId}/statements`,
      { responseType: 'blob' },
    );
  }

  downloadTaxCertificate(entityId: string, year: number): Observable<Blob> {
    return this.http.get(
      `${environment.apiUrl}/customers/${entityId}/tax-certificates/${year}`,
      { responseType: 'blob' },
    );
  }
}
