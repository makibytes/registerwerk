import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { IctIncident, ThirdPartyProvider } from '../models';

@Injectable({ providedIn: 'root' })
export class DoraService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/dora`;

  listOpenIncidents(): Observable<IctIncident[]> {
    return this.http.get<IctIncident[]>(`${this.base}/incidents`);
  }

  reportIncident(body: {
    title: string;
    description?: string;
    category: string;
    severity: string;
    sourceEventType?: string;
    sourceEventRef?: string;
  }): Observable<IctIncident> {
    return this.http.post<IctIncident>(`${this.base}/incidents`, body);
  }

  updateStatus(id: string, body: {
    status: string;
    rootCause?: string;
    remediationSteps?: string;
  }): Observable<IctIncident> {
    return this.http.patch<IctIncident>(`${this.base}/incidents/${id}/status`, body);
  }

  reportToAuthority(id: string, body: {
    authorityRef: string;
    isFinalReport: boolean;
  }): Observable<IctIncident> {
    return this.http.post<IctIncident>(`${this.base}/incidents/${id}/report-to-authority`, body);
  }

  listProviders(): Observable<ThirdPartyProvider[]> {
    return this.http.get<ThirdPartyProvider[]>(`${this.base}/providers`);
  }

  listExpiringProviders(): Observable<ThirdPartyProvider[]> {
    return this.http.get<ThirdPartyProvider[]>(`${this.base}/providers/expiring`);
  }
}
