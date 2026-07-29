import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { IctIncident, ResilienceTest, ThirdPartyProvider } from '../models';

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

  listResilienceTests(): Observable<ResilienceTest[]> {
    return this.http.get<ResilienceTest[]>(`${this.base}/resilience-tests`);
  }

  listOverdueResilienceTests(): Observable<ResilienceTest[]> {
    return this.http.get<ResilienceTest[]>(`${this.base}/resilience-tests/overdue`);
  }

  recordResilienceTest(body: {
    testType: string;
    scope: string;
    tlptRequired?: boolean;
    thirdPartyProviderId?: string;
    performedAt: string;
    nextDueDate?: string;
    result: string;
    findings?: string;
    testerName?: string;
    reportRef?: string;
  }): Observable<ResilienceTest> {
    return this.http.post<ResilienceTest>(`${this.base}/resilience-tests`, body);
  }
}
