import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { RegulatorySubmission } from '../models';

@Injectable({ providedIn: 'root' })
export class RegulatoryReportingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/regulatory-reporting`;

  listSubmissions(limit = 50): Observable<RegulatorySubmission[]> {
    return this.http.get<RegulatorySubmission[]>(`${this.base}/submissions`, {
      params: { limit: String(limit) },
    });
  }

  generateDac8(taxYear?: number): Observable<{ status: string; taxYear: string; message: string }> {
    const params: Record<string, string> = {};
    if (taxYear != null) params['taxYear'] = String(taxYear);
    return this.http.post<{ status: string; taxYear: string; message: string }>(
      `${this.base}/dac8/generate`,
      null,
      { params },
    );
  }

  generateMifir(reportingDate?: string): Observable<{ status: string; reportingDate: string; message: string }> {
    const params: Record<string, string> = {};
    if (reportingDate) params['reportingDate'] = reportingDate;
    return this.http.post<{ status: string; reportingDate: string; message: string }>(
      `${this.base}/mifir/generate`,
      null,
      { params },
    );
  }
}
