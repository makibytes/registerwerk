import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { OpenHitView, ScreeningHit, ScreeningRun } from '../models';

@Injectable({ providedIn: 'root' })
export class ScreeningService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/compliance/screening`;

  /** Global open-hit work-queue: all unresolved hits across all entities. */
  listOpenHits(): Observable<OpenHitView[]> {
    return this.http.get<OpenHitView[]>(`${this.base}/hits?status=open`);
  }

  /** Recent screening runs for a legal entity. */
  listRunsByEntity(entityId: string): Observable<ScreeningRun[]> {
    return this.http.get<ScreeningRun[]>(`${this.base}/entities/${entityId}/runs`);
  }

  /** Fetch a single run by ID. */
  getRun(runId: string): Observable<ScreeningRun> {
    return this.http.get<ScreeningRun>(`${this.base}/runs/${runId}`);
  }

  /** Unresolved hits for a given run. */
  listHitsByRun(runId: string): Observable<ScreeningHit[]> {
    return this.http.get<ScreeningHit[]>(`${this.base}/runs/${runId}/hits`);
  }

  /** Trigger on-demand screening for a legal entity. */
  screenEntity(
    entityId: string,
    body: { name: string; countryCode?: string; lei?: string },
  ): Observable<ScreeningRun> {
    return this.http.post<ScreeningRun>(`${this.base}/entities/${entityId}/screen`, body);
  }

  /** Trigger on-demand screening for a natural person (beneficial owner). */
  screenPerson(
    personId: string,
    body: { fullName: string; countryCode?: string },
  ): Observable<ScreeningRun> {
    return this.http.post<ScreeningRun>(`${this.base}/persons/${personId}/screen`, body);
  }

  /**
   * Accept a false-positive screening hit.
   * Requires a step-up JWT as the Bearer token; dual-control requires a second
   * approver's step-up JWT in X-Dual-Control-Token.
   */
  acceptHit(
    hitId: string,
    body: { reason: string; approverActorId?: string },
    stepUpToken: string,
    dualControlToken?: string,
  ): Observable<ScreeningHit> {
    let headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    if (dualControlToken) {
      headers = headers.set('X-Dual-Control-Token', dualControlToken);
    }
    return this.http.post<ScreeningHit>(
      `${this.base}/hits/${hitId}/accept`,
      body,
      { headers },
    );
  }
}
